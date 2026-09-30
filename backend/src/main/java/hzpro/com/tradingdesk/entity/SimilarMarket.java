package hzpro.com.tradingdesk.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "similar_markets")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SimilarMarket {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    private Double similarity;

    @Column(name = "polymarket_event_ticker")
    private String polymarketEventTicker;

    @Column(name = "polymarket_event_title")
    private String polymarketEventTitle;

    @Column(name = "polymarket_market_ticker")
    private String polymarketMarketTicker;

    @Column(name = "polymarket_market_title")
    private String polymarketMarketTitle;

    @Column(name = "kalshi_event_ticker")
    private String kalshiEventTicker;

    @Column(name = "kalshi_event_title")
    private String kalshiEventTitle;

    @Column(name = "kalshi_market_ticker")
    private String kalshiMarketTicker;

    @Column(name = "kalshi_market_title")
    private String kalshiMarketTitle;

    @Builder.Default
    private Boolean enabled = true;
}