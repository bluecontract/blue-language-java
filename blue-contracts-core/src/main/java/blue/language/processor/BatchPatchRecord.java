package blue.language.processor;

import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.identity.NodeToBlueIdInput;
import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import blue.language.snapshot.FrozenNode;
import blue.language.model.wire.ParsedJsonPointer;

import java.util.List;

/**
 * Immutable evidence captured for one patch while an atomic batch is planned.
 *
 * <p>The historically named canonical plan is the exact selected input lane:
 * preprocessed Source for a Source-backed snapshot, otherwise already-proven
 * Canonical Identity Input. It and the resolved plan describe the same
 * authored operation at the same batch position. The before/after values are
 * therefore patch-time values, not projections of the final batch root.</p>
 */
final class BatchPatchRecord {

    private final ParsedJsonPointer parsedPath;
    private final ImmutablePatchPlanner.PatchPlan canonicalPlan;
    private final ImmutablePatchPlanner.PatchPlan resolvedPlan;
    private final FrozenNode exactBeforeAtPatchTime;
    private final FrozenNode exactAfterAtPatchTime;
    private final FrozenNode beforeAtPatchTime;
    private final FrozenNode afterAtPatchTime;
    private final boolean objectMemberTarget;
    private final PatchImpact impact;
    private final boolean processorManagedConformanceBypass;

    BatchPatchRecord(ImmutableJsonPatch patch,
                     ImmutablePatchPlanner.PatchPlan canonicalPlan,
                     ImmutablePatchPlanner.PatchPlan resolvedPlan,
                     FrozenNode exactBeforeAtPatchTime,
                     FrozenNode exactAfterAtPatchTime,
                     boolean objectMemberTarget,
                     PatchImpact impact,
                     boolean processorManagedConformanceBypass) {
        this.parsedPath = patch.path();
        this.canonicalPlan = canonicalPlan;
        this.resolvedPlan = resolvedPlan;
        this.exactBeforeAtPatchTime = exactBeforeAtPatchTime;
        this.exactAfterAtPatchTime = exactAfterAtPatchTime;
        this.beforeAtPatchTime = resolvedPlan.before();
        this.afterAtPatchTime = resolvedPlan.after();
        this.objectMemberTarget = objectMemberTarget;
        this.impact = impact;
        this.processorManagedConformanceBypass = processorManagedConformanceBypass;
    }

    ParsedJsonPointer parsedPath() {
        return parsedPath;
    }

    ImmutablePatchPlanner.PatchPlan canonicalPlan() {
        return canonicalPlan;
    }

    ImmutablePatchPlanner.PatchPlan resolvedPlan() {
        return resolvedPlan;
    }

    String path() {
        return canonicalPlan.path();
    }

    JsonPatch.Op op() {
        return canonicalPlan.op();
    }

    String originScope() {
        return canonicalPlan.originScope();
    }

    List<String> cascadeScopes() {
        return canonicalPlan.cascadeScopes();
    }

    FrozenNode beforeAtPatchTime() {
        return beforeAtPatchTime;
    }

    FrozenNode afterAtPatchTime() {
        return afterAtPatchTime;
    }

    /** Returns the exact selected-input snapshot for a Document Update. */
    FrozenNode exactBeforeAtPatchTime() {
        return exactBeforeAtPatchTime;
    }

    /** Returns the exact selected-input snapshot for a Document Update. */
    FrozenNode exactAfterAtPatchTime() {
        return exactAfterAtPatchTime;
    }

    boolean objectMemberTarget() {
        return objectMemberTarget;
    }

    PatchImpact impact() {
        return impact;
    }

    boolean processorManagedConformanceBypass() {
        return processorManagedConformanceBypass;
    }

    /**
     * Selects an exact event value without leaking a mixed resolved view.
     *
     * <p>The planning lane may be preprocessed Source or already-proven
     * Canonical Identity Input. When that lane omits an inherited value that
     * is present in the effective lane, the fallback is rebuilt as strict
     * Canonical Identity Input from resolver-issued type evidence. Raw
     * resolved content is never published as an exact Document Update
     * snapshot.</p>
     */
    static FrozenNode exactInputSnapshot(
            FrozenNode selected,
            FrozenNode resolved,
            CanonicalTypeIdentityLookup canonicalTypeIdentities,
            ProcessingSnapshotManager snapshotManager) {
        if (selected != null) {
            return selected;
        }
        if (resolved == null) {
            return null;
        }
        if (resolved.isReferenceOnly()) {
            return resolved;
        }
        String resolvedReferenceBlueId = resolved.getReferenceBlueId();
        if (resolvedReferenceBlueId != null) {
            if (snapshotManager == null) {
                throw new IllegalStateException(
                        "Exact Document Update snapshot requires verified "
                                + "reference provenance for "
                                + resolvedReferenceBlueId);
            }
            FrozenNode reference = FrozenNode.fromNode(
                    new Node().blueId(resolvedReferenceBlueId));
            ExecutableBodyPathCatalog.materializeVerifiedExact(
                    snapshotManager,
                    reference,
                    "Document Update snapshot");
            FrozenNode verifiedResolved = snapshotManager
                    .materializeVerifiedReference(reference);
            Node unannotatedResolved = resolved.toNode();
            unannotatedResolved.blueId(null);
            if (!FrozenNode.fromResolvedNode(unannotatedResolved)
                    .sameResolvedStructure(verifiedResolved)) {
                throw new IllegalStateException(
                        "Resolved Document Update value does not match its "
                                + "verified reference provenance: "
                                + resolvedReferenceBlueId);
            }
            return reference;
        }
        Node resolvedValue = resolved.toNode();
        Node projectionSource = NodeToBlueIdInput
                .stripResolvedBlueIdMetadata(resolvedValue.clone());
        return CanonicalIdentityEvidence.projectResolvedGraph(
                resolvedValue,
                projectionSource,
                canonicalTypeIdentities);
    }
}
