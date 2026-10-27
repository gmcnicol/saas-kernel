package io.github.gmcnicol.kernel.internal;

import io.github.gmcnicol.kernel.application.CollectionReadException;

/** Internal marker: native admission/deadline failure is not a policy/dependency failure. */
final class ReadEvaluationLimit extends IllegalStateException {
    final CollectionReadException.Reason reason;
    ReadEvaluationLimit(CollectionReadException.Reason reason) {
        super("Cedar evaluation limit");
        if (reason != CollectionReadException.Reason.BUDGET && reason != CollectionReadException.Reason.CANCELLED) throw new IllegalArgumentException();
        this.reason = reason;
    }
}
