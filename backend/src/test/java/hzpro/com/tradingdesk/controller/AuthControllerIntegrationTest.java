package hzpro.com.tradingdesk.controller;

import hzpro.com.tradingdesk.controller.dto.LoginRequest;
import hzpro.com.tradingdesk.controller.dto.LoginResponse;
import hzpro.com.tradingdesk.entity.User;
import hzpro.com.tradingdesk.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("AuthController Integration Tests")
class AuthControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();

        // Create test users
        User adminUser = new User();
        adminUser.setUsername("admin");
        adminUser.setPassword(passwordEncoder.encode("admin123"));
        adminUser.setRole("ADMIN");
        userRepository.save(adminUser);

        User regularUser = new User();
        regularUser.setUsername("testuser");
        regularUser.setPassword(passwordEncoder.encode("password123"));
        regularUser.setRole("USER");
        userRepository.save(regularUser);
    }

    @Test
    @DisplayName("POST /auth/login - Should successfully login with valid credentials")
    void testLoginSuccess() throws Exception {
        LoginRequest loginRequest = new LoginRequest();
        loginRequest.setUsername("admin");
        loginRequest.setPassword("admin123");

        @SuppressWarnings("null")
        MvcResult result = mockMvc.perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.token").exists())
                .andExpect(jsonPath("$.username").value("admin"))
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        LoginResponse loginResponse = objectMapper.readValue(responseBody, LoginResponse.class);

        assertThat(loginResponse.getToken()).isNotNull();
        assertThat(loginResponse.getToken()).isNotEmpty();
        assertThat(loginResponse.getUsername()).isEqualTo("admin");

        String token = loginResponse.getToken();
        assertThat(token).isNotNull();
        assertThat(token.split("\\.")).hasSize(3);
        assertThat(token).matches("^[A-Za-z0-9-_]+\\.[A-Za-z0-9-_]+\\.[A-Za-z0-9-_]+$");

    }

    @ParameterizedTest
    @CsvSource(value={"admin, wrongpassword","nonexistent, password123"})
    @DisplayName("POST /auth/login - Should fail with non-existent user")
    void testLoginNonExistentUser(String login, String password) throws Exception {
        LoginRequest loginRequest = new LoginRequest();
        loginRequest.setUsername(login);
        loginRequest.setPassword(password);

        mockMvc.perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isBadRequest());
    }


}
