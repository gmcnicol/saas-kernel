package io.github.gmcnicol.kernel.application;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/** Immutable authorised output only; no candidate counts, keys or skipped-row metadata. */
public record ReadPage<P>(List<AuthorisedRecord<P>> records, OptionalLong exactCount, Optional<String> continuation) {
    public ReadPage { records = List.copyOf(records); }
    @Override public String toString() { return "ReadPage[redacted]"; }
}
