package blue.language.processor;

import blue.language.conformance.ConformanceEngine;
import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import blue.language.snapshot.ResolvedSnapshot;
import blue.language.snapshot.FrozenNode;

/**
 * Bridges the mutable processor runtime to the canonical immutable snapshot layer.
 */
public interface ProcessingSnapshotManager {

    ResolvedSnapshot fromDocument(Node document);

    /**
     * Resolves a short-lived processing state without requiring it to be
     * published to shared snapshot caches. Implementations that do not have a
     * separate transient path retain their historical behavior by default.
     */
    default ResolvedSnapshot fromDocumentTransient(Node document) {
        return fromDocument(document);
    }

    /**
     * Opens a short-lived manager for one observable patch sequence. The
     * default preserves historical manager behavior; cache-aware managers can
     * retain intermediate resolution data locally until final publication.
     * Decorators around a cache-aware manager must override and delegate this
     * method if they need to preserve that manager's optimized cache scope.
     */
    default ProcessingSnapshotManager transientSequence() {
        return this;
    }

    /** Returns an independent hand-off scope containing the current transient evidence. */
    default ProcessingSnapshotManager forkTransientSequence() {
        return transientSequence();
    }

    /** Prunes a reusable transient scope to entries reachable from the current working state. */
    default void retainTransientState(FrozenNode canonicalRoot, FrozenNode resolvedRoot) {
        // Historical managers have no explicit transient cache to prune.
    }

    /** Releases a transient manager after its preview/sequence ownership ends. */
    default void releaseTransientState() {
        // Historical managers have no explicitly owned transient state.
    }

    /** Whether this transient scope still belongs to the manager's current cache generation. */
    default boolean isTransientStateCurrent() {
        return true;
    }

    /**
     * Whether this manager accepts dependency-proven value-only snapshot
     * updates without invoking {@link #fromDocumentTransient(Node)}.
     *
     * <p>The default is deliberately conservative for custom managers.</p>
     */
    default boolean supportsIncrementalValueResolution() {
        return false;
    }

    /**
     * Returns the conformance view that shares this sequence's transient
     * resolution scope. Cache-aware decorators should delegate this method
     * together with {@link #transientSequence()}.
     */
    default ConformanceEngine transientConformanceEngine(ConformanceEngine conformanceEngine) {
        return conformanceEngine != null ? conformanceEngine.transientView() : null;
    }

    ResolvedSnapshot applyPatch(ResolvedSnapshot snapshot, JsonPatch patch);

    default ResolvedSnapshot cacheSnapshot(ResolvedSnapshot snapshot) {
        return snapshot;
    }
}
