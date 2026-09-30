package hzpro.com.tradingdesk.client.config;

import hzpro.com.tradingdesk.config.ProxyConfig;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.io.HttpClientConnectionManager;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactory;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.ssl.SSLContextBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.net.ssl.SSLContext;

import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;

@Configuration
@Slf4j
public class HttpClientWithHttpsProxyConfig {

    private final ProxyConfig proxyConfig;

    public HttpClientWithHttpsProxyConfig(ProxyConfig proxyConfig) {
        this.proxyConfig = proxyConfig;
    }

    @Bean
    public CloseableHttpClient httpClient() throws NoSuchAlgorithmException, KeyStoreException, KeyManagementException {
        SSLContext sslContext = SSLContextBuilder.create()
                .loadTrustMaterial((chain, authType) -> true)
                .build();

        SSLConnectionSocketFactory sslSocketFactory = new SSLConnectionSocketFactory(
                sslContext,
                (hostname, session) -> true
        );

        HttpClientConnectionManager connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setSSLSocketFactory(sslSocketFactory)
                .setMaxConnTotal(100)
                .setMaxConnPerRoute(20)
                .build();

        RequestConfig requestConfig = RequestConfig.custom()
                .setResponseTimeout(Timeout.ofSeconds(30))
                .build();

        var httpClientBuilder = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig);

        if (proxyConfig.isConfigured()) {
            String scheme = proxyConfig.isUseSsl() ? "https" : "http";
            HttpHost proxy = new HttpHost(scheme, proxyConfig.getHost(), proxyConfig.getPort());

            log.info("Configuring {} proxy: {}://{}:{}",
                    proxyConfig.isUseSsl() ? "HTTPS" : "HTTP", scheme, proxyConfig.getHost(), proxyConfig.getPort());

            httpClientBuilder.setProxy(proxy);

            if (proxyConfig.hasAuth()) {
                log.info("Proxy authentication enabled for user: {}", proxyConfig.getUsername());

                BasicCredentialsProvider credentialsProvider = new BasicCredentialsProvider();
                credentialsProvider.setCredentials(
                        new AuthScope(proxy),
                        new UsernamePasswordCredentials(proxyConfig.getUsername(), proxyConfig.getPassword().toCharArray())
                );

                httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider);
            }
        } else {
            log.info("No proxy configuration found, using direct connection");
        }

        return httpClientBuilder.build();
    }
}
