package hzpro.com.tradingdesk.controller;

import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import hzpro.com.tradingdesk.arbitrage.config.ArbitrageConfig;
import hzpro.com.tradingdesk.controller.dto.AllowedMarketPairsCountResponse;
import hzpro.com.tradingdesk.controller.dto.EventToggleRequest;
import hzpro.com.tradingdesk.controller.dto.EventToggleResponse;
import hzpro.com.tradingdesk.controller.dto.WorkflowStartResponse;
import hzpro.com.tradingdesk.entity.AllowedMarketPair;
import hzpro.com.tradingdesk.repository.AllowedMarketPairRepository;
import hzpro.com.tradingdesk.repository.PredictionMarketRepository;
import hzpro.com.tradingdesk.similarmarkets.repository.SimilarMarketsRepository;
import hzpro.com.tradingdesk.similarmarkets.service.SimilarMarketsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/similar-markets")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("isAuthenticated()")
public class SimilarMarketsController {

    private final ArbitrageConfig config;

    private final SimilarMarketsService similarMarketsService;
    private final SimilarMarketsRepository similarMarketsRepository;
    private final PredictionMarketRepository predictionMarketRepository;
    private final AllowedMarketPairRepository allowedMarketPairRepository;

    @GetMapping("/count-fetched-markets")
    public ResponseEntity<List<Map<String, Object>>> countFetchedMarkets() {
        return ResponseEntity.ok(similarMarketsService.countFetchedMarketsByDatasource());
    }

    @GetMapping("/count-similar-markets")
    public ResponseEntity<Map<String, Long>> countSimilarMarkets() {
        return ResponseEntity.ok(similarMarketsService.countSimilarMarketsAndEvents());
    }

    @GetMapping("/count-allowed-market-pairs")
    public ResponseEntity<AllowedMarketPairsCountResponse> countAllowedMarketPairs() {
        long count = predictionMarketRepository.findActivePairs(config.getPairLimit()).size();

        return ResponseEntity.ok(new AllowedMarketPairsCountResponse(count));
    }

    @PostMapping("/recalculate-similar-markets")
    public ResponseEntity<WorkflowStartResponse> recalculateSimilarMarkets() {
        log.info("Received request to refresh markets and recalculate similar markets");
        try {
            String workflowId = similarMarketsService.triggerRecalculation();
            return ResponseEntity.ok(new WorkflowStartResponse(
                    "success", "MarketsRefreshWorkflow started", workflowId));
        } catch (Exception e) {
            log.error("Failed to trigger MarketsRefreshWorkflow", e);
            return ResponseEntity.internalServerError()
                    .body(new WorkflowStartResponse("error", e.getMessage(), null));
        }
    }

    @GetMapping("/list")
    public ResponseEntity<Page<Map<String, Object>>> listSimilarMarkets(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size
    ) {
        return ResponseEntity.ok(similarMarketsService.getSimilarMarkets(page, size));
    }

    @PutMapping("/{id}/toggle")
    @Transactional
    public ResponseEntity<Map<String, Object>> toggleEnabled(@PathVariable Long id) {
        Map<String, Object> row = similarMarketsService.getSimilarMarketById(id);
        String pmTicker = (String) row.get("polymarket_market_ticker");
        String ksTicker = (String) row.get("kalshi_market_ticker");

        boolean allowed = allowedMarketPairRepository.existsByPolymarketTickerAndKalshiTicker(pmTicker, ksTicker);

        if (allowed) {
            allowedMarketPairRepository.deleteByPolymarketTickerAndKalshiTicker(pmTicker, ksTicker);
            return ResponseEntity.ok(Map.of("id", id, "enabled", false));
        } else {
            allowedMarketPairRepository.save(AllowedMarketPair.builder()
                    .polymarketTicker(pmTicker)
                    .kalshiTicker(ksTicker)
                    .build());
            return ResponseEntity.ok(Map.of("id", id, "enabled", true));
        }
    }

    @PutMapping("/event/toggle")
    @Transactional
    public ResponseEntity<EventToggleResponse> toggleEventEnabled(@RequestBody EventToggleRequest request) {
        log.info("Toggling event {} / {} to enabled={}", request.polymarketEventTicker(), request.kalshiEventTicker(), request.enabled());
        EventToggleResponse result = similarMarketsService.toggleEvent(
                request.polymarketEventTicker(),
                request.kalshiEventTicker(),
                request.enabled()
        );
        return ResponseEntity.ok(result);
    }
}
