package hzpro.com.tradingdesk.portfolio.service;

import hzpro.com.tradingdesk.portfolio.model.PortfolioRefreshResult;
import hzpro.com.tradingdesk.portfolio.model.PortfolioRefreshStatus;
import hzpro.com.tradingdesk.portfolio.model.PortfolioVenue;
import hzpro.com.tradingdesk.portfolio.model.PortfolioVenueRefreshResult;
import hzpro.com.tradingdesk.portfolio.model.VenueCollectionResult;
import hzpro.com.tradingdesk.portfolio.repository.PortfolioPositionRefreshStateRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
public class PortfolioPositionRefreshCoordinator {

    private final Map<PortfolioVenue, PortfolioVenueCollector> collectors;
    private final PortfolioPositionSnapshotWriter snapshotWriter;
    private final PortfolioPositionRefreshStateRepository refreshStateRepository;
    private final AtomicBoolean refreshRunning = new AtomicBoolean(false);

    public PortfolioPositionRefreshCoordinator(
            List<PortfolioVenueCollector> collectors,
            PortfolioPositionSnapshotWriter snapshotWriter,
            PortfolioPositionRefreshStateRepository refreshStateRepository
    ) {
        this.collectors = new EnumMap<>(PortfolioVenue.class);
        for (PortfolioVenueCollector collector : collectors) {
            this.collectors.put(collector.venue(), collector);
        }
        this.snapshotWriter = snapshotWriter;
        this.refreshStateRepository = refreshStateRepository;
    }

    public PortfolioRefreshResult refreshAll() {
        if (!refreshRunning.compareAndSet(false, true)) {
            throw new PortfolioRefreshInProgressException();
        }
        Instant requestedAt = Instant.now();
        try {
            List<PortfolioVenueRefreshResult> results = new ArrayList<>();
            for (PortfolioVenue venue : List.of(PortfolioVenue.POLYMARKET, PortfolioVenue.KALSHI)) {
                results.add(refreshVenue(venue));
            }
            boolean allSucceeded = results.stream()
                    .allMatch(result -> result.status() == PortfolioRefreshStatus.SUCCESS);
            return new PortfolioRefreshResult(requestedAt, allSucceeded, results);
        } finally {
            refreshRunning.set(false);
        }
    }

    public boolean isRefreshRunning() {
        return refreshRunning.get();
    }

    private PortfolioVenueRefreshResult refreshVenue(PortfolioVenue venue) {
        PortfolioVenueCollector collector = collectors.get(venue);
        if (collector == null) {
            return failed(venue, "Portfolio collector is not configured");
        }
        try {
            VenueCollectionResult collection = collector.collect();
            if (!collection.successful()) {
                log.warn("{} portfolio refresh failed: {}", venue, collection.message());
                return failed(venue, safeMessage(collection.message(), venue));
            }
            Instant refreshedAt = snapshotWriter.replaceSnapshot(venue, collection.positions(), Instant.now());
            return new PortfolioVenueRefreshResult(
                    venue,
                    PortfolioRefreshStatus.SUCCESS,
                    refreshedAt,
                    null);
        } catch (Exception exception) {
            log.warn("{} portfolio refresh failed: {}", venue, exception.getMessage());
            return failed(venue, venue + " portfolio update failed");
        }
    }

    private PortfolioVenueRefreshResult failed(PortfolioVenue venue, String message) {
        Instant lastSuccess = refreshStateRepository.findById(venue.name())
                .map(state -> state.getLastSuccessfulRefreshAt())
                .orElse(null);
        return new PortfolioVenueRefreshResult(
                venue,
                PortfolioRefreshStatus.FAILED,
                lastSuccess,
                message);
    }

    private String safeMessage(String message, PortfolioVenue venue) {
        if (message == null || message.isBlank()) {
            return venue + " portfolio update failed";
        }
        return message.length() > 200 ? message.substring(0, 200) : message;
    }
}
