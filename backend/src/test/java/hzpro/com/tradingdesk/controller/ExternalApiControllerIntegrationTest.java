package hzpro.com.tradingdesk.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("ExternalApiController Integration Tests")
class ExternalApiControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Value("${external.api.key}")
    private String apiKey;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DELETE FROM similar_markets");
    }

    @Test
    @DisplayName("GET /external/similar-markets - Should return 401 when API key is missing")
    void testListSimilarMarketsUnauthorizedWhenNoApiKey() throws Exception {
        mockMvc.perform(get("/external/similar-markets"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /external/similar-markets - Should return 401 when API key is wrong")
    void testListSimilarMarketsUnauthorizedWhenWrongApiKey() throws Exception {
        mockMvc.perform(get("/external/similar-markets")
                        .header("X-API-Key", "wrong-key"))
                .andExpect(status().isUnauthorized());
    }


    @Test
    @DisplayName("GET /external/similar-markets - Should respect page and size query params")
    void testListSimilarMarketsPagination() throws Exception {
        for (int i = 1; i <= 5; i++) {
            insertSimilarMarket(
                    0.9,
                    "POLY-EVT-" + i, "Poly Event " + i,
                    "POLY-MKT-" + i, "Poly Market " + i,
                    "KALSHI-EVT-" + i, "Kalshi Event " + i,
                    "KALSHI-MKT-" + i, "Kalshi Market " + i
            );
        }

        // First page of 2
        mockMvc.perform(get("/external/similar-markets")
                        .param("page", "0")
                        .param("size", "2")
                        .header("X-API-Key", apiKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.number").value(0));

        // Second page of 2
        mockMvc.perform(get("/external/similar-markets")
                        .param("page", "1")
                        .param("size", "2")
                        .header("X-API-Key", apiKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.number").value(1));
    }

    // --- helpers ---

    private void insertSimilarMarket(
            double similarity,
            String polyEventTicker, String polyEventTitle,
            String polyMarketTicker, String polyMarketTitle,
            String kalshiEventTicker, String kalshiEventTitle,
            String kalshiMarketTicker, String kalshiMarketTitle
    ) {
        jdbcTemplate.update("""
                INSERT INTO similar_markets (
                    created_at, updated_at, similarity,
                    polymarket_event_ticker, polymarket_event_title,
                    polymarket_market_ticker, polymarket_market_title,
                    kalshi_event_ticker, kalshi_event_title,
                    kalshi_market_ticker, kalshi_market_title
                ) VALUES (NOW(), NOW(), ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                similarity,
                polyEventTicker, polyEventTitle,
                polyMarketTicker, polyMarketTitle,
                kalshiEventTicker, kalshiEventTitle,
                kalshiMarketTicker, kalshiMarketTitle
        );
    }
}
