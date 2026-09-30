package hzpro.com.tradingdesk.marketsfetcher.service;

import hzpro.com.tradingdesk.marketsfetcher.temporal.workflow.MarketsRefreshWorkflow;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.workflow.Functions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MarketsRefreshLauncherTest {

    @Mock
    private WorkflowClient workflowClient;

    @Mock
    private MarketsRefreshWorkflow workflow;

    @Test
    void usesOneSharedWorkflowIdToRejectConcurrentRefreshes() {
        ArgumentCaptor<WorkflowOptions> optionsCaptor = ArgumentCaptor.forClass(WorkflowOptions.class);
        when(workflowClient.newWorkflowStub(eq(MarketsRefreshWorkflow.class), optionsCaptor.capture()))
                .thenReturn(workflow);

        try (MockedStatic<WorkflowClient> staticClient = mockStatic(WorkflowClient.class)) {
            staticClient.when(() -> WorkflowClient.start(any(Functions.Proc.class)))
                    .thenReturn(mock(WorkflowExecution.class));

            MarketsRefreshLauncher launcher = new MarketsRefreshLauncher(workflowClient);
            assertThat(launcher.start()).isEqualTo(MarketsRefreshLauncher.WORKFLOW_ID);
        }

        WorkflowOptions options = optionsCaptor.getValue();
        assertThat(options.getWorkflowId()).isEqualTo(MarketsRefreshLauncher.WORKFLOW_ID);
        assertThat(options.getTaskQueue()).isEqualTo(MarketsRefreshLauncher.TASK_QUEUE);
        assertThat(options.getWorkflowIdReusePolicy())
                .isEqualTo(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE);
        verify(workflowClient).newWorkflowStub(eq(MarketsRefreshWorkflow.class), any(WorkflowOptions.class));
    }
}
