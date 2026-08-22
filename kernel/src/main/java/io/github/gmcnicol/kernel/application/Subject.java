package io.github.gmcnicol.kernel.application;

public record Subject(String type, String id) {

    public Subject {
        if (type == null || type.isBlank() || id == null || id.isBlank()) {
            throw new IllegalArgumentException("Subject requires type and ID");
        }
    }
}
