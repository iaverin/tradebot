package hzpro.com.tradingdesk.manual;

import hzpro.com.tradingdesk.arbitrage.config.ArbitrageConfig;
import hzpro.com.tradingdesk.arbitrage.entity.ArbitrageEvent;
import hzpro.com.tradingdesk.arbitrage.model.ArbitrageDirection;
import hzpro.com.tradingdesk.arbitrage.repository.ArbitrageEventMarketsInfoRepository;
import hzpro.com.tradingdesk.arbitrage.repository.ArbitrageEventRepository;
import hzpro.com.tradingdesk.arbitrage.repository.ArbitrageOrderRepository;
import hzpro.com.tradingdesk.arbitrage.service.ArbitrageMonitorService;
import hzpro.com.tradingdesk.arbitrage.service.KalshiFetchCommonOrderBookService;
import hzpro.com.tradingdesk.arbitrage.service.KalshiTradingService;
import hzpro.com.tradingdesk.arbitrage.service.PairPriceCache;
import hzpro.com.tradingdesk.arbitrage.service.PolymarketFetchCommonOrderBookService;
import hzpro.com.tradingdesk.arbitrage.service.PolymarketTradingService;
import hzpro.com.tradingdesk.arbitrage.service.KalshiTradingService.OrderResult;
import hzpro.com.tradingdesk.domain.enums.YesOrNoResult;
import hzpro.com.tradingdesk.domain.orderbook.OrderBook;
import hzpro.com.tradingdesk.domain.orderbook.OrderBookEntry;
import hzpro.com.tradingdesk.entity.PredictionMarket;
import hzpro.com.tradingdesk.entity.enums.DataSource;
import hzpro.com.tradingdesk.repository.PredictionMarketRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * Manual integration test that triggers a full arbitrage order placement with
 * <strong>real venue orders</strong> (Polymarket + Kalshi).
 *
 * <h3>What is mocked</h3>
 * <ul>
 *   <li>WebSocket clients — never started (no pairs in DB at context-load time).</li>
 *   <li>Order-book fetch ({@link PolymarketFetchCommonOrderBookService},
 *       {@link KalshiFetchCommonOrderBookService}) — stubbed to return the prices/sizes
 *       configured below.</li>
 * </ul>
 *
 * <h3>What is REAL</h3>
 * <ul>
 *   <li>{@link PolymarketTradingService} — places a live limit order on the Polymarket CLOB.</li>
 *   <li>{@link KalshiTradingService} — places a live limit order on Kalshi.</li>
 *   <li>All DB persistence (events, orders).</li>
 * </ul>
 *
 * <h3>How to configure</h3>
 * <p>Edit the constants at the top of this class to match two markets that are currently
 * trading and have a real arbitrage spread. The PM token IDs come from the Polymarket CLOB
 * (look up the market's token IDs via the Polymarket API or UI).</p>
 *
 * <h3>Run</h3>
 * <pre>{@code
 *   ./gradlew test --tests "hzpro.com.tradingdesk.arbitrage.service.ArbitrageOrderManualTest" --info
 * }</pre>
 *
 * <p>Tagged {@code manual} so it is excluded from regular CI builds.</p>
 */
@Tag("manual")
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Manual Arbitrage Order Placement (real venue orders)")
class ArbitrageOrderManualTest {

    // ---- CONFIGURE THESE FOR YOUR TEST ----

    /** Polymarket market ticker (slug). */
    // will-mexico-be-eliminated-in-the-semifinals-of-the-world-cup-20260605224223439
    // private static final String PM_TICKER = "will-trump-be-impeached-before-his-term-ends";
    private static final String PM_TICKER = "will-mexico-be-eliminated-in-the-semifinals-of-the-world-cup-20260605224223439";

    /** Polymarket YES token ID (CTF token on Polygon). */
    private static final String PM_YES_TOKEN_ID = "99020283867209289889119738709824148614345922887757122199903300146152690995270";

    /** Polymarket NO token ID (CTF token on Polygon). */
    // 113614667824932229937389188628564593579566209924373845291432394447463029786434
    // private static final String PM_NO_TOKEN_ID = "93407877955114874013681720453737167910717495090774867631160491764908235156561";

    private static final String PM_NO_TOKEN_ID = "113614667824932229937389188628564593579566209924373845291432394447463029786434";


    /** Kalshi market ticker. */
    private static final String KS_TICKER = "KXF1FASTLAP-BRIGP26-GAS";

    /**
     * Arbitrage direction to test.
     * PM_YES_KS_NO = buy PM YES, buy KS NO (1 - pmYesAsk - ksNoAsk > threshold).
     * PM_NO_KS_YES  = buy PM NO,  buy KS YES (1 - pmNoAsk - ksYesAsk > threshold).
     */
    private static final ArbitrageDirection DIRECTION = ArbitrageDirection.PM_NO_KS_YES;

    // ---- PRICE CONFIGURATION ----

    /**
     * Prices to inject into the price cache. The spread is calculated as:
     * <pre>1 - (pmYesAsk + ksNoAsk)</pre> for PM_YES_KS_NO.
     * Must exceed {@code arbitrage.threshold} (default 0.01).
     * <p>
     * Example below: spread = 1 - (0.55 + 0.40) = 0.05 → triggers arb.
     */
    private static final BigDecimal PM_YES_ASK = new BigDecimal("0.50");
    private static final BigDecimal PM_NO_ASK  = new BigDecimal("0.51");
    private static final BigDecimal KS_YES_ASK = new BigDecimal("0.40");
    private static final BigDecimal KS_NO_ASK  = new BigDecimal("0.50");

    // ---- ORDER-BOOK CONFIGURATION (used at order-creation time) ----

    /**
     * The ask price and size reported by the order book for each venue.
     * These determine the actual limit price and quantity of the placed orders.
     * Keep quantities small and the total cost under {@code max-order-cost} (default $2).
     */
    private static final BigDecimal PM_ORDERBOOK_PRICE = new BigDecimal("0.5");
    private static final long PM_ORDERBOOK_SIZE = 5L;

    private static final BigDecimal KS_ORDERBOOK_PRICE = new BigDecimal("0.4");
    private static final long KS_ORDERBOOK_SIZE = 5L;

    // ---- SPRING CONTEXT ----

    private static final Long PAIR_ID = 9999L;

    @Autowired
    private ArbitrageMonitorService monitorService;

    @Autowired
    private PairPriceCache priceCache;

    @Autowired
    private ArbitrageEventRepository eventRepository;

    @Autowired
    private ArbitrageEventMarketsInfoRepository marketsInfoRepository;

    @Autowired
    private ArbitrageOrderRepository orderRepository;

    @Autowired
    private PredictionMarketRepository pmRepo;

    @Autowired
    private ArbitrageConfig config;

    @Autowired
    private PolymarketTradingService polymarketTradingService;

    @Autowired
    private hzpro.com.tradingdesk.service.account.KalshiBalanceService kalshiBalanceService;

    @Autowired
    private hzpro.com.tradingdesk.service.account.PolymarketBalanceService polymarketBalanceService;

    @MockitoBean
    private KalshiTradingService kalshiTradingService;

    // Mock order-book retrieval so we control the prices/sizes used at order-create time.
    @MockitoBean
    private PolymarketFetchCommonOrderBookService pmOrderBookService;

    @MockitoBean
    private KalshiFetchCommonOrderBookService ksOrderBookService;

    @BeforeEach
    void setUp() {
        clearState();

        // ---- 1. Seed the in-memory ticker maps ----
        serviceMap("pmSlugs").put(PAIR_ID, PM_TICKER);
        serviceMap("ksTickers").put(PAIR_ID, KS_TICKER);

        // ---- 2. Seed fetching_prediction_markets so createOrders can resolve token IDs ----
        pmRepo.save(PredictionMarket.builder()
                .datasource(DataSource.POLYMARKET)
                .marketTicker(PM_TICKER)
                .yesTokenId(PM_YES_TOKEN_ID)
                .noTokenId(PM_NO_TOKEN_ID)
                .build());

        // ---- 3. Stub order books ----
        YesOrNoResult pmSide = DIRECTION == ArbitrageDirection.PM_YES_KS_NO ? YesOrNoResult.YES : YesOrNoResult.NO;
        YesOrNoResult ksSide = DIRECTION == ArbitrageDirection.PM_YES_KS_NO ? YesOrNoResult.NO : YesOrNoResult.YES;

        OrderBook pmBook = new OrderBook(List.of(
                new OrderBookEntry(PM_ORDERBOOK_PRICE, PM_ORDERBOOK_SIZE)));
        OrderBook ksBook = new OrderBook(List.of(
                new OrderBookEntry(KS_ORDERBOOK_PRICE, KS_ORDERBOOK_SIZE)));

        when(pmOrderBookService.fetchCommonOrderBook(PM_TICKER, pmSide)).thenReturn(pmBook);
        when(ksOrderBookService.fetchCommonOrderBook(KS_TICKER, ksSide)).thenReturn(ksBook);

        // disable kalshi orders
        when(kalshiTradingService.placeOrder(any(), any(), anyInt(), any())).thenReturn(
            new OrderResult(true, "kalshi-order-id","placed", null
             )
        );


        // ---- 4. Seed prices to trigger the arb ----
        seedPrices(PM_YES_ASK, PM_NO_ASK, KS_YES_ASK, KS_NO_ASK);

        // ---- 5. Enable trading ----
        config.setTradingEnabled(true);
    }

    @AfterEach
    void tearDown() {
        clearState();
    }

    @SuppressWarnings("unchecked")
    private Map<Object, Object> serviceMap(String field) {
        return (Map<Object, Object>) ReflectionTestUtils.getField(monitorService, field);
    }

    private void clearState() {
        marketsInfoRepository.deleteAll();
        orderRepository.deleteAll();
        eventRepository.deleteAll();
        pmRepo.deleteAll();
        priceCache.clear();
        serviceMap("activeOpps").clear();
        serviceMap("pmSlugs").clear();
        serviceMap("ksTickers").clear();
        config.setTradingEnabled(false);
    }

    private void seedPrices(BigDecimal pmYes, BigDecimal pmNo, BigDecimal ksYes, BigDecimal ksNo) {
        priceCache.updatePmPrice(PAIR_ID, true, pmYes);
        priceCache.updatePmPrice(PAIR_ID, false, pmNo);
        priceCache.updateKsPrice(PAIR_ID, true, ksYes);
        priceCache.updateKsPrice(PAIR_ID, false, ksNo);
    }

    private void invokeCheckArbitrage() {
        ReflectionTestUtils.invokeMethod(monitorService, "checkArbitrage", PAIR_ID);
    }

    // ---- TESTS ----

    @Test
    @DisplayName("Place real arbitrage orders on both venues")
    void placeRealArbitrageOrders() {

        // Compute expected spread for logging
        BigDecimal spread = DIRECTION.calculateSpread(PM_YES_ASK, PM_NO_ASK, KS_YES_ASK, KS_NO_ASK);
        System.out.println("=== Manual Arbitrage Order Test ===");
        System.out.println("Direction: " + DIRECTION.getCode());
        System.out.println("Spread:   " + spread);
        System.out.println("Threshold: " + config.getThreshold());
        System.out.println("PM ticker: " + PM_TICKER);
        System.out.println("KS ticker: " + KS_TICKER);
        System.out.println("PM YES token: " + PM_YES_TOKEN_ID);
        System.out.println("PM NO token:  " + PM_NO_TOKEN_ID);
        System.out.println();

        // ---- Trigger the arbitrage check ----
        invokeCheckArbitrage();

        // ---- Verify OPPORTUNITY_START was persisted ----
        List<ArbitrageEvent> events = eventRepository.findAll();
        System.out.println("Arbitrage events persisted: " + events.size());
        for (ArbitrageEvent e : events) {
            System.out.println("  [" + e.getEventType() + "] dir=" + e.getDirection()
                    + " spread=" + e.getSpread() + " uuid=" + e.getUuid());
        }

        // ---- Check Polymarket balance first ----
        try {
            var pmBalance = polymarketBalanceService.getBalance();
            System.out.println();
            System.out.println("Polymarket balance: " + pmBalance.balance() + " USDC");
        } catch (Exception e) {
            System.out.println("Polymarket balance check failed: " + e.getMessage());
        }

        // ---- Check Kalshi balance first ----
        try {
            var ksBalance = kalshiBalanceService.getBalance();
            System.out.println("Kalshi balance: $" + ksBalance.balanceDollars() + " (" + ksBalance.balanceCents() + " cents)");
        } catch (Exception e) {
            System.out.println("Kalshi balance check failed: " + e.getMessage());
        }

        // ---- Check orders placed ----
        var orders = orderRepository.findAll();
        System.out.println();
        System.out.println("Orders placed: " + orders.size());
        for (var o : orders) {
            System.out.println("  [" + o.getPlatform() + "] " + o.getContractType()
                    + " qty=" + o.getQuantity() + " price=" + o.getPrice()
                    + " status=" + o.getStatus() + " orderId=" + o.getOrderId());
        }

        // ---- Try to cancel any placed orders (cleanup) ----
        for (var o : orders) {
            if (o.getOrderId() != null) {
                try {
                    if ("POLYMARKET".equals(o.getPlatform())) {
                        boolean cancelled = polymarketTradingService.cancelOrder(o.getOrderId());
                        System.out.println("Cancel PM " + o.getOrderId() + ": " + cancelled);
                    } else if ("KALSHI".equals(o.getPlatform())) {
                        boolean cancelled = kalshiTradingService.cancelOrder(o.getOrderId());
                        System.out.println("Cancel KS " + o.getOrderId() + ": " + cancelled);
                    }
                } catch (Exception e) {
                    System.out.println("Failed to cancel " + o.getPlatform() + " order " + o.getOrderId()
                            + ": " + e.getMessage());
                }
            }
        }
    }

    // ---- Emergency cleanup ----

    /**
     * Cancels ALL open orders on both venues. Run this manually if the test leaves orphaned orders.
     * <p>
     * <b>Polymarket:</b> requires the order IDs. You'll need to list them from the CLOB or DB first.
     * <br>
     * <b>Kalshi:</b> cancels all open orders via {@code DELETE /portfolio/orders}.
     */
    @Test
    @Tag("manual")
    @DisplayName("Emergency: cancel ALL open orders on both venues")
    void cancelAllOpenOrders() {
        System.out.println("Checking for open orders in DB...");
        var openOrders = orderRepository.findAll().stream()
                .filter(o -> o.getOrderId() != null)
                .toList();

        System.out.println("Found " + openOrders.size() + " orders with IDs");
        for (var o : openOrders) {
            try {
                if ("POLYMARKET".equals(o.getPlatform())) {
                    boolean ok = polymarketTradingService.cancelOrder(o.getOrderId());
                    System.out.println("PM cancel " + o.getOrderId() + ": " + ok);
                } else {
                    boolean ok = kalshiTradingService.cancelOrder(o.getOrderId());
                    System.out.println("KS cancel " + o.getOrderId() + ": " + ok);
                }
            } catch (Exception e) {
                System.out.println("Cancel failed for " + o.getOrderId() + ": " + e.getMessage());
            }
        }
    }
}
