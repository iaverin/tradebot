package hzpro.com.tradingdesk.arbitrage.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "arbitrage_events_markets_info")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ArbitrageEventMarketsInfo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "uuid", nullable = false)
    private UUID uuid;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "markets_info", nullable = false, columnDefinition = "jsonb")
    private String marketsInfo;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "similar_markets_info", nullable = false, columnDefinition = "jsonb")
    private String similarMarketsInfo;

    @Column(name = "created_at")
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }
}