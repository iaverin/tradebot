package hzpro.com.tradingdesk.arbitrage.model;

import java.math.BigDecimal;
import java.time.Instant;

import org.jspecify.annotations.Nullable;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PriceSnapshot {

    private String platform;
    @Nullable
    private BigDecimal yesAsk;
    @Nullable
    private BigDecimal noAsk;
    private Instant updatedAt;

    @Builder.Default
    private boolean fromRest = false;

    public boolean isComplete() {
        return yesAsk != null && noAsk != null;
    }
}