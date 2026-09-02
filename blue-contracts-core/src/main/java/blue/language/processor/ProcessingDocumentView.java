package blue.language.processor;

import blue.language.identity.CanonicalIdentityInputBuilder;
import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.identity.NodeToBlueIdInput;
import blue.language.model.Node;
import blue.language.processor.util.PointerUtils;
import blue.language.processor.util.ProcessorPointerConstants;
import blue.language.snapshot.FrozenNode;
import blue.language.merge.ResolvedSnapshot;
import blue.language.model.wire.JsonPointer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
    private final Map<String, ResolvedScopeView> resolvedDeferredScopes =
            new LinkedHashMap<>();
    private ReferenceTransparentPathAccess transparentPathAccess;
    private ProcessingSnapshotManager transparentPathManager;
    private long exactReferencedScopesVersion = Long.MIN_VALUE;
    private ResolvedSnapshot canonicalIdentitySnapshot;
    private ProcessingSnapshotManager canonicalIdentityManager;
    private long canonicalIdentityVersion = Long.MIN_VALUE;

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
        if (!runtime.selectedDocumentBacked && runtime.snapshot != null) {
            return runtime.snapshot.sourceRoot();
        }
        runtime.syncMaterializedView();
        return runtime.materializedView.root();
    }

    ResolvedSnapshot snapshot() {
        if (runtime.snapshot == null && runtime.snapshotManager != null) {
            runtime.snapshot = runtime.snapshotFromDocument(
                    runtime.materializedView.root());
            runtime.retainEntrySnapshot(runtime.snapshot);
            if (!runtime.selectedDocumentBacked) {
                runtime.materializedView.replaceWithSnapshot(runtime.snapshot);
            }
        }
        return runtime.snapshot;
    }

    Node resolvedNodeAt(String path) {
        ResolvedScopeView view = scopeViewAt(path);
        return view != null && view.resolved() != null
                ? view.resolved().toNode()
                : null;
    }

    FrozenNode resolvedFrozenAt(String path) {
        ResolvedScopeView view = scopeViewAt(path);
        return view != null ? view.resolved() : null;
    }

    /** Returns one selected/effective scope with its exact producing proof. */
    ResolvedScopeView scopeViewAt(String path) {
        String normalized = PointerUtils.normalizePointer(path);
        ResolvedSnapshot current = snapshot();
        if (current != null) {
            FrozenNode selected = selectedSourceFrozenAt(normalized);
            ProcessingSnapshotManager manager =
                    runtime.currentSnapshotManager();
            if (selected != null
                    && manager != null
                    && ((selected.isReferenceOnly()
                            && !isOpaqueManagedPath(
                                    manager, normalized))
                    || requiresDeferredScopeResolution(
                            normalized, current, selected))) {
                return resolvedDeferredScope(
                        normalized, selected, manager);
            }
            ResolvedScopeView transparent = transparentPathAccess()
                    .scopeAt(
                            current.frozenSourceRoot(),
                            current.frozenResolvedRoot(),
                            current.canonicalTypeIdentities(),
                            normalized);
            if (transparent != null) {
                FrozenNode exactSelected = selectedFrozenAt(normalized);
                return exactSelected != null
                        ? transparent.withSelected(exactSelected)
                        : transparent;
            }
            FrozenNode resolved = current.resolvedAt(normalized);
            return resolved != null || selected != null
                    ? new ResolvedScopeView(
                            selected,
                            resolved,
                            current.canonicalTypeIdentities())
                    : null;
        }
        Node node = runtime.materializedView.nodeAt(normalized);
        if (node == null) {
            return null;
        }
        FrozenNode frozen = FrozenNode.fromResolvedNode(node);
        return new ResolvedScopeView(
                frozen,
                frozen,
                CanonicalTypeIdentityLookup.incomplete());
    }

    Node canonicalNodeAt(String path) {
        String normalized = PointerUtils.normalizePointer(path);
        ResolvedSnapshot current = snapshot();
        FrozenNode opaqueReference = opaqueManagedSourceReference(
                current, normalized);
        if (opaqueReference != null) {
            return opaqueReference.toNode();
        }
        ResolvedSnapshot canonical = current != null
                ? canonicalIdentitySnapshot(current)
                : null;
        return canonical != null
                ? canonical.canonicalNodeAt(normalized)
                : runtime.materializedView.nodeAt(normalized);
    }

    FrozenNode canonicalFrozenAt(String path) {
        String normalized = PointerUtils.normalizePointer(path);
        ResolvedSnapshot current = snapshot();
        if (current != null) {
            FrozenNode opaqueReference = opaqueManagedSourceReference(
                    current, normalized);
            if (opaqueReference != null) {
                return opaqueReference;
            }
            return canonicalIdentitySnapshot(current).canonicalAt(normalized);
        }
        Node node = runtime.materializedView.nodeAt(normalized);
        return node != null ? FrozenNode.fromResolvedNode(node) : null;
    }

    FrozenNode selectedFrozenAt(String path) {
        String normalized = PointerUtils.normalizePointer(path);
        FrozenNode selected = selectedSourceFrozenAt(normalized);
        if (selected == null || !selected.isReferenceOnly()) {
            return selected;
        }
        ProcessingSnapshotManager manager = runtime.currentSnapshotManager();
        return manager != null
                && !isOpaqueManagedPath(manager, normalized)
                ? exactReferencedScope(normalized, selected, manager)
                : selected;
    }

    private static boolean isOpaqueManagedPath(
            ProcessingSnapshotManager manager,
            String normalizedPath) {
        return manager instanceof ManagedDocumentOverlaySnapshotManager
                && ((ManagedDocumentOverlaySnapshotManager) manager)
                        .isOpaqueManagedPath(normalizedPath);
    }

    private FrozenNode opaqueManagedSourceReference(
            ResolvedSnapshot current,
            String normalizedPath) {
        if (current == null
                || !isOpaqueManagedPath(
                        runtime.currentSnapshotManager(), normalizedPath)) {
            return null;
        }
        FrozenNode source = current.sourceAt(normalizedPath);
        return source != null && source.isReferenceOnly()
                ? source
                : null;
    }

    /**
     * Returns the authored contribution without opening a pure reference.
     * Reference materialization is deliberately layered above this lookup so
     * selected and resolved reads can share one verified exact provider value.
     */
    private FrozenNode selectedSourceFrozenAt(String normalizedPath) {
        if (!runtime.selectedDocumentBacked) {
            ResolvedSnapshot current = snapshot();
            if (current != null) {
                return current.sourceAt(normalizedPath);
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
    private ResolvedScopeView resolvedDeferredScope(
            String normalizedPath,
            FrozenNode selected,
            ProcessingSnapshotManager manager) {
        resetScopeCachesIfStateChanged();
        ResolvedScopeView cached = resolvedDeferredScopes.get(normalizedPath);
        if (cached != null) {
            return cached;
        }
        FrozenNode exact = selected.isReferenceOnly()
                ? exactReferencedScope(
                        normalizedPath, selected, manager)
                : selected;
        ResolvedSnapshot deferred = runtime.strictPlatformInvocation
                ? DocumentProcessingRuntime
                        .resolveCanonicalTransientIncludingTypeContracts(
                                manager,
                                exact,
                                Collections.singleton(JsonPointer.ROOT),
                                runtime.executableBodyFieldsByType)
                : DocumentProcessingRuntime.resolveCanonicalTransient(
                        manager,
                        exact,
                        Collections.singleton(JsonPointer.ROOT),
                        runtime.executableBodyFieldsByType);
        ResolvedScopeView resolved = new ResolvedScopeView(
                exact,
                deferred.frozenResolvedRoot(),
                deferred.canonicalTypeIdentities());
        resolvedDeferredScopes.put(normalizedPath, resolved);
        return resolved;
    }

    private boolean requiresDeferredScopeResolution(
            String normalizedPath,
            ResolvedSnapshot current,
            FrozenNode selected) {
        if (!runtime.strictPlatformInvocation
                || current.isResolutionComplete()
                || !runtime.evidenceScopePaths()
                        .contains(normalizedPath)) {
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
        ResolvedSnapshot current = snapshot();
        if (current != null) {
            FrozenNode transparent = transparentPathAccess()
                    .resolvedAt(
                            current.frozenSourceRoot(),
                            current.frozenResolvedRoot(),
                            normalized);
            return transparent != null ? transparent.toNode() : null;
        }
        return runtime.materializedView.nodeAt(normalized);
    }

    private ReferenceTransparentPathAccess transparentPathAccess() {
        ProcessingSnapshotManager manager =
                runtime.currentSnapshotManager();
        if (transparentPathAccess == null
                || transparentPathManager != manager) {
            transparentPathManager = manager;
            transparentPathAccess = new ReferenceTransparentPathAccess(
                    manager,
                    runtime.strictPlatformInvocation,
                    runtime.executableBodyFieldsByType);
        }
        return transparentPathAccess;
    }

    boolean contains(String path) {
        return nodeAt(path) != null;
    }

    FrozenNode selectedRootWithoutResolution() {
        if (runtime.selectedDocumentBacked) {
            runtime.syncMaterializedView();
            return FrozenNode.fromResolvedNode(
                    runtime.materializedView.copyRoot());
        }
        return runtime.snapshot != null
                ? runtime.snapshot.frozenSourceRoot()
                : FrozenNode.fromResolvedNode(runtime.materializedView.root());
    }

    FrozenNode identityChargeCanonicalRoot() {
        ResolvedSnapshot current = runtime.snapshot;
        if (current != null) {
            if (current.hasCanonicalIdentity()) {
                return current.frozenCanonicalRoot();
            }
            ProcessingSnapshotManager manager = runtime.currentSnapshotManager();
            if (canonicalIdentitySnapshot != null
                    && canonicalIdentityVersion == runtime.stateVersion
                    && canonicalIdentityManager == manager) {
                return canonicalIdentitySnapshot.frozenCanonicalRoot();
            }
            try {
                return CanonicalIdentityEvidence.projectSnapshot(current)
                        .frozenCanonicalRoot();
            } catch (CanonicalTypeIdentityEvidenceUnion
                    .MissingEvidenceException missingEvidence) {
                return canonicalIdentitySnapshot(current)
                        .frozenCanonicalRoot();
            }
        }
        Node source = runtime.materializedView.copyRoot();
        if (!CanonicalIdentityEvidence
                .requiresEffectiveTypeIdentity(source)) {
            return FrozenNode.fromNode(source);
        }
        ProcessingSnapshotManager manager =
                runtime.currentSnapshotManager();
        if (manager == null) {
            throw new IllegalStateException(
                    "Inline effective type identity requires the active "
                            + "ProcessingSnapshotManager");
        }
        ResolvedSnapshot authoritative = Objects.requireNonNull(
                manager.fromDocumentTransientForCanonicalIdentity(source),
                "canonicalIdentitySnapshot");
        if (!authoritative.isResolutionComplete()
                || !authoritative.hasCanonicalIdentity()) {
            throw new IllegalStateException(
                    "Inline effective type identity requires a complete "
                            + "authoritative resolution");
        }
        canonicalIdentitySnapshot = authoritative;
        canonicalIdentityManager = manager;
        canonicalIdentityVersion = runtime.stateVersion;
        return authoritative.frozenCanonicalRoot();
    }

    FrozenNode resolvedRootWithoutResolution() {
        return runtime.snapshot != null
                ? runtime.snapshot.frozenResolvedRoot()
                : FrozenNode.fromResolvedNode(runtime.materializedView.root());
    }

    ResolvedScopeView contractRecognitionScope(
            ResolvedScopeView scope) {
        return contractRecognitionScope(
                scope, null);
    }

    ResolvedScopeView contractRecognitionScope(
            ResolvedScopeView scope,
            Set<String> recognizedContractKeys) {
        ResolvedScopeView checkedScope = Objects.requireNonNull(
                scope, "scope");
        FrozenNode selectedScope = checkedScope.selected();
        FrozenNode resolvedScope = checkedScope.resolved();
        if (!hasContractProperties(selectedScope)
                || !hasContractProperties(resolvedScope)) {
            return checkedScope;
        }
        ProcessingSnapshotManager manager = runtime.currentSnapshotManager();
        Node recognitionScope = null;
        FrozenNode refreshedEffectiveScope = null;
        CanonicalTypeIdentityLookup refreshedScopeIdentities = null;
        List<CanonicalTypeIdentityLookup> materializedIdentities =
                new ArrayList<>();
        for (String key : selectedScope.getContracts().getProperties().keySet()) {
            if (recognizedContractKeys != null
                    && !recognizedContractKeys.contains(key)) {
                continue;
            }
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
            FrozenNode exact = ExecutableBodyPathCatalog
                    .materializeVerifiedExact(
                            manager,
                            effectiveContract,
                            "Contract Recognition Resolution");
            FrozenNode materialized = exact;
            if (exact.getType() != null) {
                ResolvedSnapshot materializedSnapshot =
                        resolveRecognitionScope(manager, exact);
                materialized = materializedSnapshot.frozenResolvedRoot();
                materializedIdentities.add(
                        materializedSnapshot.canonicalTypeIdentities());
            } else {
                if (refreshedEffectiveScope == null) {
                    ResolvedSnapshot refreshed = resolveRecognitionScope(
                            manager,
                            withExactSelectedContract(
                                    selectedScope,
                                    key,
                                    exact));
                    refreshedEffectiveScope =
                            refreshed.frozenResolvedRoot();
                    refreshedScopeIdentities =
                            refreshed.canonicalTypeIdentities();
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
        if (recognitionScope == null) {
            return checkedScope;
        }
        if (refreshedScopeIdentities != null) {
            materializedIdentities.add(refreshedScopeIdentities);
        }
        return checkedScope.withRecombinedResolved(
                FrozenNode.fromResolvedNode(recognitionScope),
                materializedIdentities.toArray(
                        new CanonicalTypeIdentityLookup[0]));
    }

    /** Reuses already-verified contract Source during effective refresh. */
    private FrozenNode withExactSelectedContract(
            FrozenNode selectedScope,
            String key,
            FrozenNode exactContract) {
        Node exactScope = selectedScope.toNode();
        exactScope.getContracts().properties(
                key,
                exactContract.toNode());
        return FrozenNode.fromNode(exactScope);
    }

    private ResolvedSnapshot resolveRecognitionScope(
            ProcessingSnapshotManager manager,
            FrozenNode exactScope) {
        return DocumentProcessingRuntime
                .resolveCanonicalTransientIncludingTypeContracts(
                        manager,
                        exactScope,
                        Collections.singleton(JsonPointer.ROOT),
                        runtime.executableBodyFieldsByType);
    }

    FrozenNode capturePreInitializationScopeDocument(String scopePath) {
        String normalized = PointerUtils.normalizeScope(scopePath);
        runtime.syncMaterializedView();
        ResolvedSnapshot current = snapshot();
        if (current != null && current.hasCanonicalIdentity()) {
            FrozenNode canonical = current.canonicalAt(normalized);
            if (canonical != null) {
                return canonical;
            }
        }
        ResolvedScopeView scope = scopeViewAt(normalized);
        if (scope == null || scope.selected() == null
                || scope.resolved() == null) {
            throw new IllegalStateException(
                    "Exact selected scope is absent at " + normalized);
        }
        Node exactSource = NodeToBlueIdInput.stripResolvedBlueIdMetadata(
                scope.selected().toNode());
        if (!CanonicalIdentityEvidence
                .requiresEffectiveTypeIdentity(exactSource)) {
            return FrozenNode.fromNode(exactSource);
        }
        Node resolvedScope = scope.resolved().toNode();
        CanonicalTypeIdentityLookup scopedEvidence =
                CanonicalTypeIdentityEvidenceUnion
                        .establishForResolvedGraph(
                                resolvedScope,
                                Collections.singletonList(
                                        scope.canonicalTypeIdentities()));
        Node canonical = new CanonicalIdentityInputBuilder().build(
                resolvedScope, exactSource, scopedEvidence);
        return FrozenNode.fromNode(canonical);
    }

    WorkingDocument workingDocument(
            String originScopePath,
            PatchSource mutablePatchSource) {
        String normalizedScope = PointerUtils.normalizeScope(originScopePath);
        ResolvedSnapshot current = runtime.snapshot;
        boolean materializedFallback = false;
        if (current == null && runtime.snapshotManager != null) {
            runtime.syncMaterializedView();
            current = runtime.snapshotFromDocumentTransient(
                    runtime.materializedView.copyRoot());
            runtime.snapshot = current;
            runtime.retainEntrySnapshot(current);
            runtime.sharedSnapshotVersion = runtime.stateVersion;
            materializedFallback = true;
        }
        if (current != null) {
            FrozenNode selectedRoot = runtime.selectedDocumentBacked
                    ? FrozenNode.fromResolvedNode(
                            runtime.materializedView.copyRoot())
                    : current.frozenSourceRoot();
            if (!runtime.selectedDocumentBacked
                    && !current.isSourceBacked()) {
                selectedRoot = current.frozenCanonicalRoot();
            }
            return new WorkingDocument(
                    normalizedScope,
                    selectedRoot,
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
                    current.isResolutionComplete(),
                    runtime.strictPlatformInvocation,
                    runtime.selectedDocumentBacked
                            || current.isSourceBacked());
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
                true,
                runtime.strictPlatformInvocation,
                true);
    }

    private ResolvedSnapshot canonicalIdentitySnapshot(
            ResolvedSnapshot current) {
        if (current.hasCanonicalIdentity()) {
            return current;
        }
        ProcessingSnapshotManager manager = runtime.currentSnapshotManager();
        if (manager == null) {
            throw new IllegalStateException(
                    "Whole-document canonical identity requires the active "
                            + "ProcessingSnapshotManager");
        }
        if (canonicalIdentitySnapshot != null
                && canonicalIdentityVersion == runtime.stateVersion
                && canonicalIdentityManager == manager) {
            return canonicalIdentitySnapshot;
        }
        ResolvedSnapshot authoritative = Objects.requireNonNull(
                manager.fromDocumentTransientForCanonicalIdentity(
                        current.sourceRoot()),
                "canonicalIdentitySnapshot");
        if (!authoritative.isResolutionComplete()
                || !authoritative.hasCanonicalIdentity()) {
            throw new IllegalStateException(
                    "Whole-document canonical identity requires a complete "
                            + "authoritative resolution");
        }
        canonicalIdentitySnapshot = authoritative;
        canonicalIdentityManager = manager;
        canonicalIdentityVersion = runtime.stateVersion;
        return authoritative;
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
