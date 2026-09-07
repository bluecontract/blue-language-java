package blue.language.merge;

import blue.language.api.BlueCacheStats;
import blue.language.model.Node;

import java.util.Collection;
import java.util.Optional;

/** Creates, loads, and caches immutable resolved/canonical pairs. */
public interface BlueSnapshots {

    /**
     * Creates a complete snapshot from authored Source.
     * Completeness describes resolution's semantic closure, not conformance
     * of undemanded reference targets. Opaque references can remain in the lane.
     *
     * @param source authored Source Document
     * @return complete resolved snapshot
     */
    ResolvedSnapshot resolve(Node source);

    /**
     * Creates an invocation-local snapshot with deferred selected paths.
     *
     * @param source authored Source Document
     * @param preservedPaths RFC 6901 pointers retained in authored form
     * @return resolved snapshot with selected paths deferred
     */
    ResolvedSnapshot resolvePreservingPaths(
            Node source, Collection<String> preservedPaths);

    /**
     * Loads an exact canonical identity input as a snapshot.
     *
     * @param canonicalIdentityInput exact canonical identity input
     * @return snapshot loaded from the canonical input
     */
    ResolvedSnapshot load(Node canonicalIdentityInput);

    /**
     * Loads verified canonical content addressed by {@code blueId} and runs
     * completed resolution on that target, retaining any undemanded inner references.
     *
     * @param blueId Content BlueId selecting the canonical content
     * @return snapshot loaded from verified provider content
     */
    ResolvedSnapshot load(String blueId);

    /**
     * Publishes a complete snapshot to this runtime's bounded cache.
     *
     * @param snapshot complete snapshot to cache
     * @return cached snapshot
     */
    ResolvedSnapshot cache(ResolvedSnapshot snapshot);

    /**
     * Looks up a runtime-owned cached snapshot.
     *
     * @param blueId Content BlueId of the desired snapshot
     * @return cached snapshot, or an empty optional when absent
     */
    Optional<ResolvedSnapshot> cached(String blueId);

    /** Clears reloadable derived snapshot state. */
    void clear();

    /**
     * Returns a point-in-time immutable cache report.
     *
     * @return cache statistics for the owning runtime
     */
    BlueCacheStats stats();
}
