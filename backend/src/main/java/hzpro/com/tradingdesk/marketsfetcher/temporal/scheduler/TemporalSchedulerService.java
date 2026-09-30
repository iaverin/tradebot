package hzpro.com.tradingdesk.marketsfetcher.temporal.scheduler;

import java.time.Instant;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import hzpro.com.tradingdesk.marketsfetcher.temporal.workflow.MarketsRefreshWorkflow;
import hzpro.com.tradingdesk.marketsfetcher.service.MarketsRefreshLauncher;
import io.temporal.api.enums.v1.ScheduleOverlapPolicy;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.schedules.Schedule;
import io.temporal.client.schedules.ScheduleActionStartWorkflow;
import io.temporal.client.schedules.ScheduleAlreadyRunningException;
import io.temporal.client.schedules.ScheduleClient;
import io.temporal.client.schedules.ScheduleDescription;
import io.temporal.client.schedules.ScheduleHandle;
import io.temporal.client.schedules.ScheduleOptions;
import io.temporal.client.schedules.SchedulePolicy;
import io.temporal.client.schedules.ScheduleSpec;
import io.temporal.client.schedules.ScheduleUpdate;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class TemporalSchedulerService {

    private static final String MARKETS_REFRESH_DAILY_SCHEDULE_ID = "markets-refresh-daily-schedule";

    private final ScheduleClient scheduleClient;
    private final String cronString;

    public TemporalSchedulerService(
            ScheduleClient scheduleClient,
            @Value("${fetcher.cron.stringUtc:0 0 * * *}") String cronString) {
        this.scheduleClient = scheduleClient;
        this.cronString = cronString;
        log.info("Fetcher be scheduled to fetcher.cron.stringUtc {}", this.cronString);
    }

    @PostConstruct
    public void initSchedules() {
        createMarketsRefreshDailyScheduleIfAbsent();
    }

    private void createMarketsRefreshDailyScheduleIfAbsent() {
        try {
            var existing = scheduleClient.listSchedules()
                    .filter(s -> s.getScheduleId().equals(MARKETS_REFRESH_DAILY_SCHEDULE_ID))
                    .findFirst();

            if (existing.isPresent()) {
                ScheduleHandle handle = scheduleClient.getHandle(MARKETS_REFRESH_DAILY_SCHEDULE_ID);
                handle.update(input -> new ScheduleUpdate(
                        Schedule.newBuilder(buildSchedule())
                                .setState(input.getDescription().getSchedule().getState())
                                .build()));
                log.info("Updated existing Temporal schedule '{}' with cron '{}'",
                        MARKETS_REFRESH_DAILY_SCHEDULE_ID, cronString);
                return;
            }
        } catch (Exception e) {
            log.warn("Could not list schedules, will attempt to create '{}'", MARKETS_REFRESH_DAILY_SCHEDULE_ID, e);
        }

        try {
            scheduleClient.createSchedule(
                    MARKETS_REFRESH_DAILY_SCHEDULE_ID,
                    buildSchedule(),
                    ScheduleOptions.newBuilder().build());
            log.info("Created Temporal schedule '{}' with cron '{}'", MARKETS_REFRESH_DAILY_SCHEDULE_ID, this.cronString);
        } catch (ScheduleAlreadyRunningException e) {
            log.info("Temporal schedule '{}' already exists (race condition), skipping", MARKETS_REFRESH_DAILY_SCHEDULE_ID);
        }
    }

    private Schedule buildSchedule() {
        var action = ScheduleActionStartWorkflow.newBuilder()
                .setWorkflowType(MarketsRefreshWorkflow.class)
                .setOptions(WorkflowOptions.newBuilder()
                        .setWorkflowId(MarketsRefreshLauncher.WORKFLOW_ID)
                        .setTaskQueue(MarketsRefreshLauncher.TASK_QUEUE)
                        .build())
                .build();

        var spec = ScheduleSpec.newBuilder()
                .setCronExpressions(List.of(cronString))
                .build();

        var policy = SchedulePolicy.newBuilder()
                .setOverlap(ScheduleOverlapPolicy.SCHEDULE_OVERLAP_POLICY_SKIP)
                .build();

        return Schedule.newBuilder()
                .setAction(action)
                .setSpec(spec)
                .setPolicy(policy)
                .build();
    }

    public Instant getNextExecutionTime() {
        try {
            ScheduleHandle handle = scheduleClient.getHandle(MARKETS_REFRESH_DAILY_SCHEDULE_ID);
            ScheduleDescription description = handle.describe();
            List<Instant> nextTimes = description.getInfo().getNextActionTimes();
            if (nextTimes != null && !nextTimes.isEmpty()) {
                return nextTimes.get(0);
            }
        } catch (Exception e) {
            log.warn("Could not retrieve next execution time from Temporal schedule", e);
        }
        return null;
    }
}
