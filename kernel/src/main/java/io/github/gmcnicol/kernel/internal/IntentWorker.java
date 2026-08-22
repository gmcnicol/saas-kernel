package io.github.gmcnicol.kernel.internal;

import java.time.Clock;

final class IntentWorker extends FixedDelayWorker {

    private static final System.Logger LOG = System.getLogger(IntentWorker.class.getName());
    private final DefaultKernel kernel;
    private final Clock clock;

    IntentWorker(DefaultKernel kernel, IntentWorkerProperties policy, Clock clock) {
        super(policy);
        this.kernel = kernel;
        this.clock = clock;
    }

    @Override
    String threadName() {
        return "kernel-intent-worker";
    }

    @Override
    void poll() {
        try {
            kernel.processDue(clock.instant(), this::isAcceptingWork);
        } catch (RuntimeException exception) {
            LOG.log(System.Logger.Level.WARNING, "intent_worker_poll_failed type={0}",
                    exception.getClass().getSimpleName());
        }
    }

}
