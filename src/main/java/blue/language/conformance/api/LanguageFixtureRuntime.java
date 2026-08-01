package blue.language.conformance.api;

import blue.language.api.BlueCachePolicy;
import blue.language.runtime.BlueLanguageRuntime;
import blue.language.api.BlueOperationLimits;
import blue.language.api.BlueOperationResult;
import blue.language.codec.BlueFormat;
import blue.language.conformance.ConformanceEngine;
import blue.language.model.Node;
import blue.language.provider.NodeProvider;
import blue.language.merge.ResolvedSnapshot;

import java.util.Collection;
import java.util.Collections;
import java.util.Objects;

/**
 * Conformance-owned convenience adapter over the focused Language services.
 *
 * <p>The adapter contains no Language algorithms. It gives the closed fixture
 * engines concise names for public service calls while keeping them entirely
 * independent of the aggregate {@code Blue} facade.</p>
 */
final class LanguageFixtureRuntime implements AutoCloseable {

    private static final NodeProvider EMPTY_PROVIDER = blueId -> null;

    private final BlueLanguageRuntime runtime;

    /** Creates a runtime using only the released bootstrap provider. */
    LanguageFixtureRuntime() {
        this(EMPTY_PROVIDER);
    }

    /** Creates a runtime using the supplied external-content provider. */
    LanguageFixtureRuntime(NodeProvider nodeProvider) {
        this.runtime = BlueLanguageRuntime.create(
                nodeProvider,
                BlueCachePolicy.boundedDefaults(),
                Collections.emptyMap());
    }

    /** Returns the narrow downstream runtime contract used by Contracts. */
    public BlueLanguageRuntime access() {
        return runtime;
    }

    /** Parses exact direct-BlueId YAML input. */
    public Node parseBlueIdInputYaml(String yaml) {
        return runtime.codec().parseBlueIdInput(yaml, BlueFormat.YAML);
    }

    /** Parses authored Source YAML input. */
    public Node parseSourceYaml(String yaml) {
        return runtime.codec().parseSource(yaml, BlueFormat.YAML);
    }

    /** Applies the released preprocessing environment. */
    public Node preprocess(Node source) {
        return runtime.preprocessing().preprocess(source);
    }

    /** Resolves an authored Source value completely. */
    public Node resolve(Node source) {
        return runtime.resolution().resolve(source);
    }

    /** Produces the canonical direct identity input for authored Source. */
    public Node canonicalize(Node source) {
        return runtime.identity().canonicalIdentityInput(source);
    }

    /** Canonicalizes only an established complete operation result. */
    public Node canonicalize(BlueOperationResult<Node> result) {
        Objects.requireNonNull(result, "result");
        if (!result.isEstablished()) {
            throw new IllegalStateException(
                    "Canonicalization requires an established complete result; outcome was "
                            + result.outcome() + ".");
        }
        return canonicalize(result.requireEstablished());
    }

    /** Produces the Source Document BlueId. */
    public String calculateSourceDocumentBlueId(Node source) {
        return runtime.identity().sourceDocumentBlueId(source);
    }

    /** Reveals exact referenced content. */
    public Node expand(Node source) {
        return runtime.graph().expand(source);
    }

    /** Reveals only the demanded exact referenced content. */
    public BlueOperationResult<Node> expandLimited(
            Node source,
            BlueOperationLimits limits) {
        return runtime.graph().expandLimited(source, limits);
    }

    /** Resolves only the demanded semantic closure. */
    public BlueOperationResult<Node> resolveLimited(
            Node source,
            BlueOperationLimits limits) {
        return runtime.resolution().resolveLimited(source, limits);
    }

    /** Hides exact content behind its direct identity. */
    public Node collapse(Node source) {
        return runtime.graph().collapse(source);
    }

    /** Produces an author-facing minimized overlay. */
    public Node minimize(Node source) {
        return runtime.resolution().minimize(source);
    }

    /** Tests the resolved type relation exposed by the focused matcher. */
    public boolean nodeMatchesType(Node candidate, Node type) {
        return runtime.matching().matches(candidate, type);
    }

    /** Reports the released Language version. */
    public String languageVersion() {
        return runtime.languageVersion();
    }

    /** Creates a complete immutable processing snapshot. */
    public ResolvedSnapshot resolveToSnapshot(Node source) {
        return runtime.snapshots().resolve(source);
    }

    /** Creates a snapshot while preserving selected authored paths. */
    public ResolvedSnapshot resolveToSnapshotPreservingPaths(
            Node source,
            Collection<String> preservedPaths) {
        return runtime.snapshots().resolvePreservingPaths(
                source, preservedPaths);
    }

    /** Loads a verified exact snapshot by BlueId. */
    public ResolvedSnapshot loadSnapshot(String blueId) {
        return runtime.snapshots().load(blueId);
    }

    /** Applies one Language-owned patch to a processing snapshot. */
    public ResolvedSnapshot applyCanonicalPatch(
            ResolvedSnapshot snapshot,
            blue.language.snapshot.BluePatch patch) {
        return runtime.patching().apply(snapshot, patch);
    }

    /** Publishes a complete snapshot to the runtime-owned cache. */
    public ResolvedSnapshot cacheResolvedSnapshot(
            ResolvedSnapshot snapshot) {
        return runtime.snapshots().cache(snapshot);
    }

    /** Creates an independent semantic generalization engine. */
    public ConformanceEngine newConformanceEngine() {
        return runtime.newConformanceEngine();
    }

    /** Releases runtime-owned caches without affecting borrowed providers. */
    @Override
    public void close() {
        runtime.close();
    }
}
