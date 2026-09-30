package hzpro.com.tradingdesk.config;

import io.temporal.client.WorkflowClient;
import io.temporal.client.schedules.ScheduleClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.WorkerFactory;

import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "temporal.enabled", havingValue = "false")
public class TestTemporalConfig {

    @Bean
    public WorkerFactory workerFactory(WorkflowClient workflowClient) {
            return Mockito.mock(WorkerFactory.class);
        }

    @Bean
    public WorkflowClient workflowClient() {
        return Mockito.mock(WorkflowClient.class);
    }

    @Bean
    public ScheduleClient scheduleClient() {
        return Mockito.mock(ScheduleClient.class);
    }
}
