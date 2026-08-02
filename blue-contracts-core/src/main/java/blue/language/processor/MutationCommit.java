package blue.language.processor;

import blue.language.model.wire.BlueLanguageConstants;
import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.processor.util.ProcessorPointerConstants;
import blue.language.processor.util.PointerUtils;
import blue.language.snapshot.FrozenNode;
import blue.language.merge.ResolvedSnapshot;
import blue.language.model.wire.JsonPointer;
import blue.language.model.NodePathEditor;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Atomically publishes processor-owned direct writes to authoritative state.
 *
 * <p>The selected-node and snapshot lanes remain separate because they have
 * different provider and cache publication boundaries, but both retain the
 * same exact patch classification and rollback rules.</p>
 */
final class MutationCommit {

    private static final String DIRECT_WRITE_ANCESTOR_PURPOSE =
            "Direct-write ancestor";

    private final DocumentProcessingRuntime runtime;

    MutationCommit(DocumentProcessingRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    void publishSelected(String path, Node value) {
        Node selectedRollback = runtime.materializedView.copyRoot();
        ResolvedSnapshot snapshotRollback = runtime.snapshot;
        try {
            Node tentativeSelected = selectedRollback.clone();
            materializeReferenceAncestors(tentativeSelected, path);
            Node before = ImmutablePatchPlanner.readNode(
                    tentativeSelected, path);
            JsonPatch patch = directWritePatch(path, before, value);
            if (patch == null) {
                return;
            }
            applyMaterializedWrite(tentativeSelected, path, value);
            ResolvedSnapshot authoritative =
                    runtime.snapshotFromDocument(tentativeSelected);
            boolean published = authoritative.isResolutionComplete();
            ResolvedSnapshot cached =
                    DocumentProcessingRuntime.cacheSnapshotIfComplete(
                            runtime.currentSnapshotManager(), authoritative);
            runtime.materializedView.replaceWith(tentativeSelected);
            runtime.snapshot = cached;
            runtime.materializedViewStale = false;
            runtime.markStateAdvanced(published);
        } catch (RuntimeException failure) {
            runtime.materializedView.replaceWith(selectedRollback);
            runtime.snapshot = snapshotRollback;
            runtime.materializedViewStale = false;
            throw failure;
        }
    }

    void publishSnapshot(String path, Node value) {
        ResolvedSnapshot snapshotRollback = runtime.snapshot;
        try {
            PatchPlanningContext planning =
                    runtime.planningContext(runtime.materializedView.root());
            FrozenNode before = planning.canonicalPlanner().read(path);
            Node beforeNode = before != null ? before.toNode() : null;
            JsonPatch snapshotPatch =
                    directWritePatch(path, beforeNode, value);
            if (snapshotPatch == null) {
                return;
            }
            ImmutablePatchPlanner.PatchPlan canonicalPlan =
                    planning.canonicalPlanner()
                            .planWithExactReplacement(
                                    JsonPointer.ROOT, snapshotPatch);
            ResolvedSnapshot next;
            try {
                next = planning.resolveCanonical(canonicalPlan.root());
            } catch (RuntimeException resolutionFailure) {
                if (!isTerminationMarkerProviderFailure(
                        path, value, resolutionFailure)) {
                    throw resolutionFailure;
                }
                ImmutablePatchPlanner.PatchPlan resolvedPlan =
                        planning.resolvedPlanner()
                                .planWithExactReplacement(
                                        JsonPointer.ROOT, snapshotPatch);
                next = DocumentProcessingRuntime.snapshotWithCompleteness(
                        canonicalPlan.root(),
                        resolvedPlan.root(),
                        planning.isResolutionComplete(),
                        false);
            }
            boolean published = next.isResolutionComplete();
            runtime.snapshot =
                    DocumentProcessingRuntime.cacheSnapshotIfComplete(
                            runtime.currentSnapshotManager(), next);
            runtime.commitMaterializedSnapshot(runtime.snapshot);
            runtime.markStateAdvanced(published);
        } catch (RuntimeException failure) {
            runtime.snapshot = snapshotRollback;
            throw failure;
        }
    }

    private void materializeReferenceAncestors(Node root, String path) {
        List<String> segments = JsonPointer.split(path);
        ProcessingSnapshotManager manager =
                runtime.currentSnapshotManager();
        for (int depth = 0; depth < segments.size(); depth++) {
            String prefix = JsonPointer.toPointer(
                    segments.subList(0, depth));
            Node ancestor = NodePathEditor.getOrNull(root, prefix);
            if (ancestor == null) {
                return;
            }
            if (!ancestor.isReferenceOnly()) {
                continue;
            }
            if (manager == null) {
                throw new IllegalStateException(
                        "Direct-write ancestor materialization requires the "
                                + "active ProcessingSnapshotManager");
            }
            Node exact = ExecutableBodyPathCatalog.materializeVerifiedExact(
                    manager,
                    FrozenNode.fromNode(ancestor),
                    DIRECT_WRITE_ANCESTOR_PURPOSE)
                    .toNode();
            NodePathEditor.put(root, prefix, exact);
        }
    }

    private boolean isTerminationMarkerProviderFailure(
            String path,
            Node value,
            RuntimeException failure) {
        String normalizedPath = PointerUtils.canonicalizePointer(path);
        Node type = value != null ? value.getType() : null;
        return normalizedPath.endsWith(
                ProcessorPointerConstants.RELATIVE_TERMINATED)
                && type != null
                && RuntimeBlueIds.PROCESSING_TERMINATED_MARKER.equals(
                        type.getBlueId())
                && ScopeIdentityErrorMapper.isProviderIdentityFailure(failure);
    }

    private static JsonPatch directWritePatch(
            String path,
            Node before,
            Node value) {
        if (before == null && value == null) {
            return null;
        }
        if (value == null) {
            return JsonPatch.remove(path);
        }
        return before == null
                ? JsonPatch.add(path, value.clone())
                : JsonPatch.replace(path, value.clone());
    }

    private static void applyMaterializedWrite(
            Node root,
            String path,
            Node value) {
        if (value == null) {
            removeMaterializedPath(root, path);
        } else {
            NodePathEditor.put(root, path, value.clone());
        }
    }

    private static void removeMaterializedPath(Node root, String path) {
        List<String> segments = JsonPointer.split(path);
        if (segments.isEmpty()) {
            root.replaceWith(new Node());
            return;
        }
        List<String> parentSegments = new ArrayList<>(
                segments.subList(0, segments.size() - 1));
        Node parent = NodePathEditor.getOrNull(
                root, JsonPointer.toPointer(parentSegments));
        if (parent == null) {
            return;
        }
        String leaf = segments.get(segments.size() - 1);
        if (BlueLanguageConstants.OBJECT_TYPE.equals(leaf)) {
            parent.type((Node) null);
        } else if (BlueLanguageConstants.OBJECT_ITEM_TYPE.equals(leaf)) {
            parent.itemType((Node) null);
        } else if (BlueLanguageConstants.OBJECT_KEY_TYPE.equals(leaf)) {
            parent.keyType((Node) null);
        } else if (BlueLanguageConstants.OBJECT_VALUE_TYPE.equals(leaf)) {
            parent.valueType((Node) null);
        } else if (BlueLanguageConstants.OBJECT_BLUE.equals(leaf)) {
            parent.blue(null);
        } else if (ProcessorContractConstants.KEY_CONTRACTS.equals(leaf)) {
            parent.contracts(null);
        } else if (JsonPointer.isArrayIndexSegment(leaf)
                && parent.getItems() != null
                && !"-".equals(leaf)) {
            int index = Integer.parseInt(leaf);
            if (index >= 0 && index < parent.getItems().size()) {
                parent.getItems().remove(index);
            }
        } else if (parent.getProperties() != null) {
            parent.getProperties().remove(leaf);
        }
    }
}
