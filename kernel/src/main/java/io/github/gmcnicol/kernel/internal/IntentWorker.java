package io.github.gmcnicol.kernel.internal;

import java.time.Clock;

final class IntentWorker extends FixedDelayWorker {

    private static final System.Logger LOG = System.getLogger(IntentWorker.class.getName());
    private final IntentExecutionService execution;
    private final Clock clock;

    IntentWorker(IntentExecutionService execution, IntentWorkerProperties policy, Clock clock) {
        super(policy);
        this.execution = execution;
        this.clock = clock;
    }

    @Override
    String threadName() {
        return "kernel-intent-worker";
    }

    @Override
    void poll() {
        try {
            execution.processDue(clock.instant());
        } catch (RuntimeException exception) {
            LOG.log(System.Logger.Level.WARNING, "intent_worker_poll_failed type={0}",
                    exception.getClass().getSimpleName());
        }
    }

}
