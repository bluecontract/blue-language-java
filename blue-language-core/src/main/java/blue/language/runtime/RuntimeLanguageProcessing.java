package blue.language.runtime;

import blue.language.api.BlueOperationResult;
import blue.language.api.NodeProviderOutcome;
import blue.language.conformance.ConformanceEngine;
import blue.language.merge.IncrementalMergingProcessorCapability;
import blue.language.merge.IncrementalValueResolutionRequest;
import blue.language.merge.Merger;
import blue.language.merge.MergingProcessor;
import blue.language.merge.ResolvedReferenceCache;
import blue.language.merge.ResolvedSnapshot;
import blue.language.model.Node;
import blue.language.model.wire.JsonPointer;
import blue.language.preprocess.Preprocessor;
import blue.language.provider.NodeProvider;
import blue.language.provider.NodeProviderResult;
import blue.language.resolve.ReferenceCacheAdmissionPolicy;
import blue.language.snapshot.BluePatch;
import blue.language.snapshot.BluePatchOperation;
import blue.language.snapshot.CanonicalOverlayPatchEngine;
import blue.language.snapshot.CanonicalPatchResult;
import blue.language.snapshot.FrozenNode;
import blue.language.snapshot.ImmutableBluePatch;
import blue.language.identity.BlueIds;
import blue.language.identity.CanonicalIdentityInputBuilder;
import blue.language.model.NodePathEditor;
import blue.language.resolve.ResolutionLimits;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

/** Runtime implementation kept package-private behind {@link LanguageProcessing}. */
final class RuntimeLanguageProcessing implements LanguageProcessing {

    private static final Observer NO_OP_OBSERVER = new Observer() {
    };

    private final BlueLanguageRuntime runtime;
    private final NodeProvider nodeProvider;
    private final MergingProcessor mergingProcessor;
    private final LanguageRuntimeSnapshotStore snapshotStore;
    private final Map<String, String> directiveAliases;
    private final Map<String, String> environmentImports;
    private final ReferenceCacheAdmissionPolicy referenceCacheAdmission;

    RuntimeLanguageProcessing(
            BlueLanguageRuntime runtime,
            NodeProvider nodeProvider,
            MergingProcessor mergingProcessor,
            LanguageRuntimeSnapshotStore snapshotStore,
            Map<String, String> directiveAliases,
            Map<String, String> environmentImports,
            ReferenceCacheAdmissionPolicy referenceCacheAdmission) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.nodeProvider = Objects.requireNonNull(
                nodeProvider, "nodeProvider");
        this.mergingProcessor = Objects.requireNonNull(
                mergingProcessor, "mergingProcessor");
        this.snapshotStore = Objects.requireNonNull(
                snapshotStore, "snapshotStore");
        this.directiveAliases = Objects.requireNonNull(
                directiveAliases, "directiveAliases");
        this.environmentImports = Objects.requireNonNull(
                environmentImports, "environmentImports");
        this.referenceCacheAdmission = Objects.requireNonNull(
                referenceCacheAdmission,
                "referenceCacheAdmission");
    }

    @Override
    public LanguageRuntimeAccess runtimeAccess() {
        return runtime;
    }

    @Override
    public ConformanceEngine newConformanceEngine() {
        return runtime.admitted(() -> new ConformanceEngine(
                nodeProvider,
                mergingProcessor,
                snapshotStore.referenceCache()));
    }

    @Override
    public Scope openScope() {
        return openScope(NO_OP_OBSERVER);
    }

    @Override
    public Scope openScope(Observer observer) {
        return runtime.admitted(() -> new RuntimeScope(
                Objects.requireNonNull(observer, "observer"),
                null));
    }

    private final class RuntimeScope implements Scope {

        private final Observer observer;
        private final ResolvedReferenceCache sequenceCache;
        private final ReentrantReadWriteLock lifecycle =
                new ReentrantReadWriteLock(true);
        private final ThreadLocal<Integer> operationDepth =
                new ThreadLocal<>();

        private volatile boolean closed;

        private RuntimeScope(
                Observer observer,
                ResolvedReferenceCache sequenceCache) {
            this.observer = observer;
            this.sequenceCache = sequenceCache;
        }

        @Override
        public ResolvedSnapshot resolve(Node document) {
            return call(() -> resolveDocument(
                    Objects.requireNonNull(document, "document"),
                    Collections.<String>emptySet(),
                    sequenceCache == null));
        }

        @Override
        public ResolvedSnapshot resolveTransient(Node document) {
            return call(() -> resolveDocument(
                    Objects.requireNonNull(document, "document"),
                    Collections.<String>emptySet(),
                    false));
        }

        @Override
        public ResolvedSnapshot resolvePreservingPaths(
                Node document,
                Collection<String> preservedPaths) {
            return call(() -> {
                Set<String> paths = canonicalPreservedPaths(
                        preservedPaths);
                if (paths.isEmpty()) {
                    return resolveDocument(
                            Objects.requireNonNull(
                                    document, "document"),
                            paths,
                            sequenceCache == null);
                }
                return resolveDocument(
                        Objects.requireNonNull(document, "document"),
                        paths,
                        false);
            });
        }

        @Override
        public ResolvedSnapshot resolveTransientPreservingPaths(
                Node document,
                Collection<String> preservedPaths) {
            return call(() -> resolveDocument(
                    Objects.requireNonNull(document, "document"),
                    canonicalPreservedPaths(preservedPaths),
                    false));
        }

        @Override
        public BlueOperationResult<FrozenNode>
        materializeVerifiedExactReference(FrozenNode reference) {
            return call(() -> materializeExact(
                    Objects.requireNonNull(reference, "reference")));
        }

        @Override
        public Scope transientSequence() {
            return call(() -> new RuntimeScope(
                    observer,
                    activeCache().transientChild()));
        }

        @Override
        public Scope forkTransientSequence() {
            return call(() -> new RuntimeScope(
                    observer,
                    sequenceCache == null
                            ? activeCache().transientChild()
                            : sequenceCache.forkTransient()));
        }

        @Override
        public void retainTransientState(
                FrozenNode canonicalRoot,
                FrozenNode resolvedRoot) {
            run(() -> {
                if (sequenceCache != null) {
                    sequenceCache.retainOnlyReachableFrom(
                            canonicalRoot, resolvedRoot);
                }
            });
        }

        @Override
        public boolean isTransientStateCurrent() {
            lifecycle.readLock().lock();
            try {
                return !closed
                        && !runtime.isClosed()
                        && (sequenceCache == null
                        || sequenceCache.isCurrentGeneration());
            } finally {
                lifecycle.readLock().unlock();
            }
        }

        @Override
        public boolean supportsIncrementalValueResolution() {
            return call(() -> mergingProcessor
                    instanceof IncrementalMergingProcessorCapability
                    && ((IncrementalMergingProcessorCapability)
                    mergingProcessor)
                    .supportsIncrementalValueResolution());
        }

        @Override
        public boolean supportsIncrementalValueResolution(
                IncrementalValueResolutionRequest request) {
            return call(() -> mergingProcessor
                    instanceof IncrementalMergingProcessorCapability
                    && ((IncrementalMergingProcessorCapability)
                    mergingProcessor)
                    .supportsIncrementalValueResolution(
                            Objects.requireNonNull(
                                    request, "request")));
        }

        @Override
        public ConformanceEngine transientConformanceEngine(
                ConformanceEngine conformanceEngine) {
            return call(() -> {
                if (conformanceEngine == null) {
                    return null;
                }
                return sequenceCache != null
                        ? conformanceEngine.transientView(sequenceCache)
                        : conformanceEngine.transientView();
            });
        }

        @Override
        public ResolvedSnapshot applyPatch(
                ResolvedSnapshot snapshot,
                BluePatch patch) {
            return call(() -> withResolutionCache(cache ->
                    applyCanonicalPatch(
                            Objects.requireNonNull(snapshot, "snapshot"),
                            Objects.requireNonNull(patch, "patch"),
                            cache)));
        }

        @Override
        public ResolvedSnapshot publish(ResolvedSnapshot snapshot) {
            return call(() -> publishSnapshot(
                    Objects.requireNonNull(snapshot, "snapshot"),
                    sequenceCache));
        }

        @Override
        public void close() {
            Integer depth = operationDepth.get();
            if (depth != null && depth > 0) {
                throw new IllegalStateException(
                        "Language processing scope cannot close from active work");
            }
            lifecycle.writeLock().lock();
            try {
                if (closed) {
                    return;
                }
                closed = true;
                if (sequenceCache != null) {
                    sequenceCache.close();
                }
            } finally {
                lifecycle.writeLock().unlock();
            }
        }

        private ResolvedSnapshot resolveDocument(
                Node document,
                Set<String> preservedPaths,
                boolean publish) {
            if (preservedPaths.isEmpty()) {
                ResolvedSnapshot cached = lookupRecent(document);
                if (cached != null) {
                    return cached;
                }
            }
            return withResolutionCache(cache -> {
                ResolvedSnapshot resolved = resolveWithCache(
                        document, preservedPaths, cache);
                if (!publish) {
                    return resolved;
                }
                ResolvedSnapshot published = publishSnapshot(
                        resolved, cache);
                snapshotStore.rememberProcessingSnapshot(
                        structuralKey(document), published);
                return published;
            });
        }

        private ResolvedSnapshot lookupRecent(Node document) {
            long started = System.nanoTime();
            try {
                FrozenNode.ResolvedStructuralKey key =
                        structuralKey(document);
                ResolvedSnapshot cached = key != null
                        ? snapshotStore.processingSnapshot(key)
                        : null;
                if (cached != null) {
                    observeHit();
                } else {
                    observeMiss();
                }
                return cached;
            } finally {
                observeNanos(System.nanoTime() - started);
            }
        }

        private BlueOperationResult<FrozenNode> materializeExact(
                FrozenNode reference) {
            if (!reference.isReferenceOnly()) {
                return BlueOperationResult.established(reference);
            }
            String blueId = reference.getReferenceBlueId();
            ResolvedReferenceCache cache = activeCache();
            FrozenNode cached = cache.getVerifiedCanonical(blueId)
                    .orElse(null);
            if (cached != null) {
                return BlueOperationResult.established(cached);
            }

            NodeProviderResult providerResult =
                    nodeProvider.fetchResultByBlueId(blueId);
            if (providerResult.outcome()
                    == NodeProviderOutcome.NOT_FOUND) {
                return BlueOperationResult.absent(
                        "No exact provider content for " + blueId);
            }
            if (providerResult.outcome()
                    == NodeProviderOutcome.UNAVAILABLE) {
                return BlueOperationResult.incomplete(
                        null,
                        Collections.singleton(blueId),
                        NodeProviderOutcome.UNAVAILABLE,
                        providerResult.diagnostic().orElse(
                                "Exact provider content is unavailable for "
                                        + blueId));
            }
            if (providerResult.outcome()
                    == NodeProviderOutcome.INVALID_EVIDENCE) {
                return BlueOperationResult.invalid(
                        providerResult.diagnostic().orElse(
                                "Provider returned invalid exact evidence for "
                                        + blueId),
                        NodeProviderOutcome.INVALID_EVIDENCE);
            }

            try {
                List<Node> nodes = providerResult.nodes();
                Node canonical = nodes.size() == 1
                        ? withoutRootIdentity(nodes.get(0))
                        : new Node().items(
                        withoutRootIdentity(nodes));
                FrozenNode exact = FrozenNode.fromNode(canonical);
                if (BlueIds.hasCyclicMemberSeparator(blueId)) {
                    // Verification has already required the complete set proof.
                    // A member has no independently hashable ordinary identity.
                    return BlueOperationResult.established(exact);
                }
                if (!blueId.equals(exact.blueId())) {
                    return BlueOperationResult.invalid(
                            "Provider content BlueId mismatch for "
                                    + blueId,
                            NodeProviderOutcome.INVALID_EVIDENCE);
                }
                return BlueOperationResult.established(
                        referenceCacheAdmission.mayCacheCanonical(blueId)
                                ? cache.putVerifiedCanonical(blueId, exact)
                                : exact);
            } catch (RuntimeException invalidEvidence) {
                return BlueOperationResult.invalid(
                        invalidEvidence.getMessage(),
                        NodeProviderOutcome.INVALID_EVIDENCE);
            }
        }

        private ResolvedReferenceCache activeCache() {
            return sequenceCache != null
                    ? sequenceCache
                    : snapshotStore.referenceCache();
        }

        private <T> T withResolutionCache(
                CacheWork<T> work) {
            if (sequenceCache != null) {
                return work.apply(sequenceCache);
            }
            ResolvedReferenceCache oneShot =
                    snapshotStore.referenceCache().transientChild();
            try {
                return work.apply(oneShot);
            } finally {
                oneShot.close();
            }
        }

        private <T> T call(Supplier<T> work) {
            return runtime.admitted(() -> {
                lifecycle.readLock().lock();
                Integer previous = operationDepth.get();
                try {
                    ensureOpen();
                    operationDepth.set(
                            previous == null ? 1 : previous + 1);
                    return work.get();
                } finally {
                    if (previous == null) {
                        operationDepth.remove();
                    } else {
                        operationDepth.set(previous);
                    }
                    lifecycle.readLock().unlock();
                }
            });
        }

        private void run(Runnable work) {
            call(() -> {
                work.run();
                return null;
            });
        }

        private void ensureOpen() {
            if (closed) {
                throw new IllegalStateException(
                        "Language processing scope is closed");
            }
        }

        private void observeHit() {
            observe(observer::snapshotCacheHit);
        }

        private void observeMiss() {
            observe(observer::snapshotCacheMiss);
        }

        private void observeNanos(long nanos) {
            observe(() -> observer.snapshotCacheLookupNanos(nanos));
        }
    }

    private ResolvedSnapshot resolveWithCache(
            Node document,
            Set<String> preservedPaths,
            ResolvedReferenceCache cache) {
        Node preprocessed = preprocessor().preprocess(document.clone());
        ResolutionLimits limits = preservedPaths.isEmpty()
                ? ResolutionLimits.NO_LIMITS
                : ResolutionLimits.allOf(
                ResolutionLimits.NO_LIMITS,
                ResolutionLimits.deferringReferencesAt(preservedPaths));
        Node resolved = merger(cache).resolve(
                preprocessed.clone(), limits);
        if (!preservedPaths.isEmpty()) {
            restorePreservedPaths(
                    resolved, preprocessed, preservedPaths);
        }
        FrozenNode canonicalRoot = FrozenNode.fromNode(
                new CanonicalIdentityInputBuilder().build(
                        resolved.clone(), preprocessed));
        FrozenNode resolvedRoot = cache.freezeResolved(resolved);
        return preservedPaths.isEmpty()
                ? new ResolvedSnapshot(
                canonicalRoot, resolvedRoot, canonicalRoot.blueId())
                : ResolvedSnapshot.withDeferredResolution(
                canonicalRoot, resolvedRoot);
    }

    private ResolvedSnapshot applyCanonicalPatch(
            ResolvedSnapshot snapshot,
            BluePatch patch,
            ResolvedReferenceCache cache) {
        CanonicalPatchResult patched =
                new CanonicalOverlayPatchEngine(
                        snapshot.frozenCanonicalRoot()).apply(patch);
        ResolvedSnapshot patchedSnapshot = snapshotFromCanonical(
                patched.root(), cache);
        if (!canMinimizePatchedOverride(patch)) {
            return patchedSnapshot;
        }

        CanonicalPatchResult withoutOverride;
        try {
            withoutOverride = new CanonicalOverlayPatchEngine(
                    patched.root()).apply(
                    ImmutableBluePatch.remove(patched.path()));
        } catch (RuntimeException unavailableInheritance) {
            return patchedSnapshot;
        }
        ResolvedSnapshot inheritedSnapshot = snapshotFromCanonical(
                withoutOverride.root(), cache);
        FrozenNode patchedEffective = patchedSnapshot.resolvedAt(
                patched.path());
        FrozenNode inheritedEffective = inheritedSnapshot.resolvedAt(
                patched.path());
        if (patchedEffective != null
                && inheritedEffective != null
                && patchedEffective.blueId().equals(
                inheritedEffective.blueId())) {
            return inheritedSnapshot;
        }
        return patchedSnapshot;
    }

    private ResolvedSnapshot snapshotFromCanonical(
            FrozenNode canonicalRoot,
            ResolvedReferenceCache cache) {
        Node canonical = canonicalRoot.toNode();
        Node resolved = merger(cache).resolve(
                canonical.clone(), ResolutionLimits.NO_LIMITS);
        return new ResolvedSnapshot(
                canonicalRoot,
                cache.freezeResolved(resolved),
                canonicalRoot.blueId());
    }

    private ResolvedSnapshot publishSnapshot(
            ResolvedSnapshot snapshot,
            ResolvedReferenceCache transientCache) {
        if (!snapshot.isResolutionComplete()) {
            return snapshot;
        }
        if (transientCache != null
                && transientCache.isCurrentGeneration()) {
            transientCache.promoteReferencesReachableFrom(
                    snapshot.frozenCanonicalRoot());
        }
        ResolvedSnapshot published = snapshotStore.derived(snapshot);
        snapshotStore.rememberProcessingSnapshot(
                published.frozenCanonicalRoot()
                        .resolvedStructuralKey(),
                published);
        snapshotStore.rememberProcessingSnapshot(
                published.frozenResolvedRoot()
                        .resolvedStructuralKey(),
                published);
        return published;
    }

    private Merger merger(ResolvedReferenceCache cache) {
        return new Merger(
                mergingProcessor,
                nodeProvider,
                cache,
                referenceCacheAdmission);
    }

    private Preprocessor preprocessor() {
        return new Preprocessor(
                Preprocessor.getStandardProvider(),
                nodeProvider,
                directiveAliases,
                environmentImports);
    }

    private static Set<String> canonicalPreservedPaths(
            Collection<String> preservedPaths) {
        if (preservedPaths == null || preservedPaths.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> canonical = new HashSet<>();
        for (String path : preservedPaths) {
            canonical.add(JsonPointer.canonicalize(path));
        }
        return canonical;
    }

    private static void restorePreservedPaths(
            Node resolved,
            Node source,
            Set<String> paths) {
        for (String path : paths) {
            Node preserved = NodePathEditor.getOrNull(source, path);
            if (preserved != null) {
                NodePathEditor.put(
                        resolved, path, preserved.clone());
            }
        }
    }

    private static FrozenNode.ResolvedStructuralKey structuralKey(
            Node node) {
        try {
            return FrozenNode.fromResolvedNode(node)
                    .resolvedStructuralKey();
        } catch (RuntimeException invalidShape) {
            return null;
        }
    }

    private static Node withoutRootIdentity(Node node) {
        Node canonical = node.clone();
        if (canonical.getBlueId() != null
                && !canonical.isReferenceOnly()) {
            canonical.blueId(null);
        }
        return canonical;
    }

    private static List<Node> withoutRootIdentity(
            List<Node> nodes) {
        List<Node> canonical = new ArrayList<>(nodes.size());
        for (Node node : nodes) {
            canonical.add(withoutRootIdentity(node));
        }
        return canonical;
    }

    private static boolean canMinimizePatchedOverride(
            BluePatch patch) {
        if (patch.operation() == BluePatchOperation.REMOVE
                || patch.path() == null
                || patch.path().isEmpty()
                || JsonPointer.ROOT.equals(patch.path())) {
            return false;
        }
        for (String segment : JsonPointer.split(patch.path())) {
            if (JsonPointer.isArrayIndexSegment(segment)) {
                return false;
            }
        }
        return true;
    }

    private static void observe(Runnable callback) {
        try {
            callback.run();
        } catch (ThreadDeath failure) {
            throw failure;
        } catch (VirtualMachineError failure) {
            throw failure;
        } catch (Throwable ignored) {
            // Telemetry cannot change deterministic Language behavior.
        }
    }

    private interface CacheWork<T> {
        T apply(ResolvedReferenceCache cache);
    }
}
