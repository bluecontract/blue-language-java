package blue.language.runtime;

import blue.language.api.BlueCachePolicy;
import blue.language.api.BlueCacheStats;
import blue.language.api.BlueOperationLimits;
import blue.language.api.BlueOperationResult;
import blue.language.codec.BlueCodec;
import blue.language.codec.StandardBlueCodec;
import blue.language.conformance.ConformanceEngine;
import blue.language.graph.BlueGraph;
import blue.language.graph.StandardBlueGraph;
import blue.language.identity.BlueIdentity;
import blue.language.identity.CanonicalTypeIdentityEvidence;
import blue.language.identity.StandardBlueIdentity;
import blue.language.matching.BlueMatching;
import blue.language.matching.MatchingRuntime;
import blue.language.merge.Merger;
import blue.language.merge.MergingProcessor;
import blue.language.merge.NodeResolver;
import blue.language.merge.SnapshotResolution;
import blue.language.merge.processor.BasicTypesVerifier;
import blue.language.merge.processor.DictionaryProcessor;
import blue.language.merge.processor.ListProcessor;
import blue.language.merge.processor.SchemaPropagator;
import blue.language.merge.processor.SchemaVerifier;
import blue.language.merge.processor.SequentialMergingProcessor;
import blue.language.merge.processor.TypeAssigner;
import blue.language.merge.processor.ValuePropagator;
import blue.language.model.Node;
import blue.language.snapshot.BluePatch;
import blue.language.snapshot.BluePatchOperation;
import blue.language.patching.BluePatching;
import blue.language.snapshot.ImmutableBluePatch;
import blue.language.preprocess.BluePreprocessing;
import blue.language.preprocess.Preprocessor;
import blue.language.preprocess.StandardBluePreprocessing;
import blue.language.provider.NodeProvider;
import blue.language.provider.SourceContentVerificationRuntime;
import blue.language.registry.BlueCoreTypeRegistry;
import blue.language.resolve.BlueResolution;
import blue.language.resolve.ReferenceCacheAdmissionPolicy;
import blue.language.merge.BlueSnapshots;
import blue.language.snapshot.CanonicalOverlayPatchEngine;
import blue.language.snapshot.CanonicalPatchResult;
import blue.language.snapshot.FrozenNode;
import blue.language.merge.ResolvedSnapshot;
import blue.language.merge.TypeEvidenceResolution;
import blue.language.model.wire.JsonPointer;
import blue.language.resolve.MinimizedOverlayBuilder;
import blue.language.model.NodePathEditor;
import blue.language.identity.NodeToBlueIdInput;
import blue.language.matching.NodeTypeMatcher;
import blue.language.provider.Types;
import blue.language.resolve.ResolutionLimits;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

import static blue.language.resolve.ResolutionLimits.NO_LIMITS;

/**
 * Immutable, Language-only runtime owned by the focused service composition.
 *
 * <p>The runtime contains no Contracts, fixture-conformance, mapping, or
 * aggregate-facade dependency. It is therefore the narrow dependency boundary
 * for hosts that need provider access, cache policy, identity, resolution,
 * snapshots, matching, patching, or semantic generalization without depending
 * on the legacy aggregate facade.</p>
 *
 * <p>Configuration is frozen at creation. Runtime-owned caches are bounded by
 * the supplied policy. Close waits for admitted operations, clears all owned
 * state, and causes subsequent semantic operations to fail.</p>
 */
public final class BlueLanguageRuntime implements NodeResolver,
        LanguageRuntimeAccess, MatchingRuntime,
        SourceContentVerificationRuntime, AutoCloseable {

    private static final ReferenceCacheAdmissionPolicy
            REFERENCE_CACHE_ADMISSION = blueId -> true;

    private final NodeProvider nodeProvider;
    private final BlueCachePolicy cachePolicy;
    private final ReferenceCacheAdmissionPolicy referenceCacheAdmission;
    private final Map<String, String> preprocessingAliases;
    private final Map<String, String> environmentImports;
    private final MergingProcessor mergingProcessor;
    private final LanguageRuntimeSnapshotStore snapshotsStore;
    private final ReentrantReadWriteLock lifecycle =
            new ReentrantReadWriteLock(true);
    private final Object closeMonitor = new Object();
    private final ThreadLocal<Integer> operationDepth =
            new ThreadLocal<>();

    private final BlueCodec codec;
    private final BluePreprocessing preprocessing;
    private final BlueGraph graph;
    private final BlueResolution resolution;
    private final BlueIdentity identity;
    private final BlueSnapshots snapshots;
    private final BlueMatching matching;
    private final BluePatching patching;
    private final RuntimeLanguageProcessing processing;

    private volatile boolean closed;

    private BlueLanguageRuntime(NodeProvider nodeProvider,
                                BlueCachePolicy cachePolicy,
                                Map<String, String> preprocessingAliases,
                                Map<String, String> environmentImports,
                                ReferenceCacheAdmissionPolicy
                                        referenceCacheAdmission) {
        this.nodeProvider = blue.language.registry.NodeProviderWrapper.unverified(
                Objects.requireNonNull(nodeProvider, "nodeProvider"));
        this.cachePolicy = Objects.requireNonNull(
                cachePolicy, "cachePolicy");
        this.referenceCacheAdmission = Objects.requireNonNull(
                referenceCacheAdmission,
                "referenceCacheAdmission");
        this.preprocessingAliases = immutableAliases(
                preprocessingAliases);
        this.environmentImports = immutableAliases(
                environmentImports);
        this.mergingProcessor = defaultMergingProcessor();
        this.snapshotsStore = new LanguageRuntimeSnapshotStore(cachePolicy);

        Preprocessor preprocessor = new Preprocessor(
                Preprocessor.getStandardProvider(),
                this.nodeProvider,
                this.preprocessingAliases,
                this.environmentImports);
        String environmentIdentity =
                LanguageRuntimeServices.preprocessingEnvironmentIdentity(
                        this.preprocessingAliases,
                        this.environmentImports);
        this.codec = new StandardBlueCodec();
        this.preprocessing = new RuntimeBluePreprocessing(
                this,
                new StandardBluePreprocessing(
                        preprocessor, environmentIdentity));
        this.graph = new RuntimeBlueGraph(
                this,
                new StandardBlueGraph(this.nodeProvider, this));
        this.resolution = new RuntimeBlueResolution(this);
        this.identity = new RuntimeBlueIdentity(
                this,
                new StandardBlueIdentity(this::canonicalize));
        this.snapshots = new RuntimeBlueSnapshots(this);
        this.matching = new RuntimeBlueMatching(this);
        this.patching = new RuntimeBluePatching(this);
        this.processing = new RuntimeLanguageProcessing(
                this,
                this.nodeProvider,
                this.mergingProcessor,
                this.snapshotsStore,
                this.preprocessingAliases,
                this.environmentImports,
                this.referenceCacheAdmission);
    }

    /**
     * Creates one independently owned immutable Language runtime.
     *
     * @param nodeProvider borrowed external-content provider
     * @param cachePolicy runtime-owned cache bounds
     * @param preprocessingAliases explicit directive aliases to freeze
     * @return a new focused runtime
     */
    public static BlueLanguageRuntime create(
            NodeProvider nodeProvider,
            BlueCachePolicy cachePolicy,
            Map<String, String> preprocessingAliases) {
        return new BlueLanguageRuntime(
                nodeProvider,
                cachePolicy,
                preprocessingAliases,
                Collections.<String, String>emptyMap(),
                REFERENCE_CACHE_ADMISSION);
    }

    /**
     * Creates a Language runtime with explicit host environment imports.
     *
     * <p>Environment imports supplement canonical Language aliases during
     * preprocessing. They are frozen at construction and become part of the
     * preprocessing environment identity.</p>
     *
     * @param nodeProvider borrowed external-content provider
     * @param cachePolicy runtime-owned cache bounds
     * @param preprocessingAliases explicit directive aliases to freeze
     * @param environmentImports host type aliases mapped to exact BlueIds
     * @return a new focused runtime
     */
    public static BlueLanguageRuntime create(
            NodeProvider nodeProvider,
            BlueCachePolicy cachePolicy,
            Map<String, String> preprocessingAliases,
            Map<String, String> environmentImports) {
        return new BlueLanguageRuntime(
                nodeProvider,
                cachePolicy,
                preprocessingAliases,
                environmentImports,
                REFERENCE_CACHE_ADMISSION);
    }

    /**
     * Creates a Language runtime with an explicit verified-reference cache
     * admission boundary.
     *
     * <p>The policy changes retained evidence and later cache reuse only; it
     * cannot change resolution results, identities, or diagnostics for the
     * same provider evidence. Excluded references may be read again by a later
     * operation. The default {@link #create(NodeProvider, BlueCachePolicy, Map)}
     * overload admits all verified references.</p>
     *
     * @param nodeProvider borrowed external-content provider
     * @param cachePolicy runtime-owned cache bounds
     * @param preprocessingAliases explicit directive aliases to freeze
     * @param referenceCacheAdmission retention policy for verified references
     * @return a new focused runtime
     */
    public static BlueLanguageRuntime create(
            NodeProvider nodeProvider,
            BlueCachePolicy cachePolicy,
            Map<String, String> preprocessingAliases,
            ReferenceCacheAdmissionPolicy referenceCacheAdmission) {
        return new BlueLanguageRuntime(
                nodeProvider,
                cachePolicy,
                preprocessingAliases,
                Collections.<String, String>emptyMap(),
                referenceCacheAdmission);
    }

    /**
     * Creates a Language runtime with explicit host imports and cache
     * admission.
     *
     * @param nodeProvider borrowed external-content provider
     * @param cachePolicy runtime-owned cache bounds
     * @param preprocessingAliases explicit directive aliases to freeze
     * @param environmentImports host type aliases mapped to exact BlueIds
     * @param referenceCacheAdmission retention policy for verified references
     * @return a new focused runtime
     */
    static BlueLanguageRuntime create(
            NodeProvider nodeProvider,
            BlueCachePolicy cachePolicy,
            Map<String, String> preprocessingAliases,
            Map<String, String> environmentImports,
            ReferenceCacheAdmissionPolicy referenceCacheAdmission) {
        return new BlueLanguageRuntime(
                nodeProvider,
                cachePolicy,
                preprocessingAliases,
                environmentImports,
                referenceCacheAdmission);
    }

    /**
     * Returns the stateless strict JSON/YAML codec.
     *
     * @return runtime codec service
     */
    public BlueCodec codec() {
        return codec;
    }

    /**
     * Returns the configured preprocessing service.
     *
     * @return runtime preprocessing service
     */
    public BluePreprocessing preprocessing() {
        return preprocessing;
    }

    /**
     * Returns exact expansion, collapse, and specialization operations.
     *
     * @return runtime graph service
     */
    public BlueGraph graph() {
        return graph;
    }

    /**
     * Returns complete and demand-limited resolution operations.
     *
     * @return runtime resolution service
     */
    public BlueResolution resolution() {
        return resolution;
    }

    /**
     * Returns direct, Source Document, and cyclic-set identity operations.
     *
     * @return runtime identity service
     */
    public BlueIdentity identity() {
        return identity;
    }

    /**
     * Returns immutable snapshot and cache operations.
     *
     * @return runtime snapshot service
     */
    public BlueSnapshots snapshots() {
        return snapshots;
    }

    /**
     * Returns mutable and immutable matching operations.
     *
     * @return runtime matching service
     */
    public BlueMatching matching() {
        return matching;
    }

    /**
     * Returns immutable canonical patching operations.
     *
     * @return runtime patching service
     */
    public BluePatching patching() {
        return patching;
    }

    /** Returns the Language-owned document-processing bridge. */
    LanguageProcessing processing() {
        return processing;
    }

    /**
     * Creates an independently owned semantic conformance engine using this
     * runtime's frozen provider, merge pipeline, and cache bounds.
     *
     * <p>The returned engine owns its isolated cache and may be closed without
     * affecting this runtime. Creating a handle after this runtime is closed is
     * rejected in the same way as every other admitted runtime operation.</p>
     *
     * @return independently closeable semantic conformance engine
     */
    public ConformanceEngine newConformanceEngine() {
        return call(() -> ConformanceEngine.withIsolatedCache(
                nodeProvider, mergingProcessor, cachePolicy));
    }

    /**
     * Returns the verified provider graph selected for this runtime.
     *
     * @return borrowed provider selected at construction
     */
    public NodeProvider nodeProvider() {
        return nodeProvider;
    }

    /** Returns the verified provider graph through the host access contract. */
    @Override
    public NodeProvider getNodeProvider() {
        return nodeProvider;
    }

    /** Returns the immutable cache policy selected for this runtime. */
    @Override
    public BlueCachePolicy matchingCachePolicy() {
        return cachePolicy;
    }

    /** Alias for hosts that need the runtime's general cache policy. */
    public BlueCachePolicy cachePolicy() {
        return cachePolicy;
    }

    /** Reports the implemented Language specification version. */
    @Override
    public String languageVersion() {
        return "1.0";
    }

    /** Returns the frozen explicit preprocessing aliases. */
    @Override
    public Map<String, String> preprocessingAliases() {
        return preprocessingAliases;
    }

    /** Returns the frozen host aliases imported during preprocessing. */
    @Override
    public Map<String, String> environmentImports() {
        return environmentImports;
    }

    /** Canonicalizes Source content under the released core environment. */
    @Override
    public Node canonicalizeSourceContent(Node source) {
        return canonicalize(source);
    }

    /** Returns the canonical core-registry identity used by this runtime. */
    @Override
    public String canonicalRegistryIdentity() {
        return BlueCoreTypeRegistry.INSTANCE.packageIdentity();
    }

    /** Applies the configured preprocessing environment for matching. */
    @Override
    public Node preprocessForMatching(Node source) {
        return preprocess(source);
    }

    /** Expands a mutable matching candidate under target-driven limits. */
    @Override
    public void expandForMatching(Node source, ResolutionLimits limits) {
        run(() -> new blue.language.graph.NodeExpander(nodeProvider)
                .expand(source, limits));
    }

    /** Resolves a matching candidate and retains invocation type evidence. */
    @Override
    public blue.language.merge.TypeEvidenceResolution
    resolveTypeEvidenceForMatching(
            Node source,
            ResolutionLimits limits) {
        return call(() -> merger(nodeProvider).resolveTypeEvidence(
                Objects.requireNonNull(source, "source").clone(),
                Objects.requireNonNull(limits, "limits")));
    }

    /** Materializes one pure verified type reference for matching. */
    @Override
    public TypeEvidenceResolution materializeTypeReferenceForMatching(
            FrozenNode reference) {
        return call(() -> materializeTypeReferenceEvidence(reference));
    }

    /** Resolves already-preprocessed input under the supplied limits. */
    @Override
    public Node resolve(Node source, ResolutionLimits limits) {
        return call(() -> merger(nodeProvider).resolve(
                Objects.requireNonNull(source, "source").clone(),
                Objects.requireNonNull(limits, "limits")));
    }

    /**
     * Resolves already-preprocessed input and returns the canonical type
     * evidence established by that same resolver invocation.
     *
     * @param source non-null source graph; it is not mutated
     * @param limits non-null traversal and reference-expansion budget
     * @return immutable resolved graph and invocation-local type evidence
     * @throws NullPointerException if {@code source} or {@code limits} is null
     * @throws IllegalArgumentException if the graph contains invalid reference
     *         or type metadata
     * @throws IllegalStateException if the runtime is closed or exact type
     *         evidence cannot be established
     */
    @Override
    public TypeEvidenceResolution resolveTypeEvidence(
            Node source,
            ResolutionLimits limits) {
        return call(() -> merger(nodeProvider).resolveTypeEvidence(
                Objects.requireNonNull(source, "source").clone(),
                Objects.requireNonNull(limits, "limits")));
    }

    /**
     * Resolves an authored type declaration without applying instance rules.
     *
     * @param declaration authored inline declaration or pure reference
     * @return exact canonical identity and authored-representation evidence
     * @throws NullPointerException if {@code declaration} is null
     * @throws IllegalArgumentException if declaration or provider evidence is
     *         invalid
     * @throws IllegalStateException if the runtime is closed or exact evidence
     *         cannot be established
     */
    public CanonicalTypeIdentityEvidence resolveTypeDeclarationIdentity(
            Node declaration) {
        return call(() -> {
            Node preprocessedWrapper = rawPreprocess(
                    new Node().type(Objects.requireNonNull(
                            declaration, "declaration").clone()));
            Node authoredType = Objects.requireNonNull(
                    preprocessedWrapper.getType(),
                    "preprocessedTypeDeclaration");
            TypeEvidenceResolution resolution = merger(nodeProvider)
                    .resolveTypeDeclarationEvidence(
                            authoredType,
                            NO_LIMITS);
            return resolution.canonicalTypeIdentities()
                    .findCanonicalTypeIdentityEvidence(
                            resolution.resolvedRoot().toNode(),
                            authoredType)
                    .orElseThrow(() -> new IllegalStateException(
                            "Language did not establish canonical identity "
                                    + "evidence for the authored type declaration"));
        });
    }

    /**
     * Resolves an authored declaration against verified operation-local exact
     * evidence followed by this runtime's provider graph.
     *
     * @param declaration authored inline declaration or pure reference
     * @param exactResolutionOverlay operation-local exact evidence
     * @return exact canonical identity and authored-representation evidence
     * @throws NullPointerException if an argument is null
     * @throws IllegalArgumentException if declaration or provider evidence is
     *         invalid
     * @throws IllegalStateException if the runtime is closed or exact evidence
     *         cannot be established
     */
    public CanonicalTypeIdentityEvidence resolveTypeDeclarationIdentity(
            Node declaration,
            LanguageProcessing.ExactResolutionOverlay exactResolutionOverlay) {
        try (LanguageProcessing.Scope scope = processing.openScope()) {
            return scope.resolveTypeDeclarationIdentity(
                    Objects.requireNonNull(declaration, "declaration"),
                    Objects.requireNonNull(
                            exactResolutionOverlay,
                            "exactResolutionOverlay"));
        }
    }

    /**
     * Returns whether close has released runtime-owned state.
     *
     * @return {@code true} after terminal shutdown
     */
    public boolean isClosed() {
        return closed;
    }

    /**
     * Releases runtime-owned caches after all admitted operations complete.
     * Closing from inside an admitted operation is rejected to avoid a lock
     * upgrade that would wait for itself. Concurrent close callers serialize
     * through the complete teardown after this reentrancy check, so an active
     * provider callback can always fail fast instead of waiting on a closer
     * that is itself waiting for that callback.
     */
    @Override
    public void close() {
        Integer depth = operationDepth.get();
        if (depth != null && depth > 0) {
            throw new IllegalStateException(
                    "Blue Language runtime cannot close from active work");
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
            processing.closeScopes();
            lifecycle.writeLock().lock();
            try {
                snapshotsStore.close();
            } finally {
                lifecycle.writeLock().unlock();
            }
        }
    }

    Node preprocess(Node source) {
        return call(() -> rawPreprocess(
                Objects.requireNonNull(source, "source")));
    }

    @Override
    public Node canonicalize(Node source) {
        return call(() -> {
            Node preprocessed = rawPreprocess(
                    Objects.requireNonNull(source, "source").clone());
            if (preprocessed.isReferenceOnly()) {
                return preprocessed;
            }
            TypeEvidenceResolution definition = merger(nodeProvider)
                    .resolveTypeDeclarationEvidence(preprocessed, NO_LIMITS);
            return new blue.language.identity.CanonicalIdentityInputBuilder().build(
                    definition.resolvedRoot().toNode(), preprocessed,
                    definition.canonicalTypeIdentities());
        });
    }

    /** Calculates Source identity through this runtime's frozen environment. */
    @Override
    public String calculateSourceDocumentBlueId(Node source) {
        return identity.sourceDocumentBlueId(source);
    }

    Node resolveAuthored(Node source) {
        return call(() -> rawResolve(
                rawPreprocess(Objects.requireNonNull(
                        source, "source").clone()),
                NO_LIMITS));
    }

    Node resolveDefinition(Node source) {
        return call(() -> merger(nodeProvider).resolveTypeDeclarationEvidence(
                rawPreprocess(Objects.requireNonNull(source, "source").clone()),
                NO_LIMITS).resolvedRoot().toNode());
    }

    Node resolvePreservingPaths(
            Node source,
            Collection<String> preservedPaths) {
        return call(() -> {
            Node preprocessed = rawPreprocess(
                    Objects.requireNonNull(source, "source").clone());
            Set<String> paths = canonicalPreservedPaths(
                    preservedPaths);
            if (paths.isEmpty()) {
                return rawResolve(preprocessed, NO_LIMITS);
            }
            if (paths.contains(JsonPointer.ROOT)) {
                return preprocessed;
            }
            Node resolved = rawResolve(
                    preprocessed.clone(),
                    ResolutionLimits.excluding(paths));
            for (String path : paths) {
                Node preserved = NodePathEditor.getOrNull(
                        preprocessed, path);
                if (preserved != null) {
                    NodePathEditor.put(
                            resolved, path, preserved.clone());
                }
            }
            return resolved;
        });
    }

    Node minimize(Node source) {
        return call(() -> {
            Node preprocessed = rawPreprocess(Objects.requireNonNull(source, "source").clone());
            if (preprocessed.isReferenceOnly()) {
                return preprocessed;
            }
            TypeEvidenceResolution resolution =
                    merger(nodeProvider).resolveTypeDeclarationEvidence(
                        preprocessed,
                        NO_LIMITS);
            return new MinimizedOverlayBuilder().build(
                    resolution.resolvedRoot(),
                    resolution.canonicalTypeIdentities());
        });
    }

    boolean isSubtype(Node candidate, Node superType) {
        return call(() -> {
            if (candidate == null || superType == null) {
                return false;
            }

            Node request = new Node().properties(
                    "candidateType",
                    new Node().type(candidate.clone()),
                    "requiredSupertype",
                    new Node().type(superType.clone()));
            blue.language.merge.TypeEvidenceResolution resolution =
                    merger(nodeProvider)
                    .resolveTypeDeclarationEvidence(
                            rawPreprocess(request),
                            NO_LIMITS);
            Node completed = resolution.resolvedRoot().toNode();
            Node completedCandidate = completed.getProperties()
                    .get("candidateType")
                    .getType();
            Node completedSupertype = completed.getProperties()
                    .get("requiredSupertype")
                    .getType();
            return Types.isSubtype(
                    completedCandidate,
                    completedSupertype,
                    nodeProvider,
                    resolution.canonicalTypeIdentities());
        });
    }

    BlueOperationResult<Node> resolveLimited(
            Node source,
            BlueOperationLimits limits) {
        return call(() -> LanguageRuntimeLimitedResolution.resolve(
                nodeProvider,
                mergingProcessor,
                source,
                limits,
                this::rawPreprocess));
    }

    ResolvedSnapshot resolveSnapshot(Node source) {
        return call(() -> {
            Node preprocessed = rawPreprocess(Objects.requireNonNull(
                    source, "source").clone());
            return snapshotsStore.derived(
                    ResolvedSnapshot.fromSourceResolverResult(
                            FrozenNode.fromSourceNode(preprocessed),
                            merger(nodeProvider).resolveSnapshot(
                                    preprocessed.clone(), NO_LIMITS)));
        });
    }

    ResolvedSnapshot resolveSnapshotPreservingPaths(
            Node source,
            Collection<String> preservedPaths) {
        return call(() -> {
            Node preprocessed = rawPreprocess(
                    Objects.requireNonNull(source, "source").clone());
            Set<String> paths = canonicalPreservedPaths(
                    preservedPaths);
            if (paths.isEmpty()) {
                return snapshotsStore.derived(
                        ResolvedSnapshot.fromSourceResolverResult(
                                FrozenNode.fromSourceNode(preprocessed),
                                merger(nodeProvider).resolveSnapshot(
                                        preprocessed.clone(), NO_LIMITS)));
            }
            TypeEvidenceResolution projection = merger(nodeProvider)
                    .resolveTypeEvidence(
                    preprocessed.clone(),
                    ResolutionLimits.allOf(
                            NO_LIMITS,
                            ResolutionLimits.deferringReferencesAt(paths)));
            Node deferred = projection.resolvedRoot().toNode();
            for (String path : paths) {
                Node authored = NodePathEditor.getOrNull(
                        preprocessed, path);
                if (authored != null) {
                    NodePathEditor.put(
                            deferred, path, authored.clone());
                }
            }
            return ResolvedSnapshot.withDeferredSource(
                    FrozenNode.fromSourceNode(preprocessed),
                    snapshotsStore.referenceCache()
                            .freezeResolved(deferred),
                    projection.canonicalTypeIdentities());
        });
    }

    ResolvedSnapshot loadSnapshot(Node canonical) {
        return call(() -> loadCanonical(
                FrozenNode.fromNode(Objects.requireNonNull(
                        canonical, "canonicalIdentityInput"))));
    }

    ResolvedSnapshot loadSnapshot(String blueId) {
        return call(() -> {
            Optional<ResolvedSnapshot> cached =
                    snapshotsStore.byBlueId(blueId);
            if (cached.isPresent()) {
                return cached.get();
            }
            List<Node> nodes = nodeProvider.fetchByBlueId(blueId);
            if (nodes == null || nodes.isEmpty()) {
                throw new IllegalArgumentException(
                        "No content found for blueId: " + blueId);
            }
            Node canonical = nodes.size() == 1
                    ? withoutRootIdentity(nodes.get(0))
                    : new Node().items(withoutRootIdentity(nodes));
            return loadCanonical(FrozenNode.fromNode(canonical));
        });
    }

    ResolvedSnapshot cacheSnapshot(ResolvedSnapshot snapshot) {
        return call(() -> snapshotsStore.pin(snapshot));
    }

    Optional<ResolvedSnapshot> cachedSnapshot(String blueId) {
        return call(() -> snapshotsStore.byBlueId(blueId));
    }

    void clearSnapshots() {
        run(snapshotsStore::clear);
    }

    BlueCacheStats cacheStats() {
        lifecycle.readLock().lock();
        try {
            return snapshotsStore.stats(closed);
        } finally {
            lifecycle.readLock().unlock();
        }
    }

    boolean matches(Node candidate, Node type) {
        return call(() -> new NodeTypeMatcher(this)
                .matchesType(candidate, type, NO_LIMITS));
    }

    boolean matches(FrozenNode candidate, FrozenNode type) {
        return call(() -> new NodeTypeMatcher(this)
                .matchesResolvedType(candidate, type));
    }

    boolean matches(
            ResolvedSnapshot snapshot,
            String pointer,
            FrozenNode type) {
        return call(() -> new NodeTypeMatcher(this)
                .matchesResolvedType(snapshot, pointer, type));
    }

    CanonicalPatchResult applyPatch(
            Node canonical,
            BluePatch patch) {
        return call(() -> new CanonicalOverlayPatchEngine(
                FrozenNode.fromNode(Objects.requireNonNull(
                        canonical, "canonicalIdentityInput")))
                .apply(Objects.requireNonNull(patch, "patch")));
    }

    ResolvedSnapshot applyPatch(
            ResolvedSnapshot snapshot,
            BluePatch patch) {
        return call(() -> {
            ResolvedSnapshot requiredSnapshot = Objects.requireNonNull(
                    snapshot, "snapshot");
            CanonicalPatchResult patched =
                    new CanonicalOverlayPatchEngine(
                            requiredSnapshot.frozenCanonicalRoot())
                            .apply(Objects.requireNonNull(patch, "patch"));
            ResolvedSnapshot patchedSnapshot =
                    loadCanonical(patched.root());
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
            ResolvedSnapshot inheritedSnapshot =
                    loadCanonical(withoutOverride.root());
            FrozenNode patchedEffective =
                    patchedSnapshot.resolvedAt(patched.path());
            FrozenNode inheritedEffective =
                    inheritedSnapshot.resolvedAt(patched.path());
            if (patchedEffective != null
                    && inheritedEffective != null
                    && patchedEffective.blueId().equals(
                    inheritedEffective.blueId())) {
                return inheritedSnapshot;
            }
            return patchedSnapshot;
        });
    }

    <T> T admitted(Supplier<T> work) {
        return call(work);
    }

    void admitted(Runnable work) {
        run(work);
    }

    private void enterAdmittedOperation() {
        lifecycle.readLock().lock();
        Integer previous = operationDepth.get();
        try {
            ensureOpen();
            operationDepth.set(previous == null ? 1 : previous + 1);
        } catch (RuntimeException failure) {
            lifecycle.readLock().unlock();
            throw failure;
        } catch (Error failure) {
            lifecycle.readLock().unlock();
            throw failure;
        }
    }

    private void exitAdmittedOperation() {
        Integer depth = operationDepth.get();
        if (depth == null || depth <= 1) {
            operationDepth.remove();
        } else {
            operationDepth.set(depth - 1);
        }
        lifecycle.readLock().unlock();
    }

    private Node rawPreprocess(Node source) {
        return new Preprocessor(
                Preprocessor.getStandardProvider(),
                nodeProvider,
                preprocessingAliases,
                environmentImports)
                .preprocess(source);
    }

    private Node rawResolve(Node source, ResolutionLimits limits) {
        return merger(nodeProvider).resolve(source, limits);
    }

    private Merger merger(NodeProvider provider) {
        return new Merger(
                mergingProcessor,
                provider,
                snapshotsStore.referenceCache(),
                referenceCacheAdmission);
    }

    private ResolvedSnapshot loadCanonical(FrozenNode canonical) {
        ResolvedSnapshot cached = snapshotsStore.byCanonical(
                canonical.resolvedStructuralKey());
        if (cached != null
                && cached.verifiedReferenceResolution() != null) {
            return cached;
        }
        return snapshotsStore.derived(
                ResolvedSnapshot.fromResolverResult(
                        merger(nodeProvider).resolveSnapshot(
                                canonical, NO_LIMITS)));
    }

    private TypeEvidenceResolution materializeTypeReferenceEvidence(
            FrozenNode reference) {
        Objects.requireNonNull(reference, "reference");
        if (!reference.isReferenceOnly()
                || reference.getReferenceBlueId() == null) {
            throw new IllegalArgumentException(
                    "Matching materialization requires a pure reference");
        }
        TypeEvidenceResolution wrapper = merger(nodeProvider)
                .materializeTypeReferenceEvidence(
                        reference,
                        NO_LIMITS);
        FrozenNode materializedType = wrapper.resolvedRoot().getType();
        if (materializedType == null || materializedType.isReferenceOnly()) {
            return null;
        }
        return new TypeEvidenceResolution(
                materializedType,
                wrapper.canonicalTypeIdentities());
    }

    private <T> T call(Supplier<T> work) {
        enterAdmittedOperation();
        try {
            return work.get();
        } finally {
            exitAdmittedOperation();
        }
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
                    "Blue Language runtime is closed");
        }
    }

    private static Map<String, String> immutableAliases(
            Map<String, String> aliases) {
        if (aliases == null || aliases.isEmpty()) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(
                new LinkedHashMap<>(aliases));
    }

    private static MergingProcessor defaultMergingProcessor() {
        return new SequentialMergingProcessor(Arrays.asList(
                new ValuePropagator(),
                new TypeAssigner(),
                new ListProcessor(),
                new DictionaryProcessor(),
                new SchemaPropagator(),
                new SchemaVerifier(),
                new BasicTypesVerifier()));
    }

    private static Set<String> canonicalPreservedPaths(
            Collection<String> preservedPaths) {
        if (preservedPaths == null || preservedPaths.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> canonicalPaths = new HashSet<>();
        for (String path : preservedPaths) {
            canonicalPaths.add(JsonPointer.canonicalize(path));
        }
        return canonicalPaths;
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

}
