package hzpro.com.tradingdesk.arbitrage.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class OrderStatusSchedulerConfig {

    public static final String LOW_PRIORITY_SCHEDULER = "lowPriorityOrderStatusScheduler";

    @Bean(name = LOW_PRIORITY_SCHEDULER)
    public ThreadPoolTaskScheduler lowPriorityOrderStatusScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("order-status-checker-");
        scheduler.setThreadPriority(Thread.MIN_PRIORITY);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(20);
        return scheduler;
    }
}
