package hzpro.com.tradingdesk.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import hzpro.com.tradingdesk.arbitrage.entity.ArbitrageEvent;
import hzpro.com.tradingdesk.arbitrage.entity.ArbitrageEventMarketsInfo;
import hzpro.com.tradingdesk.arbitrage.entity.ArbitrageOrder;
import hzpro.com.tradingdesk.arbitrage.model.OrderState;
import hzpro.com.tradingdesk.arbitrage.model.OpportunityCloseReason;
import hzpro.com.tradingdesk.arbitrage.repository.ArbitrageEventRepository;
import hzpro.com.tradingdesk.arbitrage.repository.ArbitrageEventMarketsInfoRepository;
import hzpro.com.tradingdesk.arbitrage.repository.ArbitrageOrderRepository;
import hzpro.com.tradingdesk.controller.dto.OpportunityReportDto;
import hzpro.com.tradingdesk.entity.SimilarMarket;
import hzpro.com.tradingdesk.repository.UserRepository;
import hzpro.com.tradingdesk.similarmarkets.repository.SimilarMarketsRepository;
import hzpro.com.tradingdesk.testutil.AuthenticatedClient;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("ArbitrageController opportunity report integration tests")
class ArbitrageControllerOpportunityReportIntegrationTest {

    private static final Instant START_DATE = Instant.parse("2026-01-10T00:00:00Z");
    private static final Instant END_DATE = Instant.parse("2026-01-12T23:59:59Z");

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private TransactionTemplate tx;
    @Autowired private ArbitrageEventRepository eventRepository;
    @Autowired private ArbitrageEventMarketsInfoRepository marketsInfoRepository;
    @Autowired private ArbitrageOrderRepository orderRepository;
    @Autowired private SimilarMarketsRepository similarMarketsRepository;

    private AuthenticatedClient client;
    private SimilarMarket pairWithOrders;
    private SimilarMarket pairWithoutOrders;

    @BeforeEach
    void setUp() throws Exception {
        marketsInfoRepository.deleteAllInBatch();
        orderRepository.deleteAllInBatch();
        eventRepository.deleteAllInBatch();
        similarMarketsRepository.deleteAllInBatch();
        client = AuthenticatedClient.create(mockMvc, objectMapper, userRepository, passwordEncoder, tx);
        seedReportData();
    }

    @Test
    @DisplayName("onlyWithOrders=true returns only pairs having an order in the requested range")
    void returnsOnlyPairsWithOrders() throws Exception {
        List<OpportunityReportDto> report = requestReport(true);

        assertThat(report).hasSize(1);
        assertPairWithOrders(report.getFirst());
    }

    @Test
    @DisplayName("onlyWithOrders=false includes pairs without orders")
    void includesPairsWithoutOrders() throws Exception {
        List<OpportunityReportDto> report = requestReport(false);

        assertThat(report).extracting(OpportunityReportDto::similarMarketId)
                .containsExactlyInAnyOrder(pairWithOrders.getId(), pairWithoutOrders.getId());

        OpportunityReportDto withOrders = findPair(report, pairWithOrders.getId());
        assertPairWithOrders(withOrders);

        OpportunityReportDto withoutOrders = findPair(report, pairWithoutOrders.getId());
        assertThat(withoutOrders.polymarketEventTicker()).isEqualTo("SNAPSHOT-PM-EVENT-WITHOUT");
        assertThat(withoutOrders.kalshiMarketTicker()).isEqualTo("SNAPSHOT-KS-MARKET-WITHOUT");
        assertThat(withoutOrders.polymarketMarketCloseDatetime())
                .isEqualTo(Instant.parse("2026-03-01T12:00:00Z"));
        assertThat(withoutOrders.kalshiMarketCloseDatetime())
                .isEqualTo(Instant.parse("2026-03-02T12:00:00Z"));
        assertThat(withoutOrders.opportunityCreatedAt()).isEqualTo(Instant.parse("2026-01-12T12:00:00Z"));
        assertThat(withoutOrders.opportunityCount()).isEqualTo(6);
        assertThat(withoutOrders.oppsWithOrders()).isZero();
        assertThat(withoutOrders.oppsWithUnexecuted()).isZero();
        assertThat(withoutOrders.pmOrders()).isZero();
        assertThat(withoutOrders.ksOrders()).isZero();
        assertThat(withoutOrders.pmExecutedCount()).isZero();
        assertThat(withoutOrders.ksExecutedCount()).isZero();
        assertThat(withoutOrders.spreadBelowThresholdCloseCount()).isEqualTo(1);
        assertThat(withoutOrders.priceDataStaleCloseCount()).isZero();
        assertThat(withoutOrders.priceDataUnavailableCloseCount()).isZero();
        assertThat(withoutOrders.orderCreationFailedCloseCount()).isZero();
        assertThat(withoutOrders.orderbookPriceBelowThresholdCloseCount()).isEqualTo(1);
        assertThat(withoutOrders.orderBookNotEnoughAmountCloseCount()).isEqualTo(1);
        assertThat(withoutOrders.orderbookEmptyCloseCount()).isEqualTo(1);
        assertThat(withoutOrders.minimumOrderCostNotMetCloseCount()).isEqualTo(1);
        assertThat(withoutOrders.activePairLimitReachedCloseCount()).isEqualTo(1);
        assertThat(withoutOrders.ordersCreatedCloseCount()).isZero();
        assertThat(withoutOrders.unknownCloseCount()).isZero();
    }

    private List<OpportunityReportDto> requestReport(boolean onlyWithOrders) throws Exception {
        var result = client.perform(get("/api/arbitrage/opportunities/report")
                        .param("startDate", START_DATE.toString())
                        .param("endDate", END_DATE.toString())
                        .param("onlyWithOrders", Boolean.toString(onlyWithOrders)))
                .andExpect(status().isOk());

        return objectMapper.readValue(
                result.andReturn().getResponse().getContentAsString(),
                new TypeReference<>() {});
    }

    private void seedReportData() {
        pairWithOrders = savePair("WITH");
        pairWithoutOrders = savePair("WITHOUT");

        UUID orderedOpportunity = UUID.randomUUID();
        ArbitrageEvent orderedStart = saveStart(
                pairWithOrders.getId(), orderedOpportunity, Instant.parse("2026-01-10T12:00:00Z"));
        saveEnd(orderedStart, OpportunityCloseReason.ORDERS_CREATED);
        UUID latestWithOrdersPairOpportunity = UUID.randomUUID();
        ArbitrageEvent latestWithOrdersStart = saveStart(
                pairWithOrders.getId(), latestWithOrdersPairOpportunity, Instant.parse("2026-01-11T12:00:00Z"));
        saveEnd(latestWithOrdersStart, OpportunityCloseReason.ORDER_CREATION_FAILED);
        saveMarketInfo(latestWithOrdersPairOpportunity, "WITH", "2026-02-01T12:00:00Z", "2026-02-02T12:00:00Z");
        orderRepository.saveAllAndFlush(List.of(
                order(orderedOpportunity, "POLYMARKET", OrderState.EXECUTED),
                order(orderedOpportunity, "POLYMARKET", OrderState.CANCELED),
                order(orderedOpportunity, "KALSHI", OrderState.PLACED)));

        saveEnd(saveStart(pairWithoutOrders.getId(), UUID.randomUUID(),
                        Instant.parse("2026-01-12T08:00:00Z")),
                OpportunityCloseReason.ORDERBOOK_PRICE_BELOW_THRESHOLD);
        saveEnd(saveStart(pairWithoutOrders.getId(), UUID.randomUUID(),
                        Instant.parse("2026-01-12T09:00:00Z")),
                OpportunityCloseReason.ORDER_BOOK_NOT_ENOUGH_AMOUT);
        saveEnd(saveStart(pairWithoutOrders.getId(), UUID.randomUUID(),
                        Instant.parse("2026-01-12T10:00:00Z")),
                OpportunityCloseReason.ORDERBOOK_EMPTY);
        saveEnd(saveStart(pairWithoutOrders.getId(), UUID.randomUUID(),
                        Instant.parse("2026-01-12T11:00:00Z")),
                OpportunityCloseReason.MINIMIM_ORDER_COST_NOT_MET);
        saveEnd(saveStart(pairWithoutOrders.getId(), UUID.randomUUID(),
                        Instant.parse("2026-01-12T11:30:00Z")),
                OpportunityCloseReason.ACTIVE_PAIR_LIMIT_REACHED);

        UUID inRangeOpportunityWithoutOrders = UUID.randomUUID();
        ArbitrageEvent withoutOrdersStart = saveStart(
                pairWithoutOrders.getId(), inRangeOpportunityWithoutOrders, Instant.parse("2026-01-12T12:00:00Z"));
        saveEnd(withoutOrdersStart, OpportunityCloseReason.SPREAD_BELOW_THRESHOLD);
        saveMarketInfo(inRangeOpportunityWithoutOrders, "WITHOUT", "2026-03-01T12:00:00Z", "2026-03-02T12:00:00Z");

        UUID outOfRangeOpportunity = UUID.randomUUID();
        saveStart(pairWithoutOrders.getId(), outOfRangeOpportunity, Instant.parse("2026-01-09T12:00:00Z"));
        saveMarketInfo(outOfRangeOpportunity, "OUTSIDE", "2026-04-01T12:00:00Z", "2026-04-02T12:00:00Z");
        orderRepository.saveAndFlush(order(outOfRangeOpportunity, "POLYMARKET", OrderState.EXECUTED));
    }

    private SimilarMarket savePair(String suffix) {
        Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");
        return similarMarketsRepository.saveAndFlush(SimilarMarket.builder()
                .createdAt(createdAt)
                .updatedAt(createdAt)
                .similarity(0.95)
                .polymarketEventTicker("LIVE-PM-EVENT-" + suffix)
                .polymarketEventTitle("Live PM Event " + suffix)
                .polymarketMarketTicker("LIVE-PM-MARKET-" + suffix)
                .polymarketMarketTitle("Live PM Market " + suffix)
                .kalshiEventTicker("LIVE-KS-EVENT-" + suffix)
                .kalshiEventTitle("Live KS Event " + suffix)
                .kalshiMarketTicker("LIVE-KS-MARKET-" + suffix)
                .kalshiMarketTitle("Live KS Market " + suffix)
                .build());
    }

    private void saveMarketInfo(UUID uuid, String suffix, String pmClose, String ksClose) {
        Map<String, Object> marketsInfo = Map.of(
                "POLYMARKET", Map.of(
                        "eventTicker", "SNAPSHOT-PM-EVENT-" + suffix,
                        "eventTitle", "Snapshot PM Event " + suffix,
                        "marketTicker", "SNAPSHOT-PM-MARKET-" + suffix,
                        "marketTitle", "Snapshot PM Market " + suffix,
                        "marketCloseDatetime", pmClose),
                "KALSHI", Map.of(
                        "eventTicker", "SNAPSHOT-KS-EVENT-" + suffix,
                        "eventTitle", "Snapshot KS Event " + suffix,
                        "marketTicker", "SNAPSHOT-KS-MARKET-" + suffix,
                        "marketTitle", "Snapshot KS Market " + suffix,
                        "marketCloseDatetime", ksClose));

        marketsInfoRepository.saveAndFlush(ArbitrageEventMarketsInfo.builder()
                .uuid(uuid)
                .marketsInfo(writeJson(marketsInfo))
                .similarMarketsInfo(writeJson(Map.of(
                        "polymarket_event_ticker", "SIMILAR-PM-EVENT-" + suffix,
                        "kalshi_event_ticker", "SIMILAR-KS-EVENT-" + suffix)))
                .build());
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private ArbitrageEvent saveStart(long pairId, UUID uuid, Instant detectedAt) {
        return eventRepository.saveAndFlush(ArbitrageEvent.builder()
                .uuid(uuid)
                .detectedAt(detectedAt)
                .eventType("OPPORTUNITY_START")
                .similarMarketId(pairId)
                .polymarketMarketTicker("PM-TEST")
                .kalshiMarketTicker("KS-TEST")
                .direction("PM_YES_KS_NO")
                .spread(new BigDecimal("0.10"))
                .threshold(new BigDecimal("0.05"))
                .polymarketOrderbook("{}")
                .kalshiOrderbook("{}")
                .build());
    }

    private void saveEnd(ArbitrageEvent start, OpportunityCloseReason closeReason) {
        eventRepository.saveAndFlush(ArbitrageEvent.builder()
                .uuid(start.getUuid())
                .detectedAt(start.getDetectedAt().plusSeconds(60))
                .eventType("OPPORTUNITY_END")
                .closeReason(closeReason)
                .opportunityStart(start)
                .similarMarketId(start.getSimilarMarketId())
                .polymarketMarketTicker(start.getPolymarketMarketTicker())
                .kalshiMarketTicker(start.getKalshiMarketTicker())
                .direction(start.getDirection())
                .spread(BigDecimal.ZERO)
                .threshold(start.getThreshold())
                .polymarketOrderbook("{}")
                .kalshiOrderbook("{}")
                .build());
    }

    private ArbitrageOrder order(UUID opportunityUuid, String platform, OrderState status) {
        return ArbitrageOrder.builder()
                .opportunityUuid(opportunityUuid)
                .platform(platform)
                .contractType("YES")
                .price(new BigDecimal("0.50"))
                .quantity(10L)
                .status(status)
                .build();
    }

    private void assertPairWithOrders(OpportunityReportDto actual) {
        assertThat(actual.similarMarketId()).isEqualTo(pairWithOrders.getId());
        assertThat(actual.polymarketEventTicker()).isEqualTo("SNAPSHOT-PM-EVENT-WITH");
        assertThat(actual.polymarketMarketTitle()).isEqualTo("Snapshot PM Market WITH");
        assertThat(actual.kalshiEventTitle()).isEqualTo("Snapshot KS Event WITH");
        assertThat(actual.kalshiMarketTicker()).isEqualTo("SNAPSHOT-KS-MARKET-WITH");
        assertThat(actual.polymarketMarketCloseDatetime())
                .isEqualTo(Instant.parse("2026-02-01T12:00:00Z"));
        assertThat(actual.kalshiMarketCloseDatetime())
                .isEqualTo(Instant.parse("2026-02-02T12:00:00Z"));
        assertThat(actual.opportunityCreatedAt()).isEqualTo(Instant.parse("2026-01-11T12:00:00Z"));
        assertThat(actual.opportunityCount()).isEqualTo(2);
        assertThat(actual.oppsWithOrders()).isEqualTo(1);
        assertThat(actual.oppsWithUnexecuted()).isEqualTo(1);
        assertThat(actual.pmOrders()).isEqualTo(2);
        assertThat(actual.ksOrders()).isEqualTo(1);
        assertThat(actual.pmExecutedCount()).isEqualTo(1);
        assertThat(actual.ksExecutedCount()).isZero();
        assertThat(actual.spreadBelowThresholdCloseCount()).isZero();
        assertThat(actual.priceDataStaleCloseCount()).isZero();
        assertThat(actual.priceDataUnavailableCloseCount()).isZero();
        assertThat(actual.orderCreationFailedCloseCount()).isEqualTo(1);
        assertThat(actual.orderbookPriceBelowThresholdCloseCount()).isZero();
        assertThat(actual.orderBookNotEnoughAmountCloseCount()).isZero();
        assertThat(actual.orderbookEmptyCloseCount()).isZero();
        assertThat(actual.minimumOrderCostNotMetCloseCount()).isZero();
        assertThat(actual.activePairLimitReachedCloseCount()).isZero();
        assertThat(actual.ordersCreatedCloseCount()).isEqualTo(1);
        assertThat(actual.unknownCloseCount()).isZero();
    }

    private OpportunityReportDto findPair(List<OpportunityReportDto> report, long pairId) {
        return report.stream()
                .filter(row -> row.similarMarketId() == pairId)
                .findFirst()
                .orElseThrow();
    }
}
