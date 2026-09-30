package hzpro.com.tradingdesk.marketsfetcher.temporal.workflow;

import hzpro.com.tradingdesk.marketsfetcher.temporal.activity.FetchActivity;
import hzpro.com.tradingdesk.similarmarkets.temporal.workflow.FindSimilarMarketWorkflow;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Async;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;

@Slf4j
public class MarketsRefreshWorkflowImpl implements MarketsRefreshWorkflow {

    private static final String SIMILAR_MARKETS_TASK_QUEUE = "similar-markets";

    private final FetchActivity fetchActivity = Workflow.newActivityStub(
            FetchActivity.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofHours(4))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setMaximumAttempts(1)
                            .build())
                    .build()
    );

    private final FetchActivity monitorLifecycleActivity = Workflow.newActivityStub(
            FetchActivity.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofMinutes(5))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setMaximumAttempts(2)
                            .setInitialInterval(Duration.ofSeconds(5))
                            .build())
                    .build()
    );

    private final FindSimilarMarketWorkflow findSimilarMarketWorkflow;

    public MarketsRefreshWorkflowImpl(Duration findSimilarMarketWorkflowExecutionTimeout) {
        findSimilarMarketWorkflow = Workflow.newChildWorkflowStub(
                FindSimilarMarketWorkflow.class,
                ChildWorkflowOptions.newBuilder()
                        .setTaskQueue(SIMILAR_MARKETS_TASK_QUEUE)
                        .setWorkflowExecutionTimeout(findSimilarMarketWorkflowExecutionTimeout)
                        .build()
        );
    }

    @Override
    public void run() {
        fetchActivity.clearFetchingData();

        Promise<Void> kalshiPromise = Async.procedure(fetchActivity::fetchKalshi);
        Promise<Void> polymarketPromise = Async.procedure(fetchActivity::fetchPolymarket);
        Promise<Void> opinionPromise = Async.procedure(fetchActivity::fetchOpinion);

        Promise.allOf(kalshiPromise, polymarketPromise, opinionPromise).get();

        try {
                findSimilarMarketWorkflow.run();
        } catch (Exception e) {
                log.error("Similirar markets calc error {}", e.getMessage());
        }

        try {
            monitorLifecycleActivity.stopArbitrageMonitor();
            fetchActivity.publishMarketsSnapshot();
        } finally {
            monitorLifecycleActivity.startArbitrageMonitor();
        }
    }
}
