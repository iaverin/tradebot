package hzpro.com.tradingdesk.manual;

import com.fasterxml.jackson.databind.ObjectMapper;
import hzpro.com.tradingdesk.arbitrage.service.ArbitrageMonitorService;
import hzpro.com.tradingdesk.arbitrage.service.OrderStatusChecker;
import hzpro.com.tradingdesk.controller.dto.PortfolioPositionsPageDto;
import hzpro.com.tradingdesk.controller.dto.PortfolioRefreshResponseDto;
import hzpro.com.tradingdesk.controller.dto.PortfolioVenueRefreshResultDto;
import hzpro.com.tradingdesk.marketsfetcher.temporal.scheduler.TemporalSchedulerService;
import hzpro.com.tradingdesk.portfolio.service.PortfolioPositionRefreshCoordinator;
import hzpro.com.tradingdesk.portfolio.service.PortfolioPositionRefreshScheduler;
import hzpro.com.tradingdesk.repository.UserRepository;
import hzpro.com.tradingdesk.testutil.AuthenticatedClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Live read-only verification for configured venue accounts.
 * Run with {@code ./gradlew manualTest --tests '*PortfolioPositionsManualTest'}.
 */
@Tag("manual")
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PortfolioPositionsManualTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private PortfolioPositionRefreshCoordinator refreshCoordinator;

    @MockitoBean
    private ArbitrageMonitorService arbitrageMonitorService;

    @MockitoBean
    private OrderStatusChecker orderStatusChecker;

    @MockitoBean
    private TemporalSchedulerService temporalSchedulerService;

    private AuthenticatedClient client;

    @BeforeEach
    void setUp() throws Exception {
        client = AuthenticatedClient.create(
                mockMvc,
                objectMapper,
                userRepository,
                passwordEncoder,
                transactionTemplate);
    }

    @Test
    void refreshesThroughSchedulerAndAuthenticatedActionThenReadsBothVenueViews() throws Exception {
        new PortfolioPositionRefreshScheduler(refreshCoordinator).refreshPositions();

        var refreshRequest = client.perform(post("/portfolio/positions/refresh"))
                .andExpect(status().isOk());
        PortfolioRefreshResponseDto refresh = client.readValue(
                refreshRequest,
                PortfolioRefreshResponseDto.class);
        assertThat(refresh.venues()).hasSize(2);
        assertThat(refresh.allSucceeded())
                .withFailMessage("Live refresh failed: %s", refresh.venues())
                .isTrue();

        for (PortfolioVenueRefreshResultDto venueResult : refresh.venues()) {
            assertThat(venueResult.lastSuccessfulRefreshAt()).isNotNull();
            var pageRequest = client.perform(get("/portfolio/positions")
                            .param("venue", venueResult.venue())
                            .param("size", "200"))
                    .andExpect(status().isOk());
            PortfolioPositionsPageDto page = client.readValue(pageRequest, PortfolioPositionsPageDto.class);
            assertThat(page.selectedVenue()).isEqualTo(venueResult.venue());
            assertThat(page.lastSuccessfulRefreshAt()).isNotNull();
            page.content().forEach(group -> {
                assertThat(group.primary().quantity()).isPositive();
                assertThat(group.primary().venue()).isEqualTo(venueResult.venue());
                assertThat(group.related())
                        .allMatch(position -> !position.venue().equals(venueResult.venue()));
            });
            System.out.printf(
                    "%s: %d open position(s), last successful refresh %s%n",
                    venueResult.venue(),
                    page.totalElements(),
                    page.lastSuccessfulRefreshAt());
            page.content().stream().limit(3).forEach(group -> System.out.printf(
                    "  %s %s quantity=%s spent=%s current=%s related=%d%n",
                    group.primary().marketTicker(),
                    group.primary().outcome(),
                    group.primary().quantity(),
                    group.primary().spentUsd(),
                    group.primary().currentValueUsd(),
                    group.related().size()));
        }
    }
}
