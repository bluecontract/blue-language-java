package blue.language.processor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Invocation-owned registry of participating scope occurrences.
 *
 * <p>The registry preserves first-participation order and never recreates a
 * removed occurrence implicitly through a read-only lookup. Lifecycle and
 * cut-off state remain owned by each {@link ScopeRuntimeContext}.</p>
 */
final class ProcessingScopeRegistry {

    private final Map<String, ScopeRuntimeContext> scopes =
            new LinkedHashMap<>();

    Map<String, ScopeRuntimeContext> scopes() {
        return scopes;
    }

    ScopeRuntimeContext scope(String scopePath) {
        return scopes.computeIfAbsent(
                scopePath, ScopeRuntimeContext::new);
    }

    ScopeRuntimeContext existingScope(String scopePath) {
        return scopes.get(scopePath);
    }

    boolean isTerminated(String scopePath) {
        ScopeRuntimeContext context = scopes.get(scopePath);
        return context != null && context.isTerminated();
    }

    void remove(String scopePath) {
        scopes.remove(scopePath);
    }
}
