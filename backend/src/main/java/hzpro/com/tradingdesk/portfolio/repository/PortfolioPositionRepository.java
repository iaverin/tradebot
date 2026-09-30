package hzpro.com.tradingdesk.portfolio.repository;

import hzpro.com.tradingdesk.portfolio.entity.PortfolioPosition;
import hzpro.com.tradingdesk.entity.enums.DataSource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PortfolioPositionRepository extends JpaRepository<PortfolioPosition, Long> {

    List<PortfolioPosition> findByVenue(DataSource venue);

    Page<PortfolioPosition> findByVenue(DataSource venue, Pageable pageable);

    List<PortfolioPosition> findByVenueAndMarketTickerIn(DataSource venue, List<String> marketTickers);
}
