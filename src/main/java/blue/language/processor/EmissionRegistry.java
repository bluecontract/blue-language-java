package blue.language.processor;

import blue.language.model.Node;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Tracks emissions and per-scope runtime contexts.
 */
final class EmissionRegistry {

    private final Map<String, ScopeRuntimeContext> scopes = new LinkedHashMap<>();
    private final List<Node> rootEmissions = new ArrayList<>();

    Map<String, ScopeRuntimeContext> scopes() {
        return scopes;
    }

    ScopeRuntimeContext scope(String scopePath) {
        return scopes.computeIfAbsent(scopePath, ScopeRuntimeContext::new);
    }

    ScopeRuntimeContext existingScope(String scopePath) {
        return scopes.get(scopePath);
    }

    List<Node> rootEmissions() {
        return rootEmissions;
    }

    void recordRootEmission(Node emission) {
        rootEmissions.add(Objects.requireNonNull(emission, "emission"));
    }

    boolean isScopeTerminated(String scopePath) {
        ScopeRuntimeContext context = scopes.get(scopePath);
        return context != null && context.isTerminated();
    }

    void clearScope(String scopePath) {
        scopes.remove(scopePath);
    }
}
