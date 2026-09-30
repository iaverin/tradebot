package hzpro.com.tradingdesk.arbitrage.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class WebSocketMessageExecutorTest {

    @Test
    void usesBoundedQueueAndCallerBackpressure() {
        ExecutorService executor = WebSocketMessageExecutor.create("test");
        try {
            assertThat(executor).isInstanceOf(ThreadPoolExecutor.class);
            ThreadPoolExecutor threadPool = (ThreadPoolExecutor) executor;
            assertThat(threadPool.getQueue()).isInstanceOf(ArrayBlockingQueue.class);
            assertThat(threadPool.getQueue().remainingCapacity())
                    .isEqualTo(WebSocketMessageExecutor.QUEUE_CAPACITY);
            assertThat(threadPool.getRejectedExecutionHandler())
                    .isInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class);
        } finally {
            WebSocketMessageExecutor.shutdown(executor, "test");
        }
    }

    @Test
    void shutdownInterruptsWorkersAndTerminatesExecutor() throws Exception {
        ExecutorService executor = WebSocketMessageExecutor.create("test");
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();

        executor.submit(() -> {
            started.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException e) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
            }
        });

        assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
        WebSocketMessageExecutor.shutdown(executor, "test");

        assertThat(interrupted).isTrue();
        assertThat(executor.isTerminated()).isTrue();
    }

    @Test
    void disconnectShutsDownClientExecutors() throws Exception {
        PolymarketWebSocketClient polymarketClient = new PolymarketWebSocketClient(
                List.of(), (assetId, snapshot) -> { }, null);
        KalshiWebSocketClient kalshiClient = new KalshiWebSocketClient(
                List.of(), (ticker, snapshot) -> { }, null, null, null);
        ExecutorService polymarketExecutor = (ExecutorService) ReflectionTestUtils
                .getField(polymarketClient, "workerPool");
        ExecutorService kalshiExecutor = (ExecutorService) ReflectionTestUtils
                .getField(kalshiClient, "workerPool");

        polymarketClient.disconnect();
        kalshiClient.disconnect();

        assertThat(polymarketExecutor).isNotNull();
        assertThat(polymarketExecutor.isTerminated()).isTrue();
        assertThat(kalshiExecutor).isNotNull();
        assertThat(kalshiExecutor.isTerminated()).isTrue();
    }
}
