package hzpro.com.tradingdesk.portfolio.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(
        name = "portfolio.positions.scheduling-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class PortfolioPositionRefreshScheduler {

    private final PortfolioPositionRefreshCoordinator refreshCoordinator;

    public PortfolioPositionRefreshScheduler(PortfolioPositionRefreshCoordinator refreshCoordinator) {
        this.refreshCoordinator = refreshCoordinator;
    }

    @Scheduled(
            fixedDelayString = "${portfolio.positions.refresh-delay-ms:300000}",
            initialDelayString = "${portfolio.positions.initial-delay-ms:0}")
    public void refreshPositions() {
        try {
            refreshCoordinator.refreshAll();
        } catch (PortfolioRefreshInProgressException exception) {
            log.info("Skipping scheduled portfolio refresh because another refresh is running");
        }
    }
}
