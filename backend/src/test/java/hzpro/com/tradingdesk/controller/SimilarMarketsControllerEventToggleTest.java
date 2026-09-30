package hzpro.com.tradingdesk.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

import hzpro.com.tradingdesk.controller.dto.EventToggleRequest;
import hzpro.com.tradingdesk.controller.dto.EventToggleResponse;
import hzpro.com.tradingdesk.entity.AllowedMarketPair;
import hzpro.com.tradingdesk.entity.SimilarMarket;
import hzpro.com.tradingdesk.entity.enums.DataSource;
import hzpro.com.tradingdesk.repository.AllowedMarketPairRepository;
import hzpro.com.tradingdesk.repository.PredictionMarketRepository;
import hzpro.com.tradingdesk.repository.UserRepository;
import hzpro.com.tradingdesk.testutil.AuthenticatedClient;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("SimilarMarketsController event/toggle integration tests")
class SimilarMarketsControllerEventToggleTest {

    private static final String PM_EVENT_TICKER = "poly-event-1";
    private static final String KS_EVENT_TICKER = "kalshi-event-1";
    private static final String PM_EVENT_TITLE = "Poly Event 1";
    private static final String KS_EVENT_TITLE = "Kalshi Event 1";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private AllowedMarketPairRepository allowedMarketPairRepository;

    @Autowired
    private PredictionMarketRepository predictionMarketRepository;

    @Autowired
    private TransactionTemplate tx;

    @PersistenceContext
    private EntityManager entityManager;

    @BeforeEach
    void setUp() {
        tx.executeWithoutResult(status -> {
            allowedMarketPairRepository.deleteAll();
            entityManager.createQuery("DELETE FROM SimilarMarket").executeUpdate();
            userRepository.deleteAll();
            entityManager.flush();
        });

        tx.executeWithoutResult(status -> { entityManager.createNativeQuery("""
                TRUNCATE TABLE similar_markets
               """).executeUpdate();
            });

        tx.executeWithoutResult(status -> { entityManager.createNativeQuery("""
                TRUNCATE TABLE prediction_markets
               """).executeUpdate();});

        tx.executeWithoutResult(status -> { entityManager.createNativeQuery("""
                TRUNCATE TABLE allowed_market_pairs
               """).executeUpdate();});

    }

    // ── helpers ────────────────────────────────────────────────────────────

    void seedPredictionMarket(DataSource datasource, String marketTicker) {
        tx.executeWithoutResult(status -> {
            entityManager.createNativeQuery("""
                INSERT INTO prediction_markets(
                datasource, market_ticker, yes_token_id, no_token_id)
                values (:datasource, :marketTicker, 'someId', 'someId')
            """)
                    .setParameter("datasource", datasource.name())
                    .setParameter("marketTicker", marketTicker)
            .executeUpdate();

        });

        }
    Long seedMarket(String pmMarketTicker, String pmMarketTitle,
                    String ksMarketTicker, String ksMarketTitle) {
        return tx.execute(status -> {
            SimilarMarket sm = SimilarMarket.builder()
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .similarity(0.9)
                    .polymarketEventTicker(PM_EVENT_TICKER)
                    .polymarketEventTitle(PM_EVENT_TITLE)
                    .polymarketMarketTicker(pmMarketTicker)
                    .polymarketMarketTitle(pmMarketTitle)
                    .kalshiEventTicker(KS_EVENT_TICKER)
                    .kalshiEventTitle(KS_EVENT_TITLE)
                    .kalshiMarketTicker(ksMarketTicker)
                    .kalshiMarketTitle(ksMarketTitle)
                    .build();
            entityManager.persist(sm);
            return sm.getId();
        });
    }

    void seedAllowedPair(String pmTicker, String ksTicker) {
        tx.executeWithoutResult(status ->
            allowedMarketPairRepository.save(AllowedMarketPair.builder()
                    .polymarketTicker(pmTicker)
                    .kalshiTicker(ksTicker)
                    .build())
        );
    }

    long countAllowedPairs() {
        return tx.execute(status -> allowedMarketPairRepository.count());
    }

    boolean pairExists(String pmTicker, String ksTicker) {
        return tx.execute(status ->
                allowedMarketPairRepository.existsByPolymarketTickerAndKalshiTicker(pmTicker, ksTicker));
    }

    // ── tests ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("enabling an event")
    class EnableEvent {

        private AuthenticatedClient client;

        @BeforeEach
        void auth() throws Exception {
            client = AuthenticatedClient.create(mockMvc, objectMapper, userRepository, passwordEncoder, tx);
        }

        @Test
        @DisplayName("when all pairs are disabled, enables all and returns all IDs")
        void allDisabledEnablesAll() throws Exception {
            Long id1 = seedMarket("pm-mkt-1", "PM Market 1", "ks-mkt-1", "KS Market 1");
            Long id2 = seedMarket("pm-mkt-2", "PM Market 2", "ks-mkt-2", "KS Market 2");

            EventToggleResponse response = toggleEvent(client, true);

            assertThat(response.enabled()).isTrue();
            assertThat(response.affectedIds()).containsExactlyInAnyOrder(id1, id2);
            assertThat(countAllowedPairs()).isEqualTo(2);
            assertThat(pairExists("pm-mkt-1", "ks-mkt-1")).isTrue();
            assertThat(pairExists("pm-mkt-2", "ks-mkt-2")).isTrue();
        }

        @Test
        @DisplayName("when some pairs are already enabled, returns only newly enabled IDs")
        void partiallyEnabledReturnsOnlyAffected() throws Exception {
            Long id1 = seedMarket("pm-mkt-1", "PM Market 1", "ks-mkt-1", "KS Market 1");
            Long id2 = seedMarket("pm-mkt-2", "PM Market 2", "ks-mkt-2", "KS Market 2");
            seedAllowedPair("pm-mkt-1", "ks-mkt-1");

            EventToggleResponse response = toggleEvent(client, true);

            assertThat(response.enabled()).isTrue();
            assertThat(response.affectedIds()).containsExactly(id2);
            assertThat(countAllowedPairs()).isEqualTo(2);
        }

        @Test
        @DisplayName("when all pairs are already enabled, returns empty affectedIds")
        void allEnabledReturnsEmpty() throws Exception {
            seedMarket("pm-mkt-1", "PM Market 1", "ks-mkt-1", "KS Market 1");
            seedMarket("pm-mkt-2", "PM Market 2", "ks-mkt-2", "KS Market 2");
            seedAllowedPair("pm-mkt-1", "ks-mkt-1");
            seedAllowedPair("pm-mkt-2", "ks-mkt-2");

            EventToggleResponse response = toggleEvent(client, true);

            assertThat(response.enabled()).isTrue();
            assertThat(response.affectedIds()).isEmpty();
            assertThat(countAllowedPairs()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("disabling an event")
    class DisableEvent {

        private AuthenticatedClient client;

        @BeforeEach
        void auth() throws Exception {
            client = AuthenticatedClient.create(mockMvc, objectMapper, userRepository, passwordEncoder, tx);
                    tx.executeWithoutResult(status -> { entityManager.createNativeQuery("""
                TRUNCATE TABLE similar_markets
               """).executeUpdate();
            });

        tx.executeWithoutResult(status -> { entityManager.createNativeQuery("""
                TRUNCATE TABLE prediction_markets
               """).executeUpdate();});

        tx.executeWithoutResult(status -> { entityManager.createNativeQuery("""
                TRUNCATE TABLE allowed_market_pairs
               """).executeUpdate();});


        }

        @Test
        @DisplayName("when all pairs are enabled, disables all and returns all IDs")
        void allEnabledDisablesAll() throws Exception {
            Long id1 = seedMarket("pm-mkt-1", "PM Market 1", "ks-mkt-1", "KS Market 1");
            Long id2 = seedMarket("pm-mkt-2", "PM Market 2", "ks-mkt-2", "KS Market 2");
            seedAllowedPair("pm-mkt-1", "ks-mkt-1");
            seedAllowedPair("pm-mkt-2", "ks-mkt-2");

            EventToggleResponse response = toggleEvent(client, false);

            assertThat(response.enabled()).isFalse();
            assertThat(response.affectedIds()).containsExactlyInAnyOrder(id1, id2);
            assertThat(countAllowedPairs()).isEqualTo(0);
        }

        @Test
        @DisplayName("when some pairs are already disabled, returns only newly disabled IDs")
        void partiallyDisabledReturnsOnlyAffected() throws Exception {
            Long id1 = seedMarket("pm-mkt-1", "PM Market 1", "ks-mkt-1", "KS Market 1");
            seedMarket("pm-mkt-2", "PM Market 2", "ks-mkt-2", "KS Market 2");
            seedAllowedPair("pm-mkt-1", "ks-mkt-1");

            EventToggleResponse response = toggleEvent(client, false);

            assertThat(response.enabled()).isFalse();
            assertThat(response.affectedIds()).containsExactly(id1);
            assertThat(countAllowedPairs()).isEqualTo(0);
        }

        @Test
        @DisplayName("when all pairs are already disabled, returns empty affectedIds")
        void allDisabledReturnsEmpty() throws Exception {
            seedMarket("pm-mkt-1", "PM Market 1", "ks-mkt-1", "KS Market 1");

            EventToggleResponse response = toggleEvent(client, false);

            assertThat(response.enabled()).isFalse();
            assertThat(response.affectedIds()).isEmpty();
            assertThat(countAllowedPairs()).isEqualTo(0);
        }
    }

    @Test
    @DisplayName("event with no markets returns empty affectedIds")
    void noMarketsReturnsEmpty() throws Exception {
        AuthenticatedClient client = AuthenticatedClient.create(mockMvc, objectMapper, userRepository, passwordEncoder, tx);

        EventToggleResponse response = toggleEvent(client, true);

        assertThat(response.affectedIds()).isEmpty();
        assertThat(response.polymarketEventTicker()).isEqualTo(PM_EVENT_TICKER);
        assertThat(response.kalshiEventTicker()).isEqualTo(KS_EVENT_TICKER);
    }

    @Test
    @DisplayName("count allowed market pairs includes only pairs present in similar markets")
    void countAllowedMarketPairsIncludesOnlyCommonPairs() throws Exception {
        seedMarket("pm-mkt-1", "PM Market 1", "ks-mkt-1", "KS Market 1");
        seedAllowedPair("pm-mkt-1", "ks-mkt-1");
        seedAllowedPair("stale-pm-mkt", "stale-ks-mkt");
        seedPredictionMarket(DataSource.KALSHI, "ks-mkt-1");
        seedPredictionMarket(DataSource.POLYMARKET, "pm-mkt-1");

        AuthenticatedClient client = AuthenticatedClient.create(mockMvc, objectMapper, userRepository, passwordEncoder, tx);

        client.perform(get("/similar-markets/count-allowed-market-pairs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1));
    }

    @Test
    @DisplayName("requires authentication")
    void requiresAuthentication() throws Exception {
        EventToggleRequest request = new EventToggleRequest(PM_EVENT_TICKER, KS_EVENT_TICKER, true);

        mockMvc.perform(put("/similar-markets/event/toggle")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    private static EventToggleResponse toggleEvent(AuthenticatedClient client, boolean enabled) throws Exception {
        EventToggleRequest request = new EventToggleRequest(PM_EVENT_TICKER, KS_EVENT_TICKER, enabled);

        var result = client.perform(put("/similar-markets/event/toggle")
                .content(client.objectMapper().writeValueAsString(request)));

        return client.readValue(result, EventToggleResponse.class);
    }
}
