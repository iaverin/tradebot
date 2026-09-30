package hzpro.com.tradingdesk.portfolio.service;

public class PortfolioRefreshInProgressException extends RuntimeException {
    public PortfolioRefreshInProgressException() {
        super("Portfolio update is already in progress");
    }
}
