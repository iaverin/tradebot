package hzpro.com.tradingdesk.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import hzpro.com.tradingdesk.arbitrage.entity.ArbitrageEvent;
import hzpro.com.tradingdesk.arbitrage.entity.ArbitrageOrder;
import hzpro.com.tradingdesk.arbitrage.model.OrderState;
import hzpro.com.tradingdesk.arbitrage.repository.ArbitrageEventRepository;
import hzpro.com.tradingdesk.arbitrage.repository.ArbitrageOrderRepository;
import hzpro.com.tradingdesk.controller.dto.DailyOpportunityStatsDto;
import hzpro.com.tradingdesk.repository.UserRepository;
import hzpro.com.tradingdesk.testutil.AuthenticatedClient;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("ArbitrageController daily opportunity stats integration tests")
class ArbitrageControllerDailyOpportunityStatsIntegrationTest {

    private static final LocalDate START_DATE = LocalDate.of(2026, 1, 10);
    private static final LocalDate END_DATE = LocalDate.of(2026, 1, 13);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private ArbitrageEventRepository eventRepository;

    @Autowired
    private ArbitrageOrderRepository orderRepository;

    private AuthenticatedClient client;

    @BeforeEach
    void setUp() throws Exception {
        clearStatsData();
        client = AuthenticatedClient.create(mockMvc, objectMapper, userRepository, passwordEncoder, tx);
        seedStatsData();
    }

    @Test
    @DisplayName("aggregates unique start UUIDs and all linked orders by the opportunity UTC date")
    void returnsDailyStatsForRequestedDateRange() throws Exception {
        var result = client.perform(get("/api/arbitrage/opportunities/daily-stats")
                        .param("startDate", START_DATE.toString())
                        .param("endDate", END_DATE.toString()))
                .andExpect(status().isOk());

        List<DailyOpportunityStatsDto> stats = objectMapper.readValue(
                result.andReturn().getResponse().getContentAsString(),
                new TypeReference<>() {});

        assertThat(stats).extracting(DailyOpportunityStatsDto::date)
                .containsExactly(START_DATE, START_DATE.plusDays(1), START_DATE.plusDays(2), END_DATE);

        assertStats(
                stats.get(0),
                1,
                OrderState.values().length,
                Integer.toString(OrderState.values().length),
                Integer.toString(OrderState.values().length * 5));
        assertStats(stats.get(1), 1, 1, "1", "5");
        assertStats(stats.get(2), 0, 0, "0", "0");
        assertStats(stats.get(3), 0, 0, "0", "0");
    }

    @Test
    @DisplayName("defaults to the last ten UTC dates including today")
    void defaultsToLastTenUtcDates() throws Exception {
        clearStatsData();
        LocalDate todayUtc = LocalDate.now(ZoneOffset.UTC);
        saveStart(UUID.randomUUID(), todayUtc);

        var result = client.perform(get("/api/arbitrage/opportunities/daily-stats"))
                .andExpect(status().isOk());

        List<DailyOpportunityStatsDto> stats = objectMapper.readValue(
                result.andReturn().getResponse().getContentAsString(),
                new TypeReference<>() {});

        assertThat(stats).hasSize(10);
        assertThat(stats.getFirst().date()).isEqualTo(todayUtc.minusDays(9));
        assertThat(stats.getLast().date()).isEqualTo(todayUtc);
        assertStats(stats.getLast(), 1, 0, "0", "0");
    }

    @Test
    @DisplayName("rejects a reversed date range")
    void rejectsReversedDateRange() throws Exception {
        client.perform(get("/api/arbitrage/opportunities/daily-stats")
                        .param("startDate", END_DATE.toString())
                        .param("endDate", START_DATE.toString()))
                .andExpect(status().isBadRequest());
    }

    private void seedStatsData() {
        UUID duplicateUuid = UUID.randomUUID();
        UUID secondDayUuid = UUID.randomUUID();

        saveStart(duplicateUuid, START_DATE);
        saveStart(duplicateUuid, START_DATE);
        saveEvent(UUID.randomUUID(), "OPPORTUNITY_END", START_DATE);
        saveStart(secondDayUuid, START_DATE.plusDays(1));
        saveStart(UUID.randomUUID(), START_DATE.minusDays(1));

        List<ArbitrageOrder> orders = Arrays.stream(OrderState.values())
                .map(status -> order(duplicateUuid, status))
                .toList();
        ArbitrageOrder secondDayOrder = order(secondDayUuid, OrderState.ERROR);

        orderRepository.saveAllAndFlush(orders);
        orderRepository.saveAndFlush(secondDayOrder);

        // An order without its OPPORTUNITY_START cannot be attributed to an opportunity date.
        orderRepository.saveAndFlush(order(UUID.randomUUID(), OrderState.EXECUTED));

        // Deliberately put the order timestamps on dates other than their opportunity dates.
        orders.forEach(order -> order.setCreatedAt(START_DATE.plusDays(2)
                .atTime(12, 0).toInstant(ZoneOffset.UTC)));
        secondDayOrder.setCreatedAt(START_DATE.atTime(12, 0).toInstant(ZoneOffset.UTC));
        orderRepository.saveAllAndFlush(orders);
        orderRepository.saveAndFlush(secondDayOrder);
    }

    private ArbitrageEvent saveStart(UUID uuid, LocalDate date) {
        return saveEvent(uuid, "OPPORTUNITY_START", date);
    }

    private ArbitrageEvent saveEvent(UUID uuid, String eventType, LocalDate date) {
        return eventRepository.saveAndFlush(ArbitrageEvent.builder()
                .uuid(uuid)
                .detectedAt(date.atTime(12, 0).toInstant(ZoneOffset.UTC))
                .eventType(eventType)
                .similarMarketId(1L)
                .polymarketMarketTicker("PM-TEST")
                .kalshiMarketTicker("KS-TEST")
                .direction("PM_YES_KS_NO")
                .spread(new BigDecimal("0.10"))
                .threshold(new BigDecimal("0.05"))
                .polymarketOrderbook("{}")
                .kalshiOrderbook("{}")
                .build());
    }

    private ArbitrageOrder order(UUID opportunityUuid, OrderState status) {
        return ArbitrageOrder.builder()
                .opportunityUuid(opportunityUuid)
                .platform("TEST")
                .contractType("YES")
                .price(new BigDecimal("0.50"))
                .quantity(10L)
                .status(status)
                .build();
    }

    private void assertStats(
            DailyOpportunityStatsDto stats,
            long opportunityCount,
            long orderCount,
            String ratio,
            String createdOrderVolume) {
        assertThat(stats.opportunityCount()).isEqualTo(opportunityCount);
        assertThat(stats.orderCount()).isEqualTo(orderCount);
        assertThat(stats.ordersPerOpportunity()).isEqualByComparingTo(ratio);
        assertThat(stats.createdOrderVolume()).isEqualByComparingTo(createdOrderVolume);
    }

    private void clearStatsData() {
        orderRepository.deleteAllInBatch();
        eventRepository.deleteAllInBatch();
    }
}
