package io.github.gmcnicol.kernel.application;

import java.util.Map;

public record Fact(String type, String derivationId, Map<String, String> values) {

    public Fact {
        values = Map.copyOf(values);
    }
}
