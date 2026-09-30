package hzpro.com.tradingdesk.controller;

import hzpro.com.tradingdesk.controller.dto.PortfolioDashboardDto;
import hzpro.com.tradingdesk.controller.dto.PortfolioVenueDto;
import hzpro.com.tradingdesk.controller.dto.PortfolioPositionsPageDto;
import hzpro.com.tradingdesk.portfolio.model.PortfolioRefreshResult;
import hzpro.com.tradingdesk.portfolio.model.PortfolioRefreshStatus;
import hzpro.com.tradingdesk.portfolio.model.PortfolioVenue;
import hzpro.com.tradingdesk.portfolio.model.PortfolioVenueRefreshResult;
import hzpro.com.tradingdesk.portfolio.service.PortfolioPositionQueryService;
import hzpro.com.tradingdesk.portfolio.service.PortfolioPositionRefreshCoordinator;
import hzpro.com.tradingdesk.portfolio.service.PortfolioRefreshInProgressException;
import hzpro.com.tradingdesk.repository.UserRepository;
import hzpro.com.tradingdesk.service.account.KalshiPortfolioService;
import hzpro.com.tradingdesk.service.account.PolymarketPortfolioService;
import hzpro.com.tradingdesk.testutil.AuthenticatedClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("PortfolioController Integration Tests")
class PortfolioControllerTest {

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

    @MockitoBean
    private KalshiPortfolioService kalshiPortfolioService;

    @MockitoBean
    private PolymarketPortfolioService polymarketPortfolioService;

    @MockitoBean
    private PortfolioPositionQueryService portfolioPositionQueryService;

    @MockitoBean
    private PortfolioPositionRefreshCoordinator portfolioPositionRefreshCoordinator;

    private AuthenticatedClient client;

    private static final PortfolioVenueDto SAMPLE_KALSHI = new PortfolioVenueDto(
            new BigDecimal("1500.00"),  // totalPortfolio
            new BigDecimal("500.00"),   // inCash
            new BigDecimal("200.00"),   // inOrders
            new BigDecimal("800.00"),   // inAssets
            new BigDecimal("150.00"),   // resolvedProfit
            new BigDecimal("50.00")     // resolvedLoss
    );

    private static final PortfolioVenueDto SAMPLE_POLYMARKET = new PortfolioVenueDto(
            new BigDecimal("3200.00"),
            new BigDecimal("1000.00"),
            new BigDecimal("300.00"),
            new BigDecimal("1900.00"),
            new BigDecimal("400.00"),
            new BigDecimal("75.00")
    );

    @BeforeEach
    void setUp() throws Exception {
        client = AuthenticatedClient.create(mockMvc, objectMapper, userRepository, passwordEncoder, tx);
    }

    @Test
    @DisplayName("GET /portfolio/dashboard returns full portfolio data")
    void returnsFullPortfolio() throws Exception {
        when(kalshiPortfolioService.getPortfolio()).thenReturn(SAMPLE_KALSHI);
        when(polymarketPortfolioService.getPortfolio()).thenReturn(SAMPLE_POLYMARKET);

        var result = client.perform(get("/portfolio/dashboard"))
                .andExpect(status().isOk());

        PortfolioDashboardDto dto = client.readValue(result, PortfolioDashboardDto.class);

        assertThat(dto.kalshi()).isNotNull();
        assertThat(dto.kalshi().totalPortfolioUsd()).isEqualByComparingTo("1500.00");
        assertThat(dto.kalshi().inCashUsd()).isEqualByComparingTo("500.00");
        assertThat(dto.kalshi().resolvedProfit()).isEqualByComparingTo("150.00");
        assertThat(dto.kalshi().resolvedLoss()).isEqualByComparingTo("50.00");

        assertThat(dto.polymarket()).isNotNull();
        assertThat(dto.polymarket().totalPortfolioUsd()).isEqualByComparingTo("3200.00");
        assertThat(dto.polymarket().resolvedProfit()).isEqualByComparingTo("400.00");
    }

    @Test
    @DisplayName("GET /portfolio/dashboard returns nulls when a venue service throws")
    void handlesVenueFailure() throws Exception {
        when(kalshiPortfolioService.getPortfolio()).thenReturn(SAMPLE_KALSHI);
        when(polymarketPortfolioService.getPortfolio()).thenThrow(new RuntimeException("API down"));

        var result = client.perform(get("/portfolio/dashboard"))
                .andExpect(status().isOk());

        PortfolioDashboardDto dto = client.readValue(result, PortfolioDashboardDto.class);

        // Kalshi should be fine
        assertThat(dto.kalshi().inCashUsd()).isEqualByComparingTo("500.00");

        // Polymarket should be all-null
        assertThat(dto.polymarket().totalPortfolioUsd()).isNull();
        assertThat(dto.polymarket().inCashUsd()).isNull();
        assertThat(dto.polymarket().resolvedProfit()).isNull();
    }

    @Test
    @DisplayName("GET /portfolio/dashboard requires authentication")
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/portfolio/dashboard"))
                .andExpect(status().isForbidden());
    }

    @Test
    void returnsAuthenticatedPositionsPage() throws Exception {
        Instant refreshedAt = Instant.parse("2026-09-10T10:00:00Z");
        when(portfolioPositionQueryService.getPositions("KALSHI", 0, 50))
                .thenReturn(new PortfolioPositionsPageDto(
                        "KALSHI", refreshedAt, List.of(), 0, 50, 0, 0));

        client.perform(get("/portfolio/positions").param("venue", "KALSHI"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.selectedVenue").value("KALSHI"))
                .andExpect(jsonPath("$.lastSuccessfulRefreshAt").value("2026-09-10T10:00:00Z"))
                .andExpect(jsonPath("$.content").isEmpty());
    }

    @Test
    void returnsTypedBadRequestForInvalidVenue() throws Exception {
        when(portfolioPositionQueryService.getPositions("OTHER", 0, 50))
                .thenThrow(new IllegalArgumentException("venue must be POLYMARKET or KALSHI"));

        client.perform(get("/portfolio/positions").param("venue", "OTHER"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("venue must be POLYMARKET or KALSHI"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void returnsTypedBadRequestForNonNumericPage() throws Exception {
        client.perform(get("/portfolio/positions").param("page", "not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid parameter: page"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void returnsTypedServerErrorWhenThePositionQueryFails() throws Exception {
        when(portfolioPositionQueryService.getPositions("POLYMARKET", 0, 50))
                .thenThrow(new RuntimeException("relation query unavailable"));

        client.perform(get("/portfolio/positions"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("Portfolio request failed"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void triggersBothVenueRefreshes() throws Exception {
        Instant requestedAt = Instant.parse("2026-09-10T10:00:00Z");
        when(portfolioPositionRefreshCoordinator.refreshAll()).thenReturn(new PortfolioRefreshResult(
                requestedAt,
                false,
                List.of(
                        new PortfolioVenueRefreshResult(
                                PortfolioVenue.POLYMARKET,
                                PortfolioRefreshStatus.SUCCESS,
                                requestedAt,
                                null),
                        new PortfolioVenueRefreshResult(
                                PortfolioVenue.KALSHI,
                                PortfolioRefreshStatus.FAILED,
                                null,
                                "Kalshi unavailable"))));

        client.perform(post("/portfolio/positions/refresh"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allSucceeded").value(false))
                .andExpect(jsonPath("$.venues[0].venue").value("POLYMARKET"))
                .andExpect(jsonPath("$.venues[0].status").value("SUCCESS"))
                .andExpect(jsonPath("$.venues[1].status").value("FAILED"));
    }

    @Test
    void reportsConcurrentRefreshAsConflict() throws Exception {
        when(portfolioPositionRefreshCoordinator.refreshAll())
                .thenThrow(new PortfolioRefreshInProgressException());

        client.perform(post("/portfolio/positions/refresh"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Portfolio update is already in progress"));
    }

    @Test
    void positionEndpointsRequireAuthentication() throws Exception {
        mockMvc.perform(get("/portfolio/positions"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/portfolio/positions/refresh"))
                .andExpect(status().isForbidden());
    }
}
