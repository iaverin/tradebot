package hzpro.com.tradingdesk.arbitrage.model;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@Builder
public class ArbitrageOpportunity {

    private ArbitrageDirection direction;
    private BigDecimal spread;
    private BigDecimal threshold;

    private BigDecimal polymarketYesAsk;
    private BigDecimal polymarketNoAsk;
    private BigDecimal kalshiYesAsk;
    private BigDecimal kalshiNoAsk;

    private Instant detectedAt;

    public boolean isAboveThreshold() {
        return spread != null && spread.compareTo(threshold) > 0;
    }

    public String toLogString() {
        return String.format(
                "[ARBITRAGE] direction=%s, spread=%.4f, threshold=%.4f, pm_yes=%.4f, pm_no=%.4f, ks_yes=%.4f, ks_no=%.4f",
                direction.getCode(),
                spread,
                threshold,
                polymarketYesAsk,
                polymarketNoAsk,
                kalshiYesAsk,
                kalshiNoAsk
        );
    }
}