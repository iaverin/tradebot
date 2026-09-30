package hzpro.com.tradingdesk.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import hzpro.com.tradingdesk.arbitrage.entity.ArbitrageOrder;
import hzpro.com.tradingdesk.arbitrage.model.OrderState;
import hzpro.com.tradingdesk.arbitrage.repository.ArbitrageOrderRepository;
import hzpro.com.tradingdesk.controller.dto.DailyOrderTotalDto;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("ArbitrageController daily totals integration tests")
class ArbitrageControllerDailyTotalsIntegrationTest {

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
    private ArbitrageOrderRepository orderRepository;

    private AuthenticatedClient client;

    @BeforeEach
    void setUp() throws Exception {
        orderRepository.deleteAll();
        client = AuthenticatedClient.create(mockMvc, objectMapper, userRepository, passwordEncoder, tx);
        seedOrders();
    }

    @Test
    @DisplayName("GET /api/arbitrage/orders/daily-totals aggregates executed orders for each date")
    void returnsDailyTotalsForRequestedDateRange() throws Exception {
        var result = client.perform(get("/api/arbitrage/orders/daily-totals")
                        .param("startDate", START_DATE.toString())
                        .param("endDate", END_DATE.toString()))
                .andExpect(status().isOk());

        List<DailyOrderTotalDto> totals = objectMapper.readValue(
                result.andReturn().getResponse().getContentAsString(),
                new TypeReference<>() {});

        assertThat(totals).extracting(DailyOrderTotalDto::date)
                .containsExactly(START_DATE, START_DATE.plusDays(1), START_DATE.plusDays(2), END_DATE);
        assertThat(totals.get(0).totalUsd()).isEqualByComparingTo("25.00");
        assertThat(totals.get(1).totalUsd()).isEqualByComparingTo("29.00");
        assertThat(totals.get(2).totalUsd()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(totals.get(3).totalUsd()).isEqualByComparingTo("24.00");
    }

    private void seedOrders() {
        List<OrderSeed> seeds = List.of(
                seed(START_DATE, "0.50", 10, OrderState.EXECUTED),
                seed(START_DATE, "1.00", 20, OrderState.EXECUTED),
                seed(START_DATE, "0.25", 40, OrderState.PLACED),
                seed(START_DATE.plusDays(1), "0.40", 10, OrderState.EXECUTED),
                seed(START_DATE.plusDays(1), "0.50", 20, OrderState.EXECUTED),
                seed(START_DATE.plusDays(1), "0.75", 20, OrderState.EXECUTED),
                seed(START_DATE.plusDays(1), "0.30", 30, OrderState.CANCELED),
                seed(END_DATE, "0.20", 20, OrderState.EXECUTED),
                seed(END_DATE, "0.40", 50, OrderState.EXECUTED),
                seed(END_DATE, "0.90", 10, OrderState.ERROR),
                seed(START_DATE.minusDays(1), "1.00", 100, OrderState.EXECUTED),
                seed(END_DATE.plusDays(1), "1.00", 200, OrderState.EXECUTED));
        List<ArbitrageOrder> orders = seeds.stream().map(this::order).toList();

        // @PrePersist assigns the current instant, so flush once before restoring fixture dates.
        orderRepository.saveAllAndFlush(orders);
        for (int index = 0; index < orders.size(); index++) {
            orders.get(index).setCreatedAt(seeds.get(index).date().atTime(12, 0).toInstant(ZoneOffset.UTC));
        }
        orderRepository.saveAllAndFlush(orders);
    }

    private OrderSeed seed(LocalDate date, String price, long quantity, OrderState status) {
        return new OrderSeed(date, new BigDecimal(price), quantity, status);
    }

    private ArbitrageOrder order(OrderSeed seed) {
        return ArbitrageOrder.builder()
                .opportunityUuid(UUID.randomUUID())
                .platform("TEST")
                .contractType("YES")
                .price(seed.price())
                .quantity(seed.quantity())
                .status(seed.status())
                .build();
    }

    private record OrderSeed(LocalDate date, BigDecimal price, long quantity, OrderState status) {}
}
