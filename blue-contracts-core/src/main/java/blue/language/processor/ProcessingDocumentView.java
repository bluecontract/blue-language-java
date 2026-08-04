package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.util.PointerUtils;
import blue.language.processor.util.ProcessorPointerConstants;
import blue.language.snapshot.FrozenNode;
import blue.language.merge.ResolvedSnapshot;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.wire.JsonPointer;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
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
    private final Map<String, FrozenNode> exactReferencedScopes =
            new LinkedHashMap<>();
    private final Map<String, FrozenNode> resolvedDeferredScopes =
            new LinkedHashMap<>();
    private long exactReferencedScopesVersion = Long.MIN_VALUE;

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
            FrozenNode selected = selectedCanonicalFrozenAt(normalized);
            ProcessingSnapshotManager manager =
                    runtime.currentSnapshotManager();
            if (selected != null
                    && manager != null
                    && (selected.isReferenceOnly()
                    || requiresDeferredScopeResolution(
                            current, selected))) {
                return resolvedDeferredScope(
                        normalized, selected, manager);
            }
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
        FrozenNode selected = selectedCanonicalFrozenAt(normalized);
        if (selected == null || !selected.isReferenceOnly()) {
            return selected;
        }
        ProcessingSnapshotManager manager = runtime.currentSnapshotManager();
        return manager != null
                ? exactReferencedScope(normalized, selected, manager)
                : selected;
    }

    /**
     * Returns the authored contribution without opening a pure reference.
     * Reference materialization is deliberately layered above this lookup so
     * selected and resolved reads can share one verified exact provider value.
     */
    private FrozenNode selectedCanonicalFrozenAt(String normalizedPath) {
        if (!runtime.selectedDocumentBacked) {
            ResolvedSnapshot current = snapshot();
            if (current != null) {
                return current.canonicalAt(normalizedPath);
            }
        }
        Node node = runtime.materializedView.nodeAt(normalizedPath);
        return node != null ? FrozenNode.fromResolvedNode(node) : null;
    }

    /**
     * Materializes a selected pure-reference scope once per processing state.
     * The exact provider value remains canonical; its executable bodies are
     * preserved separately when the corresponding effective scope is built.
     */
    private FrozenNode exactReferencedScope(
            String normalizedPath,
            FrozenNode reference,
            ProcessingSnapshotManager manager) {
        resetScopeCachesIfStateChanged();
        FrozenNode cached = exactReferencedScopes.get(normalizedPath);
        if (cached != null) {
            return cached;
        }
        FrozenNode exact = ExecutableBodyPathCatalog.materializeVerifiedExact(
                manager, reference, "Selected processing scope");
        exactReferencedScopes.put(normalizedPath, exact);
        return exact;
    }

    /**
     * Resolves one exact scope from an intentionally incomplete admission
     * snapshot. A top-level pure reference is admitted as exact canonical
     * content before processing; its descendants are therefore concrete even
     * though their declared types have not yet contributed effective
     * contracts. Treating that concrete fragment as already resolved would
     * make handler discovery, gas, and must-understand behavior depend on the
     * caller's physical representation.
     */
    private FrozenNode resolvedDeferredScope(
            String normalizedPath,
            FrozenNode selected,
            ProcessingSnapshotManager manager) {
        resetScopeCachesIfStateChanged();
        FrozenNode cached = resolvedDeferredScopes.get(normalizedPath);
        if (cached != null) {
            return cached;
        }
        FrozenNode exact = selected.isReferenceOnly()
                ? exactReferencedScope(
                        normalizedPath, selected, manager)
                : selected;
        FrozenNode resolved = DocumentProcessingRuntime
                .resolveCanonicalTransient(
                        manager,
                        exact,
                        Collections.singleton(JsonPointer.ROOT),
                        runtime.executableBodyFieldsByType)
                .frozenResolvedRoot();
        resolvedDeferredScopes.put(normalizedPath, resolved);
        return resolved;
    }

    private boolean requiresDeferredScopeResolution(
            ResolvedSnapshot current,
            FrozenNode selected) {
        if (current.isResolutionComplete()) {
            return false;
        }
        return selected.getType() != null
                || selected.getItemType() != null
                || selected.getKeyType() != null
                || selected.getValueType() != null
                || selected.getContracts() != null
                && selected.getContracts().isReferenceOnly();
    }

    private void resetScopeCachesIfStateChanged() {
        if (exactReferencedScopesVersion == runtime.stateVersion) {
            return;
        }
        exactReferencedScopes.clear();
        resolvedDeferredScopes.clear();
        exactReferencedScopesVersion = runtime.stateVersion;
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
        return DirectBlueIdCalculator.calculateBlueId(selectedScope);
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
                    runtime.entryEmbeddedScopePlans(),
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
                runtime.entryEmbeddedScopePlans(),
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
