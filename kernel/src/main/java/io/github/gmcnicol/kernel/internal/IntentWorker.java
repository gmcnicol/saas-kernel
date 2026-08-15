package io.github.gmcnicol.kernel.internal;

import java.time.Clock;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.context.SmartLifecycle;

final class IntentWorker implements SmartLifecycle {

    private static final System.Logger LOG = System.getLogger(IntentWorker.class.getName());
    private final IntentExecutionService execution;
    private final IntentWorkerProperties policy;
    private final Clock clock;
    private volatile boolean running;
    private ScheduledExecutorService scheduler;

    IntentWorker(IntentExecutionService execution, IntentWorkerProperties policy, Clock clock) {
        this.execution = execution;
        this.policy = policy;
        this.clock = clock;
    }

    @Override
    public void start() {
        if (!policy.enabled() || running) return;
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable ->
                Thread.ofPlatform().daemon().name("kernel-intent-worker").unstarted(runnable));
        long delay = policy.pollingInterval().toMillis();
        scheduler.scheduleWithFixedDelay(this::poll, delay, delay, TimeUnit.MILLISECONDS);
        running = true;
    }

    private void poll() {
        try {
            execution.processDue(clock.instant());
        } catch (RuntimeException exception) {
            LOG.log(System.Logger.Level.WARNING, "intent_worker_poll_failed type={0}",
                    exception.getClass().getSimpleName());
        }
    }

    @Override
    public void stop() {
        if (scheduler != null) scheduler.shutdown();
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
