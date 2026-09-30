package hzpro.com.tradingdesk.portfolio.service;

import hzpro.com.tradingdesk.portfolio.model.PortfolioRefreshStatus;
import hzpro.com.tradingdesk.portfolio.model.PortfolioVenue;
import hzpro.com.tradingdesk.portfolio.model.VenueCollectionResult;
import hzpro.com.tradingdesk.portfolio.repository.PortfolioPositionRefreshStateRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PortfolioPositionRefreshCoordinatorTest {

    @Test
    void keepsVenueFailuresIndependent() {
        PortfolioPositionSnapshotWriter writer = mock(PortfolioPositionSnapshotWriter.class);
        PortfolioPositionRefreshStateRepository stateRepository = mock(PortfolioPositionRefreshStateRepository.class);
        when(stateRepository.findById(any())).thenReturn(Optional.empty());
        Instant committed = Instant.parse("2026-09-10T10:05:00Z");
        when(writer.replaceSnapshot(any(), any(), any())).thenReturn(committed);
        AtomicInteger kalshiAttempts = new AtomicInteger();

        PortfolioPositionRefreshCoordinator coordinator = new PortfolioPositionRefreshCoordinator(
                List.of(
                        collector(PortfolioVenue.POLYMARKET, VenueCollectionResult.failure("Polymarket unavailable")),
                        new PortfolioVenueCollector() {
                            @Override
                            public PortfolioVenue venue() {
                                return PortfolioVenue.KALSHI;
                            }

                            @Override
                            public VenueCollectionResult collect() {
                                kalshiAttempts.incrementAndGet();
                                return VenueCollectionResult.success(List.of());
                            }
                        }),
                writer,
                stateRepository);

        var result = coordinator.refreshAll();

        assertThat(result.allSucceeded()).isFalse();
        assertThat(result.venues()).extracting(item -> item.status())
                .containsExactly(PortfolioRefreshStatus.FAILED, PortfolioRefreshStatus.SUCCESS);
        assertThat(kalshiAttempts).hasValue(1);
    }

    @Test
    void rejectsAnOverlappingRefreshWithoutWaiting() throws Exception {
        CountDownLatch enteredCollector = new CountDownLatch(1);
        CountDownLatch releaseCollector = new CountDownLatch(1);
        PortfolioVenueCollector blockingCollector = new PortfolioVenueCollector() {
            @Override
            public PortfolioVenue venue() {
                return PortfolioVenue.POLYMARKET;
            }

            @Override
            public VenueCollectionResult collect() {
                enteredCollector.countDown();
                try {
                    if (!releaseCollector.await(5, TimeUnit.SECONDS)) {
                        return VenueCollectionResult.failure("Timed out in test");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return VenueCollectionResult.failure("Interrupted in test");
                }
                return VenueCollectionResult.success(List.of());
            }
        };
        PortfolioPositionSnapshotWriter writer = mock(PortfolioPositionSnapshotWriter.class);
        when(writer.replaceSnapshot(any(), any(), any())).thenAnswer(invocation -> invocation.getArgument(2));
        PortfolioPositionRefreshStateRepository stateRepository = mock(PortfolioPositionRefreshStateRepository.class);
        when(stateRepository.findById(any())).thenReturn(Optional.empty());
        PortfolioPositionRefreshCoordinator coordinator = new PortfolioPositionRefreshCoordinator(
                List.of(blockingCollector, collector(PortfolioVenue.KALSHI, VenueCollectionResult.success(List.of()))),
                writer,
                stateRepository);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> running = executor.submit(coordinator::refreshAll);
            assertThat(enteredCollector.await(2, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(coordinator::refreshAll)
                    .isInstanceOf(PortfolioRefreshInProgressException.class);

            releaseCollector.countDown();
            running.get(5, TimeUnit.SECONDS);
            assertThat(coordinator.isRefreshRunning()).isFalse();
        } finally {
            releaseCollector.countDown();
            executor.shutdownNow();
        }
    }

    private PortfolioVenueCollector collector(PortfolioVenue venue, VenueCollectionResult result) {
        return new PortfolioVenueCollector() {
            @Override
            public PortfolioVenue venue() {
                return venue;
            }

            @Override
            public VenueCollectionResult collect() {
                return result;
            }
        };
    }
}
