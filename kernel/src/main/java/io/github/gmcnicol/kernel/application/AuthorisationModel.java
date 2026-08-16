package io.github.gmcnicol.kernel.application;

import java.util.Map;
import java.util.Set;

/** Application-owned mapping from Projected State fields to qualified Cedar actions. */
public interface AuthorisationModel {

    String subjectType();

    default Set<String> subjectTypes() {
        return Set.of(subjectType());
    }

    String resourceType();

    Map<String, String> fields();
}
