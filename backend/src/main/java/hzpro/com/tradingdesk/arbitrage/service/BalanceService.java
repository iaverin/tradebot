package hzpro.com.tradingdesk.arbitrage.service;

import hzpro.com.tradingdesk.service.account.KalshiBalanceService;
import hzpro.com.tradingdesk.service.account.PolymarketBalanceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * Unified balance cache for pre-trade balance validation.
 *
 * <p>Balances are fetched from both venues and cached in {@code volatile} fields.
 * The cache is refreshed on monitor start, on reset, and after every successful
 * order placement. The {@link #hasSufficientBalance} check reads the cached
 * values only — no per-check API calls.
 *
 * <p>Zero is the safe default for uninitialized balances: it blocks trading until
 * the first successful refresh.
 */
@Slf4j
@Service
public class BalanceService {

    private final KalshiBalanceService kalshiBalanceService;
    private final PolymarketBalanceService polymarketBalanceService;

    private volatile BigDecimal kalshiBalance = BigDecimal.ZERO;
    private volatile BigDecimal polymarketBalance = BigDecimal.ZERO;

    public BalanceService(KalshiBalanceService kalshiBalanceService,
                          PolymarketBalanceService polymarketBalanceService) {
        this.kalshiBalanceService = kalshiBalanceService;
        this.polymarketBalanceService = polymarketBalanceService;
    }

    /**
     * Fetches current balances from both venues and caches them.
     * Failure on one venue does not prevent refreshing the other.
     */
    public void refreshBalances() {
        refreshKalshiBalance();
        refreshPolymarketBalance();
    }

    private void refreshKalshiBalance() {
        try {
            var balance = kalshiBalanceService.getBalance();
            kalshiBalance = balance.balanceDollars();
            log.info("Kalshi balance refreshed: ${}", kalshiBalance);
        } catch (Exception e) {
            log.warn("Failed to refresh Kalshi balance (keeping stale value ${}): {}",
                    kalshiBalance, e.getMessage());
        }
    }

    private void refreshPolymarketBalance() {
        try {
            var balance = polymarketBalanceService.getBalance();
            polymarketBalance = balance.balance();
            log.info("Polymarket balance refreshed: ${}", polymarketBalance);
        } catch (Exception e) {
            log.warn("Failed to refresh Polymarket balance (keeping stale value ${}): {}",
                    polymarketBalance, e.getMessage());
        }
    }

    /**
     * Returns true when both cached balances are at least the given amounts.
     * Does NOT make API calls — reads the {@code volatile} fields only.
     */
    public boolean hasSufficientBalance(BigDecimal requiredKalshi, BigDecimal requiredPolymarket) {
        boolean sufficient = kalshiBalance.compareTo(requiredKalshi) > 0
                && polymarketBalance.compareTo(requiredPolymarket) > 0;
        log.info("Balance check: kalshi=${} (need ${}), polymarket=${} (need ${}) -> {}",
                kalshiBalance, requiredKalshi, polymarketBalance, requiredPolymarket,
                sufficient ? "SUFFICIENT" : "INSUFFICIENT");
        return sufficient;
    }

    public BigDecimal getKalshiBalance() {
        return kalshiBalance;
    }

    public BigDecimal getPolymarketBalance() {
        return polymarketBalance;
    }
}
