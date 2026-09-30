package hzpro.com.tradingdesk.arbitrage.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

/**
 * Locks {@link PolymarketEip712Signer} POLY_1271 output to clob-client-v2's reference signatures.
 * The reference blobs were produced with viem + the exact signing logic from
 * {@code exchangeOrderBuilderV2.ts} for a fixed key/order. v2 is the order version production
 * currently advertises via GET /version (exchange 0xE111…, domain version "2"); v3 is kept as a
 * forward guard (exchange 0xe333…, domain version "3").
 */
class Eip712SignerParityTest {

    private static final String PK = "0x59c6995e998f97a5a0044966f0945389dc9e86dae88c7a8412f4603b6b78690d";
    private static final String DEPOSIT_WALLET = "0x1111111111111111111111111111111111111111";
    private static final String TOKEN_ID =
            "114939405159945266324373166726329780148019211964124316114378035183042688731645";
    private static final String Z = "0x0000000000000000000000000000000000000000000000000000000000000000";

    private final PolymarketEip712Signer signer = new PolymarketEip712Signer(new ObjectMapper());

    private String sign(String exchange, String domainVersion) {
        return signer.signOrder(PK, 137, exchange, domainVersion, new BigInteger("123456789"),
                DEPOSIT_WALLET, DEPOSIT_WALLET, TOKEN_ID,
                new BigInteger("1000000"), new BigInteger("2000000"),
                0, 3, 1700000000000L, Z, Z, false);
    }

    @Test
    void matchesClobClientV2_orderVersion2() {
        String reference = "0x7e4a307d262d977ece5623b4b11d2d3330ee38bed851e99d7090b260b18d1c132168eb377e19a94011ae862e92ec1c1cf859b3397cd7bd7696ee2466b7ee43d71c3264e159346253e26a64e00b69032db0e7d32f94628de3e6eecb50304d7af3d2545bb0acfa43e7ab46ad128d955cfbf9a6c6563497d6b522a6c73ac7c5f149724f726465722875696e743235362073616c742c61646472657373206d616b65722c61646472657373207369676e65722c75696e7432353620746f6b656e49642c75696e74323536206d616b6572416d6f756e742c75696e743235362074616b6572416d6f756e742c75696e743820736964652c75696e7438207369676e6174757265547970652c75696e743235362074696d657374616d702c62797465733332206d657461646174612c62797465733332206275696c6465722900ba";
        org.junit.jupiter.api.Assertions.assertEquals(
                reference, sign("0xE111180000d2663C0091e4f400237545B87B996B", "2"));
    }

    @Test
    void matchesClobClientV2_orderVersion3() {
        String reference = "0xa095a1bd3fd2b74332da57de8e87cf2270afd99c163557f4822a4f86bb8b37340b553287f4dd408f5924758a9a347cf1df300e859b822253cdf5b5c34ba7b52d1c466c63910185bbd55e8679264200c4e0abdcbb0c6264eb3d41d13326022e095b545bb0acfa43e7ab46ad128d955cfbf9a6c6563497d6b522a6c73ac7c5f149724f726465722875696e743235362073616c742c61646472657373206d616b65722c61646472657373207369676e65722c75696e7432353620746f6b656e49642c75696e74323536206d616b6572416d6f756e742c75696e743235362074616b6572416d6f756e742c75696e743820736964652c75696e7438207369676e6174757265547970652c75696e743235362074696d657374616d702c62797465733332206d657461646174612c62797465733332206275696c6465722900ba";
        org.junit.jupiter.api.Assertions.assertEquals(
                reference, sign("0xe3333700cA9d93003F00f0F71f8515005F6c00Aa", "3"));
    }
}
