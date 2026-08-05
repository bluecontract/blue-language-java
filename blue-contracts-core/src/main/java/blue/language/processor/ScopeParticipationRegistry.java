package blue.language.processor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Invocation-local index of immutable contract frames by scope path. */
final class ScopeParticipationRegistry {

    private final Map<String, ContractBundle> bundles;

    ScopeParticipationRegistry(Map<String, ContractBundle> bundles) {
        this.bundles = Objects.requireNonNull(bundles, "bundles");
    }

    ContractBundle bundle(String scopePath) {
        return bundles.get(ProcessorEngine.normalizeScope(scopePath));
    }

    void participate(String scopePath, ContractBundle bundle) {
        bundles.put(
                ProcessorEngine.normalizeScope(scopePath),
                Objects.requireNonNull(bundle, "bundle"));
    }

    void withdraw(String scopePath) {
        bundles.remove(ProcessorEngine.normalizeScope(scopePath));
    }

    boolean participates(String scopePath) {
        return bundles.containsKey(
                ProcessorEngine.normalizeScope(scopePath));
    }

    List<String> scopePaths() {
        return Collections.unmodifiableList(
                new ArrayList<>(bundles.keySet()));
    }
}
