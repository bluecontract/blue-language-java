package blue.language.snapshot;

import blue.language.api.BlueCacheStats;
import blue.language.model.Node;

import java.util.Collection;
import java.util.Optional;

/** Creates, loads, and caches immutable resolved/canonical pairs. */
public interface BlueSnapshots {

    /** Creates a complete snapshot from authored Source. */
    ResolvedSnapshot resolve(Node source);

    /** Creates an invocation-local snapshot with deferred selected paths. */
    ResolvedSnapshot resolvePreservingPaths(
            Node source, Collection<String> preservedPaths);

    /** Loads an exact canonical identity input as a snapshot. */
    ResolvedSnapshot load(Node canonicalIdentityInput);

    /** Loads verified canonical content addressed by {@code blueId}. */
    ResolvedSnapshot load(String blueId);

    /** Publishes a complete snapshot to this runtime's bounded cache. */
    ResolvedSnapshot cache(ResolvedSnapshot snapshot);

    /** Looks up a runtime-owned cached snapshot. */
    Optional<ResolvedSnapshot> cached(String blueId);

    /** Clears reloadable derived snapshot state. */
    void clear();

    /** Returns a point-in-time immutable cache report. */
    BlueCacheStats stats();
}
