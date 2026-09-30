package hzpro.com.tradingdesk.arbitrage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.ObjectMapper;

import hzpro.com.tradingdesk.arbitrage.config.ArbitrageConfig;
import hzpro.com.tradingdesk.arbitrage.model.PriceSnapshot;
import hzpro.com.tradingdesk.arbitrage.repository.ArbitrageEventMarketsInfoRepository;
import hzpro.com.tradingdesk.arbitrage.repository.ArbitrageEventRepository;
import hzpro.com.tradingdesk.arbitrage.rest.KalshiPriceRestClient;
import hzpro.com.tradingdesk.arbitrage.rest.PolymarketPriceRestClient;
import hzpro.com.tradingdesk.config.ProxyConfig;
import hzpro.com.tradingdesk.config.SettingsService;
import hzpro.com.tradingdesk.repository.PredictionMarketRepository;
import hzpro.com.tradingdesk.similarmarkets.repository.SimilarMarketsRepository;

@ExtendWith(MockitoExtension.class)
class ArbitrageMonitorPairLockTest {

    private static final long FIRST_PAIR_ID = 1L;
    private static final long SECOND_PAIR_ID = 2L;

    @Mock private ArbitrageConfig config;
    @Mock private ArbitrageEventRepository eventRepository;
    @Mock private ArbitrageEventMarketsInfoRepository marketsInfoRepository;
    @Mock private PredictionMarketRepository predictionMarketRepository;
    @Mock private SimilarMarketsRepository similarMarketsRepository;
    @Mock private ProxyConfig proxyConfig;
    @Mock private PairPriceCache priceCache;
    @Mock private KalshiPriceRestClient kalshiPriceRestClient;
    @Mock private PolymarketPriceRestClient polymarketPriceRestClient;
    @Mock private PolymarketFetchCommonOrderBookService polymarketOrderBookService;
    @Mock private KalshiFetchCommonOrderBookService kalshiOrderBookService;
    @Mock private ObjectMapper objectMapper;
    @Mock private ArbitrageOrderService arbitrageOrderService;
    @Mock private BalanceService balanceService;
    @Mock private SettingsService settingsService;
    @Mock private OrderStatusChecker orderStatusChecker;

    @InjectMocks
    private ArbitrageMonitorService monitorService;

    private PriceSnapshot prices;

    @BeforeEach
    void setUp() {
        prices = PriceSnapshot.builder()
                .yesAsk(BigDecimal.ONE)
                .noAsk(BigDecimal.ONE)
                .build();
        when(priceCache.getKsPrice(anyLong())).thenReturn(prices);
        when(config.getThreshold()).thenReturn(BigDecimal.ZERO);
        tickerMap("pmSlugs").put(FIRST_PAIR_ID, "pm-first");
        tickerMap("ksTickers").put(FIRST_PAIR_ID, "ks-first");
        tickerMap("pmSlugs").put(SECOND_PAIR_ID, "pm-second");
        tickerMap("ksTickers").put(SECOND_PAIR_ID, "ks-second");
    }

    @Test
    void serializesChecksForTheSamePair() throws Exception {
        CountDownLatch firstCheckStartedReadingPrices = new CountDownLatch(1);
        CountDownLatch releaseFirstCheck = new CountDownLatch(1);
        AtomicInteger priceReads = new AtomicInteger();

        doAnswer(invocation -> {
            if (priceReads.incrementAndGet() == 1) {
                firstCheckStartedReadingPrices.countDown();
                assertThat(releaseFirstCheck.await(2, TimeUnit.SECONDS)).isTrue();
            }
            return prices;
        }).when(priceCache).getPmPrice(FIRST_PAIR_ID);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> invokeCheckArbitrage(FIRST_PAIR_ID));
            assertThat(firstCheckStartedReadingPrices.await(1, TimeUnit.SECONDS)).isTrue();

            Future<?> second = executor.submit(() -> invokeCheckArbitrage(FIRST_PAIR_ID));
            assertThatThrownBy(() -> second.get(150, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            assertThat(priceReads).hasValue(1);

            releaseFirstCheck.countDown();
            first.get(1, TimeUnit.SECONDS);
            second.get(1, TimeUnit.SECONDS);

            assertThat(priceReads).hasValue(2);
            assertThat(pairLocks()).isEmpty();
        } finally {
            releaseFirstCheck.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void allowsChecksForDifferentPairsToRunConcurrently() throws Exception {
        CountDownLatch firstPairStartedReadingPrices = new CountDownLatch(1);
        CountDownLatch releaseFirstPair = new CountDownLatch(1);
        CountDownLatch secondPairStartedReadingPrices = new CountDownLatch(1);
        AtomicBoolean firstPairBlocked = new AtomicBoolean();

        doAnswer(invocation -> {
            long pairId = invocation.getArgument(0);
            if (pairId == FIRST_PAIR_ID && firstPairBlocked.compareAndSet(false, true)) {
                firstPairStartedReadingPrices.countDown();
                assertThat(releaseFirstPair.await(2, TimeUnit.SECONDS)).isTrue();
            } else if (pairId == SECOND_PAIR_ID) {
                secondPairStartedReadingPrices.countDown();
            }
            return prices;
        }).when(priceCache).getPmPrice(anyLong());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> invokeCheckArbitrage(FIRST_PAIR_ID));
            assertThat(firstPairStartedReadingPrices.await(1, TimeUnit.SECONDS)).isTrue();

            Future<?> second = executor.submit(() -> invokeCheckArbitrage(SECOND_PAIR_ID));
            assertThat(secondPairStartedReadingPrices.await(1, TimeUnit.SECONDS)).isTrue();
            second.get(1, TimeUnit.SECONDS);

            releaseFirstPair.countDown();
            first.get(1, TimeUnit.SECONDS);

            assertThat(pairLocks()).isEmpty();
        } finally {
            releaseFirstPair.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void serializesDifferentSimilarityIdsForTheSameTickerPair() throws Exception {
        tickerMap("pmSlugs").put(SECOND_PAIR_ID, "pm-first");
        tickerMap("ksTickers").put(SECOND_PAIR_ID, "ks-first");

        CountDownLatch firstCheckStartedReadingPrices = new CountDownLatch(1);
        CountDownLatch releaseFirstCheck = new CountDownLatch(1);
        AtomicInteger priceReads = new AtomicInteger();

        doAnswer(invocation -> {
            if (priceReads.incrementAndGet() == 1) {
                firstCheckStartedReadingPrices.countDown();
                assertThat(releaseFirstCheck.await(2, TimeUnit.SECONDS)).isTrue();
            }
            return prices;
        }).when(priceCache).getPmPrice(anyLong());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> invokeCheckArbitrage(FIRST_PAIR_ID));
            assertThat(firstCheckStartedReadingPrices.await(1, TimeUnit.SECONDS)).isTrue();

            Future<?> second = executor.submit(() -> invokeCheckArbitrage(SECOND_PAIR_ID));
            assertThatThrownBy(() -> second.get(150, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            assertThat(priceReads).hasValue(1);

            releaseFirstCheck.countDown();
            first.get(1, TimeUnit.SECONDS);
            second.get(1, TimeUnit.SECONDS);
            assertThat(pairLocks()).isEmpty();
        } finally {
            releaseFirstCheck.countDown();
            executor.shutdownNow();
        }
    }

    private void invokeCheckArbitrage(long pairId) {
        ReflectionTestUtils.invokeMethod(monitorService, "checkArbitrage", pairId);
    }

    private Map<?, ?> pairLocks() {
        return (Map<?, ?>) ReflectionTestUtils.getField(monitorService, "pairLocks");
    }

    @SuppressWarnings("unchecked")
    private Map<Long, String> tickerMap(String fieldName) {
        return (Map<Long, String>) ReflectionTestUtils.getField(monitorService, fieldName);
    }
}
