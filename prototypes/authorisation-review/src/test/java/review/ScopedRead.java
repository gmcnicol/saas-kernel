package review;

import io.github.gmcnicol.kernel.application.FieldType;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import review.bindings.review.EffectiveRead;

/** Compile sketch ONLY. Everything executable is test-scoped. See proposed ADR 0003. */
public interface ScopedRead {
    record Scope(String tenant, String principal, String principalKind, String resourceScope) {}
    record Authority(Scope scope, EffectiveRead capability, Instant evaluatedAt, Instant expiresAt, String revision) {}
    @FunctionalInterface interface Resolver { Authority current(Scope scope, Instant now); }
    @FunctionalInterface interface Query<R> { List<R> select(Connection scopedConnection) throws Exception; }
    record Identity(String tenant, String scope, String qualifiedType, String id, String owner, long revision) {}
    record Leaf<R>(String qualifiedPath, Function<R, String> value) {
        // Only scalar leaves in this spike. No raw parent escape. Generated paths are a #69 proposal.
        public static <R, N> Leaf<R> nested(FieldType<R, N> parent, FieldType<N, String> child) {
            return new Leaf<>(parent.qualifiedName() + "." + child.name(), row -> child.value(parent.value(row)));
        }
    }
    record Model<R>(Function<R, Identity> identity, List<Leaf<R>> leaves) {
        public Model { leaves = List.copyOf(leaves); }
    }
    final class View<R> {
        private final Map<String, String> fields;
        View(Map<String, String> fields) { this.fields = Map.copyOf(fields); }
        public Map<String, String> fields() { return fields; }
        @Override public String toString() { return "AuthorisedView[redacted]"; }
    }
    <R> List<View<R>> read(Scope scope, Model<R> model, Query<R> query);
}
