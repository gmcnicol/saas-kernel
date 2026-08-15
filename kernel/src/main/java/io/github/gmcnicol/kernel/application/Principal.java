package io.github.gmcnicol.kernel.application;

public record Principal(String type, String id) {

    public Principal {
        if (type == null || type.isBlank() || id == null || id.isBlank()) {
            throw new IllegalArgumentException("Principal requires type and ID");
        }
    }
}
