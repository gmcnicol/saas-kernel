package io.github.gmcnicol.kernel.internal;

import java.time.Clock;

final class ReevaluationWorker extends FixedDelayWorker {

    private static final System.Logger LOG = System.getLogger(ReevaluationWorker.class.getName());
    private final DefaultKernel kernel;
    private final IntentWorkerProperties policy;
    private final Clock clock;

    ReevaluationWorker(DefaultKernel kernel, IntentWorkerProperties policy, Clock clock) {
        super(policy);
        this.kernel = kernel;
        this.policy = policy;
        this.clock = clock;
    }

    @Override
    String threadName() {
        return "kernel-reevaluation-worker";
    }

    @Override
    void poll() {
        try {
            for (int count = 0; isAcceptingWork() && count < policy.claimBatchSize(); count++) {
                if (!kernel.processNextReevaluationWork(clock.instant())) break;
            }
        } catch (RuntimeException exception) {
            LOG.log(System.Logger.Level.WARNING, "reevaluation_worker_poll_failed type={0}",
                    exception.getClass().getSimpleName());
        }
    }

}
