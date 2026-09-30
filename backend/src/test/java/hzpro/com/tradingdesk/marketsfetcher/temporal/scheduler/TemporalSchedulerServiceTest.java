package hzpro.com.tradingdesk.marketsfetcher.temporal.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import hzpro.com.tradingdesk.marketsfetcher.service.MarketsRefreshLauncher;
import io.temporal.client.schedules.Schedule;
import io.temporal.client.schedules.ScheduleActionStartWorkflow;
import io.temporal.client.schedules.ScheduleClient;
import io.temporal.client.schedules.ScheduleOptions;

@ExtendWith(MockitoExtension.class)
class TemporalSchedulerServiceTest {

    @Mock
    private ScheduleClient scheduleClient;

    @Test
    void createsScheduleWithoutUnsupportedWorkflowIdReusePolicy() {
        when(scheduleClient.listSchedules()).thenReturn(Stream.empty());
        ArgumentCaptor<Schedule> scheduleCaptor = ArgumentCaptor.forClass(Schedule.class);

        TemporalSchedulerService service = new TemporalSchedulerService(scheduleClient, "0 0 * * *");
        service.initSchedules();

        verify(scheduleClient).createSchedule(
                eq("markets-refresh-daily-schedule"),
                scheduleCaptor.capture(),
                any(ScheduleOptions.class));

        ScheduleActionStartWorkflow action = (ScheduleActionStartWorkflow) scheduleCaptor.getValue().getAction();
        assertThat(action.getOptions().getWorkflowId()).isEqualTo(MarketsRefreshLauncher.WORKFLOW_ID);
        assertThat(action.getOptions().getTaskQueue()).isEqualTo(MarketsRefreshLauncher.TASK_QUEUE);
        assertThat(action.getOptions().getWorkflowIdReusePolicy()).isNull();
    }
}
