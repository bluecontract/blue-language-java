package blue.language.runtime;

import blue.language.conformance.ConformanceEngine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/** Internal ownership registry for runtime processing-scope resources. */
final class ProcessingScopeLifecycle {

    private final Set<LanguageProcessing.Scope> scopes = identitySet();

    synchronized <T extends LanguageProcessing.Scope> T retain(T scope) {
        scopes.add(scope);
        return scope;
    }

    synchronized void release(LanguageProcessing.Scope scope) {
        scopes.remove(scope);
    }

    void closeAll() {
        List<LanguageProcessing.Scope> retained;
        synchronized (this) {
            retained = new ArrayList<>(scopes);
            scopes.clear();
        }
        for (LanguageProcessing.Scope scope : retained) {
            scope.close();
        }
    }

    private static <T> Set<T> identitySet() {
        return Collections.newSetFromMap(
                new IdentityHashMap<T, Boolean>());
    }

    /** Owns conformance views that must not outlive their creating scope. */
    static final class ConformanceEngines {
        private final Set<ConformanceEngine> retained = identitySet();

        synchronized ConformanceEngine retain(ConformanceEngine engine) {
            retained.add(engine);
            return engine;
        }

        void closeAll() {
            List<ConformanceEngine> engines;
            synchronized (this) {
                engines = new ArrayList<>(retained);
                retained.clear();
            }
            for (ConformanceEngine engine : engines) {
                engine.close();
            }
        }
    }
}
