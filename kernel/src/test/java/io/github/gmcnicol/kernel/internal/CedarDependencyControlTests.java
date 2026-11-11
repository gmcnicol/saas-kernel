package io.github.gmcnicol.kernel.internal;

import static org.assertj.core.api.Assertions.*;

import com.cedarpolicy.BasicAuthorizationEngine;
import com.cedarpolicy.model.AuthorizationRequest;
import com.cedarpolicy.model.entity.Entity;
import com.cedarpolicy.model.policy.PolicySet;
import com.cedarpolicy.model.schema.Schema;
import com.cedarpolicy.value.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Isolated upstream reproductions, deliberately NOT Kernel policy-construction or evaluator routes. */
class CedarDependencyControlTests {
    private static final boolean PATCHED = Boolean.parseBoolean(System.getProperty("cedar.control.patched", "true"));

    @Test void unescapedSourceRenderingCanInjectAPermit() throws Exception {
        String input = "\" || true || \"";
        String source = "permit(principal, action, resource) when { resource.label == "
                + new PrimString(input).toCedarExpr() + " };";
        assertThat(rawAllow(source, Map.of("label", new PrimString("ordinary")), "label: String"))
                .as("dependency-only policy injection; patched=%s", PATCHED).isEqualTo(!PATCHED);
    }

    @Test void unescapedSourceRenderingCanDisableAMatchingForbid() throws Exception {
        String input = "\" && false && \"";
        String source = "permit(principal, action, resource); forbid(principal, action, resource) when { resource.label == "
                + new PrimString(input).toCedarExpr() + " };";
        assertThat(rawAllow(source, Map.of("label", new PrimString(input)), "label: String"))
                .as("dependency-only forbid injection; patched=%s", PATCHED).isEqualTo(!PATCHED);
    }

    @Test void reservedMapShapesAreAnUpstreamReproductionNotAKernelBoundaryPass() throws Exception {
        if (PATCHED) {
            assertThatThrownBy(CedarDependencyControlTests::reservedMap).isInstanceOf(IllegalArgumentException.class);
        } else {
            assertThat(rawAllow("permit(principal, action, resource) when { resource.data == principal };",
                    Map.of("data", reservedMap()), "data: User")).isTrue();
        }
    }

    private static CedarMap reservedMap() {
        return new CedarMap(Map.of("__entity",
                new CedarMap(Map.of("type", new PrimString("User"), "id", new PrimString("u")))));
    }

    private static boolean rawAllow(String source, Map<String, Value> attributes, String field) throws Exception {
        var schema = new Schema("entity User; entity Row { " + field + " }; "
                + "action read appliesTo { principal: User, resource: Row, context: {} };");
        var principal = new Entity(uid("User", "u"), Map.of(), Set.of());
        var resource = new Entity(uid("Row", "r"), attributes, Set.of());
        var request = new AuthorizationRequest(principal.getEUID(), uid("Action", "read"), resource.getEUID(),
                Optional.of(Map.of()), Optional.of(schema), true);
        var result = new BasicAuthorizationEngine().isAuthorized(request, PolicySet.parsePolicies(source), Set.of(principal, resource));
        assertThat(result.success).isPresent();
        assertThat(result.success.orElseThrow().getErrors()).isEmpty();
        return result.success.orElseThrow().isAllowed();
    }

    private static EntityUID uid(String type, String id) {
        return new EntityUID(EntityTypeName.parse(type).orElseThrow(), id);
    }
}
