package blue.language.processor;

import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.merge.ResolvedSnapshot;
import blue.language.snapshot.FrozenNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Owns lazy, pre-mutation type-identity evidence for one patch plan. */
final class PatchPlanningCanonicalIdentityEvidence {

    private final ProcessingSnapshotManager snapshotManager;
    private final boolean strictPlatformInvocation;
    private final Set<String> openedScopePaths;
    private final Map<String, List<String>> executableBodyFieldsByType;
    private final List<CanonicalTypeIdentityLookup> materializedEvidence =
            new ArrayList<>();
    private CanonicalTypeIdentityLookup available;
    private FrozenNode canonicalRoot;

    PatchPlanningCanonicalIdentityEvidence(
            ProcessingSnapshotManager snapshotManager,
            boolean strictPlatformInvocation,
            Set<String> openedScopePaths,
            Map<String, List<String>> executableBodyFieldsByType,
            CanonicalTypeIdentityLookup initialEvidence) {
        this.snapshotManager = snapshotManager;
        this.strictPlatformInvocation = strictPlatformInvocation;
        this.openedScopePaths = Objects.requireNonNull(
                openedScopePaths, "openedScopePaths");
        this.executableBodyFieldsByType = Objects.requireNonNull(
                executableBodyFieldsByType,
                "executableBodyFieldsByType");
        this.available = Objects.requireNonNull(
                initialEvidence, "initialEvidence");
    }

    void begin(
            FrozenNode preMutationCanonicalRoot,
            CanonicalTypeIdentityLookup preMutationEvidence) {
        canonicalRoot = Objects.requireNonNull(
                preMutationCanonicalRoot, "preMutationCanonicalRoot");
        available = Objects.requireNonNull(
                preMutationEvidence, "preMutationEvidence");
        materializedEvidence.clear();
    }

    CanonicalTypeIdentityLookup available() {
        return available;
    }

    /** Returns evidence captured before mutation, rebuilding it on demand. */
    CanonicalTypeIdentityLookup forConformance() {
        if (!available.hasCompleteCoverage() && snapshotManager != null) {
            available = CanonicalIdentityEvidence.onDemand(
                    available, this::resolveCompleteEvidence);
        }
        return available;
    }

    /** Retains evidence produced while materializing a patch base or value. */
    void include(ResolvedSnapshot materializedSnapshot) {
        materializedEvidence.add(
                Objects.requireNonNull(
                        materializedSnapshot, "materializedSnapshot")
                        .canonicalTypeIdentities());
    }

    /**
     * Establishes complete evidence for the exact recombined resolved graph.
     * No identity is inferred from materialized content: every type position
     * must be covered by the entry snapshot or by a resolver invocation that
     * produced a patch-time subtree.
     */
    CanonicalTypeIdentityLookup forResolvedGraph(FrozenNode resolvedRoot) {
        List<CanonicalTypeIdentityLookup> constituents = new ArrayList<>(
                materializedEvidence.size() + 1);
        constituents.add(forConformance());
        constituents.addAll(materializedEvidence);
        return CanonicalTypeIdentityEvidenceUnion.establishForResolvedGraph(
                Objects.requireNonNull(resolvedRoot, "resolvedRoot").toNode(),
                constituents);
    }

    private CanonicalTypeIdentityLookup resolveCompleteEvidence() {
        blue.language.model.Node source = canonicalRoot.toNode();
        Set<String> preserved = ExecutableBodyPathCatalog.forHostedOutput(
                source, executableBodyFieldsByType, snapshotManager);
        ResolvedSnapshot snapshot = preserved.isEmpty()
                ? snapshotManager.fromDocumentTransientForCanonicalIdentity(source)
                : snapshotManager.fromDocumentTransientPreservingPaths(source, preserved);
        return snapshot.canonicalTypeIdentities();
    }
}
