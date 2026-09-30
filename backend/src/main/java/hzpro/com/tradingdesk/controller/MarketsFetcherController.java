package hzpro.com.tradingdesk.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import hzpro.com.tradingdesk.controller.dto.FetcherStatusResponse;
import hzpro.com.tradingdesk.controller.dto.WorkflowStartResponse;
import hzpro.com.tradingdesk.entity.PredictionMarket;
import hzpro.com.tradingdesk.entity.enums.DataSource;
import hzpro.com.tradingdesk.marketsfetcher.lib.FetcherState;
import hzpro.com.tradingdesk.marketsfetcher.service.FetcherControllerService;
import hzpro.com.tradingdesk.marketsfetcher.service.MarketsRefreshLauncher;
import hzpro.com.tradingdesk.marketsfetcher.service.fetchers.KalshiFetcher;
import hzpro.com.tradingdesk.marketsfetcher.service.fetchers.OpinionFetcher;
import hzpro.com.tradingdesk.marketsfetcher.service.fetchers.PolymarketFetcher;
import hzpro.com.tradingdesk.marketsfetcher.temporal.scheduler.TemporalSchedulerService;
import java.time.ZonedDateTime;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;


@RestController
@RequestMapping("/markets-fetcher")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("isAuthenticated()")
public class MarketsFetcherController {

    private final FetcherControllerService fetchControllerService;
    private final TemporalSchedulerService temporalSchedulerService;
    private final MarketsRefreshLauncher marketsRefreshLauncher;

    private final PolymarketFetcher polymarketFetcher;
    private final KalshiFetcher kalshiFetcher;
    private final OpinionFetcher opinionFetcher;

    @GetMapping("/next-cron-time")
    public Instant getNextCronTime() {
        return temporalSchedulerService.getNextExecutionTime();
    }

    /**
     * Manually trigger MarketsRefreshWorkflow execution
     */
    @PostMapping("/run-markets-refresh")
    public ResponseEntity<WorkflowStartResponse> runMarketsRefresh() {
        log.info("Received request to manually trigger MarketsRefreshWorkflow");
        try {
            String workflowId = marketsRefreshLauncher.start();
            return ResponseEntity.ok(new WorkflowStartResponse(
                    "success", "MarketsRefreshWorkflow started", workflowId));
        } catch (Exception e) {
            log.error("Failed to start MarketsRefreshWorkflow", e);
            return ResponseEntity.internalServerError()
                    .body(new WorkflowStartResponse("error", e.getMessage(), null));
        }
    }

    /**
     * Manually trigger Kalshi data fetch
     */
    @PostMapping("/fetch-kalshi")
    public ResponseEntity<Map<String, String>> fetchKalshi() {
        log.info("Received request to fetch Kalshi data");
        try {
            fetchControllerService.fetchKalshiData();
            return ResponseEntity.ok(Map.of("status", "success", "message", "Kalshi fetch initiated"));
        } catch (Exception e) {
            log.error("Failed to fetch Kalshi data", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("status", "error", "message", e.getMessage()));
        }
    }

    /**
     * Manually trigger Polymarket data fetch
     */
    @PostMapping("/fetch-polymarket")
    public ResponseEntity<Map<String, String>> fetchPolymarket() {
        log.info("Received request to fetch Polymarket data");
        try {
            fetchControllerService.fetchPolymarketData();
            return ResponseEntity.ok(Map.of("status", "success", "message", "Polymarket fetch initiated"));
        } catch (Exception e) {
            log.error("Failed to fetch Polymarket data", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("status", "error", "message", e.getMessage()));
        }
    }

    /**
     * Manually trigger Opinion data fetch
     */
    @PostMapping("/fetch-opinion")
    public ResponseEntity<Map<String, String>> fetchOpinion() {
        log.info("Received request to fetch Opinion data");
        try {
            fetchControllerService.fetchOpinionData();
            return ResponseEntity.ok(Map.of("status", "success", "message", "Opinion fetch initiated"));
        } catch (Exception e) {
            log.error("Failed to fetch Opinion data", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("status", "error", "message", e.getMessage()));
        }
    }

    @PostMapping("/clear-fetching-data")
    public ResponseEntity<Map<String, String>> clearFetchingData() {
        log.info("Received request to clear fetching data");
        try {
            fetchControllerService.clearFetchingData();
            return ResponseEntity.ok(Map.of("status", "success", "message", "Fetching data cleared"));
        } catch (Exception e) {
            log.error("Failed to clear fetching data", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("status", "error", "message", e.getMessage()));
        }
    }

        @PostMapping("/stop")
        public ResponseEntity<Map<String, String>> stop() {
            fetchControllerService.stopTasks();
            return ResponseEntity.ok(Map.of("status", "success", "message",  "tasks stopped"));
        }

        @PostMapping("/pause")
        public ResponseEntity<Map<String, String>> pause() {
            fetchControllerService.pauseTasks();
            return ResponseEntity.ok(Map.of("status", "success", "message",  "tasks paused"));

        }

        @PostMapping("/resume")
        public ResponseEntity<Map<String, String>> resume() {
            fetchControllerService.resumeTasks();
            return ResponseEntity.ok(Map.of("status", "success", "message", "tasks resumed"));
        }

        @GetMapping("/status")
        public FetcherStatusResponse getPolymarketFetchCount()
        {
            var polymarketLastSateOptional = Optional.ofNullable(polymarketFetcher.getLastState());
            var kalshiLastStateOptional = Optional.ofNullable(kalshiFetcher.getLastState());
            var opinionLastStateOptional = Optional.ofNullable(opinionFetcher.getLastState());

            return new FetcherStatusResponse(
                    polymarketLastSateOptional.map(
                        FetcherState::getMarketsFetched).orElse(0L),
                    polymarketLastSateOptional.map(
                        FetcherState::getRequestNumber).orElse(0L),
                    kalshiLastStateOptional.map(
                        FetcherState::getMarketsFetched).orElse(0L),
                    kalshiLastStateOptional.map(
                        FetcherState::getRequestNumber).orElse(0L),
                    opinionLastStateOptional.map(
                        FetcherState::getMarketsFetched).orElse(0L),
                    opinionLastStateOptional.map(
                        FetcherState::getRequestNumber).orElse(0L)
                );
        }


}
