package hzpro.com.tradingdesk.arbitrage.model;

import lombok.Getter;

import java.math.BigDecimal;

@Getter
public enum ArbitrageDirection {

    PM_YES_KS_NO("PM_YES_KS_NO", true, false),
    PM_NO_KS_YES("PM_NO_KS_YES", false, true);

    private final String code;
    private final boolean usePmYes;
    private final boolean useKsYes;

    ArbitrageDirection(String code, boolean usePmYes, boolean useKsYes) {
        this.code = code;
        this.usePmYes = usePmYes;
        this.useKsYes = useKsYes;
    }

    public BigDecimal calculateSpread(BigDecimal pmYesAsk, BigDecimal pmNoAsk,
                                      BigDecimal ksYesAsk, BigDecimal ksNoAsk) {
        BigDecimal pmPrice = usePmYes ? pmYesAsk : pmNoAsk;
        BigDecimal ksPrice = useKsYes ? ksYesAsk : ksNoAsk;

        if (pmPrice == null || ksPrice == null) {
            return BigDecimal.ZERO;
        }

        return BigDecimal.ONE.subtract(pmPrice.add(ksPrice));
    }
}