package io.github.gmcnicol.kernel.internal;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.context.SmartLifecycle;

abstract class FixedDelayWorker implements SmartLifecycle {

    private final IntentWorkerProperties policy;
    private volatile boolean running;
    private ScheduledExecutorService scheduler;

    FixedDelayWorker(IntentWorkerProperties policy) {
        this.policy = policy;
    }

    @Override
    public final void start() {
        if (!policy.enabled() || running) return;
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable ->
                Thread.ofPlatform().daemon().name(threadName()).unstarted(runnable));
        long delay = policy.pollingInterval().toMillis();
        scheduler.scheduleWithFixedDelay(this::poll, delay, delay, TimeUnit.MILLISECONDS);
        running = true;
    }

    abstract String threadName();

    abstract void poll();

    @Override
    public final void stop() {
        if (scheduler != null) scheduler.shutdown();
        running = false;
    }

    @Override
    public final boolean isRunning() {
        return running;
    }
}
