package hzpro.com.tradingdesk.arbitrage.util;

import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

@Slf4j
public class WebSocketReconnectHelper {

    private static final int MAX_RETRY_ATTEMPTS = 100; // Effectively infinite
    private static final Duration INITIAL_DELAY = Duration.ofSeconds(1);
    private static final Duration MAX_DELAY = Duration.ofSeconds(60);

    private final ScheduledExecutorService scheduler;
    private final String clientName;
    private final Supplier<Boolean> connectionAttempt;
    private final BooleanSupplier isConnected;

    private int attemptCount;
    private ScheduledFuture<?> scheduledReconnect;

    public WebSocketReconnectHelper(
            String clientName,
            Supplier<Boolean> connectionAttempt,
            BooleanSupplier isConnected) {
        this.clientName = clientName;
        this.connectionAttempt = connectionAttempt;
        this.isConnected = isConnected;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, clientName + "-reconnect");
            t.setDaemon(true);
            return t;
        });
        this.attemptCount = 0;
    }

    public void startMonitoring() {
        scheduleReconnect();
        scheduler.scheduleAtFixedRate(() -> {
            if (!isConnected.getAsBoolean() && (scheduledReconnect == null || scheduledReconnect.isDone())) {
                attemptReconnect();
            }
        }, 10, 10, TimeUnit.SECONDS);
    }

    private void scheduleReconnect() {
        if (scheduledReconnect != null && !scheduledReconnect.isDone()) {
            scheduledReconnect.cancel(false);
        }

        Duration delay = calculateBackoff();
        log.info("[{}] Scheduling reconnect attempt #{} in {} seconds",
                clientName, attemptCount + 1, delay.getSeconds());

        scheduledReconnect = scheduler.schedule(this::attemptReconnect,
                delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    private Duration calculateBackoff() {
        if (attemptCount == 0) {
            return Duration.ZERO;
        }
        long seconds = Math.min(
                INITIAL_DELAY.getSeconds() * (1L << Math.min(attemptCount - 1, 6)),
                MAX_DELAY.getSeconds()
        );
        return Duration.ofSeconds(seconds);
    }

    private void attemptReconnect() {
        if (isConnected.getAsBoolean()) {
            log.debug("[{}] Already connected, skipping reconnect", clientName);
            attemptCount = 0;
            return;
        }

        log.info("[{}] Attempting to reconnect (attempt #{})",
                clientName, attemptCount + 1);

        try {
            boolean success = connectionAttempt.get();
            if (success) {
                log.info("[{}] Reconnection successful!", clientName);
                attemptCount = 0;
            } else {
                log.warn("[{}] Reconnection attempt failed", clientName);
                attemptCount++;
                scheduleReconnect();
            }
        } catch (Exception e) {
            log.error("[{}] Reconnection attempt threw exception", clientName, e);
            attemptCount++;
            scheduleReconnect();
        }
    }

    public void forceReconnect() {
        attemptCount = 0;
        if (scheduledReconnect != null) {
            scheduledReconnect.cancel(false);
        }
        scheduler.execute(this::attemptReconnect);
    }

    public void onDisconnect() {
        if (!isConnected.getAsBoolean()) {
            attemptCount = 0;
            scheduleReconnect();
        }
    }

    public void shutdown() {
        if (scheduledReconnect != null) {
            scheduledReconnect.cancel(false);
        }
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}