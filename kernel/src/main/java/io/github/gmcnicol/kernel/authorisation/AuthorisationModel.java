package io.github.gmcnicol.kernel.authorisation;

import java.util.Map;

/** Application-owned mapping from Projected State fields to qualified Cedar actions. */
public interface AuthorisationModel {

    String resourceType();

    Map<String, String> fields();
}
