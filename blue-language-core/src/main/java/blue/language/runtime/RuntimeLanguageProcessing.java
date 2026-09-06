package blue.language.runtime;

import blue.language.api.BlueCachePolicy;
import blue.language.api.BlueOperationOutcome;
import blue.language.api.BlueOperationResult;
import blue.language.api.NodeProviderOutcome;
import blue.language.conformance.ConformanceEngine;
import blue.language.graph.NodeExpander;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.identity.CanonicalTypeIdentityEvidence;
import blue.language.merge.IncrementalMergingProcessorCapability;
import blue.language.merge.IncrementalValueResolutionRequest;
import blue.language.merge.Merger;
import blue.language.merge.MergingProcessor;
import blue.language.merge.ResolvedReferenceCache;
import blue.language.merge.ResolvedSnapshot;
import blue.language.merge.SnapshotResolution;
import blue.language.merge.TypeEvidenceResolution;
import blue.language.model.Node;
import blue.language.model.wire.JsonPointer;
import blue.language.preprocess.Preprocessor;
import blue.language.provider.NodeProvider;
import blue.language.provider.NodeProviderResult;
import blue.language.provider.SequentialNodeProvider;
import blue.language.resolve.ReferenceCacheAdmissionPolicy;
import blue.language.snapshot.BluePatch;
import blue.language.snapshot.BluePatchOperation;
import blue.language.snapshot.CanonicalOverlayPatchEngine;
import blue.language.snapshot.CanonicalPatchResult;
import blue.language.snapshot.FrozenNode;
import blue.language.snapshot.ImmutableBluePatch;
import blue.language.identity.BlueIds;
import blue.language.model.NodePathEditor;
import blue.language.identity.NodeToBlueIdInput;
import blue.language.resolve.ResolutionLimits;
import blue.language.provider.ProviderUnavailableException;
import blue.language.registry.NodeProviderWrapper;

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
final class RuntimeLanguageProcessing extends NodeProviderWrapper
        implements LanguageProcessing {

    private static final Observer NO_OP_OBSERVER = new Observer() {
    };

    private final BlueLanguageRuntime runtime;
    private final NodeProvider nodeProvider;
    private final MergingProcessor mergingProcessor;
    private final LanguageRuntimeSnapshotStore snapshotStore;
    private final Map<String, String> directiveAliases;
    private final Map<String, String> environmentImports;
    private final ReferenceCacheAdmissionPolicy referenceCacheAdmission;
    private final ProcessingScopeLifecycle scopeLifecycle =
            new ProcessingScopeLifecycle();

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
        return runtime.admitted(() -> retainScope(new RuntimeScope(
                Objects.requireNonNull(observer, "observer"),
                nodeProvider,
                null,
                false)));
    }

    @Override
    public Scope openScope(NodeProvider invocationProvider) {
        return openScope(invocationProvider, NO_OP_OBSERVER);
    }

    @Override
    public Scope openScope(
            NodeProvider invocationProvider,
            Observer observer) {
        return runtime.admitted(() -> retainScope(new RuntimeScope(
                Objects.requireNonNull(observer, "observer"),
                verifyOnly(
                        Objects.requireNonNull(
                                invocationProvider,
                                "invocationProvider")),
                new ResolvedReferenceCache(runtime.cachePolicy()),
                true)));
    }

    void closeScopes() {
        scopeLifecycle.closeAll();
    }

    private Scope retainScope(RuntimeScope scope) {
        return scopeLifecycle.retain(scope);
    }

    private final class RuntimeScope implements Scope {

        private final Observer observer;
        private final NodeProvider scopeNodeProvider;
        private final ResolvedReferenceCache sequenceCache;
        private final boolean isolatedProviderDomain;
        private final NodeProvider guardedNodeProvider;
        private final LanguageRuntimeAccess scopedRuntimeAccess;
        private final ProcessingScopeLifecycle.ConformanceEngines
                scopedConformanceEngines =
                new ProcessingScopeLifecycle.ConformanceEngines();
        private final ReentrantReadWriteLock lifecycle =
                new ReentrantReadWriteLock(true);
        private final Object closeMonitor = new Object();
        private final ThreadLocal<Integer> operationDepth =
                new ThreadLocal<>();

        private volatile boolean closed;

        private RuntimeScope(
                Observer observer,
                NodeProvider scopeNodeProvider,
                ResolvedReferenceCache sequenceCache,
                boolean isolatedProviderDomain) {
            this.observer = observer;
            this.scopeNodeProvider = Objects.requireNonNull(
                    scopeNodeProvider, "scopeNodeProvider");
            this.sequenceCache = sequenceCache;
            this.isolatedProviderDomain = isolatedProviderDomain;
            this.guardedNodeProvider = verifyOnlyGuarded(
                    scopeNodeProvider,
                    this::guardProviderOperation);
            this.scopedRuntimeAccess = new ScopeRuntimeAccess();
        }

        @Override
        public LanguageRuntimeAccess runtimeAccess() {
            return call(() -> scopedRuntimeAccess);
        }

        @Override
        public ConformanceEngine newConformanceEngine() {
            return call(() -> retainConformanceEngine(
                    new ConformanceEngine(
                            guardedNodeProvider,
                            mergingProcessor,
                            activeCache())));
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
        public ResolvedSnapshot resolveCanonicalTransient(
                FrozenNode canonicalRoot, Collection<String> preservedPaths) {
            return call(() -> withResolutionCache(cache -> {
                FrozenNode canonical = Objects.requireNonNull(canonicalRoot, "canonicalRoot");
                Set<String> paths = canonicalPreservedPaths(preservedPaths);
                ResolutionLimits limits = paths.isEmpty() ? ResolutionLimits.NO_LIMITS
                        : ResolutionLimits.allOf(ResolutionLimits.NO_LIMITS,
                                ResolutionLimits.deferringReferencesAt(paths));
                ResolvedSnapshot snapshot = ResolvedSnapshot.fromResolverResult(
                        merger(scopeNodeProvider, cache).resolveSnapshot(canonical, limits));
                if (paths.isEmpty()) return snapshot;
                Node resolved = snapshot.resolvedRoot();
                restorePreservedPaths(resolved, canonical.toNode(), paths, true);
                return ResolvedSnapshot.withDeferredResolution(canonical,
                        cache.freezeResolved(resolved), snapshot.canonicalTypeIdentities());
            }));
        }

        @Override
        public ResolvedSnapshot resolveTransientForCanonicalIdentity(
                Node document,
                ExactResolutionOverlay exactResolutionOverlay) {
            return call(() -> {
                ResolvedReferenceCache identityCache =
                        new ResolvedReferenceCache(runtime.cachePolicy());
                try {
                    ExactResolutionOverlay overlay = Objects.requireNonNull(
                            exactResolutionOverlay,
                            "exactResolutionOverlay");
                    ResolvedSnapshot snapshot = resolveWithCache(
                            Objects.requireNonNull(document, "document"),
                            Collections.<String>emptySet(),
                            identityCache,
                            overlay.verifiedBefore(scopeNodeProvider));
                    snapshot.canonicalTypeIdentities()
                            .requireCompleteCoverage();
                    if (!snapshot.hasCanonicalIdentity()) {
                        throw new IllegalStateException(
                                "Language canonical identity resolution did "
                                        + "not establish a whole-document "
                                        + "identity");
                    }
                    return snapshot;
                } finally {
                    identityCache.close();
                }
            });
        }

        @Override
        public CanonicalTypeIdentityEvidence resolveTypeDeclarationIdentity(
                Node declaration) {
            return call(() -> RuntimeLanguageProcessing.this
                    .resolveTypeDeclarationIdentity(
                    Objects.requireNonNull(declaration, "declaration"),
                    activeCache(),
                    scopeNodeProvider));
        }

        @Override
        public CanonicalTypeIdentityEvidence resolveTypeDeclarationIdentity(
                Node declaration,
                ExactResolutionOverlay exactResolutionOverlay) {
            return call(() -> {
                ResolvedReferenceCache identityCache =
                        new ResolvedReferenceCache(runtime.cachePolicy());
                try {
                    ExactResolutionOverlay overlay = Objects.requireNonNull(
                            exactResolutionOverlay,
                            "exactResolutionOverlay");
                    return RuntimeLanguageProcessing.this
                            .resolveTypeDeclarationIdentity(
                            Objects.requireNonNull(declaration, "declaration"),
                            identityCache,
                            overlay.verifiedBefore(scopeNodeProvider));
                } finally {
                    identityCache.close();
                }
            });
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
            return call(() -> retainScope(new RuntimeScope(
                    observer,
                    scopeNodeProvider,
                    activeCache().transientChild(),
                    isolatedProviderDomain)));
        }

        @Override
        public Scope forkTransientSequence() {
            return call(() -> retainScope(new RuntimeScope(
                    observer,
                    scopeNodeProvider,
                    sequenceCache == null
                            ? activeCache().transientChild()
                            : sequenceCache.forkTransient(),
                    isolatedProviderDomain)));
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
                if (isolatedProviderDomain) {
                    return retainConformanceEngine(
                            new ConformanceEngine(
                                    guardedNodeProvider,
                                    mergingProcessor,
                                    activeCache()));
                }
                ConformanceEngine view = sequenceCache != null
                        ? conformanceEngine.transientView(sequenceCache)
                        : conformanceEngine.transientView();
                return view == conformanceEngine
                        ? view
                        : retainConformanceEngine(view);
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
                            cache,
                            scopeNodeProvider)));
        }

        @Override
        public ResolvedSnapshot publish(ResolvedSnapshot snapshot) {
            return call(() -> publishSnapshot(
                    Objects.requireNonNull(snapshot, "snapshot"),
                    sequenceCache,
                    isolatedProviderDomain));
        }

        @Override
        public void close() {
            Integer depth = operationDepth.get();
            if (depth != null && depth > 0) {
                throw new IllegalStateException(
                        "Language processing scope cannot close from active work");
            }
            synchronized (closeMonitor) {
                lifecycle.writeLock().lock();
                try {
                    if (closed) {
                        return;
                    }
                    closed = true;
                } finally {
                    lifecycle.writeLock().unlock();
                }
                try {
                    scopedConformanceEngines.closeAll();
                    lifecycle.writeLock().lock();
                    try {
                        if (sequenceCache != null) {
                            sequenceCache.close();
                        }
                    } finally {
                        lifecycle.writeLock().unlock();
                    }
                } finally {
                    scopeLifecycle.release(this);
                }
            }
        }

        private ConformanceEngine retainConformanceEngine(
                ConformanceEngine engine) {
            return scopedConformanceEngines.retain(engine);
        }

        private ResolvedSnapshot resolveDocument(
                Node document,
                Set<String> preservedPaths,
                boolean publish) {
            if (!isolatedProviderDomain
                    && preservedPaths.isEmpty()) {
                ResolvedSnapshot cached = lookupRecent(document);
                if (cached != null) {
                    return cached;
                }
            }
            return withResolutionCache(cache -> {
                ResolvedSnapshot resolved = resolveWithCache(
                        document,
                        preservedPaths,
                        cache,
                        scopeNodeProvider);
                if (!publish) {
                    return resolved;
                }
                ResolvedSnapshot published = publishSnapshot(
                        resolved,
                        cache,
                        isolatedProviderDomain);
                if (!isolatedProviderDomain) {
                    snapshotStore.rememberProcessingSnapshot(
                            structuralKey(document), published);
                }
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
                    scopeNodeProvider.fetchResultByBlueId(blueId);
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
                enterScopeOperation();
                try {
                    return work.get();
                } finally {
                    exitScopeOperation();
                }
            });
        }

        private void guardProviderOperation(Runnable providerCall) {
            run(providerCall);
        }

        private void enterScopeOperation() {
            lifecycle.readLock().lock();
            Integer previous = operationDepth.get();
            try {
                ensureOpen();
                operationDepth.set(
                        previous == null ? 1 : previous + 1);
            } catch (RuntimeException failure) {
                lifecycle.readLock().unlock();
                throw failure;
            } catch (Error failure) {
                lifecycle.readLock().unlock();
                throw failure;
            }
        }

        private void exitScopeOperation() {
            Integer depth = operationDepth.get();
            if (depth == null || depth <= 1) {
                operationDepth.remove();
            } else {
                operationDepth.set(depth - 1);
            }
            lifecycle.readLock().unlock();
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

        private Node canonicalizeInScope(Node source) {
            Node preprocessed = preprocessor(scopeNodeProvider).preprocess(
                    Objects.requireNonNull(source, "source").clone());
            return merger(
                    scopeNodeProvider,
                    activeCache()).resolveSnapshot(
                    preprocessed,
                    ResolutionLimits.NO_LIMITS).canonicalRoot().toNode();
        }

        private TypeEvidenceResolution materializeTypeReferenceEvidence(
                FrozenNode reference) {
            Objects.requireNonNull(reference, "reference");
            if (!reference.isReferenceOnly()
                    || reference.getReferenceBlueId() == null) {
                throw new IllegalArgumentException(
                        "Matching materialization requires a pure reference");
            }
            TypeEvidenceResolution wrapper = merger(
                    scopeNodeProvider,
                    activeCache()).materializeTypeReferenceEvidence(
                    reference,
                    ResolutionLimits.NO_LIMITS);
            FrozenNode materializedType = wrapper.resolvedRoot().getType();
            if (materializedType == null
                    || materializedType.isReferenceOnly()) {
                return null;
            }
            return new TypeEvidenceResolution(
                    materializedType,
                    wrapper.canonicalTypeIdentities());
        }

        private final class ScopeRuntimeAccess
                implements LanguageRuntimeAccess {
            @Override
            public NodeProvider getNodeProvider() {
                return call(() -> guardedNodeProvider);
            }

            @Override
            public BlueCachePolicy matchingCachePolicy() {
                return call(runtime::matchingCachePolicy);
            }

            @Override
            public BlueCachePolicy cachePolicy() {
                return call(runtime::cachePolicy);
            }

            @Override
            public String languageVersion() {
                return call(runtime::languageVersion);
            }

            @Override
            public Map<String, String> preprocessingAliases() {
                return call(runtime::preprocessingAliases);
            }

            @Override
            public Map<String, String> environmentImports() {
                return call(runtime::environmentImports);
            }

            @Override
            public String canonicalRegistryIdentity() {
                return call(runtime::canonicalRegistryIdentity);
            }

            @Override
            public Node canonicalizeSourceContent(Node source) {
                return call(() -> canonicalizeInScope(source));
            }

            @Override
            public Node preprocessForMatching(Node source) {
                return call(() -> preprocessor(
                        scopeNodeProvider).preprocess(
                        Objects.requireNonNull(source, "source").clone()));
            }

            @Override
            public void expandForMatching(
                    Node source,
                    ResolutionLimits limits) {
                run(() -> new NodeExpander(scopeNodeProvider).expand(
                        Objects.requireNonNull(source, "source"),
                        Objects.requireNonNull(limits, "limits")));
            }

            @Override
            public blue.language.merge.TypeEvidenceResolution
            resolveTypeEvidenceForMatching(
                    Node source,
                    ResolutionLimits limits) {
                return call(() -> merger(
                        scopeNodeProvider,
                        activeCache()).resolveTypeEvidence(
                        Objects.requireNonNull(source, "source").clone(),
                        Objects.requireNonNull(limits, "limits")));
            }

            @Override
            public TypeEvidenceResolution materializeTypeReferenceForMatching(
                    FrozenNode reference) {
                return call(() -> materializeTypeReferenceEvidence(reference));
            }

            @Override
            public Node canonicalize(Node source) {
                return call(() -> canonicalizeInScope(source));
            }

            @Override
            public String calculateSourceDocumentBlueId(Node source) {
                return call(() -> DirectBlueIdCalculator.calculateBlueId(
                        canonicalizeInScope(source)));
            }
        }
    }

    private ResolvedSnapshot resolveWithCache(
            Node document,
            Set<String> preservedPaths,
            ResolvedReferenceCache cache,
            NodeProvider provider) {
        Node preprocessed = preprocessor(provider).preprocess(
                document.clone());
        // A preserved subtree remains exact authored input. One
        // source-structure-preserving resolution establishes every reachable
        // materialized type identity while terminating cold subgraphs at
        // their exact pure references; it must not validate a partial matcher
        // as though it were a complete value in a separate unlimited pass.
        Merger scopedMerger = merger(provider, cache);
        if (preservedPaths.isEmpty()) {
            return ResolvedSnapshot.fromSourceResolverResult(
                    FrozenNode.fromSourceNode(preprocessed),
                    scopedMerger.resolveSnapshot(
                            preprocessed.clone(),
                            ResolutionLimits.NO_LIMITS));
        }
        ResolutionLimits limits = ResolutionLimits.allOf(
                ResolutionLimits.NO_LIMITS,
                ResolutionLimits.deferringReferencesAt(preservedPaths));
        TypeEvidenceResolution projection = scopedMerger.resolveTypeEvidence(
                preprocessed.clone(), limits);
        Node resolved = projection.resolvedRoot().toNode();
        restorePreservedPaths(resolved, preprocessed, preservedPaths);
        FrozenNode resolvedRoot = cache.freezeResolved(resolved);
        return ResolvedSnapshot.withDeferredSource(
                FrozenNode.fromSourceNode(preprocessed),
                resolvedRoot,
                projection.canonicalTypeIdentities());
    }

    private CanonicalTypeIdentityEvidence resolveTypeDeclarationIdentity(
            Node declaration,
            ResolvedReferenceCache cache,
            NodeProvider provider) {
        Node preprocessedWrapper = preprocessor(provider).preprocess(
                new Node().type(declaration.clone()));
        Node authoredType = Objects.requireNonNull(
                preprocessedWrapper.getType(),
                "preprocessedTypeDeclaration");
        TypeEvidenceResolution resolution = merger(provider, cache)
                .resolveTypeDeclarationEvidence(
                        authoredType,
                        ResolutionLimits.NO_LIMITS);
        Node completedType = resolution.resolvedRoot().toNode();
        return resolution.canonicalTypeIdentities()
                .findCanonicalTypeIdentityEvidence(
                        completedType,
                        authoredType)
                .orElseThrow(() -> new IllegalStateException(
                        "Language did not establish canonical identity evidence "
                                + "for the authored type declaration"));
    }

    private ResolvedSnapshot applyCanonicalPatch(
            ResolvedSnapshot snapshot,
            BluePatch patch,
            ResolvedReferenceCache cache,
            NodeProvider provider) {
        CanonicalPatchResult patched =
                new CanonicalOverlayPatchEngine(
                        snapshot.frozenCanonicalRoot()).apply(patch);
        ResolvedSnapshot patchedSnapshot = snapshotFromCanonical(
                patched.root(), cache, provider);
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
                withoutOverride.root(), cache, provider);
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
            ResolvedReferenceCache cache,
            NodeProvider provider) {
        return ResolvedSnapshot.fromResolverResult(
                merger(provider, cache).resolveSnapshot(
                        canonicalRoot,
                        ResolutionLimits.NO_LIMITS));
    }

    private ResolvedSnapshot publishSnapshot(
            ResolvedSnapshot snapshot,
            ResolvedReferenceCache transientCache,
            boolean isolatedProviderDomain) {
        if (!snapshot.isResolutionComplete()) {
            return snapshot;
        }
        if (isolatedProviderDomain) {
            return snapshot.toStrictBlueIdValidatedCanonical();
        }
        if (transientCache != null
                && transientCache.isCurrentGeneration()) {
            transientCache.promoteReferencesReachableFrom(
                    snapshot.frozenCanonicalRoot());
        }
        ResolvedSnapshot published = snapshotStore.derived(snapshot);
        ResolvedSnapshot shared = published.toCanonicalBacked();
        snapshotStore.rememberProcessingSnapshot(
                shared.frozenCanonicalRoot()
                        .resolvedStructuralKey(),
                shared);
        snapshotStore.rememberProcessingSnapshot(
                shared.frozenResolvedRoot()
                        .resolvedStructuralKey(),
                shared);
        return published;
    }

    private Merger merger(
            NodeProvider provider,
            ResolvedReferenceCache cache) {
        return new Merger(
                mergingProcessor,
                provider,
                cache,
                referenceCacheAdmission);
    }

    private Preprocessor preprocessor(NodeProvider provider) {
        return new Preprocessor(
                Preprocessor.getStandardProvider(),
                provider,
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
        restorePreservedPaths(resolved, source, paths, false);
    }

    private static void restorePreservedPaths(
            Node resolved, Node source, Set<String> paths, boolean canonicalInput) {
        for (String path : paths) {
            Node preserved = NodePathEditor.getOrNull(source, path);
            if (preserved != null) {
                Node retained = preserved.clone();
                if (canonicalInput) {
                    // Canonical projection can compact core scalar types. Restore
                    // their parsed representation without expanding references or
                    // interpreting the retained executable body as Source input.
                    retained = new blue.language.preprocess.InferBasicTypesForUntypedValues().process(retained);
                }
                NodePathEditor.put(resolved, path, retained);
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
