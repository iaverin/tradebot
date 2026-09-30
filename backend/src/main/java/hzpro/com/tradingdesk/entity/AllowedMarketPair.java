package hzpro.com.tradingdesk.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "allowed_market_pairs")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AllowedMarketPair {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "polymarket_ticker", nullable = false)
    private String polymarketTicker;

    @Column(name = "kalshi_ticker", nullable = false)
    private String kalshiTicker;

    @Column(name = "created_at")
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }
}
