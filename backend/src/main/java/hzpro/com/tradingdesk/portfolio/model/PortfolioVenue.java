package hzpro.com.tradingdesk.portfolio.model;

import java.util.Locale;

public enum PortfolioVenue {
    POLYMARKET,
    KALSHI;

    public static PortfolioVenue parse(String value) {
        if (value == null) {
            return POLYMARKET;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("venue must be POLYMARKET or KALSHI");
        }
    }

    public PortfolioVenue other() {
        return this == POLYMARKET ? KALSHI : POLYMARKET;
    }
}
