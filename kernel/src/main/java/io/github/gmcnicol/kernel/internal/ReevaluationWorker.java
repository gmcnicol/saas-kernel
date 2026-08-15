package io.github.gmcnicol.kernel.internal;

import java.time.Clock;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.context.SmartLifecycle;

final class ReevaluationWorker implements SmartLifecycle {

    private static final System.Logger LOG = System.getLogger(ReevaluationWorker.class.getName());
    private final DefaultKernel kernel;
    private final IntentWorkerProperties policy;
    private final Clock clock;
    private volatile boolean running;
    private ScheduledExecutorService scheduler;

    ReevaluationWorker(DefaultKernel kernel, IntentWorkerProperties policy, Clock clock) {
        this.kernel = kernel;
        this.policy = policy;
        this.clock = clock;
    }

    @Override
    public void start() {
        if (!policy.enabled() || running) return;
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable ->
                Thread.ofPlatform().daemon().name("kernel-reevaluation-worker").unstarted(runnable));
        long delay = policy.pollingInterval().toMillis();
        scheduler.scheduleWithFixedDelay(this::poll, delay, delay, TimeUnit.MILLISECONDS);
        running = true;
    }

    private void poll() {
        try {
            for (int count = 0; count < policy.claimBatchSize(); count++) {
                if (!kernel.processNextReevaluationWork(clock.instant())) break;
            }
        } catch (RuntimeException exception) {
            LOG.log(System.Logger.Level.WARNING, "reevaluation_worker_poll_failed type={0}",
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
