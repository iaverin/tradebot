package hzpro.com.tradingdesk.arbitrage.websocket;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import lombok.extern.slf4j.Slf4j;

@Slf4j
final class WebSocketMessageExecutor {

    static final int QUEUE_CAPACITY = 512;
    private static final int SHUTDOWN_TIMEOUT_SECONDS = 5;

    private WebSocketMessageExecutor() {
    }

    static ExecutorService create(String clientName) {
        int workerCount = Math.max(2, Runtime.getRuntime().availableProcessors());
        AtomicInteger threadCounter = new AtomicInteger();
        ThreadFactory threadFactory = task -> {
            Thread thread = new Thread(task,
                    clientName + "-ms-" + threadCounter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };

        return new ThreadPoolExecutor(
                workerCount,
                workerCount,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                threadFactory,
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    static void shutdown(ExecutorService executor, String clientName) {
        List<Runnable> discardedMessages = executor.shutdownNow();
        if (!discardedMessages.isEmpty()) {
            log.debug("Discarded {} queued {} WebSocket messages during shutdown",
                    discardedMessages.size(), clientName);
        }

        try {
            if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                log.warn("{} WebSocket message workers did not stop within {} seconds",
                        clientName, SHUTDOWN_TIMEOUT_SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while stopping {} WebSocket message workers", clientName);
        }
    }
}
