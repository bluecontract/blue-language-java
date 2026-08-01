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

    /**
     * Returns the narrow Language runtime capability used by semantic hosts.
     *
     * @return runtime capability borrowed by this bridge
     */
    LanguageRuntimeAccess runtimeAccess();

    /**
     * Creates a conformance engine that borrows the runtime's verified cache.
     * Closing the returned engine does not close the Language runtime.
     *
     * @return independently closeable conformance engine
     * @throws IllegalStateException if the owning Language runtime is closed
     */
    ConformanceEngine newConformanceEngine();

    /**
     * Opens a processing scope without observation callbacks.
     *
     * @return new scope borrowing the current runtime generation
     * @throws IllegalStateException if the owning Language runtime is closed
     */
    Scope openScope();

    /**
     * Opens a processing scope with invocation-independent cache observation.
     *
     * @param observer telemetry callback receiver
     * @return new scope borrowing the current runtime generation
     * @throws NullPointerException if {@code observer} is {@code null}
     * @throws IllegalStateException if the owning Language runtime is closed
     */
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

        /**
         * Records elapsed monotonic lookup time.
         *
         * @param nanos elapsed lookup time in nanoseconds
         */
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

        /**
         * Resolves and publishes one complete authored document snapshot.
         *
         * @param document authored document to resolve
         * @return complete resolved snapshot
         * @throws NullPointerException if {@code document} is {@code null}
         * @throws IllegalStateException if this scope or its runtime is closed
         */
        ResolvedSnapshot resolve(Node document);

        /**
         * Resolves one document without publishing newly discovered state.
         *
         * @param document authored document to resolve
         * @return invocation-local resolved snapshot
         * @throws NullPointerException if {@code document} is {@code null}
         * @throws IllegalStateException if this scope or its runtime is closed
         */
        ResolvedSnapshot resolveTransient(Node document);

        /**
         * Resolves a document while retaining exact authored subtrees at the
         * supplied RFC 6901 paths.
         *
         * @param document authored document to resolve
         * @param preservedPaths paths retained in authored form; null or empty
         *        means no paths are retained
         * @return resolved snapshot with the selected subtrees deferred
         * @throws NullPointerException if {@code document} is {@code null}
         * @throws IllegalStateException if this scope or its runtime is closed
         */
        ResolvedSnapshot resolvePreservingPaths(
                Node document,
                Collection<String> preservedPaths);

        /**
         * Transient counterpart to
         * {@link #resolvePreservingPaths(Node, Collection)}.
         *
         * @param document authored document to resolve
         * @param preservedPaths paths retained in authored form; null or empty
         *        means no paths are retained
         * @return invocation-local snapshot with the selected subtrees deferred
         * @throws NullPointerException if {@code document} is {@code null}
         * @throws IllegalStateException if this scope or its runtime is closed
         */
        ResolvedSnapshot resolveTransientPreservingPaths(
                Node document,
                Collection<String> preservedPaths);

        /**
         * Materializes exact provider content with typed absence,
         * unavailability, and invalid-evidence outcomes.
         *
         * @param reference immutable value or pure reference to materialize
         * @return exhaustive materialization outcome
         * @throws NullPointerException if {@code reference} is {@code null}
         * @throws IllegalStateException if this scope or its runtime is closed
         */
        BlueOperationResult<FrozenNode> materializeVerifiedExactReference(
                FrozenNode reference);

        /**
         * Opens a child sequence that can reuse this scope's visible evidence.
         *
         * @return independently closeable child sequence
         * @throws IllegalStateException if this scope or its runtime is closed
         */
        Scope transientSequence();

        /**
         * Forks independently owned transient state for hand-off.
         *
         * @return independently closeable forked sequence
         * @throws IllegalStateException if this scope or its runtime is closed
         */
        Scope forkTransientSequence();

        /**
         * Retains only transient entries reachable from the current graph.
         *
         * @param canonicalRoot current canonical graph root
         * @param resolvedRoot current resolved graph root
         * @throws IllegalStateException if this scope or its runtime is closed
         */
        void retainTransientState(
                FrozenNode canonicalRoot,
                FrozenNode resolvedRoot);

        /**
         * Reports whether this scope still belongs to the active generation.
         *
         * @return {@code true} when the scope and runtime generation are current
         */
        boolean isTransientStateCurrent();

        /**
         * Reports generic value-only incremental-resolution support.
         *
         * @return whether the configured merge pipeline supports incremental
         *         value resolution
         * @throws IllegalStateException if this scope or its runtime is closed
         */
        boolean supportsIncrementalValueResolution();

        /**
         * Tests support for one dependency-proven incremental request.
         *
         * @param request immutable incremental-resolution evidence
         * @return whether the configured merge pipeline supports this request
         * @throws NullPointerException if {@code request} is {@code null}
         * @throws IllegalStateException if this scope or its runtime is closed
         */
        boolean supportsIncrementalValueResolution(
                IncrementalValueResolutionRequest request);

        /**
         * Creates a conformance view that shares this scope's transient cache.
         * The returned view borrows sequence state and must not outlive it.
         *
         * @param conformanceEngine source engine, or {@code null}
         * @return transient conformance view, or {@code null} when the source
         *         engine is {@code null}
         * @throws IllegalStateException if this scope or its runtime is closed
         */
        ConformanceEngine transientConformanceEngine(
                ConformanceEngine conformanceEngine);

        /**
         * Applies one immutable canonical patch in this scope.
         *
         * @param snapshot snapshot whose canonical root is patched
         * @param patch immutable patch operation
         * @return completely resolved patched snapshot
         * @throws NullPointerException if {@code snapshot} or {@code patch} is
         *         {@code null}
         * @throws IllegalStateException if this scope or its runtime is closed
         */
        ResolvedSnapshot applyPatch(
                ResolvedSnapshot snapshot,
                BluePatch patch);

        /**
         * Publishes one complete snapshot and reachable verified evidence.
         *
         * @param snapshot snapshot to publish
         * @return published snapshot, or the unchanged incomplete snapshot
         * @throws NullPointerException if {@code snapshot} is {@code null}
         * @throws IllegalStateException if this scope or its runtime is closed
         */
        ResolvedSnapshot publish(ResolvedSnapshot snapshot);

        /**
         * Closes this scope and releases any sequence-local transient state.
         * A root scope owns no transient cache, but closing it still prevents
         * further scope operations.
         *
         * @throws IllegalStateException if invoked from an active operation on
         *         the same scope
         */
        @Override
        void close();
    }
}
