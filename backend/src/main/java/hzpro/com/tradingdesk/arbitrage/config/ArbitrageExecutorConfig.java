package hzpro.com.tradingdesk.arbitrage.config;

import java.util.concurrent.Executor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableAsync
public class ArbitrageExecutorConfig {
    @Bean(name="arbitrageOrdersExecutor")
    public Executor arbitrageOrdersExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(20);

        executor.setWaitForTasksToCompleteOnShutdown(true);

        executor.setAwaitTerminationSeconds(20);

        executor.initialize();
        return executor;
    }

}
