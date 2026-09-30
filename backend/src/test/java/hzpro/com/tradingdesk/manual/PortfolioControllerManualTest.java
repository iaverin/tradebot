package hzpro.com.tradingdesk.manual;

import com.fasterxml.jackson.databind.ObjectMapper;
import hzpro.com.tradingdesk.controller.dto.PortfolioDashboardDto;
import hzpro.com.tradingdesk.controller.dto.PortfolioVenueDto;
import hzpro.com.tradingdesk.repository.UserRepository;
import hzpro.com.tradingdesk.testutil.AuthenticatedClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Manual test that calls {@code GET /portfolio/dashboard} with real venue APIs
 * (no mocked services). Requires valid Kalshi and Polymarket credentials in the
 * environment ({@code .env} or env vars).
 *
 * <p>Run with: {@code ./gradlew manualTest --tests "*PortfolioControllerManualTest*"}
 */
@Tag("manual")
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("PortfolioController Manual Test")
class PortfolioControllerManualTest {

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

    private AuthenticatedClient client;

    @BeforeEach
    void setUp() throws Exception {
        client = AuthenticatedClient.create(mockMvc, objectMapper, userRepository, passwordEncoder, tx);
    }

    @Test
    @DisplayName("GET /portfolio/dashboard returns real portfolio data from both venues")
    void returnsRealPortfolioData() throws Exception {
        var result = client.perform(get("/portfolio/dashboard"))
                .andExpect(status().isOk());

        PortfolioDashboardDto dto = client.readValue(result, PortfolioDashboardDto.class);

        assertNotNull(dto, "Dashboard DTO must not be null");
        assertNotNull(dto.kalshi(), "Kalshi venue DTO must not be null");
        assertNotNull(dto.polymarket(), "Polymarket venue DTO must not be null");

        printVenue("Kalshi", dto.kalshi());
        printVenue("Polymarket", dto.polymarket());
    }

    @Test
    @DisplayName("GET /portfolio/dashboard requires authentication")
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/portfolio/dashboard"))
                .andExpect(status().isForbidden());
    }

    private static void printVenue(String name, PortfolioVenueDto v) {
        System.out.println("── " + name + " ──");
        System.out.println("  Total portfolio:  " + v.totalPortfolioUsd());
        System.out.println("  In cash:          " + v.inCashUsd());
        System.out.println("  In orders:        " + v.inOrdersUsd());
        System.out.println("  In assets:        " + v.inAssetsUsd());
        System.out.println("  Resolved profit:  " + v.resolvedProfit());
        System.out.println("  Resolved loss:    " + v.resolvedLoss());
    }
}
