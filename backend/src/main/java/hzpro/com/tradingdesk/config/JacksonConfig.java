package hzpro.com.tradingdesk.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JacksonConfig {

    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();

        // Automatically discover and register modules including JavaTimeModule
        mapper.findAndRegisterModules();

        // Configure datetime handling
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        // Allow unknown properties (matches @JsonIgnoreProperties(ignoreUnknown = true))
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

        return mapper;
    }
}
