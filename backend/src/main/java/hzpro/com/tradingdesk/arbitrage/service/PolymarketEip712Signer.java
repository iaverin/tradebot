package hzpro.com.tradingdesk.arbitrage.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.Hash;
import org.web3j.crypto.Sign;
import org.web3j.crypto.StructuredDataEncoder;
import org.web3j.utils.Numeric;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Signs Polymarket CTF Exchange V3 orders (EIP-712). Domain/struct match
 * clob-client-v2's createExchangeV3OrderFromAmounts.
 *
 * <p>For signatureType POLY_1271 (3) the maker is a smart-contract "deposit wallet" that verifies
 * signatures via ERC-1271 using the ERC-7739 {@code TypedDataSign} nested-EIP-712 scheme, so the
 * returned signature is the wrapped blob rather than a bare 65-byte ECDSA signature.
 */
@Service
public class PolymarketEip712Signer {

    private static final String DOMAIN_NAME = "Polymarket CTF Exchange";
    private static final String PRIMARY_TYPE = "Order";
    private static final int POLY_1271 = 3;

    // Order type string (canonical EIP-712 encodeType for the V3 Order struct).
    private static final String ORDER_TYPE_STRING =
            "Order(uint256 salt,address maker,address signer,uint256 tokenId,uint256 makerAmount,"
            + "uint256 takerAmount,uint8 side,uint8 signatureType,uint256 timestamp,bytes32 metadata,bytes32 builder)";

    private static final String EIP712_DOMAIN_TYPE_STRING =
            "EIP712Domain(string name,string version,uint256 chainId,address verifyingContract)";

    // Polymarket deposit-wallet ERC-712 domain (from the wallet's ERC-5267 eip712Domain(): fields=0x0f).
    private static final String ACCOUNT_DOMAIN_NAME = "DepositWallet";
    private static final String ACCOUNT_DOMAIN_VERSION = "1";
    private static final byte[] ACCOUNT_DOMAIN_SALT = new byte[32]; // eip712Domain().salt == 0x00..00
    // ERC-6492-style magic suffix marking a DepositWallet session-signer envelope.
    private static final byte[] SESSION_SIGNER_MAGIC =
            Numeric.hexStringToByteArray("0x6492649264926492649264926492649264926492649264926492649264926492");

    private final ObjectMapper objectMapper;

    public PolymarketEip712Signer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * @param side 0 = BUY, 1 = SELL
     * @param timestampMillis order creation time in milliseconds (Date.now())
     * @param metadata 32-byte hex string (0x-prefixed)
     * @param builder 32-byte hex string (0x-prefixed)
     */
    public String signOrder(String privateKeyHex, long chainId, String verifyingContract, String domainVersion,
                            BigInteger salt, String maker, String signer, String tokenId,
                            BigInteger makerAmount, BigInteger takerAmount, int side, int signatureType,
                            long timestampMillis, String metadata, String builder, boolean sessionSigner) {
        Credentials credentials = Credentials.create(privateKeyHex);

        byte[] appDomainSeparator = domainSeparator(DOMAIN_NAME, domainVersion, chainId, verifyingContract);
        byte[] contentsHash = orderStructHash(salt, maker, signer, tokenId, makerAmount, takerAmount,
                side, signatureType, timestampMillis, metadata, builder);
        byte[] orderDigest = eip712Digest(appDomainSeparator, contentsHash);

        // Cross-check the manual ABI encoding against web3j's canonical EIP-712 digest.
        verifyAgainstCanonical(chainId, verifyingContract, domainVersion, salt, maker, signer, tokenId,
                makerAmount, takerAmount, side, signatureType, timestampMillis, metadata, builder, orderDigest);

        if (signatureType == POLY_1271) {
            return sign1271(credentials, maker, chainId, appDomainSeparator, contentsHash, sessionSigner);
        }
        return buildSignatureHex(Sign.signMessage(orderDigest, credentials.getEcKeyPair(), false));
    }

    /** Builds the ERC-7739 TypedDataSign wrapped signature for an ERC-1271 deposit wallet. */
    private String sign1271(Credentials credentials, String account, long chainId,
                            byte[] appDomainSeparator, byte[] contentsHash, boolean sessionSigner) {
        // Solady ERC1271 TypedDataSign type string ALWAYS includes `bytes32 salt` (the account's
        // eip712Domain().salt), regardless of the domain `fields` byte. Order of members matters:
        // contents, name, version, chainId, verifyingContract, salt.
        String typedDataSignType =
                "TypedDataSign(Order contents,string name,string version,uint256 chainId,"
                + "address verifyingContract,bytes32 salt)" + ORDER_TYPE_STRING;
        byte[] typedDataSignTypehash = Hash.sha3(typedDataSignType.getBytes(StandardCharsets.UTF_8));

        byte[] structHash = Hash.sha3(concat(
                typedDataSignTypehash,
                contentsHash,
                Hash.sha3(ACCOUNT_DOMAIN_NAME.getBytes(StandardCharsets.UTF_8)),
                Hash.sha3(ACCOUNT_DOMAIN_VERSION.getBytes(StandardCharsets.UTF_8)),
                encUint(BigInteger.valueOf(chainId)),
                encAddress(account),
                ACCOUNT_DOMAIN_SALT
        ));

        // ERC-7739: the outer separator is the APP (exchange) domain separator; the account's
        // domain fields are embedded inside the TypedDataSign struct above.
        byte[] nestedDigest = eip712Digest(appDomainSeparator, structHash);
        byte[] innerSignature = sigBytes(Sign.signMessage(nestedDigest, credentials.getEcKeyPair(), false));

        byte[] contentsDescr = ORDER_TYPE_STRING.getBytes(StandardCharsets.UTF_8);
        byte[] wrapped = concat(
                innerSignature,
                appDomainSeparator,
                contentsHash,
                contentsDescr,
                new byte[]{(byte) (contentsDescr.length >> 8), (byte) contentsDescr.length}
        );

        if (sessionSigner) {
            wrapped = wrapSessionSigner(credentials.getAddress(), wrapped);
        }
        return Numeric.toHexString(wrapped);
    }

    /**
     * Wraps a signature in the Polymarket DepositWallet session-signer envelope (ERC-6492-style):
     * {@code abi.encode(bytes32(sessionSigner), bytes32(0), innerSig) ‖ SESSION_SIGNER_MAGIC}.
     */
    private byte[] wrapSessionSigner(String sessionSigner, byte[] innerSig) {
        byte[] head = concat(
                encAddress(sessionSigner),              // bytes32(uint256(uint160(sessionSigner)))
                new byte[32],                            // bytes32(0)
                encUint(BigInteger.valueOf(0x60)),       // offset to the bytes (3 head words)
                encUint(BigInteger.valueOf(innerSig.length))
        );
        byte[] padded = new byte[((innerSig.length + 31) / 32) * 32];
        System.arraycopy(innerSig, 0, padded, 0, innerSig.length);
        return concat(head, padded, SESSION_SIGNER_MAGIC);
    }

    private byte[] orderStructHash(BigInteger salt, String maker, String signer, String tokenId,
                                   BigInteger makerAmount, BigInteger takerAmount, int side, int signatureType,
                                   long timestampMillis, String metadata, String builder) {
        byte[] orderTypehash = Hash.sha3(ORDER_TYPE_STRING.getBytes(StandardCharsets.UTF_8));
        return Hash.sha3(concat(
                orderTypehash,
                encUint(salt),
                encAddress(maker),
                encAddress(signer),
                encUint(new BigInteger(tokenId)),
                encUint(makerAmount),
                encUint(takerAmount),
                encUint(BigInteger.valueOf(side)),
                encUint(BigInteger.valueOf(signatureType)),
                encUint(BigInteger.valueOf(timestampMillis)),
                encBytes32(metadata),
                encBytes32(builder)
        ));
    }

    private byte[] domainSeparator(String name, String version, long chainId, String verifyingContract) {
        byte[] domainTypehash = Hash.sha3(EIP712_DOMAIN_TYPE_STRING.getBytes(StandardCharsets.UTF_8));
        return Hash.sha3(concat(
                domainTypehash,
                Hash.sha3(name.getBytes(StandardCharsets.UTF_8)),
                Hash.sha3(version.getBytes(StandardCharsets.UTF_8)),
                encUint(BigInteger.valueOf(chainId)),
                encAddress(verifyingContract)
        ));
    }

    private byte[] eip712Digest(byte[] domainSeparator, byte[] structHash) {
        return Hash.sha3(concat(new byte[]{0x19, 0x01}, domainSeparator, structHash));
    }

    private void verifyAgainstCanonical(long chainId, String verifyingContract, String domainVersion, BigInteger salt,
                                        String maker, String signer, String tokenId, BigInteger makerAmount,
                                        BigInteger takerAmount, int side, int signatureType, long timestampMillis,
                                        String metadata, String builder, byte[] manualDigest) {
        try {
            String canonical = Numeric.toHexString(new StructuredDataEncoder(buildTypedDataJson(
                    chainId, verifyingContract, domainVersion, salt, maker, signer, tokenId, makerAmount, takerAmount,
                    side, signatureType, timestampMillis, metadata, builder)).hashStructuredData());
            if (!canonical.equalsIgnoreCase(Numeric.toHexString(manualDigest))) {
                throw new IllegalStateException("Order EIP-712 digest mismatch: manual=" + Numeric.toHexString(manualDigest)
                        + " canonical=" + canonical);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to build canonical typed data for cross-check", e);
        }
    }

    private String buildTypedDataJson(long chainId, String verifyingContract, String domainVersion, BigInteger salt,
                                      String maker, String signer, String tokenId, BigInteger makerAmount,
                                      BigInteger takerAmount, int side, int signatureType, long timestampMillis,
                                      String metadata, String builder) throws IOException {
        ObjectNode eip712Json = objectMapper.createObjectNode();
        ObjectNode types = eip712Json.putObject("types");

        ArrayNode eip712Domain = types.putArray("EIP712Domain");
        addType(eip712Domain, "name", "string");
        addType(eip712Domain, "version", "string");
        addType(eip712Domain, "chainId", "uint256");
        addType(eip712Domain, "verifyingContract", "address");

        ArrayNode orderType = types.putArray(PRIMARY_TYPE);
        addType(orderType, "salt", "uint256");
        addType(orderType, "maker", "address");
        addType(orderType, "signer", "address");
        addType(orderType, "tokenId", "uint256");
        addType(orderType, "makerAmount", "uint256");
        addType(orderType, "takerAmount", "uint256");
        addType(orderType, "side", "uint8");
        addType(orderType, "signatureType", "uint8");
        addType(orderType, "timestamp", "uint256");
        addType(orderType, "metadata", "bytes32");
        addType(orderType, "builder", "bytes32");

        eip712Json.put("primaryType", PRIMARY_TYPE);

        ObjectNode domain = eip712Json.putObject("domain");
        domain.put("name", DOMAIN_NAME);
        domain.put("version", domainVersion);
        domain.put("chainId", chainId);
        domain.put("verifyingContract", verifyingContract);

        ObjectNode message = eip712Json.putObject("message");
        message.put("salt", salt.toString());
        message.put("maker", maker);
        message.put("signer", signer);
        message.put("tokenId", tokenId);
        message.put("makerAmount", makerAmount.toString());
        message.put("takerAmount", takerAmount.toString());
        message.put("side", side);
        message.put("signatureType", signatureType);
        message.put("timestamp", String.valueOf(timestampMillis));
        message.put("metadata", metadata);
        message.put("builder", builder);

        return objectMapper.writeValueAsString(eip712Json);
    }

    private void addType(ArrayNode typeArray, String name, String type) {
        ObjectNode field = typeArray.addObject();
        field.put("name", name);
        field.put("type", type);
    }

    private static byte[] encUint(BigInteger value) {
        return Numeric.toBytesPadded(value, 32);
    }

    private static byte[] encAddress(String address) {
        return Numeric.toBytesPadded(Numeric.toBigInt(address), 32);
    }

    private static byte[] encBytes32(String hex) {
        byte[] bytes = Numeric.hexStringToByteArray(hex);
        if (bytes.length != 32) {
            throw new IllegalArgumentException("Expected 32-byte value, got " + bytes.length + " bytes: " + hex);
        }
        return bytes;
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    private byte[] sigBytes(Sign.SignatureData signature) {
        ByteBuffer buffer = ByteBuffer.allocate(signature.getR().length + signature.getS().length + 1);
        buffer.put(signature.getR());
        buffer.put(signature.getS());
        buffer.put(signature.getV());
        return buffer.array();
    }

    private String buildSignatureHex(Sign.SignatureData signature) {
        return Numeric.toHexString(sigBytes(signature));
    }
}
