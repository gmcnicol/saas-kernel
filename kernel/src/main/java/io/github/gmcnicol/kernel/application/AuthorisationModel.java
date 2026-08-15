package io.github.gmcnicol.kernel.application;

import java.util.Map;

/** Application-owned mapping from Projected State fields to qualified Cedar actions. */
public interface AuthorisationModel {

    String subjectType();

    String resourceType();

    Map<String, String> fields();
}
