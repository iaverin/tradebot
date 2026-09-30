package hzpro.com.tradingdesk.marketsfetcher.temporal.activity;

import hzpro.com.tradingdesk.arbitrage.service.ArbitrageMonitorService;
import hzpro.com.tradingdesk.marketsfetcher.service.fetchers.KalshiFetcher;
import hzpro.com.tradingdesk.marketsfetcher.service.fetchers.OpinionFetcher;
import hzpro.com.tradingdesk.marketsfetcher.service.fetchers.PolymarketFetcher;
import hzpro.com.tradingdesk.repository.PredictionMarketRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Slf4j
public class FetchActivityImpl implements FetchActivity {

    private final KalshiFetcher kalshiFetcher;
    private final PolymarketFetcher polymarketFetcher;
    private final OpinionFetcher opinionFetcher;
    private final PredictionMarketRepository predictionMarketRepository;
    /** Lazy provider to avoid potential circular wiring and tolerate the bean being absent. */
    private final ObjectProvider<ArbitrageMonitorService> arbitrageMonitorProvider;

    @Override
    @Transactional
    public void clearFetchingData() {
        log.info("Temporal activity: clearing fetching_prediction_markets table");
        predictionMarketRepository.truncateFetchingTable();
        log.info("Temporal activity: fetching_prediction_markets table cleared");
    }

    @Override
    public void fetchKalshi() {
        log.info("Temporal activity: starting Kalshi fetch");
        kalshiFetcher.fetchAll();
        log.info("Temporal activity: Kalshi fetch completed");
    }

    @Override
    public void fetchPolymarket() {
        log.info("Temporal activity: starting Polymarket fetch");
        polymarketFetcher.fetchAll();
        log.info("Temporal activity: Polymarket fetch completed");
    }

    @Override
    public void fetchOpinion() {
        log.info("Temporal activity: starting Opinion fetch");
        opinionFetcher.fetchAll();
        log.info("Temporal activity: Opinion fetch completed");
    }

    @Override
    @Transactional
    public void publishMarketsSnapshot() {
        log.info("Temporal activity: atomically publishing prediction and similar markets snapshot");
        predictionMarketRepository.truncatePublishedTables();
        predictionMarketRepository.copyFetchingToPredictionMarkets();
        predictionMarketRepository.copyStagingToSimilarMarkets();
        log.info("Temporal activity: markets snapshot published");
    }

    @Override
    public void stopArbitrageMonitor() {
        ArbitrageMonitorService monitor = arbitrageMonitorProvider.getIfAvailable();
        if (monitor == null) {
            log.info("Temporal activity: ArbitrageMonitorService not available, skipping stop");
            return;
        }
        if (!monitor.isRunning()) {
            log.info("Temporal activity: ArbitrageMonitorService already stopped");
            return;
        }
        log.info("Temporal activity: stopping ArbitrageMonitorService");
        monitor.stop();
        log.info("Temporal activity: ArbitrageMonitorService stopped");
    }

    @Override
    public void startArbitrageMonitor() {
        ArbitrageMonitorService monitor = arbitrageMonitorProvider.getIfAvailable();
        if (monitor == null) {
            log.info("Temporal activity: ArbitrageMonitorService not available, skipping start");
            return;
        }
        if (monitor.isRunning()) {
            log.info("Temporal activity: ArbitrageMonitorService already running, restarting to refresh pair set");
        } else {
            log.info("Temporal activity: starting ArbitrageMonitorService");
        }
        monitor.restart();
        log.info("Temporal activity: ArbitrageMonitorService started (running={})", monitor.isRunning());
    }
}
