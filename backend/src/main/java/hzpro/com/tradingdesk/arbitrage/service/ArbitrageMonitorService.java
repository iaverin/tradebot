package hzpro.com.tradingdesk.arbitrage.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import hzpro.com.tradingdesk.arbitrage.config.ArbitrageConfig;
import hzpro.com.tradingdesk.arbitrage.entity.ArbitrageEvent;
import hzpro.com.tradingdesk.arbitrage.entity.ArbitrageEventMarketsInfo;
import hzpro.com.tradingdesk.arbitrage.model.ArbitrageDirection;
import hzpro.com.tradingdesk.arbitrage.model.ArbitrageOrderLifecycleEvent;
import hzpro.com.tradingdesk.arbitrage.model.OpportunityCloseReason;
import hzpro.com.tradingdesk.arbitrage.model.OrderState;
import hzpro.com.tradingdesk.arbitrage.model.OrderStatusRefreshResult;
import hzpro.com.tradingdesk.arbitrage.model.PriceSnapshot;
import hzpro.com.tradingdesk.arbitrage.repository.ArbitrageEventMarketsInfoRepository;
import hzpro.com.tradingdesk.arbitrage.repository.ArbitrageEventRepository;
import hzpro.com.tradingdesk.arbitrage.rest.KalshiPriceRestClient;
import hzpro.com.tradingdesk.arbitrage.rest.PolymarketPriceRestClient;
import hzpro.com.tradingdesk.arbitrage.util.WebSocketReconnectHelper;
import hzpro.com.tradingdesk.arbitrage.websocket.KalshiWebSocketClient;
import hzpro.com.tradingdesk.arbitrage.websocket.PolymarketWebSocketClient;
import hzpro.com.tradingdesk.config.ProxyConfig;
import hzpro.com.tradingdesk.config.SettingsService;
import hzpro.com.tradingdesk.domain.enums.YesOrNoResult;
import hzpro.com.tradingdesk.domain.orderbook.OrderBook;
import hzpro.com.tradingdesk.entity.enums.DataSource;
import hzpro.com.tradingdesk.repository.PredictionMarketRepository;
import hzpro.com.tradingdesk.similarmarkets.repository.SimilarMarketsRepository;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class ArbitrageMonitorService {

    private final ArbitrageConfig config;
    private final ArbitrageEventRepository eventRepository;
    private final ArbitrageEventMarketsInfoRepository marketsInfoRepository;
    private final PredictionMarketRepository pmRepo;
    private final SimilarMarketsRepository similarMarketsRepository;
    private final ProxyConfig proxyConfig;
    private final PairPriceCache priceCache;
    private final KalshiPriceRestClient kalshiRestClient;
    private final PolymarketPriceRestClient polymarketRestClient;
    private final PolymarketFetchCommonOrderBookService pmOrderBookService;
    private final KalshiFetchCommonOrderBookService ksOrderBookService;
    private final ObjectMapper objectMapper;
    private final ArbitrageOrderService arbitrageOrderService;
    private final BalanceService balanceService;
    private final SettingsService settingsService;
    private final OrderStatusChecker orderStatusChecker;
    private PolymarketWebSocketClient polymarketClient;
    private KalshiWebSocketClient kalshiClient;
    private WebSocketReconnectHelper pmReconnect, ksReconnect;

    private final Map<String, ArbitrageEvent> activeOpps = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<MarketPairKey, PairLock> pairLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<MarketPairKey, ConcurrentHashMap<UUID, ActivePairInfo>> activeOrderPairs =
            new ConcurrentHashMap<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final AtomicLong lifecycleGeneration = new AtomicLong();
    private volatile boolean running;
    private volatile boolean activePairRegistryReady;

    private final Map<Long, String> pmSlugs = new ConcurrentHashMap<>();
    private final Map<Long, String> ksTickers = new ConcurrentHashMap<>();

    private static final int MIN_BALANCE_TO_MAX_COST_RATIO = 5;

    public ArbitrageMonitorService(ArbitrageConfig config, ArbitrageEventRepository eventRepository,
            ArbitrageEventMarketsInfoRepository marketsInfoRepository,
            PredictionMarketRepository pmRepo,
            SimilarMarketsRepository similarMarketsRepository,
            ProxyConfig proxyConfig,
            PairPriceCache priceCache,
            KalshiPriceRestClient kalshiRestClient,
            PolymarketPriceRestClient polymarketRestClient,
            PolymarketFetchCommonOrderBookService pmOrderBookService,
            KalshiFetchCommonOrderBookService ksOrderBookService,
            ObjectMapper objectMapper,
            ArbitrageOrderService arbitrageOrderService1,
            BalanceService balanceService,
            SettingsService settingsService,
            OrderStatusChecker orderStatusChecker) {
        this.config = config;
        this.eventRepository = eventRepository;
        this.marketsInfoRepository = marketsInfoRepository;
        this.pmRepo = pmRepo;
        this.similarMarketsRepository = similarMarketsRepository;
        this.proxyConfig = proxyConfig;
        this.priceCache = priceCache;
        this.kalshiRestClient = kalshiRestClient;
        this.polymarketRestClient = polymarketRestClient;
        this.pmOrderBookService = pmOrderBookService;
        this.ksOrderBookService = ksOrderBookService;
        this.objectMapper = objectMapper;
        this.arbitrageOrderService = arbitrageOrderService1;
        this.balanceService = balanceService;
        this.settingsService = settingsService;
        this.orderStatusChecker = orderStatusChecker;
    }

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        log.info("Starting Arbitrage Monitor Service (N pairs)...");
        if (running) {
            log.warn("Arbitrage monitor is already running");
            return;
        }
        settingsService.loadSettings();
        if (!initializeActivePairRegistry()) {
            log.error("Arbitrage monitor will not start because unfinished orders could not be initialized");
            return;
        }
        closeUnclosedOpportunitiesFromDb();
        startMonitoring();
    }

    private boolean initializeActivePairRegistry() {
        activePairRegistryReady = false;
        activeOrderPairs.clear();
        OrderStatusRefreshResult result = orderStatusChecker.checkUnfinishedOrders();
        activePairRegistryReady = result.completed();
        log.info("Active order-pair registry initialized: ready={} checked={} updated={} activePairs={}",
                result.completed(), result.checkedOrders(), result.updatedOrders(), activePairCount());
        return result.completed();
    }

    private void closeUnclosedOpportunitiesFromDb() {
        try {
            List<ArbitrageEvent> unclosed = eventRepository.findUnclosedOpportunities();
            int closedCount = 0;
            for (ArbitrageEvent opportunity : unclosed) {
                try {
                    saveEndOnMonitorStart(opportunity);
                    closedCount++;
                    log.info("Closed unclosed opportunity on monitor start: pair={} dir={} spread={}",
                            opportunity.getSimilarMarketId(), opportunity.getDirection(), opportunity.getSpread());
                } catch (Exception ex) {
                    log.warn("Failed to close opportunity {} on monitor start: {}",
                            opportunity.getId(), ex.getMessage());
                }
            }
            log.info("Closed {} of {} unclosed opportunities on monitor start",
                    closedCount, unclosed.size());
        } catch (Exception ex) {
            log.warn("Failed to find unclosed opportunities on monitor start: {}", ex.getMessage());
        }
    }

    private void saveEndOnMonitorStart(ArbitrageEvent start) {
        PriceSnapshot pm = PriceSnapshot.builder()
                .yesAsk(start.getPolymarketYesAsk())
                .noAsk(start.getPolymarketNoAsk())
                .build();
        PriceSnapshot ks = PriceSnapshot.builder()
                .yesAsk(start.getKalshiYesAsk())
                .noAsk(start.getKalshiNoAsk())
                .build();

        saveEnd(start, ArbitrageDirection.valueOf(start.getDirection()), start.getSpread(), pm, ks,
                OpportunityCloseReason.PRICE_DATA_UNAVAILABLE);
    }

    private void startMonitoring() {
        log.info("Is trading enabled {}", config.isTradingEnabled());

        if (running) {
            log.warn("Already running");
            return;
        }

        pmSlugs.clear();
        ksTickers.clear();

        try {
            List<Object[]> pairs = pmRepo.findActivePairs(config.getPairLimit());
            if (pairs.isEmpty()) {
                log.warn("No active pairs found in similar_markets with token_ids");
                return;
            }
            List<String> pmAssetIds = new ArrayList<>();
            List<String> ksTickerList = new ArrayList<>();
            Map<String, String> pmTokenTypes = new HashMap<>();
            Set<String> conditionIds = new HashSet<>();

            for (Object[] row : pairs) {
                Long id = ((Number) row[0]).longValue();
                String pmTicker = (String) row[1];
                String ksTicker = (String) row[2];
                String yesId = (String) row[3];
                String noId = (String) row[4];
                String conditionId = (String) row[5];

                if (yesId == null || noId == null)
                    continue;

                priceCache.registerPair(id, yesId, noId, ksTicker);
                pmAssetIds.add(yesId);
                pmAssetIds.add(noId);
                ksTickerList.add(ksTicker);
                pmTokenTypes.put(yesId, "YES");
                pmTokenTypes.put(noId, "NO");
                pmSlugs.put(id, pmTicker);
                ksTickers.put(id, ksTicker);

                if (conditionId != null && !conditionId.isEmpty()) {
                    conditionIds.add(conditionId);
                }
            }
            log.info("Loaded {} pairs, {} PM assets, {} KS tickers, {} conditions",
                    pairs.size(), pmAssetIds.size(), ksTickerList.size(), conditionIds.size());

            log.info("Starting REST prefetch for {} pairs. Timeout={}ms ...", pmSlugs.size(),
                    config.getPrefetchTimeoutMs());
            long prefetchTimeout = config.getPrefetchTimeoutMs() > 0 ? config.getPrefetchTimeoutMs() : 5000;

            Map<String, PriceSnapshot> pmPrices;
            Map<String, PriceSnapshot> ksPrices;

            try {
                var pmFuture = CompletableFuture
                        .supplyAsync(() -> polymarketRestClient.fetchPrices(pmTokenTypes, prefetchTimeout));
                var ksFuture = CompletableFuture
                        .supplyAsync(() -> kalshiRestClient.fetchPrices(ksTickerList, prefetchTimeout));

                pmPrices = pmFuture.get(prefetchTimeout, TimeUnit.MILLISECONDS);
                ksPrices = ksFuture.get(prefetchTimeout, TimeUnit.MILLISECONDS);

                priceCache.initializeFromRest(pmPrices, ksPrices);

            } catch (Exception e) {
                log.warn("REST prefetch failed (continuing with WebSocket only): {}", e.getMessage());
            }

            try {
                balanceService.refreshBalances();
            } catch (Exception e) {
                log.warn("Initial balance refresh failed: {}", e.getMessage());
            }

            long generation = lifecycleGeneration.incrementAndGet();
            polymarketClient = new PolymarketWebSocketClient(pmAssetIds,
                    (assetId, price) -> onPmUpdate(generation, assetId, price), proxyConfig);
            kalshiClient = new KalshiWebSocketClient(ksTickerList,
                    (ticker, price) -> onKsUpdate(generation, ticker, price), proxyConfig,
                    config.getKalshiApiKey(), config.getKalshiPrivateKey());

            pmReconnect = new WebSocketReconnectHelper("PM", this::connectPm, polymarketClient::isConnected);
            ksReconnect = new WebSocketReconnectHelper("KS", this::connectKs, kalshiClient::isConnected);

            running = true;
            connectPm();
            connectKs();
            pmReconnect.startMonitoring();
            ksReconnect.startMonitoring();
            log.info("Monitor started with {} pairs", pmSlugs.size());

        } catch (Exception e) {
            log.error("Failed to start", e);
            stopMonitoringResources();
        }
    }

    private boolean connectPm() {
        try {
            polymarketClient.connect();
            return true;
        } catch (Exception e) {
            log.error("PM connect", e);
            return false;
        }
    }

    private boolean connectKs() {
        try {
            kalshiClient.connect();
            return true;
        } catch (Exception e) {
            log.error("KS connect", e);
            return false;
        }
    }

    private void onPmUpdate(long generation, String assetId, PriceSnapshot price) {
        if (!isCurrentGeneration(generation)) {
            return;
        }
        handlePmUpdate(assetId, price);
    }

    private void handlePmUpdate(String assetId, PriceSnapshot price) {
        log.debug("pm price update yesAsk={}, noAsk={} assetId {}", price.getYesAsk(), price.getNoAsk(), assetId);
        Long pairId = priceCache.findPairIdByAssetId(assetId);
        if (pairId == null) {
            log.warn("PM Price for was not found in cache {}", assetId);
            return;
        }

        Boolean isYes = priceCache.isYesToken(assetId);
        log.debug("pm price update got pairId={} isYes={} for assetId {}", pairId, isYes, assetId);
        if (isYes == null) {
            log.error("PM No yes token in cache {}", assetId);
            return;
        }

        priceCache.ensurePair(pairId);

        BigDecimal priceValue = price.getYesAsk() != null ? price.getYesAsk() : price.getNoAsk();
        if (priceValue == null) {
            log.warn("PM empty price for pairId={} assetId={}", pairId, assetId);
            return;
        }

        priceCache.updatePmPrice(pairId, isYes, priceValue);
        if (!checkArbitrageIfReady(pairId)) return;
        checkArbitrage(pairId);
    }

    private void onKsUpdate(long generation, String ticker, PriceSnapshot price) {
        if (!isCurrentGeneration(generation)) {
            return;
        }
        handleKsUpdate(ticker, price);
    }

    private void handleKsUpdate(String ticker, PriceSnapshot price) {
        log.debug("kalshi price update {}", ticker);
        Long pairId = priceCache.findPairIdByTicker(ticker);
        if (pairId == null) {
            log.warn("KS Price for was not found in cache {}", ticker);
            return;
        }

        priceCache.ensurePair(pairId);
        priceCache.updateKsPrice(pairId, true, price.getYesAsk());
        priceCache.updateKsPrice(pairId, false, price.getNoAsk());
        if (!checkArbitrageIfReady(pairId)) return;
        checkArbitrage(pairId);
    }

    private boolean checkArbitrageIfReady(Long pairId) {
        if (!priceCache.hasBothPrices(pairId)) {
            log.debug("Prices are not complete yet for pairId={}", pairId);
            return false;
        }
        return true;
    }

    private void checkArbitrage(Long pairId) {
        UUID uuid = UUID.randomUUID();
        MarketPairKey marketPairKey = marketPairKey(pairId);
        if (marketPairKey == null) {
            log.error("[uuid={}] Missing market tickers for pairId={}; refusing order evaluation", uuid, pairId);
            return;
        }

        PairLock pairLock = acquirePairLock(marketPairKey, uuid);

        try {
            log.debug("[uuid={}] Checking arbitrage for {}", uuid, pairId);
            PriceSnapshot pm = priceCache.getPmPrice(pairId);
            PriceSnapshot ks = priceCache.getKsPrice(pairId);
            if (pm == null || ks == null || !pm.isComplete() || !ks.isComplete()) {
                log.debug("[uuid={}] Prices are not complete for pairId={}", uuid, pairId);
                return;
            }

            for (ArbitrageDirection dir : ArbitrageDirection.values()) {
                BigDecimal spread = dir.calculateSpread(pm.getYesAsk(), pm.getNoAsk(), ks.getYesAsk(), ks.getNoAsk());
                boolean above = spread.compareTo(config.getThreshold()) > 0;
                String key = pairId + ":" + dir.getCode();
                ArbitrageEvent active = activeOpps.get(key);

                if (!above && active != null) {
                    logNoOpportunity(uuid, pairId, dir, spread, pm, ks);
                    saveEnd(active, dir, spread, pm, ks,
                            OpportunityCloseReason.SPREAD_BELOW_THRESHOLD);
                    activeOpps.remove(key);
                    log.debug("[uuid={}] Saved opprtunity close", uuid);
                }
                else if (!above && active == null) {
                    logNoOpportunity(uuid, pairId, dir, spread, pm, ks);
                } else if (above && active == null) {
                    ArbitrageEvent e = saveStart(uuid, pairId, dir, spread, pm, ks);
                    activeOpps.put(key, e);

                    var checkActivePairDecision = checkActivePairLimit(marketPairKey, uuid);
                    if (!checkActivePairDecision.allowsOrderCreation()) {
                        saveEnd(e, dir, spread, pm, ks, checkActivePairDecision.closeReason());
                        activeOpps.remove(key);
                        log.info("[uuid={}] Closed opportunity", uuid);
                        continue;
                    }

                    log.info("[uuid={}] Is trading enabled {} ", uuid, config.isTradingEnabled());
                    if (!config.isTradingEnabled()) {
                        continue;
                    }
                        BigDecimal requredBalance = config.getMaxOrderCost()
                                .multiply(BigDecimal.valueOf(MIN_BALANCE_TO_MAX_COST_RATIO));
                        if (!balanceService.hasSufficientBalance(requredBalance, requredBalance)) {
                            log.warn("[uuid={}] Insufficient balance for max trade cost. Required: ${}. Skipping.",
                                    uuid, requredBalance);
                            continue;
                        }

                        OpportunityCloseReason closeReason = createOrders(marketPairKey, uuid, dir);

                        if (closeReason == OpportunityCloseReason.ORDERS_CREATED) {
                            log.info("[uuid={}] Orders are created will close opportunity", uuid);
                        } else {
                            log.info("[uuid={}] Orders are not created: {}. Resetting opportunity.",
                                    uuid, closeReason);
                        }
                        saveEnd(e, dir, spread, pm, ks, closeReason);
                        activeOpps.remove(key);
                    }
                }
        } finally {
            releasePairLock(marketPairKey, pairLock);
        }
    }

    private ActivePairLimitDecision checkActivePairLimit(MarketPairKey pairKey, UUID opportunityUuid) {
        if (!activePairRegistryReady) {
            log.error("[uuid={}] Active order-pair registry is not ready; refusing order creation", opportunityUuid);
            return ActivePairLimitDecision.REJECT_CHECK_FAILED;
        }

        final int limit;
        try {
            limit = settingsService.getActivePairsLimit();
        } catch (RuntimeException exception) {
            log.error("[uuid={}] Failed to read active pairs limit: {}", opportunityUuid, exception.getMessage());
            return ActivePairLimitDecision.REJECT_CHECK_FAILED;
        }

        if (activePairCount(pairKey) >= limit) {
            OrderStatusRefreshResult refreshResult = orderStatusChecker.checkUnfinishedOrdersForPair(
                    pairKey.polymarketTicker(),
                    pairKey.kalshiTicker());
            if (!refreshResult.completed()) {
                log.warn("[uuid={}] Unfinished-order refresh did not complete for pair {}", opportunityUuid, pairKey);
            }
            if (activePairCount(pairKey) >= limit) {
                log.warn(
                        "[uuid={}] Active pair limit reached: pmTicker={} ksTicker={} activePairs={} limit={}",
                        opportunityUuid,
                        pairKey.polymarketTicker(),
                        pairKey.kalshiTicker(),
                        activePairCount(pairKey),
                        limit);
                return ActivePairLimitDecision.REJECT_LIMIT_REACHED;
            }
        }

        return ActivePairLimitDecision.ALLOW;
    }

    /**
     * The caller holds the exact-pair lock through this method. Order creation
     * publishes its initial PENDING lifecycle events synchronously, so the
     * registry is populated before another creation for this pair can enter.
     */
    private OpportunityCloseReason createOrders(
            MarketPairKey pairKey,
            UUID opportunityUuid,
            ArbitrageDirection direction) {
        return arbitrageOrderService.createOrders(
                opportunityUuid,
                pairKey.polymarketTicker(),
                pairKey.kalshiTicker(),
                direction);
    }

    @EventListener
    public void onArbitrageOrderLifecycle(ArbitrageOrderLifecycleEvent event) {
        if (event.opportunityUuid() == null || event.status() == null || event.platform() == null) {
            log.warn("Ignoring incomplete arbitrage order lifecycle event: {}", event);
            return;
        }
        MarketPairKey pairKey = eventPairKey(event);
        if (pairKey == null) {
            log.warn("Ignoring lifecycle event without a market pair for opportunity {}", event.opportunityUuid());
            return;
        }

        OrderIdentity orderIdentity = new OrderIdentity(event.platform(), event.databaseOrderId());
        activeOrderPairs.compute(pairKey, (ignored, existingPairs) -> {
            ConcurrentHashMap<UUID, ActivePairInfo> pairs = existingPairs;
            if (pairs == null) {
                if (event.status().isTerminal()) {
                    return null;
                }
                pairs = new ConcurrentHashMap<>();
            }

            ActivePairInfo info = pairs.get(event.opportunityUuid());
            if (info == null) {
                if (event.status().isTerminal()) {
                    return pairs.isEmpty() ? null : pairs;
                }
                info = new ActivePairInfo();
                pairs.put(event.opportunityUuid(), info);
            }

            if (event.status().isActive()) {
                info.activeOrders.put(orderIdentity, event.status());
            } else {
                info.activeOrders.remove(orderIdentity);
            }

            if (info.activeOrders.isEmpty()) {
                pairs.remove(event.opportunityUuid(), info);
            }
            return pairs.isEmpty() ? null : pairs;
        });
    }

    private MarketPairKey eventPairKey(ArbitrageOrderLifecycleEvent event) {
        if (event.polymarketTicker() != null && !event.polymarketTicker().isBlank()
                && event.kalshiTicker() != null && !event.kalshiTicker().isBlank()) {
            return new MarketPairKey(event.polymarketTicker(), event.kalshiTicker());
        }
        return null;
    }

    private MarketPairKey marketPairKey(Long pairId) {
        String polymarketTicker = pmSlugs.get(pairId);
        String kalshiTicker = ksTickers.get(pairId);
        if (polymarketTicker == null || polymarketTicker.isBlank()
                || kalshiTicker == null || kalshiTicker.isBlank()) {
            return null;
        }
        return new MarketPairKey(polymarketTicker, kalshiTicker);
    }

    private int activePairCount(MarketPairKey pairKey) {
        Map<UUID, ActivePairInfo> pairs = activeOrderPairs.get(pairKey);
        return pairs == null ? 0 : pairs.size();
    }

    private int activePairCount() {
        return activeOrderPairs.values().stream().mapToInt(Map::size).sum();
    }

    private void logNoOpportunity(UUID uuid, Long pairId, ArbitrageDirection dir, BigDecimal spread, PriceSnapshot pm,
            PriceSnapshot ks) {
        String pmTicker = pmSlugs.getOrDefault(pairId, "");
        String ksTicker = ksTickers.getOrDefault(pairId, "");
        log.info(
                "[uuid={}] No opportunity pairId={}  spread={} pmPrice={} ksPrice={} pmTicker={} ksTicker={} direction {}",
                uuid, pairId,
                spread, pm, ks, pmTicker, ksTicker, dir);

    }

    private PairLock acquirePairLock(MarketPairKey pairKey, UUID uuid) {
        PairLock pairLock = pairLocks.compute(pairKey, (ignored, existing) -> {
            PairLock result = existing != null ? existing : new PairLock();
            result.users++;
            return result;
        });
        log.debug("[uuid={}] Current pair is locked={} queue={}", uuid, Integer.compare(pairLock.users , 1), pairLock.users - 1);
        pairLock.lock.lock();
        return pairLock;
    }

    private void releasePairLock(MarketPairKey pairKey, PairLock pairLock) {
        pairLock.lock.unlock();
        releasePairLockReference(pairKey, pairLock);
    }

    private void releasePairLockReference(MarketPairKey pairKey, PairLock pairLock) {
        pairLocks.computeIfPresent(pairKey, (ignored, current) -> {
            if (current != pairLock) {
                return current;
            }
            current.users--;
            return current.users == 0 ? null : current;
        });
    }

    private static final class PairLock {
        private final ReentrantLock lock = new ReentrantLock();
        private int users;
    }

    private record MarketPairKey(String polymarketTicker, String kalshiTicker) {
    }

    private record OrderIdentity(String platform, Long databaseOrderId) {
    }

    private static final class ActivePairInfo {
        private final ConcurrentHashMap<OrderIdentity, OrderState> activeOrders = new ConcurrentHashMap<>();
    }

    private enum ActivePairLimitDecision {
        ALLOW(null),
        REJECT_LIMIT_REACHED(OpportunityCloseReason.ACTIVE_PAIR_LIMIT_REACHED),
        REJECT_CHECK_FAILED(OpportunityCloseReason.ORDER_CREATION_FAILED);

        private final OpportunityCloseReason closeReason;

        ActivePairLimitDecision(OpportunityCloseReason closeReason) {
            this.closeReason = closeReason;
        }

        private boolean allowsOrderCreation() {
            return this == ALLOW;
        }

        private OpportunityCloseReason closeReason() {
            return closeReason;
        }
    }

    /**
     * Estimates the cost of placing both sides of an arbitrage trade.
     * Uses {@link ArbitrageConfig#getMaxOrderCost()} as a conservative estimate
     * that never understates the actual cost.
     */
    private BigDecimal calculateTradeCost(PriceSnapshot pm, PriceSnapshot ks, ArbitrageDirection dir) {
        return config.getMaxOrderCost();
    }

    private ArbitrageEvent saveStart(UUID uuid, Long pairId, ArbitrageDirection dir, BigDecimal spread, PriceSnapshot pm, PriceSnapshot ks) {
        String pmTicker = pmSlugs.getOrDefault(pairId, "");
        String ksTicker = ksTickers.getOrDefault(pairId, "");

        ArbitrageEvent event = eventRepository.save(ArbitrageEvent.builder()
                .eventType("OPPORTUNITY_START")
                .similarMarketId(pairId)
                .uuid(uuid)
                .polymarketMarketTicker(pmTicker)
                .kalshiMarketTicker(ksTicker)
                .direction(dir.getCode())
                .polymarketYesAsk(pm.getYesAsk()).polymarketNoAsk(pm.getNoAsk())
                .kalshiYesAsk(ks.getYesAsk()).kalshiNoAsk(ks.getNoAsk())
                .spread(spread).threshold(config.getThreshold())
                .polymarketOrderbook(fetchPmOrderBook(pmTicker, dir))
                .kalshiOrderbook(fetchKsOrderBook(ksTicker, dir))
                .build());

        saveMarketsInfo(event.getUuid(), pmTicker, ksTicker, pairId);
        return event;
    }

    private void saveMarketsInfo(UUID uuid, String pmTicker, String ksTicker, Long similarMarketId) {
        try {
            Map<String, Object> pmData = pmRepo.findFirstByMarketTickerAndDatasourceOrderByCreatedAtDesc(
                            pmTicker, DataSource.POLYMARKET)
                    .map(m -> objectMapper.convertValue(m, Map.class))
                    .orElse(Map.of());
            Map<String, Object> ksData = pmRepo.findFirstByMarketTickerAndDatasourceOrderByCreatedAtDesc(
                            ksTicker, DataSource.KALSHI)
                    .map(m -> objectMapper.convertValue(m, Map.class))
                    .orElse(Map.of());

            Map<String, Object> marketsInfo = Map.of("KALSHI", ksData, "POLYMARKET", pmData);
            String marketsInfoJson = objectMapper.writeValueAsString(marketsInfo);

            Map<String, Object> similarMarket = similarMarketsRepository.findFullSimilarMarketById(similarMarketId);
            String similarInfoJson = similarMarket != null ? objectMapper.writeValueAsString(similarMarket) : "{}";

            marketsInfoRepository.save(ArbitrageEventMarketsInfo.builder()
                    .uuid(uuid)
                    .marketsInfo(marketsInfoJson)
                    .similarMarketsInfo(similarInfoJson)
                    .build());

        } catch (Exception e) {
            log.warn("Failed to save markets info for uuid {}: {}", uuid, e.getMessage());
        }
    }

    private void saveEnd(ArbitrageEvent start, ArbitrageDirection dir, BigDecimal spread,
                         PriceSnapshot pm, PriceSnapshot ks, OpportunityCloseReason closeReason) {
        String pmTicker = start.getPolymarketMarketTicker();
        String ksTicker = start.getKalshiMarketTicker();

        eventRepository.save(ArbitrageEvent.builder()
                .eventType("OPPORTUNITY_END").opportunityStart(start)
                .closeReason(closeReason)
                .similarMarketId(start.getSimilarMarketId())
                .polymarketMarketTicker(pmTicker)
                .kalshiMarketTicker(ksTicker)
                .direction(dir.getCode())
                .polymarketYesAsk(pm.getYesAsk()).polymarketNoAsk(pm.getNoAsk())
                .kalshiYesAsk(ks.getYesAsk()).kalshiNoAsk(ks.getNoAsk())
                .spread(spread).threshold(config.getThreshold())
                .polymarketOrderbook(fetchPmOrderBook(pmTicker, dir))
                .kalshiOrderbook(fetchKsOrderBook(ksTicker, dir))
                .uuid(start.getUuid())
                .build());
    }

    private String fetchPmOrderBook(String ticker, ArbitrageDirection dir) {
        try {
            YesOrNoResult side = dir == ArbitrageDirection.PM_YES_KS_NO ? YesOrNoResult.YES : YesOrNoResult.NO;
            OrderBook book = pmOrderBookService.fetchCommonOrderBook(ticker, side);
            return book != null ? objectMapper.writeValueAsString(book) : "{}";
        } catch (Exception e) {
            log.warn("Failed to fetch PM orderbook for {}: {}", ticker, e.getMessage());
            return "{}";
        }
    }

    private String fetchKsOrderBook(String ticker, ArbitrageDirection dir) {
        try {
            YesOrNoResult side = dir == ArbitrageDirection.PM_YES_KS_NO ? YesOrNoResult.NO : YesOrNoResult.YES;
            OrderBook book = ksOrderBookService.fetchCommonOrderBook(ticker, side);
            return book != null ? objectMapper.writeValueAsString(book) : "{}";
        } catch (Exception e) {
            log.warn("Failed to fetch KS orderbook for {}: {}", ticker, e.getMessage());
            return "{}";
        }
    }

    public int getTotalPairs() {
        return pmSlugs.size();
    }

    public int getActiveOpportunitiesCount() {
        return activeOpps.size();
    }

    public List<ArbitrageEvent> getActiveOpportunities() {
        return new ArrayList<>(activeOpps.values());
    }

    @PreDestroy
    public synchronized void stop() {
        stopMonitoringResources();
    }

    private void stopMonitoringResources() {
        running = false;
        lifecycleGeneration.incrementAndGet();

        if (pmReconnect != null) {
            pmReconnect.shutdown();
            pmReconnect = null;
        }
        if (ksReconnect != null) {
            ksReconnect.shutdown();
            ksReconnect = null;
        }

        try {
            if (polymarketClient != null) {
                polymarketClient.disconnect();
            }
        } catch (Exception e) {
            log.warn("Error disconnecting PM", e);
        } finally {
            polymarketClient = null;
        }
        try {
            if (kalshiClient != null) {
                kalshiClient.disconnect();
            }
        } catch (Exception e) {
            log.warn("Error disconnecting KS", e);
        } finally {
            kalshiClient = null;
        }

        lock.lock();
        try {
            priceCache.clear();
            activeOpps.clear();
            activeOrderPairs.clear();
            activePairRegistryReady = false;
        } finally {
            lock.unlock();
        }
    }

    private boolean isCurrentGeneration(long generation) {
        return running && lifecycleGeneration.get() == generation;
    }

    public boolean isRunning() { return running; }
    public synchronized void restart() {
        stopMonitoringResources();
        settingsService.loadSettings();
        if (!initializeActivePairRegistry()) {
            log.error("Arbitrage monitor will not restart because unfinished orders could not be initialized");
            return;
        }
        closeUnclosedOpportunitiesFromDb();
        startMonitoring();
    }
}
