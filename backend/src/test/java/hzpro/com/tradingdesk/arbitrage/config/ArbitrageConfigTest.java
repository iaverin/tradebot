package hzpro.com.tradingdesk.arbitrage.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class ArbitrageConfigTest {

    @Test
    void activePairsLimitDefaultsToOne() {
        assertThat(validConfig().getActivePairsLimit()).isEqualTo(1);
    }

    @Test
    void acceptsPositiveActivePairsLimitOverride() {
        ArbitrageConfig config = validConfig();
        config.setActivePairsLimit(3);

        assertThatCode(config::validate).doesNotThrowAnyException();
        assertThat(config.getActivePairsLimit()).isEqualTo(3);
    }

    @Test
    void rejectsNonPositiveActivePairsLimit() {
        ArbitrageConfig zero = validConfig();
        zero.setActivePairsLimit(0);
        ArbitrageConfig negative = validConfig();
        negative.setActivePairsLimit(-1);

        assertThatThrownBy(zero::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active-pairs-limit");
        assertThatThrownBy(negative::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active-pairs-limit");
    }

    private ArbitrageConfig validConfig() {
        ArbitrageConfig config = new ArbitrageConfig();
        config.setMaxOrderCost(BigDecimal.valueOf(6));
        return config;
    }
}
