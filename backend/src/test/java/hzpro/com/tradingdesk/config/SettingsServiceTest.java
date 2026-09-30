package hzpro.com.tradingdesk.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import hzpro.com.tradingdesk.arbitrage.config.ArbitrageConfig;
import hzpro.com.tradingdesk.entity.Setting;
import hzpro.com.tradingdesk.repository.SettingsRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("SettingsService tests")
class SettingsServiceTest {

    @Mock
    private SettingsRepository settingsRepository;

    private ArbitrageConfig arbitrageConfig;
    private SettingsService settingsService;

    @BeforeEach
    void setUp() {
        arbitrageConfig = new ArbitrageConfig();
        arbitrageConfig.setMaxOrderCost(BigDecimal.valueOf(6));
        arbitrageConfig.setTradingEnabled(true);
        arbitrageConfig.setConsecutiveErrorsCountToStopTrading(2);
        settingsService = new SettingsService(settingsRepository, arbitrageConfig);
    }

    @Test
    @DisplayName("disables trading when errors count reaches the configured threshold")
    void disablesTradingWhenErrorsCountReachesConfiguredThreshold() {
        when(settingsRepository.findByKey(SettingsService.TRADING_ENABLED_KEY)).thenReturn(Optional.empty());

        assertThat(settingsService.addAndGet(1)).isEqualTo(1);
        assertThat(arbitrageConfig.isTradingEnabled()).isTrue();

        assertThat(settingsService.addAndGet(1)).isEqualTo(2);

        assertThat(arbitrageConfig.isTradingEnabled()).isFalse();
        verify(settingsRepository).save(argThat(setting ->
                SettingsService.TRADING_ENABLED_KEY.equals(setting.getKey())
                        && "false".equals(setting.getValue())));
    }

    @Test
    void activePairsLimitUsesConfigOnlyWhenDatabaseRowIsAbsent() {
        arbitrageConfig.setActivePairsLimit(2);
        when(settingsRepository.findByKey(SettingsService.ACTIVE_PAIRS_LIMIT_KEY))
                .thenReturn(Optional.empty());

        assertThat(settingsService.getActivePairsLimit()).isEqualTo(2);
    }

    @Test
    void activePairsLimitReadsAndPersistsDatabaseValue() {
        Setting existing = new Setting();
        existing.setKey(SettingsService.ACTIVE_PAIRS_LIMIT_KEY);
        existing.setValue("2");
        when(settingsRepository.findByKey(SettingsService.ACTIVE_PAIRS_LIMIT_KEY))
                .thenReturn(Optional.of(existing));

        assertThat(settingsService.getActivePairsLimit()).isEqualTo(2);
        assertThat(settingsService.setActivePairsLimit(4)).isEqualTo(4);

        verify(settingsRepository).save(argThat(setting ->
                SettingsService.ACTIVE_PAIRS_LIMIT_KEY.equals(setting.getKey())
                        && "4".equals(setting.getValue())));
    }

    @Test
    void activePairsLimitRejectsInvalidInputAndCorruptStoredValue() {
        assertThatThrownBy(() -> settingsService.setActivePairsLimit(0))
                .isInstanceOf(IllegalArgumentException.class);

        Setting corrupt = new Setting();
        corrupt.setKey(SettingsService.ACTIVE_PAIRS_LIMIT_KEY);
        corrupt.setValue("not-a-number");
        when(settingsRepository.findByKey(SettingsService.ACTIVE_PAIRS_LIMIT_KEY))
                .thenReturn(Optional.of(corrupt));

        assertThatThrownBy(settingsService::getActivePairsLimit)
                .isInstanceOf(IllegalStateException.class);
    }
}
