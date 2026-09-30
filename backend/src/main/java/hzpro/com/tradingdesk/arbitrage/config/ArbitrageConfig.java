package hzpro.com.tradingdesk.arbitrage.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "arbitrage")
public class ArbitrageConfig {

    private BigDecimal threshold = BigDecimal.valueOf(0.01);
    private String kalshiApiKey;
    private String kalshiPrivateKey;
    private TestPair testPair = new TestPair();
    private long prefetchTimeoutMs = 5000;
    private String kalshiApiUrl = "https://external-api.kalshi.com/trade-api/v2";
    private String polymarketApiUrl = "https://clob.polymarket.com";
    private int pairLimit = 100;
    private String polymarketPrivateKey;
    private String polymarketClobUrl = "https://clob.polymarket.com";
    private String polymarketDepositWalletAddress;
    private int polymarketSignatureType = 3; // 0=EOA, 1=POLY_PROXY, 2=POLY_GNOSIS_SAFE, 3=POLY_1271
    // When true and signatureType=POLY_1271, the signing EOA is a DepositWallet session signer (not
    // the owner), so the ERC-7739 signature is wrapped in the session-signer envelope.
    private boolean polymarketSessionSigner = false;
    private boolean tradingEnabled = false;
    private int consecutiveErrorsCountToStopTrading = 2;
    private long polymarketChainId = 137;
    // Polymarket CTF Exchange V3 (Polygon / chainId 137). Used as EIP-712 verifyingContract for order signing.
    private String polymarketVerifyingContract = "0xe3333700cA9d93003F00f0F71f8515005F6c00Aa";
    private BigDecimal maxOrderCost;
    private int activePairsLimit = 1;
    private String polymarketDataApiUrl = "https://data-api.polymarket.com";

    static final BigDecimal MIN_MAX_ORDER_COST = BigDecimal.valueOf(6);

    @PostConstruct
    void validate() {
        boolean disabled = maxOrderCost != null && maxOrderCost.compareTo(BigDecimal.ZERO) == 0;
        if (maxOrderCost == null || (!disabled && maxOrderCost.compareTo(MIN_MAX_ORDER_COST) < 0)) {
            throw new IllegalStateException(
                    "arbitrage.max-order-cost must be 0 (disabled) or at least " + MIN_MAX_ORDER_COST
                            + " (was " + maxOrderCost + ")");
        }
        if (activePairsLimit < 1) {
            throw new IllegalStateException(
                    "arbitrage.active-pairs-limit must be at least 1 (was " + activePairsLimit + ")");
        }
    }

    @Getter
    @Setter
    public static class TestPair {
        private String polymarketSlug;
        private String polymarketYesAssetId;
        private String polymarketNoAssetId;
        private String kalshiTicker;
        private Long similarMarketId;
    }
}
