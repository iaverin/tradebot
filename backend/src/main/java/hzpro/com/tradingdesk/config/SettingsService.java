package hzpro.com.tradingdesk.config;

import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import hzpro.com.tradingdesk.arbitrage.config.ArbitrageConfig;
import hzpro.com.tradingdesk.controller.dto.SettingsInfoDto;
import hzpro.com.tradingdesk.entity.Setting;
import hzpro.com.tradingdesk.repository.SettingsRepository;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class SettingsService {

    public static final String TRADING_ENABLED_KEY = "TRADING_ENABLED";
    public static final String ACTIVE_PAIRS_LIMIT_KEY = "ACTIVE_PAIRS_LIMIT";

    /** Polymarket default min_order_size (5 CTF tokens). */
    public static final int MINIMUM_ORDER_SHARES = 5;

    private final SettingsRepository repo;
    private final ArbitrageConfig arbitrageConfig;

    private final AtomicInteger ordersErrorsCount = new AtomicInteger();

    public SettingsService(SettingsRepository repo, ArbitrageConfig arbitrageConfig) {
        this.repo = repo;
        this.arbitrageConfig = arbitrageConfig;
    }

    public SettingsInfoDto getSettingsInfo() {
        return new SettingsInfoDto(
                arbitrageConfig.getMaxOrderCost(),
                MINIMUM_ORDER_SHARES,
                getErrorsCount(),
                getActivePairsLimit());
    }

    /**
     * Called at monitor start/restart. Loads persisted settings from DB
     * and applies them to the live {@link ArbitrageConfig} bean.
     * If no DB row exists, the env-var default already on the bean is kept.
     */
    public void loadSettings() {
        repo.findByKey(TRADING_ENABLED_KEY).ifPresentOrElse(
                s -> arbitrageConfig.setTradingEnabled(Boolean.parseBoolean(s.getValue())),
                () -> { /* keep env-var default already on the bean */ }
        );
    }

    public boolean getTradingEnabled() {
        return repo.findByKey(TRADING_ENABLED_KEY)
                .map(s -> Boolean.parseBoolean(s.getValue()))
                .orElseGet(arbitrageConfig::isTradingEnabled);
    }

    public boolean setTradingEnabled(boolean enabled) {
        Setting s = repo.findByKey(TRADING_ENABLED_KEY)
                .orElseGet(() -> {
                    Setting ns = new Setting();
                    ns.setKey(TRADING_ENABLED_KEY);
                    return ns;
                });
        s.setValue(String.valueOf(enabled));
        repo.save(s);
        arbitrageConfig.setTradingEnabled(enabled);
        return enabled;
    }

    public int getActivePairsLimit() {
        return repo.findByKey(ACTIVE_PAIRS_LIMIT_KEY)
                .map(setting -> parseActivePairsLimit(setting.getValue()))
                .orElseGet(arbitrageConfig::getActivePairsLimit);
    }

    @Transactional
    public int setActivePairsLimit(int activePairsLimit) {
        validateActivePairsLimit(activePairsLimit);
        Setting setting = repo.findByKey(ACTIVE_PAIRS_LIMIT_KEY)
                .orElseGet(() -> {
                    Setting created = new Setting();
                    created.setKey(ACTIVE_PAIRS_LIMIT_KEY);
                    return created;
                });
        setting.setValue(Integer.toString(activePairsLimit));
        repo.save(setting);
        return activePairsLimit;
    }

    public int getErrorsCount() {
        return ordersErrorsCount.get();
    }

    public int addAndGet(int delta) {
        int maxErrorsCount = arbitrageConfig.getConsecutiveErrorsCountToStopTrading();
        int errorsCount = ordersErrorsCount.addAndGet(delta);

        if (errorsCount >= maxErrorsCount) {
            log.info("Errors count {} is above limit {}. Will disable trading.",
                    errorsCount,
                    maxErrorsCount);
            setTradingEnabled(false);
        }
        return errorsCount;
    }

    public void resetErrorsCount() {
        ordersErrorsCount.set(0);
    }

    private int parseActivePairsLimit(String value) {
        try {
            int parsed = Integer.parseInt(value);
            validateActivePairsLimit(parsed);
            return parsed;
        } catch (NumberFormatException ex) {
            throw new IllegalStateException("Stored ACTIVE_PAIRS_LIMIT must be an integer", ex);
        }
    }

    private void validateActivePairsLimit(int activePairsLimit) {
        if (activePairsLimit < 1) {
            throw new IllegalArgumentException("Active pairs limit must be at least 1");
        }
    }
}
