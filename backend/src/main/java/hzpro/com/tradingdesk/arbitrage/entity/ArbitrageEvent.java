package hzpro.com.tradingdesk.arbitrage.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import hzpro.com.tradingdesk.arbitrage.model.OpportunityCloseReason;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "arbitrage_events")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ArbitrageEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "uuid", nullable = false, unique = true)
    @Builder.Default
    private UUID uuid = UUID.randomUUID();

    @Column(name = "detected_at", nullable = false, updatable = false)
    private Instant detectedAt;

    @Column(name = "event_type", nullable = false, length = 20)
    private String eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "close_reason", length = 50)
    private OpportunityCloseReason closeReason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "opportunity_start_id")
    private ArbitrageEvent opportunityStart;

    @Column(name = "similar_market_id", nullable = false)
    private Long similarMarketId;

    @Column(name = "polymarket_market_ticker", nullable = false)
    private String polymarketMarketTicker;

    @Column(name = "kalshi_market_ticker", nullable = false)
    private String kalshiMarketTicker;

    @Column(name = "direction", nullable = false, length = 20)
    private String direction;

    @Column(name = "polymarket_yes_ask", precision = 8, scale = 6)
    private BigDecimal polymarketYesAsk;

    @Column(name = "polymarket_no_ask", precision = 8, scale = 6)
    private BigDecimal polymarketNoAsk;

    @Column(name = "kalshi_yes_ask", precision = 8, scale = 6)
    private BigDecimal kalshiYesAsk;

    @Column(name = "kalshi_no_ask", precision = 8, scale = 6)
    private BigDecimal kalshiNoAsk;

    @Column(name = "spread", nullable = false, precision = 8, scale = 6)
    private BigDecimal spread;

    @Column(name = "threshold", nullable = false, precision = 8, scale = 6)
    private BigDecimal threshold;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "polymarket_orderbook", nullable = false, columnDefinition = "jsonb")
    private String polymarketOrderbook;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "kalshi_orderbook", nullable = false, columnDefinition = "jsonb")
    private String kalshiOrderbook;

    @JsonProperty("uuid")
    public UUID getUuid() {
        return uuid;
    }

    @PrePersist
    protected void onCreate() {
        if (detectedAt == null) {
            detectedAt = Instant.now();
        }
        if (uuid == null) {
            uuid = UUID.randomUUID();
        }
        if ("OPPORTUNITY_END".equals(eventType) && closeReason == null) {
            closeReason = OpportunityCloseReason.UNKNOWN;
        }
    }
}
