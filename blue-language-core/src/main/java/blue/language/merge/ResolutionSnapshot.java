package blue.language.merge;

import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.snapshot.FrozenNode;

/**
 * Read-only contract shared by standalone and compatibility resolution results.
 */
public interface ResolutionSnapshot {

    /**
     * Returns the strict canonical identity root.
     *
     * @return immutable strict canonical root
     */
    FrozenNode canonicalRoot();

    /**
     * Returns the completed resolved runtime root.
     *
     * @return immutable completed resolved root
     */
    FrozenNode resolvedRoot();

    /**
     * Returns provenance from the same resolver invocation.
     *
     * @return immutable resolution provenance
     */
    ResolutionProvenance provenance();

    /**
     * Returns resolver-issued canonical type identity evidence for this run.
     *
     * @return immutable fail-closed effective-type identity lookup
     */
    CanonicalTypeIdentityLookup canonicalTypeIdentities();

    /**
     * Reports whether the resolved lane covers the complete input graph.
     *
     * <p>A snapshot produced under target or path limits may still carry a
     * strict canonical input, but that does not make its resolved projection
     * complete. Consumers must preserve this distinction when adapting the
     * result for publication or caching.</p>
     *
     * @return {@code true} only for an unlimited completed resolution
     */
    boolean isResolutionComplete();
}
