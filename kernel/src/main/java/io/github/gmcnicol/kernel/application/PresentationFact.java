package io.github.gmcnicol.kernel.application;

import java.util.Map;

public record PresentationFact(String type, Map<String, String> values) {

    public PresentationFact {
        values = Map.copyOf(values);
    }
}
