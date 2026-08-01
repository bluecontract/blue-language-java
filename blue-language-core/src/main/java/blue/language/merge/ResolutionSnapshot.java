package blue.language.merge;

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
}
