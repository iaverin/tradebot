package hzpro.com.tradingdesk.marketsfetcher.temporal.workflow;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface MarketsRefreshWorkflow {
    @WorkflowMethod
    void run();
}
