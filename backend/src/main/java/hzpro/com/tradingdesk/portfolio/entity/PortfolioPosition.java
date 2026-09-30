package hzpro.com.tradingdesk.portfolio.entity;

import hzpro.com.tradingdesk.domain.enums.YesOrNoResult;
import hzpro.com.tradingdesk.entity.enums.DataSource;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "portfolio_positions")
@Getter
@Setter
@NoArgsConstructor
public class PortfolioPosition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private DataSource venue;

    @Column(name = "source_position_id", nullable = false)
    private String sourcePositionId;

    @Column(name = "market_ticker", nullable = false)
    private String marketTicker;

    @Column(name = "condition_id")
    private String conditionId;

    @Column(name = "event_ticker")
    private String eventTicker;

    @Column(name = "event_title")
    private String eventTitle;

    @Column(name = "market_title")
    private String marketTitle;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private YesOrNoResult outcome;

    @Column(nullable = false, precision = 30, scale = 10)
    private BigDecimal quantity;

    @Column(name = "spent_usd", precision = 30, scale = 10)
    private BigDecimal spentUsd;

    @Column(name = "current_value_usd", precision = 30, scale = 10)
    private BigDecimal currentValueUsd;

    @Column(name = "source_updated_at")
    private Instant sourceUpdatedAt;

    @Column(name = "refreshed_at", nullable = false)
    private Instant refreshedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
