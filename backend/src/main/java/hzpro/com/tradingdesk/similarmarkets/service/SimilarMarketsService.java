package hzpro.com.tradingdesk.similarmarkets.service;

import hzpro.com.tradingdesk.controller.dto.EventToggleResponse;
import hzpro.com.tradingdesk.controller.dto.MarketPairInfo;
import hzpro.com.tradingdesk.entity.AllowedMarketPair;
import hzpro.com.tradingdesk.repository.AllowedMarketPairRepository;
import hzpro.com.tradingdesk.marketsfetcher.service.MarketsRefreshLauncher;
import hzpro.com.tradingdesk.similarmarkets.repository.SimilarMarketsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class SimilarMarketsService {

    private final SimilarMarketsRepository similarMarketsRepository;
    private final AllowedMarketPairRepository allowedMarketPairRepository;
    private final MarketsRefreshLauncher marketsRefreshLauncher;

    public List<Map<String, Object>> countFetchedMarketsByDatasource() {
        return similarMarketsRepository.countFetchedMarketsByDatasource();
    }

    public Map<String, Long> countSimilarMarketsAndEvents() {
        long similarEventsCount = similarMarketsRepository.countSimilarEvents();
        long similarMarketsCount = similarMarketsRepository.countSimilarMarkets();
        return Map.of(
                "similar_events_count", similarEventsCount,
                "similar_markets_count", similarMarketsCount
        );
    }

    public Page<Map<String, Object>> getSimilarMarkets(int page, int size) {
        return similarMarketsRepository.findSimilarMarketsPageable(PageRequest.of(page, size));
    }

    public String triggerRecalculation() {
        log.info("Starting full MarketsRefreshWorkflow from similar-markets recalculation request");
        return marketsRefreshLauncher.start();
    }

    public Map<String, Object> getSimilarMarketById(Long id) {
        return similarMarketsRepository.findSimilarMarketById(id);
    }

    public EventToggleResponse toggleEvent(String pmEventTicker, String ksEventTicker, boolean enabled) {
        List<MarketPairInfo> marketPairs = similarMarketsRepository.findMarketPairsByEventTickers(pmEventTicker, ksEventTicker);
        List<Long> affectedIds = new ArrayList<>();

        if (enabled) {
            List<AllowedMarketPair> toInsert = new ArrayList<>();
            for (MarketPairInfo pair : marketPairs) {
                if (!allowedMarketPairRepository.existsByPolymarketTickerAndKalshiTicker(
                        pair.polymarketMarketTicker(), pair.kalshiMarketTicker())) {
                    toInsert.add(AllowedMarketPair.builder()
                            .polymarketTicker(pair.polymarketMarketTicker())
                            .kalshiTicker(pair.kalshiMarketTicker())
                            .build());
                    affectedIds.add(pair.id());
                }
            }
            if (!toInsert.isEmpty()) {
                allowedMarketPairRepository.saveAll(toInsert);
            }
        } else {
            for (MarketPairInfo pair : marketPairs) {
                if (allowedMarketPairRepository.existsByPolymarketTickerAndKalshiTicker(
                        pair.polymarketMarketTicker(), pair.kalshiMarketTicker())) {
                    affectedIds.add(pair.id());
                }
            }
            allowedMarketPairRepository.deleteByEventTickers(pmEventTicker, ksEventTicker);
        }

        return new EventToggleResponse(pmEventTicker, ksEventTicker, enabled, affectedIds);
    }
}
