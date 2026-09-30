package hzpro.com.tradingdesk.portfolio.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "portfolio_position_refresh_state")
@Getter
@Setter
@NoArgsConstructor
public class PortfolioPositionRefreshState {

    @Id
    @Column(nullable = false, length = 50)
    private String venue;

    @Column(name = "last_successful_refresh_at", nullable = false)
    private Instant lastSuccessfulRefreshAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
