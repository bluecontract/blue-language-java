package blue.language.runtime;

import blue.language.api.BlueCacheStats;
import blue.language.api.BlueOperationLimits;
import blue.language.api.BlueOperationResult;
import blue.language.graph.BlueGraph;
import blue.language.identity.BlueIdentity;
import blue.language.identity.CanonicalJsonHasher;
import blue.language.matching.BlueMatching;
import blue.language.model.Node;
import blue.language.snapshot.BluePatch;
import blue.language.patching.BluePatching;
import blue.language.preprocess.BluePreprocessing;
import blue.language.preprocess.StandardBluePreprocessing;
import blue.language.resolve.BlueResolution;
import blue.language.merge.BlueSnapshots;
import blue.language.snapshot.CanonicalPatchResult;
import blue.language.snapshot.FrozenNode;
import blue.language.merge.ResolvedSnapshot;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** Shared construction helpers for focused runtime service adapters. */
public final class LanguageRuntimeServices {

    private LanguageRuntimeServices() {
    }

    /**
     * Calculates the stable preprocessing-environment identity for directive
     * aliases without host environment imports.
     *
     * @param aliases directive aliases, or {@code null} for the baseline
     *        environment
     * @return baseline environment identity, optionally extended by the
     *         canonical alias-map hash
     */
    public static String preprocessingEnvironmentIdentity(
            Map<String, String> aliases) {
        if (aliases == null || aliases.isEmpty()) {
            return StandardBluePreprocessing
                    .BASELINE_ENVIRONMENT_IDENTITY;
        }
        return StandardBluePreprocessing.BASELINE_ENVIRONMENT_IDENTITY
                + "/" + new CanonicalJsonHasher().hash(
                new TreeMap<>(aliases));
    }

    /**
     * Identifies directive aliases and host environment imports without
     * conflating their distinct namespaces.
     */
    static String preprocessingEnvironmentIdentity(
            Map<String, String> aliases,
            Map<String, String> environmentImports) {
        if (environmentImports == null
                || environmentImports.isEmpty()) {
            return preprocessingEnvironmentIdentity(aliases);
        }
        Map<String, Object> environment = new LinkedHashMap<>();
        environment.put("directiveAliases",
                aliases == null
                        ? new TreeMap<String, String>()
                        : new TreeMap<>(aliases));
        environment.put("environmentImports",
                new TreeMap<>(environmentImports));
        return StandardBluePreprocessing.BASELINE_ENVIRONMENT_IDENTITY
                + "/" + new CanonicalJsonHasher().hash(environment);
    }
}

/** Close-aware preprocessing view over one immutable runtime. */
final class RuntimeBluePreprocessing implements BluePreprocessing {

    private final BlueLanguageRuntime runtime;
    private final BluePreprocessing delegate;

    RuntimeBluePreprocessing(
            BlueLanguageRuntime runtime,
            BluePreprocessing delegate) {
        this.runtime = runtime;
        this.delegate = delegate;
    }

    @Override
    public Node preprocess(Node source) {
        return runtime.admitted(() -> delegate.preprocess(source));
    }

    @Override
    public String environmentIdentity() {
        return delegate.environmentIdentity();
    }
}

/** Close-aware graph view over the core graph implementation. */
final class RuntimeBlueGraph implements BlueGraph {

    private final BlueLanguageRuntime runtime;
    private final BlueGraph delegate;

    RuntimeBlueGraph(
            BlueLanguageRuntime runtime,
            BlueGraph delegate) {
        this.runtime = runtime;
        this.delegate = delegate;
    }

    @Override
    public Node expand(Node source) {
        return runtime.admitted(() -> delegate.expand(source));
    }

    @Override
    public BlueOperationResult<Node> expandLimited(
            Node source,
            BlueOperationLimits limits) {
        return runtime.admitted(
                () -> delegate.expandLimited(source, limits));
    }

    @Override
    public Node collapse(Node exactInput) {
        return runtime.admitted(() -> delegate.collapse(exactInput));
    }

    @Override
    public Node specialize(Node type, Node overlay) {
        return runtime.admitted(
                () -> delegate.specialize(type, overlay));
    }
}

/** Focused authored-resolution view over the runtime kernel. */
final class RuntimeBlueResolution implements BlueResolution {

    private final BlueLanguageRuntime runtime;

    RuntimeBlueResolution(BlueLanguageRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public Node resolve(Node source) {
        return runtime.resolveAuthored(source);
    }

    @Override
    public BlueOperationResult<Node> resolveLimited(
            Node source,
            BlueOperationLimits limits) {
        return runtime.resolveLimited(source, limits);
    }

    @Override
    public Node resolvePreservingPaths(
            Node source,
            Collection<String> preservedPaths) {
        return runtime.resolvePreservingPaths(
                source, preservedPaths);
    }

    @Override
    public Node minimize(Node source) {
        return runtime.minimize(source);
    }

    @Override
    public boolean isSubtype(Node candidateType, Node superType) {
        return runtime.isSubtype(candidateType, superType);
    }
}

/** Close-aware identity view over the standard identity implementation. */
final class RuntimeBlueIdentity implements BlueIdentity {

    private final BlueLanguageRuntime runtime;
    private final BlueIdentity delegate;

    RuntimeBlueIdentity(
            BlueLanguageRuntime runtime,
            BlueIdentity delegate) {
        this.runtime = runtime;
        this.delegate = delegate;
    }

    @Override
    public String directBlueId(Node blueIdInput) {
        return runtime.admitted(
                () -> delegate.directBlueId(blueIdInput));
    }

    @Override
    public String sourceDocumentBlueId(Node sourceDocument) {
        return runtime.admitted(
                () -> delegate.sourceDocumentBlueId(sourceDocument));
    }

    @Override
    public Node canonicalIdentityInput(Node sourceDocument) {
        return runtime.admitted(
                () -> delegate.canonicalIdentityInput(sourceDocument));
    }

    @Override
    public java.util.List<String> circularBlueIds(
            java.util.List<Node> documents) {
        return runtime.admitted(
                () -> delegate.circularBlueIds(documents));
    }
}

/** Snapshot/cache service over runtime-owned bounded state. */
final class RuntimeBlueSnapshots implements BlueSnapshots {

    private final BlueLanguageRuntime runtime;

    RuntimeBlueSnapshots(BlueLanguageRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public ResolvedSnapshot resolve(Node source) {
        return runtime.resolveSnapshot(source);
    }

    @Override
    public ResolvedSnapshot resolvePreservingPaths(
            Node source,
            Collection<String> preservedPaths) {
        return runtime.resolveSnapshotPreservingPaths(
                source, preservedPaths);
    }

    @Override
    public ResolvedSnapshot load(Node canonicalIdentityInput) {
        return runtime.loadSnapshot(canonicalIdentityInput);
    }

    @Override
    public ResolvedSnapshot load(String blueId) {
        return runtime.loadSnapshot(blueId);
    }

    @Override
    public ResolvedSnapshot cache(ResolvedSnapshot snapshot) {
        return runtime.cacheSnapshot(snapshot);
    }

    @Override
    public Optional<ResolvedSnapshot> cached(String blueId) {
        return runtime.cachedSnapshot(blueId);
    }

    @Override
    public void clear() {
        runtime.clearSnapshots();
    }

    @Override
    public BlueCacheStats stats() {
        return runtime.cacheStats();
    }
}

/** Matching service that keeps incomplete evidence distinct from absence. */
final class RuntimeBlueMatching implements BlueMatching {

    private final BlueLanguageRuntime runtime;
    private final BlueMatching delegate;

    RuntimeBlueMatching(BlueLanguageRuntime runtime) {
        this.runtime = runtime;
        this.delegate = new LanguageMatchingService(
                runtime,
                blue.language.resolve.ResolutionLimits.NO_LIMITS,
                runtime::resolveLimited);
    }

    @Override
    public boolean matches(Node candidate, Node type) {
        return runtime.admitted(
                () -> delegate.matches(candidate, type));
    }

    @Override
    public boolean matches(FrozenNode candidate, FrozenNode type) {
        return runtime.admitted(
                () -> delegate.matches(candidate, type));
    }

    @Override
    public boolean matches(
            ResolvedSnapshot snapshot,
            String pointer,
            FrozenNode type) {
        return runtime.admitted(
                () -> delegate.matches(snapshot, pointer, type));
    }

    @Override
    public BlueOperationResult<Boolean> matchesLimited(
            Node candidate,
            Node type,
            BlueOperationLimits limits) {
        return runtime.admitted(
                () -> delegate.matchesLimited(
                        candidate, type, limits));
    }
}

/** Canonical patch service over runtime-owned snapshot resolution. */
final class RuntimeBluePatching implements BluePatching {

    private final BlueLanguageRuntime runtime;

    RuntimeBluePatching(BlueLanguageRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public CanonicalPatchResult apply(
            Node canonicalIdentityInput,
            BluePatch patch) {
        return runtime.applyPatch(canonicalIdentityInput, patch);
    }

    @Override
    public ResolvedSnapshot apply(
            ResolvedSnapshot snapshot,
            BluePatch patch) {
        return runtime.applyPatch(snapshot, patch);
    }
}
