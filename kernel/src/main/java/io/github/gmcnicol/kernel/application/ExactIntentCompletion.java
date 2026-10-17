package io.github.gmcnicol.kernel.application;

/** Outcome of executing one exact Intent in a caller-owned transaction. */
public enum ExactIntentCompletion {
    /** Execution completed in the current transaction and is provisional until the caller commits. */
    COMPLETED,

    /** The identical request had already completed successfully. */
    ALREADY_SUCCEEDED
}
