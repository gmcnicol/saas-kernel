package io.github.gmcnicol.kernel.internal;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

final class KernelRuntimeHealth implements HealthIndicator {

    private final ObjectProvider<FixedDelayWorker> workers;
    private final ObjectProvider<SemanticDeploymentGuard> deploymentGuard;

    KernelRuntimeHealth(
            ObjectProvider<FixedDelayWorker> workers,
            ObjectProvider<SemanticDeploymentGuard> deploymentGuard) {
        this.workers = workers;
        this.deploymentGuard = deploymentGuard;
    }

    @Override
    public Health health() {
        var currentWorkers = workers.orderedStream().toList();
        var currentGuards = deploymentGuard.orderedStream().toList();
        boolean ready = currentWorkers.size() == 2
                && currentWorkers.stream().allMatch(FixedDelayWorker::isReady)
                && currentGuards.size() == 1
                && currentGuards.getFirst().isRunning();
        return ready ? Health.up().build() : Health.down().build();
    }
}
