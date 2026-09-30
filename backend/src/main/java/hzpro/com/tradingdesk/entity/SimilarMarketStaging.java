package hzpro.com.tradingdesk.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "similar_markets_staging")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SimilarMarketStaging {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(nullable = false)
    private Double similarity;

    @Column(name = "polymarket_event_ticker", nullable = false)
    private String polymarketEventTicker;

    @Column(name = "polymarket_event_title", nullable = false)
    private String polymarketEventTitle;

    @Column(name = "polymarket_market_ticker", nullable = false)
    private String polymarketMarketTicker;

    @Column(name = "polymarket_market_title", nullable = false)
    private String polymarketMarketTitle;

    @Column(name = "kalshi_event_ticker", nullable = false)
    private String kalshiEventTicker;

    @Column(name = "kalshi_event_title", nullable = false)
    private String kalshiEventTitle;

    @Column(name = "kalshi_market_ticker", nullable = false)
    private String kalshiMarketTicker;

    @Column(name = "kalshi_market_title", nullable = false)
    private String kalshiMarketTitle;

    @Builder.Default
    @Column(nullable = false)
    private Boolean enabled = true;
}
