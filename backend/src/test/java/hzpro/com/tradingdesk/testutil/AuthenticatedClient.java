package hzpro.com.tradingdesk.testutil;

import com.fasterxml.jackson.databind.ObjectMapper;
import hzpro.com.tradingdesk.controller.dto.LoginRequest;
import hzpro.com.tradingdesk.controller.dto.LoginResponse;
import hzpro.com.tradingdesk.entity.User;
import hzpro.com.tradingdesk.repository.UserRepository;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Wraps a {@link MockMvc} with JWT authentication, creating a random user
 * and logging in automatically so tests don't need to manage auth tokens.
 *
 * <p>Usage:
 * <pre>{@code
 * @Autowired private MockMvc mockMvc;
 * @Autowired private TransactionTemplate tx;
 * ...
 * var client = AuthenticatedClient.create(mockMvc, objectMapper, userRepo, pwEncoder, tx);
 * client.perform(put("/some/endpoint").content(...));
 * }</pre>
 */
public class AuthenticatedClient {

    private final MockMvc mockMvc;
    private final ObjectMapper objectMapper;
    private final String authToken;

    private AuthenticatedClient(MockMvc mockMvc, ObjectMapper objectMapper, String authToken) {
        this.mockMvc = mockMvc;
        this.objectMapper = objectMapper;
        this.authToken = authToken;
    }

    /**
     * Creates a random user in the database, logs in, and returns an
     * authenticated client ready for use.
     */
    public static AuthenticatedClient create(
            MockMvc mockMvc,
            ObjectMapper objectMapper,
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            TransactionTemplate tx
    ) throws Exception {
        return create(mockMvc, objectMapper, userRepository, passwordEncoder, tx, "ADMIN");
    }

    /** Creates and authenticates a random user with the requested application role. */
    public static AuthenticatedClient create(
            MockMvc mockMvc,
            ObjectMapper objectMapper,
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            TransactionTemplate tx,
            String role
    ) throws Exception {
        String username = "user-" + UUID.randomUUID().toString().substring(0, 8);
        String password = "pw-" + UUID.randomUUID().toString().substring(0, 12);

        tx.executeWithoutResult(status -> {
            User user = new User();
            user.setUsername(username);
            user.setPassword(passwordEncoder.encode(password));
            user.setRole(role);
            userRepository.save(user);
        });

        LoginRequest loginRequest = new LoginRequest();
        loginRequest.setUsername(username);
        loginRequest.setPassword(password);

        String responseBody = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andReturn()
                .getResponse()
                .getContentAsString();

        String token = objectMapper.readValue(responseBody, LoginResponse.class).getToken();
        return new AuthenticatedClient(mockMvc, objectMapper, token);
    }

    /** Performs a request with Authorization header and JSON content type automatically added. */
    public ResultActions perform(MockHttpServletRequestBuilder requestBuilder) throws Exception {
        requestBuilder.header("Authorization", "Bearer " + authToken);
        requestBuilder.contentType(MediaType.APPLICATION_JSON);
        return mockMvc.perform(requestBuilder);
    }

    /** Reads the response body and deserializes it into the given type. */
    public <T> T readValue(ResultActions result, Class<T> type) throws Exception {
        String body = result.andReturn().getResponse().getContentAsString();
        return objectMapper.readValue(body, type);
    }

    public MockMvc rawMockMvc() {
        return mockMvc;
    }

    public ObjectMapper objectMapper() {
        return objectMapper;
    }

    public String authToken() {
        return authToken;
    }
}
