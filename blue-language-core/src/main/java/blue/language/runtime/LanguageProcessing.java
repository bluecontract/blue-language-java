package blue.language.runtime;

import blue.language.api.BlueOperationResult;
import blue.language.conformance.ConformanceEngine;
import blue.language.merge.IncrementalValueResolutionRequest;
import blue.language.merge.ResolvedSnapshot;
import blue.language.identity.CanonicalTypeIdentityEvidence;
import blue.language.model.Node;
import blue.language.provider.NodeProvider;
import blue.language.provider.SequentialNodeProvider;
import blue.language.registry.NodeProviderWrapper;
import blue.language.snapshot.BluePatch;
import blue.language.snapshot.FrozenNode;

import java.util.Collection;
import java.util.Objects;

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
     * Opens a strict processing scope over exactly one invocation provider.
     *
     * <p>The supplied provider is verified but is not combined with the
     * construction-time provider, the Language bootstrap provider, or retained
     * provider-derived cache state. The caller must explicitly compose every
     * fallback needed by the invocation. Closing the scope never closes the
     * borrowed provider.</p>
     *
     * @param invocationProvider complete borrowed provider graph for this scope
     * @return isolated processing scope
     * @throws NullPointerException if {@code invocationProvider} is {@code null}
     * @throws IllegalStateException if the owning Language runtime is closed
     */
    default Scope openScope(NodeProvider invocationProvider) {
        throw new UnsupportedOperationException(
                "This Language processing bridge does not support strict invocation providers");
    }

    /**
     * Opens an observed strict scope over one invocation provider.
     *
     * @param invocationProvider complete borrowed provider graph for this scope
     * @param observer telemetry callback receiver
     * @return isolated processing scope
     * @throws NullPointerException if either argument is {@code null}
     * @throws IllegalStateException if the owning Language runtime is closed
     */
    default Scope openScope(
            NodeProvider invocationProvider,
            Observer observer) {
        Objects.requireNonNull(observer, "observer");
        return openScope(Objects.requireNonNull(
                invocationProvider, "invocationProvider"));
    }

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
     * Opaque operation-local exact-node overlay for canonical resolution.
     *
     * <p>The wrapped provider remains untrusted. Only a Language-owned
     * {@link Scope} can consume this value directly, and every returned
     * candidate still crosses the ordinary provider-verification boundary.
     * Downstream Language adapters can compose only a verification-only view;
     * the raw provider remains inaccessible.</p>
     */
    final class ExactResolutionOverlay {
        private final NodeProvider provider;

        private ExactResolutionOverlay(NodeProvider provider) {
            this.provider = Objects.requireNonNull(provider, "provider");
        }

        /**
         * Wraps one borrowed provider as operation-local exact evidence.
         *
         * @param provider provider whose results Language must verify
         * @return opaque exact-resolution overlay
         * @throws NullPointerException if {@code provider} is {@code null}
         */
        public static ExactResolutionOverlay from(NodeProvider provider) {
            return new ExactResolutionOverlay(provider);
        }

        /**
         * Composes this overlay ahead of one fallback overlay.
         *
         * <p>Only a definitive miss reaches the fallback. Unavailability and
         * invalid evidence remain authoritative.</p>
         *
         * @param fallback overlay consulted after a definitive miss
         * @return ordered opaque overlay
         * @throws NullPointerException if {@code fallback} is {@code null}
         */
        public ExactResolutionOverlay followedBy(
                ExactResolutionOverlay fallback) {
            ExactResolutionOverlay checked = Objects.requireNonNull(
                    fallback, "fallback");
            return new ExactResolutionOverlay(
                    new SequentialNodeProvider(
                            provider, checked.provider));
        }

        /**
         * Composes this untrusted overlay ahead of one fallback provider and
         * independently verifies every result-producing leaf.
         *
         * <p>The returned graph inserts no bootstrap or ambient fallback.
         * Invalid or unavailable overlay evidence therefore remains
         * authoritative, while cyclic members retain the proof capability of
         * their exact provider leaf.</p>
         *
         * @param fallback verified provider graph consulted after a definitive
         *        overlay miss
         * @return fallback-free verification-only provider composition
         * @throws NullPointerException if {@code fallback} is null
         */
        public NodeProvider verifiedBefore(NodeProvider fallback) {
            return VerificationBridge.verifyOnlyWithoutFallback(
                    new SequentialNodeProvider(
                            provider,
                            Objects.requireNonNull(fallback, "fallback")));
        }

        /** Keeps the fallback-free verifier hook inside Language ownership. */
        private static final class VerificationBridge
                extends NodeProviderWrapper {
            private static NodeProvider verifyOnlyWithoutFallback(
                    NodeProvider provider) {
                return verifyOnly(provider);
            }
        }

        NodeProvider provider() {
            return provider;
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
         * Returns a lifecycle-bound Language capability using this scope's
         * exact provider and cache domain.
         *
         * @return provider-scoped Language runtime access
         * @throws IllegalStateException if this scope or its runtime is closed
         */
        default LanguageRuntimeAccess runtimeAccess() {
            throw new UnsupportedOperationException(
                    "This Language processing scope does not expose scoped runtime access");
        }

        /**
         * Creates a conformance engine borrowing this scope's exact provider
         * and cache domain. Closing the engine does not close the scope;
         * closing the scope or its runtime invalidates the engine.
         *
         * @return scope-bound conformance engine
         * @throws IllegalStateException if this scope or its runtime is closed
         */
        default ConformanceEngine newConformanceEngine() {
            throw new UnsupportedOperationException(
                    "This Language processing scope does not expose scoped conformance");
        }

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
         * Resolves one complete authored document against operation-local
         * exact evidence followed by this scope's provider graph.
         *
         * <p>The overlay is not trusted materialized state: every returned
         * candidate passes the ordinary Language provider-verification
         * boundary before it can contribute to resolution. An unavailable or
         * invalid overlay result is authoritative and is not hidden by the
         * scope provider. The returned snapshot has complete canonical type
         * evidence and a whole-document canonical identity. This operation
         * never publishes newly discovered state.</p>
         *
         * @param document authored document to resolve
         * @param exactResolutionOverlay opaque operation-local exact evidence
         *        tried before this scope's provider
         * @return invocation-local complete resolved snapshot
         * @throws NullPointerException if either argument is {@code null}
         * @throws IllegalStateException if this scope or its runtime is closed
         */
        ResolvedSnapshot resolveTransientForCanonicalIdentity(
                Node document,
                ExactResolutionOverlay exactResolutionOverlay);

        /**
         * Resolves an authored type declaration as metadata and returns its
         * resolver-issued canonical identity evidence. Required-field and
         * other instance-schema checks never run against the declaration.
         *
         * @param declaration authored inline declaration or pure reference
         * @return identity evidence selected with the exact preprocessed
         *         authored representation
         * @throws NullPointerException if {@code declaration} is null
         * @throws IllegalArgumentException if declaration or provider evidence
         *         is invalid
         * @throws IllegalStateException if the scope is closed or exact
         *         evidence cannot be established
         */
        CanonicalTypeIdentityEvidence resolveTypeDeclarationIdentity(
                Node declaration);

        /**
         * Overlay-aware declaration-identity resolution. Exact operation-local
         * evidence is verified before the scope provider is consulted.
         *
         * @param declaration authored inline declaration or pure reference
         * @param exactResolutionOverlay operation-local exact evidence
         * @return identity evidence selected with the exact preprocessed
         *         authored representation
         * @throws NullPointerException if an argument is null
         * @throws IllegalArgumentException if declaration or provider evidence
         *         is invalid
         * @throws IllegalStateException if the scope is closed or exact
         *         evidence cannot be established
         */
        CanonicalTypeIdentityEvidence resolveTypeDeclarationIdentity(
                Node declaration,
                ExactResolutionOverlay exactResolutionOverlay);

        /**
         * Resolves a document while retaining exact authored subtrees at the
         * supplied RFC 6901 paths. A retained pure reference remains cold.
         * Its exact input is available through
         * {@link ResolvedSnapshot#sourceRoot()}. When retained inline type
         * metadata leaves canonical evidence incomplete, canonical-root and
         * BlueId access fail closed until an unlimited retry succeeds.
         *
         * @param document authored document to resolve
         * @param preservedPaths paths retained in authored form; null or empty
         *        means no paths are retained
         * @return target-limited snapshot with selected subtrees deferred
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
