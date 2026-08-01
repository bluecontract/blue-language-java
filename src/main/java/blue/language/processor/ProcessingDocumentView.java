package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.util.PointerUtils;
import blue.language.processor.util.ProcessorPointerConstants;
import blue.language.snapshot.FrozenNode;
import blue.language.snapshot.ResolvedSnapshot;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.JsonPointer;

import java.util.Collections;
import java.util.Objects;

/**
 * Representation-blind read boundary for one PROCESS invocation.
 *
 * <p>This component is the only runtime service that decides whether a read
 * comes from the caller-owned selected node, the canonical snapshot lane, or
 * the resolved snapshot lane. Returned mutable values preserve the historical
 * API while frozen accessors avoid materialization on internal paths.</p>
 */
final class ProcessingDocumentView {

    private final DocumentProcessingRuntime runtime;

    ProcessingDocumentView(DocumentProcessingRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    Node document() {
        if (!runtime.selectedDocumentBacked && runtime.snapshot != null) {
            return runtime.snapshot.resolvedRoot();
        }
        runtime.syncMaterializedView();
        return runtime.materializedView.root();
    }

    Node selectedDocument() {
        if (runtime.snapshot != null) {
            return runtime.snapshot.canonicalRoot();
        }
        runtime.syncMaterializedView();
        return runtime.materializedView.root();
    }

    ResolvedSnapshot snapshot() {
        if (runtime.snapshot == null && runtime.snapshotManager != null) {
            runtime.snapshot = runtime.snapshotFromDocument(
                    runtime.materializedView.root());
            if (!runtime.selectedDocumentBacked) {
                runtime.materializedView.replaceWithSnapshot(runtime.snapshot);
            }
        }
        return runtime.snapshot;
    }

    Node resolvedNodeAt(String path) {
        String normalized = PointerUtils.normalizePointer(path);
        ResolvedSnapshot current = snapshot();
        return current != null
                ? current.resolvedNodeAt(normalized)
                : runtime.materializedView.nodeAt(normalized);
    }

    FrozenNode resolvedFrozenAt(String path) {
        String normalized = PointerUtils.normalizePointer(path);
        ResolvedSnapshot current = snapshot();
        if (current != null) {
            return current.resolvedAt(normalized);
        }
        Node node = runtime.materializedView.nodeAt(normalized);
        return node != null ? FrozenNode.fromResolvedNode(node) : null;
    }

    Node canonicalNodeAt(String path) {
        String normalized = PointerUtils.normalizePointer(path);
        ResolvedSnapshot current = snapshot();
        return current != null
                ? current.canonicalNodeAt(normalized)
                : runtime.materializedView.nodeAt(normalized);
    }

    FrozenNode canonicalFrozenAt(String path) {
        String normalized = PointerUtils.normalizePointer(path);
        ResolvedSnapshot current = snapshot();
        if (current != null) {
            return current.canonicalAt(normalized);
        }
        Node node = runtime.materializedView.nodeAt(normalized);
        return node != null ? FrozenNode.fromResolvedNode(node) : null;
    }

    FrozenNode selectedFrozenAt(String path) {
        String normalized = PointerUtils.normalizePointer(path);
        if (!runtime.selectedDocumentBacked) {
            ResolvedSnapshot current = snapshot();
            if (current != null) {
                return current.canonicalAt(normalized);
            }
        }
        Node node = runtime.materializedView.nodeAt(normalized);
        return node != null ? FrozenNode.fromResolvedNode(node) : null;
    }

    Node nodeAt(String path) {
        String normalized = PointerUtils.normalizePointer(path);
        return runtime.snapshot != null
                ? runtime.snapshot.resolvedNodeAt(normalized)
                : runtime.materializedView.nodeAt(normalized);
    }

    boolean contains(String path) {
        return nodeAt(path) != null;
    }

    FrozenNode canonicalRootWithoutResolution() {
        return runtime.snapshot != null
                ? runtime.snapshot.frozenCanonicalRoot()
                : FrozenNode.fromResolvedNode(runtime.materializedView.root());
    }

    FrozenNode identityChargeCanonicalRoot() {
        return runtime.snapshot != null
                ? runtime.snapshot.frozenCanonicalRoot()
                : FrozenNode.fromNode(runtime.materializedView.copyRoot());
    }

    FrozenNode resolvedRootWithoutResolution() {
        return runtime.snapshot != null
                ? runtime.snapshot.frozenResolvedRoot()
                : FrozenNode.fromResolvedNode(runtime.materializedView.root());
    }

    FrozenNode contractRecognitionScope(
            FrozenNode selectedScope,
            FrozenNode resolvedScope) {
        if (!hasContractProperties(selectedScope)
                || !hasContractProperties(resolvedScope)) {
            return resolvedScope;
        }
        ProcessingSnapshotManager manager = runtime.currentSnapshotManager();
        Node recognitionScope = null;
        FrozenNode refreshedEffectiveScope = null;
        for (String key : selectedScope.getContracts().getProperties().keySet()) {
            FrozenNode effectiveContract =
                    resolvedScope.getContracts().property(key);
            if (effectiveContract == null
                    || !effectiveContract.isReferenceOnly()) {
                continue;
            }
            if (manager == null) {
                throw new IllegalStateException(
                        "Contract Recognition Resolution requires provider "
                                + "content for contract '" + key
                                + "' at scope without a "
                                + "ProcessingSnapshotManager");
            }
            FrozenNode materialized =
                    manager.materializeVerifiedReference(effectiveContract);
            if (materialized.getType() == null) {
                if (refreshedEffectiveScope == null) {
                    refreshedEffectiveScope =
                            DocumentProcessingRuntime.resolveCanonicalTransient(
                                    manager,
                                    selectedScope,
                                    Collections.singleton(JsonPointer.ROOT),
                                    runtime.executableBodyFieldsByType)
                                    .frozenResolvedRoot();
                }
                FrozenNode refreshedContract =
                        refreshedEffectiveScope.getContracts() != null
                                ? refreshedEffectiveScope.getContracts()
                                        .property(key)
                                : null;
                if (refreshedContract != null
                        && !refreshedContract.isReferenceOnly()) {
                    materialized = refreshedContract;
                }
            }
            if (recognitionScope == null) {
                recognitionScope = resolvedScope.toNode();
            }
            recognitionScope.getContracts()
                    .properties(key, materialized.toNode());
        }
        return recognitionScope != null
                ? FrozenNode.fromResolvedNode(recognitionScope)
                : resolvedScope;
    }

    FrozenNode capturePreInitializationScopeDocument(String scopePath) {
        String normalized = PointerUtils.normalizeScope(scopePath);
        runtime.syncMaterializedView();
        ResolvedSnapshot current = snapshot();
        FrozenNode exactScope = current != null
                ? current.canonicalAt(normalized)
                : null;
        if (exactScope != null) {
            return exactScope;
        }
        Node selectedScope = runtime.materializedView.nodeAt(normalized);
        if (selectedScope == null) {
            throw new IllegalStateException(
                    "Exact selected scope is absent at " + normalized);
        }
        return FrozenNode.fromUncheckedCanonicalNode(selectedScope.clone());
    }

    String calculatePreInitializationScopeNodeBlueId(String scopePath) {
        String normalized = PointerUtils.normalizeScope(scopePath);
        runtime.observe(
                ProcessingMetricId
                        .INITIALIZATION_DOCUMENT_ID_CONTENT_BLUE_ID_CALCULATIONS,
                1L);
        runtime.syncMaterializedView();
        ResolvedSnapshot current = snapshot();
        FrozenNode exactScope = current != null
                ? current.canonicalAt(normalized)
                : null;
        if (exactScope != null) {
            return exactScope.blueId();
        }
        Node selectedScope = runtime.materializedView.nodeAt(normalized);
        if (selectedScope == null) {
            throw new IllegalStateException(
                    "Exact selected scope is absent at " + normalized);
        }
        return BlueIdCalculator.calculateBlueId(selectedScope);
    }

    WorkingDocument workingDocument(
            String originScopePath,
            PatchSource mutablePatchSource) {
        String normalizedScope = PointerUtils.normalizeScope(originScopePath);
        ResolvedSnapshot current = runtime.snapshot;
        boolean materializedFallback = false;
        if (current == null && runtime.snapshotManager != null) {
            runtime.syncMaterializedView();
            current = runtime.snapshotFromDocument(
                    runtime.materializedView.copyRoot());
            runtime.snapshot = current;
            runtime.sharedSnapshotVersion = runtime.stateVersion;
            materializedFallback = true;
        }
        if (current != null) {
            return new WorkingDocument(
                    normalizedScope,
                    current.frozenCanonicalRoot(),
                    current.frozenResolvedRoot(),
                    runtime.conformanceEngine,
                    runtime.conformancePlannerOverride,
                    runtime.currentSnapshotManager(),
                    current,
                    materializedFallback,
                    !runtime.selectedDocumentBacked,
                    mutablePatchSource,
                    runtime.metrics,
                    runtime.scopes().keySet(),
                    runtime.executableBodyFieldsByType,
                    current.isResolutionComplete());
        }
        Node root = runtime.materializedView.copyRoot();
        FrozenNode canonical =
                FrozenNode.fromUncheckedCanonicalNode(root.clone());
        FrozenNode resolved = FrozenNode.fromResolvedNode(root.clone());
        return new WorkingDocument(
                normalizedScope,
                canonical,
                resolved,
                runtime.conformanceEngine,
                runtime.conformancePlannerOverride,
                runtime.currentSnapshotManager(),
                null,
                true,
                false,
                mutablePatchSource,
                runtime.metrics,
                runtime.scopes().keySet(),
                runtime.executableBodyFieldsByType,
                true);
    }

    boolean hasInitializationMarker(String scopePath) {
        String pointer = PointerUtils.resolvePointer(
                scopePath, ProcessorPointerConstants.RELATIVE_INITIALIZED);
        FrozenNode selected = selectedFrozenAt(pointer);
        Node marker = selected != null ? selected.toNode() : null;
        if (marker == null) {
            return false;
        }
        ProcessorEngine.validateInitializationMarker(marker, pointer);
        return true;
    }

    ProcessorEngine.TerminationMarker terminationMarker(String scopePath) {
        String pointer = PointerUtils.resolvePointer(
                scopePath, ProcessorPointerConstants.RELATIVE_TERMINATED);
        FrozenNode selected = selectedFrozenAt(pointer);
        Node marker = selected != null ? selected.toNode() : null;
        return marker != null
                ? ProcessorEngine.validateTerminationMarker(marker, pointer)
                : null;
    }

    private boolean hasContractProperties(FrozenNode scope) {
        return scope != null
                && scope.getContracts() != null
                && scope.getContracts().getProperties() != null;
    }
}
