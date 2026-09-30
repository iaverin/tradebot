package hzpro.com.tradingdesk.marketsfetcher.temporal.config;

import hzpro.com.tradingdesk.marketsfetcher.temporal.activity.FetchActivityImpl;
import hzpro.com.tradingdesk.marketsfetcher.temporal.workflow.MarketsRefreshWorkflow;
import hzpro.com.tradingdesk.marketsfetcher.temporal.workflow.MarketsRefreshWorkflowImpl;
import hzpro.com.tradingdesk.marketsfetcher.temporal.workflow.FetchWorkflowImpl;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.schedules.ScheduleClient;
import io.temporal.client.schedules.ScheduleClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
@ConditionalOnProperty(name = "temporal.enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
public class TemporalConfig {

    private static final String TASK_QUEUE = "prediction-markets-task-queue";

    @Value("${temporal.target-endpoint}")
    private String targetEndpoint;

    @Value("${temporal.namespace}")
    private String namespace;

    @Bean
    public WorkflowServiceStubs workflowServiceStubs() {
        return WorkflowServiceStubs.newServiceStubs(
                WorkflowServiceStubsOptions.newBuilder()
                        .setTarget(targetEndpoint)
                        .build()
        );
    }

    @Bean
    public WorkflowClient workflowClient(WorkflowServiceStubs stubs) {
        return WorkflowClient.newInstance(stubs,
                WorkflowClientOptions.newBuilder()
                        .setNamespace(namespace)
                        .build()
        );
    }

    @Bean
    public ScheduleClient scheduleClient(WorkflowServiceStubs stubs) {
        return ScheduleClient.newInstance(stubs,
                ScheduleClientOptions.newBuilder()
                        .setNamespace(namespace)
                        .build()
        );
    }

    @Bean
    public WorkerFactory workerFactory(WorkflowClient workflowClient) {
        return WorkerFactory.newInstance(workflowClient);
    }

    @Bean
    public Worker worker(
            WorkerFactory workerFactory,
            FetchActivityImpl fetchActivity,
            @Value("${fetcher.similar-markets.workflow-execution-timeout}")
            Duration findSimilarMarketWorkflowExecutionTimeout) {
        Worker worker = workerFactory.newWorker(TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(FetchWorkflowImpl.class);
        worker.registerWorkflowImplementationFactory(
                MarketsRefreshWorkflow.class,
                () -> new MarketsRefreshWorkflowImpl(findSimilarMarketWorkflowExecutionTimeout)
        );
        worker.registerActivitiesImplementations(fetchActivity);
        workerFactory.start();
        log.info("Temporal worker started on task queue '{}'", TASK_QUEUE);
        return worker;
    }
}
