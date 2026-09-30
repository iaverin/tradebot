package hzpro.com.tradingdesk.marketsfetcher.temporal.workflow;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface FetchWorkflow {
    @WorkflowMethod
    void fetchMarketsFromProvider(String dataSource); // "KALSHI" or "POLYMARKET"
}
