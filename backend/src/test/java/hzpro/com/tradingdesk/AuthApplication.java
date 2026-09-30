package hzpro.com.tradingdesk;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.http.client.HttpClientAutoConfiguration;
import org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration;
import org.springframework.test.context.ActiveProfiles;

@SpringBootApplication(exclude = {
    HttpClientAutoConfiguration.class,
    RestClientAutoConfiguration.class
})
@ActiveProfiles("test")
public class AuthApplication {
    public static void main(String[] args) {
        SpringApplication.run(AuthApplication.class, args);
    }
}
