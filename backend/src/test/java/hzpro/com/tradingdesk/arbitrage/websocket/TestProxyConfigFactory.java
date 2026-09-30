package hzpro.com.tradingdesk.arbitrage.websocket;

import hzpro.com.tradingdesk.config.ProxyConfig;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Profile;
import org.springframework.test.context.ActiveProfiles;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.Map;

@Slf4j
@TestConfiguration
@ActiveProfiles("test")
class TestProxyConfigFactory {

    @Autowired
    private static ProxyConfig config;


    static ProxyConfig create() {
        return config;
    }

}
