package hzpro.com.tradingdesk.marketsfetcher.service;

import hzpro.com.tradingdesk.marketsfetcher.temporal.workflow.MarketsRefreshWorkflow;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class MarketsRefreshLauncher {

    public static final String TASK_QUEUE = "prediction-markets-task-queue";
    public static final String WORKFLOW_ID = "markets-refresh-workflow";

    private final WorkflowClient workflowClient;

    public String start() {
        log.info("Starting MarketsRefreshWorkflow with id '{}'", WORKFLOW_ID);
        MarketsRefreshWorkflow workflow = workflowClient.newWorkflowStub(
                MarketsRefreshWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setWorkflowId(WORKFLOW_ID)
                        .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE)
                        .setTaskQueue(TASK_QUEUE)
                        .build());
        WorkflowClient.start(workflow::run);
        return WORKFLOW_ID;
    }
}
