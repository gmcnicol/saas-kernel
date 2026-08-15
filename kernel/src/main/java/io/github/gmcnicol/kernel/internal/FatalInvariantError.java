package io.github.gmcnicol.kernel.internal;

final class FatalInvariantError extends Error {

    FatalInvariantError(String message) {
        super(message);
    }
}
