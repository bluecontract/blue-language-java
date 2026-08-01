package blue.language.merge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Shared generation and live-scope registry for one root reference cache. */
final class ResolvedReferenceCacheGeneration {

    final AtomicLong value = new AtomicLong();
    final Object mutationLock = new Object();
    final VerifiedCanonicalLoadCoordinator canonicalLoads =
            new VerifiedCanonicalLoadCoordinator();
    final Set<ResolvedReferenceCache> caches =
            Collections.newSetFromMap(
                    new WeakHashMap<ResolvedReferenceCache, Boolean>());
    volatile boolean closed;
    long verifiedHighWaterWeight;
    long structuralHighWaterWeight;

    void register(ResolvedReferenceCache cache) {
        synchronized (mutationLock) {
            if (closed
                    || ResolvedReferenceCacheLifecycle
                    .hasClosedAncestor(cache)) {
                throw new IllegalStateException(
                        "Resolved reference cache is closed");
            }
            caches.add(cache);
        }
    }

    List<ResolvedReferenceCache> liveCaches() {
        return new ArrayList<>(caches);
    }

    void unregister(ResolvedReferenceCache target) {
        caches.remove(target);
    }
}
