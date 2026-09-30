package hzpro.com.tradingdesk.marketsfetcher.temporal.workflow;

import hzpro.com.tradingdesk.marketsfetcher.temporal.activity.FetchActivity;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;

import java.time.Duration;

public class FetchWorkflowImpl implements FetchWorkflow {

    private final FetchActivity fetchActivity = Workflow.newActivityStub(
            FetchActivity.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofHours(4))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setMaximumAttempts(1)
                            .build())
                    .build()
    );

    @Override
    public void fetchMarketsFromProvider(String providerSlug) {
        switch (providerSlug) {
            case "KALSHI" -> fetchActivity.fetchKalshi();
            case "POLYMARKET" -> fetchActivity.fetchPolymarket();
            case "OPINION" -> fetchActivity.fetchOpinion();
            default -> throw new IllegalArgumentException("Unknown dataSource: " + providerSlug);
        }
    }
}
