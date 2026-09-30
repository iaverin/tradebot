package hzpro.com.tradingdesk.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
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

import com.fasterxml.jackson.databind.ObjectMapper;

import hzpro.com.tradingdesk.config.SettingsService;
import hzpro.com.tradingdesk.controller.dto.ActivePairsLimitDto;
import hzpro.com.tradingdesk.entity.Setting;
import hzpro.com.tradingdesk.controller.dto.TradingEnabledDto;
import hzpro.com.tradingdesk.repository.SettingsRepository;
import hzpro.com.tradingdesk.repository.UserRepository;
import hzpro.com.tradingdesk.testutil.AuthenticatedClient;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("SettingsController integration tests")
class SettingsControllerIntegrationTest {

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
    private SettingsRepository settingsRepository;

    @Autowired
    private SettingsService settingsService;

    private AuthenticatedClient client;

    @BeforeEach
    void setUp() throws Exception {
        settingsRepository.deleteAll();
        settingsService.resetErrorsCount();
        settingsService.setTradingEnabled(false);
        client = AuthenticatedClient.create(mockMvc, objectMapper, userRepository, passwordEncoder, tx);
    }

    @AfterEach
    void tearDown() {
        settingsService.resetErrorsCount();
        settingsService.setTradingEnabled(false);
        settingsRepository.deleteAll();
    }

    @Test
    @DisplayName("PUT /api/settings/trading-enabled resets errorsCount when trading is enabled")
    void enablingTradingResetsErrorsCount() throws Exception {
        settingsService.addAndGet(1);

        client.perform(put("/api/settings/trading-enabled")
                        .content(objectMapper.writeValueAsString(new TradingEnabledDto(true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tradingEnabled").value(true));

        client.perform(get("/api/settings/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errorsCount").value(0));
    }

    @Test
    @DisplayName("GET /api/settings/info exposes the configured active-pairs fallback")
    void settingsInfoIncludesActivePairsLimit() throws Exception {
        client.perform(get("/api/settings/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activePairsLimit").value(1));
    }

    @Test
    @DisplayName("PUT /api/settings/active-pairs-limit persists and returns the canonical value")
    void updatesActivePairsLimit() throws Exception {
        client.perform(put("/api/settings/active-pairs-limit")
                        .content(objectMapper.writeValueAsString(new ActivePairsLimitDto(3))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activePairsLimit").value(3));

        Setting stored = settingsRepository.findByKey(SettingsService.ACTIVE_PAIRS_LIMIT_KEY).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(stored.getValue()).isEqualTo("3");
        org.assertj.core.api.Assertions.assertThat(settingsService.getActivePairsLimit()).isEqualTo(3);
    }

    @Test
    @DisplayName("PUT /api/settings/active-pairs-limit returns typed 400 responses for invalid input")
    void rejectsInvalidActivePairsLimit() throws Exception {
        client.perform(put("/api/settings/active-pairs-limit").content("{\"activePairsLimit\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.timestamp").exists());

        client.perform(put("/api/settings/active-pairs-limit")
                        .content("{\"activePairsLimit\":\"not-an-integer\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid settings request"));

        client.perform(put("/api/settings/active-pairs-limit").content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PUT /api/settings/active-pairs-limit requires ADMIN authority")
    void activePairsLimitUpdateRequiresAdmin() throws Exception {
        AuthenticatedClient nonAdmin = AuthenticatedClient.create(
                mockMvc, objectMapper, userRepository, passwordEncoder, tx, "USER");

        nonAdmin.perform(put("/api/settings/active-pairs-limit")
                        .content(objectMapper.writeValueAsString(new ActivePairsLimitDto(2))))
                .andExpect(status().isForbidden());
    }
}
