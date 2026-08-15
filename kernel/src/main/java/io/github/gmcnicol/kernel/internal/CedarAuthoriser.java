package io.github.gmcnicol.kernel.internal;

import com.cedarpolicy.BasicAuthorizationEngine;
import com.cedarpolicy.model.AuthorizationRequest;
import com.cedarpolicy.model.entity.Entity;
import com.cedarpolicy.model.policy.PolicySet;
import com.cedarpolicy.model.schema.Schema;
import com.cedarpolicy.value.EntityTypeName;
import com.cedarpolicy.value.EntityUID;
import io.github.gmcnicol.kernel.application.Principal;
import io.github.gmcnicol.kernel.application.Subject;
import io.github.gmcnicol.kernel.authorisation.AuthorisationModel;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class CedarAuthoriser {

    private final BasicAuthorizationEngine engine = new BasicAuthorizationEngine();
    private final Schema schema;
    private final PolicySet policies;
    private final AuthorisationModel model;
    private final String bundleId;
    private final String bundleChecksum;

    CedarAuthoriser(
            Schema schema,
            PolicySet policies,
            AuthorisationModel model,
            String bundleId,
            String bundleChecksum) {
        this.schema = schema;
        this.policies = policies;
        this.model = model;
        this.bundleId = bundleId;
        this.bundleChecksum = bundleChecksum;
    }

    boolean allows(Principal principal, Subject subject, String operation) {
        try {
            Entity principalEntity = entity(principal.type(), principal.id());
            Entity resourceEntity = entity(model.resourceType(), subject.id());
            var request = new AuthorizationRequest(
                    principalEntity.getEUID(),
                    euid("Action", operation),
                    resourceEntity.getEUID(),
                    Optional.of(Map.of()),
                    Optional.of(schema),
                    true);
            return engine.isAuthorized(request, policies, Set.of(principalEntity, resourceEntity))
                    .success
                    .map(response -> response.isAllowed())
                    .orElse(false);
        } catch (Exception exception) {
            return false;
        }
    }

    Map<String, String> fields() {
        return model.fields();
    }

    String bundleId() {
        return bundleId;
    }

    String bundleChecksum() {
        return bundleChecksum;
    }

    private static Entity entity(String type, String id) {
        return new Entity(euid(type, id), Map.of(), Set.of());
    }

    private static EntityUID euid(String type, String id) {
        return new EntityUID(
                EntityTypeName.parse(type).orElseThrow(() -> new IllegalArgumentException("Invalid Cedar entity type")),
                id);
    }
}
