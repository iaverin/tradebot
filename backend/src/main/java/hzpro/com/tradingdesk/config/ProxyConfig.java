package hzpro.com.tradingdesk.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Getter
@Setter
@Component
public class ProxyConfig {

    @Value("${proxy.host:#{null}}")
    private String host;

    @Value("${proxy.port:#{null}}")
    private Integer port;

    @Value("${proxy.username:#{null}}")
    private String username;

    @Value("${proxy.password:#{null}}")
    private String password;

    @Value("${proxy.use.ssl:false}")
    private boolean useSsl;

    public boolean isConfigured() {
        return StringUtils.hasText(host) && port != null;
    }

    public boolean hasAuth() {
        return StringUtils.hasText(username) && StringUtils.hasText(password);
    }

    public String toProxyUri() {
        return "http://" + host + ":" + port;
    }
}
