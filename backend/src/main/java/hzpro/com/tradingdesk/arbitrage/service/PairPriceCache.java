package hzpro.com.tradingdesk.arbitrage.service;

import hzpro.com.tradingdesk.arbitrage.model.PriceSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class PairPriceCache {

    private final Map<Long, PriceSnapshot> pmPrices = new ConcurrentHashMap<>();
    private final Map<Long, PriceSnapshot> ksPrices = new ConcurrentHashMap<>();
    private final Map<String, Boolean> assetIdIsYes = new ConcurrentHashMap<>();
    private final Map<String, Long> assetIdToPairId = new ConcurrentHashMap<>();
    private final Map<String, Long> tickerToPairId = new ConcurrentHashMap<>();

    public void registerPair(Long pairId, String pmYesTokenId, String pmNoTokenId, String ksTicker) {
        assetIdToPairId.put(pmYesTokenId, pairId);
        assetIdToPairId.put(pmNoTokenId, pairId);
        assetIdIsYes.put(pmYesTokenId, true);
        assetIdIsYes.put(pmNoTokenId, false);
        tickerToPairId.put(ksTicker, pairId);
    }

    public Boolean isYesToken(String assetId) {
        return assetIdIsYes.get(assetId);
    }

    public Long findPairIdByAssetId(String assetId) {
        return assetIdToPairId.get(assetId);
    }

    public Long findPairIdByTicker(String ticker) {
        return tickerToPairId.get(ticker);
    }

    public void ensurePair(Long pairId) {
        pmPrices.computeIfAbsent(pairId, ignored -> new PriceSnapshot());
        ksPrices.computeIfAbsent(pairId, ignored -> new PriceSnapshot());
    }

    public boolean updatePmPrice(Long pairId, boolean isYes, BigDecimal price) {
        PriceSnapshot snapshot = pmPrices.computeIfAbsent(pairId, k -> new PriceSnapshot());
        BigDecimal oldPrice = isYes ? snapshot.getYesAsk() : snapshot.getNoAsk();
        // if (price.equals(oldPrice)) return false;

        if (isYes) snapshot.setYesAsk(price); else snapshot.setNoAsk(price);
        snapshot.setUpdatedAt(Instant.now());
        snapshot.setFromRest(false);
        return true;
    }

    public boolean updateKsPrice(Long pairId, boolean isYes, BigDecimal price) {
        PriceSnapshot snapshot = ksPrices.computeIfAbsent(pairId, k -> new PriceSnapshot());
        BigDecimal oldPrice = isYes ? snapshot.getYesAsk() : snapshot.getNoAsk();
        // if (price.equals(oldPrice)) return false;

        if (isYes) snapshot.setYesAsk(price); else snapshot.setNoAsk(price);
        snapshot.setUpdatedAt(Instant.now());
        snapshot.setFromRest(false);
        return true;
    }

    public PriceSnapshot getPmPrice(Long pairId) {
        return pmPrices.get(pairId);
    }

    public PriceSnapshot getKsPrice(Long pairId) {
        return ksPrices.get(pairId);
    }

    public boolean hasBothPrices(Long pairId) {
        PriceSnapshot pm = pmPrices.get(pairId);
        PriceSnapshot ks = ksPrices.get(pairId);
        return pm != null && ks != null;
    }

    public boolean isKsPriceFromRest(Long pairId) {
        PriceSnapshot ks = ksPrices.get(pairId);
        return ks != null && ks.isFromRest();
    }

    public int getPairCount() {
        return assetIdToPairId.size() / 2;
    }

    public void clear() {
        pmPrices.clear();
        ksPrices.clear();
        assetIdToPairId.clear();
        tickerToPairId.clear();
    }

    public void initializeFromRest(
            Map<String, PriceSnapshot> pmPricesByTokenId,
            Map<String, PriceSnapshot> ksPricesByTicker) {

        int pmCount = 0, ksCount = 0;

        for (Map.Entry<String, PriceSnapshot> entry : pmPricesByTokenId.entrySet()) {
            String tokenId = entry.getKey();
            PriceSnapshot snapshot = entry.getValue();
            Long pairId = assetIdToPairId.get(tokenId);
            if (pairId == null) continue;

            Boolean isYes = assetIdIsYes.get(tokenId);
            if (isYes == null) continue;

            PriceSnapshot cached = pmPrices.computeIfAbsent(pairId, k -> new PriceSnapshot());
            BigDecimal price = isYes ? snapshot.getYesAsk() : snapshot.getNoAsk();
            if (isYes) {
                cached.setYesAsk(price);
            } else {
                cached.setNoAsk(price);
            }
            cached.setUpdatedAt(snapshot.getUpdatedAt());
            cached.setFromRest(true);
            pmCount++;
        }

        for (Map.Entry<String, PriceSnapshot> entry : ksPricesByTicker.entrySet()) {
            String ticker = entry.getKey();
            PriceSnapshot snapshot = entry.getValue();
            Long pairId = tickerToPairId.get(ticker);
            if (pairId == null) continue;

            PriceSnapshot cached = ksPrices.computeIfAbsent(pairId, k -> new PriceSnapshot());
            if (snapshot.getYesAsk() != null) cached.setYesAsk(snapshot.getYesAsk());
            if (snapshot.getNoAsk() != null) cached.setNoAsk(snapshot.getNoAsk());
            cached.setUpdatedAt(snapshot.getUpdatedAt());
            cached.setFromRest(true);
            ksCount++;
        }

        log.info("Initialized cache from REST: {} PM prices, {} KS prices", pmCount, ksCount);

        int readyPairs = 0;
        for (Long pairId : new java.util.HashSet<>(pmPrices.keySet())) {
            if (hasBothPrices(pairId)) {
                readyPairs++;
            }
        }
        log.info("Pairs ready for arbitrage check after prefetch: {}/{}", readyPairs, pmPrices.size());
    }
}
