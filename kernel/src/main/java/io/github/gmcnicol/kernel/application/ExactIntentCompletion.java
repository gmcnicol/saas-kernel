package io.github.gmcnicol.kernel.application;

/**
 * Outcome of executing one exact Intent through {@link Kernel#execute}.
 *
 * <p>The outcome does not replace the caller-owned transaction's commit or rollback result.
 */
public enum ExactIntentCompletion {
    /** Execution completed in the current transaction and is provisional until the caller commits. */
    COMPLETED,

    /** An identical request was already durably committed as succeeded, so it was not executed again. */
    ALREADY_SUCCEEDED
}
