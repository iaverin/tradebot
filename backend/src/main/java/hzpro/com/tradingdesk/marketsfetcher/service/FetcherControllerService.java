package hzpro.com.tradingdesk.marketsfetcher.service;

import hzpro.com.tradingdesk.entity.PredictionMarket;
import hzpro.com.tradingdesk.entity.enums.DataSource;
import hzpro.com.tradingdesk.marketsfetcher.service.fetchers.KalshiFetcher;
import hzpro.com.tradingdesk.marketsfetcher.service.fetchers.OpinionFetcher;
import hzpro.com.tradingdesk.marketsfetcher.service.fetchers.PolymarketFetcher;
import hzpro.com.tradingdesk.marketsfetcher.temporal.workflow.FetchWorkflow;
import hzpro.com.tradingdesk.repository.PredictionMarketRepository;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class FetcherControllerService {

    private static final String TASK_QUEUE = "prediction-markets-task-queue";

    private final PredictionMarketRepository repository;
    private final WorkflowClient workflowClient;
    private final KalshiFetcher kalshiFetcher;
    private final PolymarketFetcher polymarketFetcher;
    private final OpinionFetcher opinionFetcher;

    /**
     * Manually trigger Kalshi data fetch via Temporal workflow
     */
    public void fetchKalshiData() {
        log.info("Starting one-time Kalshi fetch workflow");
        FetchWorkflow workflow = workflowClient.newWorkflowStub(
                FetchWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setTaskQueue(TASK_QUEUE)
                        .setWorkflowId("kalshi-manual-" + Instant.now().toEpochMilli())
                        .build()
        );
        WorkflowClient.start(workflow::fetchMarketsFromProvider, "KALSHI");
    }

    /**
     * Manually trigger Polymarket data fetch via Temporal workflow
     */
    public void fetchPolymarketData() {
        log.info("Starting one-time Polymarket fetch workflow");
        FetchWorkflow workflow = workflowClient.newWorkflowStub(
                FetchWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setTaskQueue(TASK_QUEUE)
                        .setWorkflowId("polymarket-manual-" + Instant.now().toEpochMilli())
                        .build()
        );
        WorkflowClient.start(workflow::fetchMarketsFromProvider, "POLYMARKET");
    }

    /**
     * Manually trigger Opinion data fetch via Temporal workflow
     */
    public void fetchOpinionData() {
        log.info("Starting one-time Opinion fetch workflow");
        FetchWorkflow workflow = workflowClient.newWorkflowStub(
                FetchWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setTaskQueue(TASK_QUEUE)
                        .setWorkflowId("opinion-manual-" + Instant.now().toEpochMilli())
                        .build()
        );
        WorkflowClient.start(workflow::fetchMarketsFromProvider, "OPINION");
    }

    /**
     * Get all prediction markets by data source
     */
    public List<PredictionMarket> getByDataSource(DataSource dataSource) {
        return repository.findByDatasource(dataSource);
    }

    /**
     * Get all prediction markets
     */
    public List<PredictionMarket> getAll() {
        return repository.findAll();
    }

    /**
     * Get markets by event ID
     */
    public List<PredictionMarket> getByEventId(String eventId) {
        return repository.findByEventId(eventId);
    }

    /**
     * Get markets opening within a date range
     */
    public List<PredictionMarket> getMarketsOpeningBetween(ZonedDateTime start, ZonedDateTime end) {
        return repository.findByMarketOpenDatetimeBetween(start, end);
    }

    /**
     * Get markets closing within a date range
     */
    public List<PredictionMarket> getMarketsClosingBetween(ZonedDateTime start, ZonedDateTime end) {
        return repository.findByMarketCloseDatetimeBetween(start, end);
    }

    /**
     * Get markets by data source and status
     */
    public List<PredictionMarket> getByDataSourceAndStatus(DataSource dataSource, String status) {
        return repository.findByDatasourceAndMarketStatus(dataSource, status);
    }

    public void stopTasks() {
        kalshiFetcher.setForceStop();
        polymarketFetcher.setForceStop();
        opinionFetcher.setForceStop();
    }

    public void pauseTasks() {
        kalshiFetcher.setPaused(true);
        polymarketFetcher.setPaused(true);
        opinionFetcher.setPaused(true);
    }

    public void resumeTasks() {
        kalshiFetcher.setPaused(false);
        polymarketFetcher.setPaused(false);
        opinionFetcher.setPaused(false);
    }

    @Transactional
    public void clearFetchingData() {
        log.info("Clearing fetching_prediction_markets table");
        repository.truncateFetchingTable();
    }
}
