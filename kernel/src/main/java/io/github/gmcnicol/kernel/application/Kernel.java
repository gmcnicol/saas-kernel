package io.github.gmcnicol.kernel.application;

import java.time.Instant;

/** Stable entry point for Application-owned state and Kernel workflow mechanics. */
public interface Kernel {

    EvaluationSnapshot evaluate(ProjectedState state, Instant evaluatedAt);
}
