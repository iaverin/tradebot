package hzpro.com.tradingdesk.controller.dto;

/**
 * Top-level portfolio dashboard response — one {@link PortfolioVenueDto} per venue.
 */
public record PortfolioDashboardDto(
        PortfolioVenueDto kalshi,
        PortfolioVenueDto polymarket
) {}
