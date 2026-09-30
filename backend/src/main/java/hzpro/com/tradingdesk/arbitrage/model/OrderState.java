package hzpro.com.tradingdesk.arbitrage.model;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

public enum OrderState {
    CREATED,
    PENDING,
    PLACED,
    EXECUTED,
    CANCELED,
    ERROR,
    UNKNOWN;

    private static final Set<OrderState> ACTIVE_STATES = Collections.unmodifiableSet(
            EnumSet.of(CREATED, PENDING, PLACED, UNKNOWN));

    public boolean isActive() {
        return ACTIVE_STATES.contains(this);
    }

    public boolean isTerminal() {
        return !isActive();
    }

    public static Set<OrderState> activeStates() {
        return ACTIVE_STATES;
    }
}
