package blue.language.processor;

import blue.language.conformance.ConformanceEngine;
import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.util.PointerUtils;
import blue.language.snapshot.FrozenNode;
import blue.language.snapshot.ResolvedSnapshot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Frozen preview state for processor-side read-your-writes workflows.
 *
 * <p>A working document applies the same immutable patch transaction used by
 * {@link DocumentProcessingRuntime}, including conformance planning, dynamic
 * type generalization, and Type Generalization Policy enforcement. It never
 * commits to the processor runtime, emits cascades, charges gas, or writes
 * processor-managed markers.</p>
 */
public final class WorkingDocument {

    private static final DocumentProcessingRuntime.UpdateMaterializationMetrics NOOP_MATERIALIZATION_METRICS =
            new DocumentProcessingRuntime.UpdateMaterializationMetrics() {
                @Override
                public void recordBeforeNodeMaterialization() {
                    // Working previews keep update metadata frozen and do not expose document-update materialization.
                }

                @Override
                public void recordAfterNodeMaterialization() {
                    // Working previews keep update metadata frozen and do not expose document-update materialization.
                }
            };

    private final String originScope;
    private FrozenNode canonicalRoot;
    private FrozenNode resolvedRoot;
    private final ConformanceEngine conformanceEngine;
    private final ConformancePlannerOverride conformancePlannerOverride;
    private final ProcessingSnapshotManager snapshotManager;
    private final boolean materializedFallback;
    private final boolean exactReplacement;
    private ResolvedSnapshot snapshot;

    WorkingDocument(String originScope,
                    FrozenNode canonicalRoot,
                    FrozenNode resolvedRoot,
                    ConformanceEngine conformanceEngine,
                    ConformancePlannerOverride conformancePlannerOverride,
                    ProcessingSnapshotManager snapshotManager,
                    ResolvedSnapshot snapshot,
                    boolean materializedFallback,
                    boolean exactReplacement) {
        this.originScope = PointerUtils.normalizeScope(originScope);
        this.canonicalRoot = Objects.requireNonNull(canonicalRoot, "canonicalRoot");
        this.resolvedRoot = Objects.requireNonNull(resolvedRoot, "resolvedRoot");
        this.conformanceEngine = conformanceEngine;
        this.conformancePlannerOverride = conformancePlannerOverride;
        this.snapshotManager = snapshotManager;
        this.snapshot = snapshot;
        this.materializedFallback = materializedFallback;
        this.exactReplacement = exactReplacement;
    }

    public FrozenNode canonicalRoot() {
        return canonicalRoot;
    }

    public FrozenNode resolvedRoot() {
        return resolvedRoot;
    }

    public FrozenNode canonicalAt(String absolutePointer) {
        return ImmutablePatchPlanner.forFrozen(canonicalRoot)
                .read(PointerUtils.normalizePointer(absolutePointer));
    }

    public FrozenNode resolvedAt(String absolutePointer) {
        return ImmutablePatchPlanner.forFrozen(resolvedRoot)
                .read(PointerUtils.normalizePointer(absolutePointer));
    }

    public WorkingDocument applyPatch(JsonPatch patch) {
        if (patch == null) {
            return this;
        }
        return applyPatches(Collections.singletonList(patch));
    }

    public WorkingDocument applyPatches(List<JsonPatch> patches) {
        previewAndApplyPatches(patches);
        return this;
    }

    public Preview previewAndApplyPatches(List<JsonPatch> patches) {
        if (patches == null || patches.isEmpty()) {
            return Preview.empty(originScope);
        }
        List<JsonPatch> copy = copyPatches(patches);
        FrozenNode nextCanonical = canonicalRoot;
        FrozenNode nextResolved = resolvedRoot;
        List<PatchPreview> previews = new ArrayList<>(copy.size());
        for (JsonPatch patch : copy) {
            PatchPreview preview = previewSinglePatch(nextCanonical, nextResolved, patch);
            BatchPatchResult result = preview.result();
            previews.add(preview);
            nextCanonical = result.canonicalRoot();
            nextResolved = result.resolvedRoot();
        }
        canonicalRoot = nextCanonical;
        resolvedRoot = nextResolved;
        snapshot = null;
        return new Preview(originScope, previews);
    }

    public ResolvedSnapshot snapshot() {
        if (snapshot == null) {
            snapshot = new ResolvedSnapshot(canonicalRoot, resolvedRoot, canonicalRoot.blueId());
        }
        return snapshot;
    }

    public Node materializeCanonicalRoot() {
        return canonicalRoot.toNode();
    }

    public Node materializeResolvedRoot() {
        return resolvedRoot.toNode();
    }

    public Node commitToNode() {
        return materializeCanonicalRoot();
    }

    public ResolvedSnapshot commitSnapshot() {
        ResolvedSnapshot current = snapshot();
        snapshot = snapshotManager != null ? snapshotManager.cacheSnapshot(current) : current;
        return snapshot;
    }

    /**
     * Returns true when this preview had to freeze a materialized runtime tree
     * because no processor snapshot was available at creation time.
     */
    public boolean usedMaterializedFallback() {
        return materializedFallback;
    }

    private PatchPreview previewSinglePatch(FrozenNode baseCanonical,
                                            FrozenNode baseResolved,
                                            JsonPatch patch) {
        DocumentProcessingRuntime.PlanningContext planning =
                DocumentProcessingRuntime.workingPlanningContext(
                        baseCanonical, baseResolved, exactReplacement, snapshotManager);
        BatchPatchResult result = new BatchPatchTransaction(originScope,
                Collections.singletonList(Objects.requireNonNull(patch, "patch")),
                planning,
                conformanceEngine,
                conformancePlannerOverride,
                NOOP_MATERIALIZATION_METRICS,
                false).apply();
        return new PatchPreview(originScope,
                patch,
                baseCanonical,
                baseResolved,
                result);
    }

    private static List<JsonPatch> copyPatches(List<JsonPatch> patches) {
        List<JsonPatch> copy = new ArrayList<>(patches.size());
        for (JsonPatch patch : patches) {
            copy.add(copyPatch(patch));
        }
        return Collections.unmodifiableList(copy);
    }

    private static JsonPatch copyPatch(JsonPatch patch) {
        Objects.requireNonNull(patch, "patch");
        switch (patch.getOp()) {
            case ADD:
                return JsonPatch.add(patch.getPath(), patch.getVal().clone());
            case REPLACE:
                return JsonPatch.replace(patch.getPath(), patch.getVal().clone());
            case REMOVE:
                return JsonPatch.remove(patch.getPath());
            default:
                throw new IllegalStateException("Unsupported patch op: " + patch.getOp());
        }
    }

    public static final class Preview {
        private final String originScope;
        private final List<PatchPreview> patches;

        private Preview(String originScope, List<PatchPreview> patches) {
            this.originScope = PointerUtils.normalizeScope(originScope);
            this.patches = Collections.unmodifiableList(new ArrayList<>(patches));
        }

        private static Preview empty(String originScope) {
            return new Preview(originScope, Collections.<PatchPreview>emptyList());
        }

        String originScope() {
            return originScope;
        }

        int size() {
            return patches.size();
        }

        PatchPreview patch(int index) {
            return index >= 0 && index < patches.size() ? patches.get(index) : null;
        }
    }

    static final class PatchPreview {
        private final String originScope;
        private final JsonPatch patch;
        private final FrozenNode baseCanonical;
        private final FrozenNode baseResolved;
        private final BatchPatchResult result;
        private final String valueBlueId;

        private PatchPreview(String originScope,
                             JsonPatch patch,
                             FrozenNode baseCanonical,
                             FrozenNode baseResolved,
                             BatchPatchResult result) {
            this.originScope = PointerUtils.normalizeScope(originScope);
            this.patch = patch;
            this.baseCanonical = baseCanonical;
            this.baseResolved = baseResolved;
            this.result = result;
            this.valueBlueId = patch.getOp() == JsonPatch.Op.REMOVE
                    ? null
                    : FrozenNode.fromResolvedNode(patch.getVal()).blueId();
        }

        String originScope() {
            return originScope;
        }

        FrozenNode baseCanonical() {
            return baseCanonical;
        }

        FrozenNode baseResolved() {
            return baseResolved;
        }

        BatchPatchResult result() {
            return result;
        }

        boolean matches(JsonPatch candidate) {
            if (candidate == null
                    || patch.getOp() != candidate.getOp()
                    || !PointerUtils.normalizePointer(patch.getPath())
                    .equals(PointerUtils.normalizePointer(candidate.getPath()))) {
                return false;
            }
            if (patch.getOp() == JsonPatch.Op.REMOVE) {
                return true;
            }
            return valueBlueId.equals(FrozenNode.fromResolvedNode(candidate.getVal()).blueId());
        }
    }
}
