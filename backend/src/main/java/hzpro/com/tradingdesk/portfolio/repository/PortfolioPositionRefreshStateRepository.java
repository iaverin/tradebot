package hzpro.com.tradingdesk.portfolio.repository;

import hzpro.com.tradingdesk.portfolio.entity.PortfolioPositionRefreshState;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PortfolioPositionRefreshStateRepository
        extends JpaRepository<PortfolioPositionRefreshState, String> {
}
