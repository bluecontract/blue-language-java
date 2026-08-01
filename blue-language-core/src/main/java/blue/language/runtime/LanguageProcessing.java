package blue.language.runtime;

import blue.language.api.BlueOperationResult;
import blue.language.conformance.ConformanceEngine;
import blue.language.merge.IncrementalValueResolutionRequest;
import blue.language.merge.ResolvedSnapshot;
import blue.language.model.Node;
import blue.language.snapshot.BluePatch;
import blue.language.snapshot.FrozenNode;

import java.util.Collection;

/**
 * Language-owned bridge for deterministic document-processing snapshots.
 *
 * <p>The bridge contains no Contracts types. A downstream runtime may adapt a
 * {@link Scope} to its own processing API while retaining the exact verified
 * provider, preprocessing environment, merge pipeline, and cache generation
 * owned by one {@link BlueLanguage} instance.</p>
 *
 * <p>The bridge is immutable and thread-safe. Every opened scope borrows the
 * owning Language runtime. Closing a scope releases only scope-local transient
 * state; closing the Language runtime invalidates every scope.</p>
 */
public interface LanguageProcessing {

    /** Returns the narrow Language runtime capability used by semantic hosts. */
    LanguageRuntimeAccess runtimeAccess();

    /**
     * Creates a conformance engine that borrows the runtime's verified cache.
     * Closing the returned engine does not close the Language runtime.
     */
    ConformanceEngine newConformanceEngine();

    /** Opens a processing scope without observation callbacks. */
    Scope openScope();

    /** Opens a processing scope with invocation-independent cache observation. */
    Scope openScope(Observer observer);

    /**
     * Language-neutral observation boundary for processing snapshot reuse.
     *
     * <p>Callbacks are telemetry only and cannot affect semantic results.</p>
     */
    interface Observer {

        /** Records one completed cache hit. */
        default void snapshotCacheHit() {
        }

        /** Records one completed cache miss. */
        default void snapshotCacheMiss() {
        }

        /** Records elapsed monotonic lookup time. */
        default void snapshotCacheLookupNanos(long nanos) {
        }
    }

    /**
     * Closeable processing view over one Language runtime generation.
     *
     * <p>A root scope uses one-shot transient caches. A scope returned by
     * {@link #transientSequence()} owns a reusable transient cache, and must be
     * closed when the invocation or working-document sequence ends.</p>
     */
    interface Scope extends AutoCloseable {

        /** Resolves and publishes one complete authored document snapshot. */
        ResolvedSnapshot resolve(Node document);

        /** Resolves one document without publishing newly discovered state. */
        ResolvedSnapshot resolveTransient(Node document);

        /**
         * Resolves a document while retaining exact authored subtrees at the
         * supplied RFC 6901 paths.
         */
        ResolvedSnapshot resolvePreservingPaths(
                Node document,
                Collection<String> preservedPaths);

        /** Transient counterpart to {@link #resolvePreservingPaths(Node, Collection)}. */
        ResolvedSnapshot resolveTransientPreservingPaths(
                Node document,
                Collection<String> preservedPaths);

        /**
         * Materializes exact provider content with typed absence,
         * unavailability, and invalid-evidence outcomes.
         */
        BlueOperationResult<FrozenNode> materializeVerifiedExactReference(
                FrozenNode reference);

        /** Opens a child sequence that can reuse this scope's visible evidence. */
        Scope transientSequence();

        /** Forks independently owned transient state for hand-off. */
        Scope forkTransientSequence();

        /** Retains only transient entries reachable from the current graph. */
        void retainTransientState(
                FrozenNode canonicalRoot,
                FrozenNode resolvedRoot);

        /** Reports whether this scope still belongs to the active generation. */
        boolean isTransientStateCurrent();

        /** Reports generic value-only incremental-resolution support. */
        boolean supportsIncrementalValueResolution();

        /** Tests support for one dependency-proven incremental request. */
        boolean supportsIncrementalValueResolution(
                IncrementalValueResolutionRequest request);

        /**
         * Creates a conformance view that shares this scope's transient cache.
         * The returned view borrows sequence state and must not outlive it.
         */
        ConformanceEngine transientConformanceEngine(
                ConformanceEngine conformanceEngine);

        /** Applies one immutable canonical patch in this scope. */
        ResolvedSnapshot applyPatch(
                ResolvedSnapshot snapshot,
                BluePatch patch);

        /** Publishes one complete snapshot and reachable verified evidence. */
        ResolvedSnapshot publish(ResolvedSnapshot snapshot);

        /** Releases sequence-local transient state; root-scope close is a no-op. */
        @Override
        void close();
    }
}
