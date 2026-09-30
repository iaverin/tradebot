package hzpro.com.tradingdesk.portfolio.service;

import hzpro.com.tradingdesk.portfolio.model.PortfolioVenue;
import hzpro.com.tradingdesk.portfolio.model.VenueCollectionResult;

public interface PortfolioVenueCollector {
    PortfolioVenue venue();

    VenueCollectionResult collect();
}
