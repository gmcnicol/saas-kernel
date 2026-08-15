package io.github.gmcnicol.kernel.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("kernel.intent-worker")
final class IntentWorkerProperties {

    private Duration leaseDuration = Duration.ofSeconds(30);
    private int maximumAttempts = 3;
    private Duration retryBackoff = Duration.ofMinutes(1);
    private int claimBatchSize = 10;
    private Duration pollingInterval = Duration.ofSeconds(1);
    private Duration shutdownTimeout = Duration.ofSeconds(20);
    private boolean enabled = true;

    Duration leaseDuration() { return leaseDuration; }
    int maximumAttempts() { return maximumAttempts; }
    Duration retryBackoff() { return retryBackoff; }
    int claimBatchSize() { return claimBatchSize; }
    Duration pollingInterval() { return pollingInterval; }
    Duration shutdownTimeout() { return shutdownTimeout; }
    boolean enabled() { return enabled; }

    public void setLeaseDuration(Duration value) { leaseDuration = positive(value, "lease duration"); }
    public void setMaximumAttempts(int value) {
        if (value < 1) throw new IllegalArgumentException("Maximum attempts must be positive");
        maximumAttempts = value;
    }
    public void setRetryBackoff(Duration value) { retryBackoff = positive(value, "retry backoff"); }
    public void setClaimBatchSize(int value) {
        if (value < 1) throw new IllegalArgumentException("Claim batch size must be positive");
        claimBatchSize = value;
    }
    public void setPollingInterval(Duration value) { pollingInterval = positive(value, "polling interval"); }
    public void setShutdownTimeout(Duration value) { shutdownTimeout = positive(value, "shutdown timeout"); }
    public void setEnabled(boolean value) { enabled = value; }

    private static Duration positive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
