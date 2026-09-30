package hzpro.com.tradingdesk.marketsfetcher.temporal.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import hzpro.com.tradingdesk.marketsfetcher.temporal.activity.FetchActivity;
import hzpro.com.tradingdesk.similarmarkets.temporal.workflow.FindSimilarMarketWorkflow;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;

class MarketsRefreshWorkflowTest {

    private static final String MAIN_QUEUE = "markets-refresh-test";
    private static final String SIMILAR_MARKETS_QUEUE = "similar-markets";

    private TestWorkflowEnvironment environment;
    private RecordingFetchActivity activities;

    @BeforeEach
    void setUp() {
        environment = TestWorkflowEnvironment.newInstance();
        activities = new RecordingFetchActivity();

        Worker mainWorker = environment.newWorker(MAIN_QUEUE);
        mainWorker.registerWorkflowImplementationFactory(
                MarketsRefreshWorkflow.class,
                () -> new MarketsRefreshWorkflowImpl(Duration.ofMinutes(5)));
        mainWorker.registerActivitiesImplementations(activities);

        Worker similarMarketsWorker = environment.newWorker(SIMILAR_MARKETS_QUEUE);
        similarMarketsWorker.registerWorkflowImplementationTypes(RecordingSimilarMarketsWorkflow.class);

        environment.start();
    }

    @AfterEach
    void tearDown() {
        environment.close();
    }

    @Test
    void publishesOnlyAfterFetchingAndStagingCalculation() {
        newWorkflow().run();

        assertThat(activities.events)
                .startsWith("clear")
                .containsSubsequence("fetch-kalshi", "similar-markets")
                .containsSubsequence("fetch-polymarket", "similar-markets")
                .containsSubsequence("fetch-opinion", "similar-markets")
                .endsWith("similar-markets", "stop-monitor", "publish-snapshot", "start-monitor");
    }

    @Test
    void leavesMonitorRunningWhenFetchFails() {
        activities.failFetch = true;

        assertThatThrownBy(() -> newWorkflow().run()).isInstanceOf(WorkflowFailedException.class);

        assertThat(activities.events)
                .contains("clear", "fetch-kalshi", "fetch-polymarket", "fetch-opinion")
                .doesNotContain("similar-markets", "stop-monitor", "publish-snapshot", "start-monitor");
    }

    @Test
    void startsMonitorWhenPublishingFails() {
        activities.failPublish = true;

        assertThatThrownBy(() -> newWorkflow().run()).isInstanceOf(WorkflowFailedException.class);

        assertThat(activities.events).endsWith("stop-monitor", "publish-snapshot", "start-monitor");
    }

    @Test
    void leavesMonitorRunningWhenStagingCalculationFails() {
        activities.failSimilarMarkets = true;

        newWorkflow().run();

        assertThat(activities.events)
                .contains("similar-markets", "stop-monitor", "publish-snapshot", "start-monitor");
    }

    private MarketsRefreshWorkflow newWorkflow() {
        return environment.getWorkflowClient().newWorkflowStub(
                MarketsRefreshWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setWorkflowId("markets-refresh-test-" + environment.currentTimeMillis())
                        .setTaskQueue(MAIN_QUEUE)
                        .build());
    }

    public static class RecordingSimilarMarketsWorkflow implements FindSimilarMarketWorkflow {
        @Override
        public void run() {
            RecordingFetchActivity.current.events.add("similar-markets");
            if (RecordingFetchActivity.current.failSimilarMarkets) {
                throw ApplicationFailure.newNonRetryableFailure("similar markets failed", "test");
            }
        }
    }

    private static class RecordingFetchActivity implements FetchActivity {
        private static RecordingFetchActivity current;

        private final List<String> events = new CopyOnWriteArrayList<>();
        private volatile boolean failFetch;
        private volatile boolean failPublish;
        private volatile boolean failSimilarMarkets;

        private RecordingFetchActivity() {
            current = this;
        }

        @Override
        public void clearFetchingData() {
            events.add("clear");
        }

        @Override
        public void fetchKalshi() {
            events.add("fetch-kalshi");
            failFetchIfRequested();
        }

        @Override
        public void fetchPolymarket() {
            events.add("fetch-polymarket");
            failFetchIfRequested();
        }

        @Override
        public void fetchOpinion() {
            events.add("fetch-opinion");
            failFetchIfRequested();
        }

        @Override
        public void publishMarketsSnapshot() {
            events.add("publish-snapshot");
            if (failPublish) {
                throw new IllegalStateException("publish failed");
            }
        }

        @Override
        public void stopArbitrageMonitor() {
            events.add("stop-monitor");
        }

        @Override
        public void startArbitrageMonitor() {
            events.add("start-monitor");
        }

        private void failFetchIfRequested() {
            if (failFetch) {
                throw new IllegalStateException("fetch failed");
            }
        }
    }
}
