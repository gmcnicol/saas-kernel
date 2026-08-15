package io.github.gmcnicol.kernel.semanticpack;

import java.util.Map;

/** Application-owned forward adapter for one historical payload or Event version. */
public interface SemanticVersionAdapter extends SemanticImplementation {

    Contract contract();

    String type();

    int fromVersion();

    int toVersion();

    Map<String, String> adapt(Map<String, String> values);

    @Override
    default Kind kind() {
        return Kind.ADAPTER;
    }

    @Override
    default String target() {
        return contract() + ":" + type() + "@" + fromVersion() + "->" + toVersion();
    }

    static SemanticVersionAdapter identity(Contract contract, String type, int fromVersion, int toVersion) {
        return new Identity(contract, type, fromVersion, toVersion);
    }

    enum Contract {
        PAYLOAD,
        EVENT
    }

    record Identity(Contract contract, String type, int fromVersion, int toVersion)
            implements SemanticVersionAdapter {

        public Identity {
            if (contract == null || type == null || type.isBlank()
                    || fromVersion < 1 || toVersion <= fromVersion) {
                throw new IllegalArgumentException("Semantic adapter requires a forward version step");
            }
        }

        @Override
        public Map<String, String> adapt(Map<String, String> values) {
            return Map.copyOf(values);
        }
    }
}
