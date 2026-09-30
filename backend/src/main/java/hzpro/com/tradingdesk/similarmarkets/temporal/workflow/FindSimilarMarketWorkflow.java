package hzpro.com.tradingdesk.similarmarkets.temporal.workflow;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface FindSimilarMarketWorkflow {

    @WorkflowMethod(name = "find_similar_market")
    void run();
}
