package io.github.gmcnicol.kernel.internal;

import io.github.gmcnicol.kernel.application.CandidatePayload;
import io.github.gmcnicol.kernel.application.Event;
import io.github.gmcnicol.kernel.semanticpack.SemanticVersionAdapter;
import io.github.gmcnicol.kernel.semanticpack.SemanticVersionAdapter.Contract;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

final class SemanticCompatibility {

    private final Map<Key, SemanticVersionAdapter> adapters;
    private final Map<ContractType, Integer> currentVersions;

    SemanticCompatibility(List<SemanticVersionAdapter> adapters) {
        var bySource = new HashMap<Key, SemanticVersionAdapter>();
        var current = new HashMap<ContractType, Integer>();
        for (SemanticVersionAdapter adapter : adapters) {
            var key = new Key(adapter.contract(), adapter.type(), adapter.fromVersion());
            if (bySource.putIfAbsent(key, adapter) != null) {
                throw new IllegalStateException("Duplicate semantic compatibility adapter: " + adapter.target());
            }
            current.merge(new ContractType(adapter.contract(), adapter.type()), adapter.toVersion(), Math::max);
        }
        this.adapters = Map.copyOf(bySource);
        this.currentVersions = Map.copyOf(current);
    }

    CandidatePayload adapt(CandidatePayload payload) {
        Adapted adapted = adapt(Contract.PAYLOAD, payload.type(), payload.version(), payload.values());
        return new CandidatePayload(
                payload.type(), adapted.version(), adapted.values(), payload.traceContext(), payload.priorIntentId());
    }

    Event adapt(Event event) {
        Adapted adapted = adapt(Contract.EVENT, event.type(), event.version(), event.payload());
        return new Event(event.type(), adapted.version(), adapted.values(), event.resultingState());
    }

    private Adapted adapt(Contract contract, String type, int version, Map<String, String> values) {
        var seen = new HashSet<Integer>();
        while (seen.add(version)) {
            SemanticVersionAdapter adapter = adapters.get(new Key(contract, type, version));
            if (adapter == null) break;
            values = adapter.adapt(values);
            version = adapter.toVersion();
        }
        int current = currentVersions.getOrDefault(new ContractType(contract, type), 1);
        if (version != current || values == null) {
            throw new IllegalArgumentException("Unsupported semantic contract version");
        }
        return new Adapted(version, Map.copyOf(values));
    }

    private record Key(Contract contract, String type, int version) { }

    private record ContractType(Contract contract, String type) { }

    private record Adapted(int version, Map<String, String> values) { }
}
