package blue.language.merge;

import blue.language.snapshot.FrozenNode;

/**
 * Read-only contract shared by standalone and compatibility resolution results.
 */
public interface ResolutionSnapshot {

    /** Returns the strict canonical identity root. */
    FrozenNode canonicalRoot();

    /** Returns the completed resolved runtime root. */
    FrozenNode resolvedRoot();

    /** Returns immutable provenance from the same resolver invocation. */
    ResolutionProvenance provenance();
}
