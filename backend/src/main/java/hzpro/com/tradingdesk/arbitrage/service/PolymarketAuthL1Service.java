package hzpro.com.tradingdesk.arbitrage.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.springframework.stereotype.Service;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;
import org.web3j.crypto.StructuredDataEncoder;
import org.web3j.utils.Numeric;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import hzpro.com.tradingdesk.arbitrage.config.ArbitrageConfig;

@Service
public class PolymarketAuthL1Service {

    private static final String DOMAIN_NAME = "ClobAuthDomain";
    private static final String DOMAIN_VERSION = "1";
    private static final String PRIMARY_TYPE = "ClobAuth";
    private static final String MSG_TO_SIGN = "This message attests that I control the given wallet";

    private final CloseableHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String clobUrl;

    public PolymarketAuthL1Service(CloseableHttpClient httpClient, ObjectMapper objectMapper,
                                    ArbitrageConfig config) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.clobUrl = config.getPolymarketClobUrl();
    }

    public record ApiCredentials(String apiKey, String secret, String passphrase) {}

    public ApiCredentials createOrDeriveApiKey(String privateKeyHex, long chainId) {
        try {
            Credentials credentials = Credentials.create(privateKeyHex);
            String walletAddress = Keys.toChecksumAddress(credentials.getAddress());

            String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
            long nonce = 0;
            String signature = signClobAuth(credentials, walletAddress, timestamp, nonce, chainId);

            // Create returns the credentials; if a key already exists the create endpoint
            // returns HTTP 400 "Could not create api key", so fall back to deterministic derive.
            HttpPost create = new HttpPost(clobUrl + "/auth/api-key");
            ApiCredentials created = sendL1(create, walletAddress, signature, timestamp, nonce, false);
            if (created != null) {
                return created;
            }

            HttpGet derive = new HttpGet(clobUrl + "/auth/derive-api-key");
            ApiCredentials derived = sendL1(derive, walletAddress, signature, timestamp, nonce, true);
            if (derived != null) {
                return derived;
            }
            throw new RuntimeException("Failed to create or derive API key");

        } catch (IOException e) {
            throw new RuntimeException("Failed to create API key", e);
        }
    }

    private ApiCredentials sendL1(HttpUriRequestBase request, String walletAddress, String signature,
                                  String timestamp, long nonce, boolean throwOnError) throws IOException {
        request.setHeader("POLY_ADDRESS", walletAddress);
        request.setHeader("POLY_SIGNATURE", signature);
        request.setHeader("POLY_TIMESTAMP", timestamp);
        request.setHeader("POLY_NONCE", String.valueOf(nonce));

        return httpClient.execute(request, response -> {
            int code = response.getCode();
            String responseBody = new String(response.getEntity().getContent().readAllBytes(), StandardCharsets.UTF_8);
            if (code == 200 || code == 201) {
                JsonNode json = objectMapper.readTree(responseBody);
                return new ApiCredentials(
                        json.path("apiKey").asText(),
                        json.path("secret").asText(),
                        json.path("passphrase").asText()
                );
            }
            if (throwOnError) {
                throw new RuntimeException("Failed L1 request " + request.getMethod()
                        + ": HTTP " + code + " - " + responseBody);
            }
            return null;
        });
    }

    private String signClobAuth(Credentials credentials, String walletAddress, String timestamp,
                                long nonce, long chainId) {
        try {
            ObjectNode eip712Json = objectMapper.createObjectNode();

            ObjectNode types = eip712Json.putObject("types");

            ArrayNode eip712Domain = types.putArray("EIP712Domain");
            addType(eip712Domain, "name", "string");
            addType(eip712Domain, "version", "string");
            addType(eip712Domain, "chainId", "uint256");

            ArrayNode clobAuthType = types.putArray(PRIMARY_TYPE);
            addType(clobAuthType, "address", "address");
            addType(clobAuthType, "timestamp", "string");
            addType(clobAuthType, "nonce", "uint256");
            addType(clobAuthType, "message", "string");

            eip712Json.put("primaryType", PRIMARY_TYPE);

            ObjectNode domain = eip712Json.putObject("domain");
            domain.put("name", DOMAIN_NAME);
            domain.put("version", DOMAIN_VERSION);
            domain.put("chainId", chainId);

            ObjectNode message = eip712Json.putObject("message");
            message.put("address", walletAddress);
            message.put("timestamp", timestamp);
            message.put("nonce", nonce);
            message.put("message", MSG_TO_SIGN);

            String eip712JsonPayload = objectMapper.writeValueAsString(eip712Json);

            StructuredDataEncoder encoder = new StructuredDataEncoder(eip712JsonPayload);
            byte[] hashStructuredData = encoder.hashStructuredData();

            Sign.SignatureData signature = Sign.signMessage(hashStructuredData, credentials.getEcKeyPair(), false);

            return buildSignatureHex(signature);

        } catch (IOException e) {
            throw new RuntimeException("Failed to sign ClobAuth", e);
        }
    }

    private void addType(ArrayNode typeArray, String name, String type) {
        ObjectNode field = typeArray.addObject();
        field.put("name", name);
        field.put("type", type);
    }

    private String buildSignatureHex(Sign.SignatureData signature) {
        ByteBuffer sigBuffer = ByteBuffer.allocate(signature.getR().length + signature.getS().length + 1);
        sigBuffer.put(signature.getR());
        sigBuffer.put(signature.getS());
        sigBuffer.put(signature.getV());
        return Numeric.toHexString(sigBuffer.array());
    }
}
