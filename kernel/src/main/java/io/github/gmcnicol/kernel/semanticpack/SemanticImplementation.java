package io.github.gmcnicol.kernel.semanticpack;

/** One Application-owned implementation bound to a qualified Taxi target. */
public interface SemanticImplementation {

    Kind kind();

    String target();

    static SemanticImplementation binding(Kind kind, String target) {
        return new Binding(kind, target);
    }

    public enum Kind {
        DERIVATION,
        APPLICABILITY,
        HANDLER
    }

    record Binding(Kind kind, String target) implements SemanticImplementation {

        public Binding {
            if (kind == null || target == null || target.isBlank()) {
                throw new IllegalArgumentException("Semantic implementation requires kind and target");
            }
        }
    }
}
