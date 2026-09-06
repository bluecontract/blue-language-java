package blue.language.processor;

import blue.language.conformance.ConformanceEngine;
import blue.language.merge.IncrementalValueResolutionRequest;
import blue.language.merge.ResolvedSnapshot;
import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import blue.language.snapshot.FrozenNode;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Invocation-local exact-provider overlay for an isolated managed Root. */
final class ManagedDocumentOverlaySnapshotManager
        implements ProcessingSnapshotManager {

    private final ProcessingSnapshotManager delegate;
    private final Map<String, FrozenNode> exactNodesByBlueId;
    private final Map<String, String> expectedManagedBlueIdsByPath;
    private final Set<String> admittedManagedBlueIds;

    ManagedDocumentOverlaySnapshotManager(
            ProcessingSnapshotManager delegate,
            ManagedDocumentResolutionOverlay overlay) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        ManagedDocumentResolutionOverlay admitted = Objects.requireNonNull(
                overlay, "overlay");
        LinkedHashMap<String, FrozenNode> nodes =
                new LinkedHashMap<String, FrozenNode>();
        for (Map.Entry<String, Node> entry
                : admitted.exactNodesByBlueId().entrySet()) {
            nodes.put(entry.getKey(), FrozenNode.fromNode(entry.getValue()));
        }
        this.exactNodesByBlueId = nodes;
        this.admittedManagedBlueIds = new LinkedHashSet<String>(admitted.admittedManagedBlueIds());
        this.expectedManagedBlueIdsByPath =
                new LinkedHashMap<String, String>(admitted.expectedManagedBlueIdsByPath());
    }

    /**
     * All transient views of this isolated step share these invocation-private maps. Update
     * them only after its synchronous continuation returns, before another handler can read.
     * A missing body stays a named evidence need; refresh never consults ambient storage.
     */
    void refresh(ManagedDocumentResolutionOverlay overlay) {
        if (overlay == null) return;
        Map<String, FrozenNode> nodes = new LinkedHashMap<String, FrozenNode>();
        for (Map.Entry<String, Node> entry : overlay.exactNodesByBlueId().entrySet())
            nodes.put(entry.getKey(), FrozenNode.fromNode(entry.getValue()));
        exactNodesByBlueId.clear(); exactNodesByBlueId.putAll(nodes);
        expectedManagedBlueIdsByPath.clear(); expectedManagedBlueIdsByPath.putAll(overlay.expectedManagedBlueIdsByPath());
        admittedManagedBlueIds.clear(); admittedManagedBlueIds.addAll(overlay.admittedManagedBlueIds());
    }

    private ManagedDocumentOverlaySnapshotManager(
            ProcessingSnapshotManager delegate,
            Map<String, FrozenNode> exactNodesByBlueId,
            Map<String, String> expectedManagedBlueIdsByPath,
            Set<String> admittedManagedBlueIds) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.exactNodesByBlueId = exactNodesByBlueId;
        this.expectedManagedBlueIdsByPath = expectedManagedBlueIdsByPath;
        this.admittedManagedBlueIds = admittedManagedBlueIds;
    }

    @Override
    public ResolvedSnapshot fromDocument(Node document) {
        return expectedManagedBlueIdsByPath.isEmpty()
                ? delegate.fromDocument(document)
                : delegate.fromDocumentPreservingPaths(
                        document, expectedManagedBlueIdsByPath.keySet());
    }

    @Override
    public ResolvedSnapshot fromDocumentTransient(Node document) {
        return expectedManagedBlueIdsByPath.isEmpty()
                ? delegate.fromDocumentTransient(document)
                : delegate.fromDocumentTransientPreservingPaths(
                        document, expectedManagedBlueIdsByPath.keySet());
    }

    @Override
    public ResolvedSnapshot fromDocumentPreservingPaths(
            Node document,
            Collection<String> preservedPaths) {
        return delegate.fromDocumentPreservingPaths(
                document, union(preservedPaths));
    }

    @Override
    public ResolvedSnapshot fromDocumentTransientPreservingPaths(
            Node document,
            Collection<String> preservedPaths) {
        return delegate.fromDocumentTransientPreservingPaths(
                document, union(preservedPaths));
    }

    @Override
    public FrozenNode materializeVerifiedExactReference(
            FrozenNode reference) {
        FrozenNode checked = Objects.requireNonNull(reference, "reference");
        if (!checked.isReferenceOnly()) {
            return checked;
        }
        FrozenNode current = exactNodesByBlueId.get(
                checked.getReferenceBlueId());
        if (current == null && admittedManagedBlueIds.contains(checked.getReferenceBlueId())) {
            throw new ExecutionEvidenceUnavailableException("Managed reference requires exact admitted content",
                    Collections.singletonList(checked.getReferenceBlueId()));
        }
        return current != null
                ? current
                : delegate.materializeVerifiedExactReference(checked);
    }

    /** Whether one selected Root path remains owned by closure processing. */
    boolean isOpaqueManagedPath(String absolutePointer) {
        return expectedManagedBlueIdsByPath.containsKey(
                blue.language.processor.util.PointerUtils.normalizePointer(
                        absolutePointer));
    }

    /**
     * Opens one managed occurrence only through its exact invocation-local
     * path and identity binding.
     *
     * <p>This method deliberately never falls through to {@link #delegate}.
     * A missing overlay value must suspend the owning invocation rather than
     * acquire ambient content that was not admitted for this occurrence.</p>
     */
    FrozenNode materializeVerifiedManagedRead(
            String absolutePointer,
            FrozenNode reference) {
        String normalized =
                blue.language.processor.util.PointerUtils.normalizePointer(
                        absolutePointer);
        String expectedBlueId = expectedManagedBlueIdsByPath.get(normalized);
        if (expectedBlueId == null) {
            throw new InvalidExecutionEvidenceException(
                    "Managed read path has no invocation-local occurrence "
                            + "binding: " + normalized,
                    ProcessorErrorCategory.InvalidProcessingDocument);
        }
        FrozenNode checked = Objects.requireNonNull(reference, "reference");
        if (!checked.isReferenceOnly()) {
            throw new InvalidExecutionEvidenceException(
                    "Managed read path is not a compact exact reference: "
                            + normalized,
                    ProcessorErrorCategory.InvalidProcessingDocument);
        }
        String actualBlueId = checked.getReferenceBlueId();
        if (!expectedBlueId.equals(actualBlueId)) {
            throw new InvalidExecutionEvidenceException(
                    "Managed read reference disagrees with invocation-local "
                            + "occurrence evidence at " + normalized
                            + ": expected " + expectedBlueId
                            + " but found " + actualBlueId,
                    ProcessorErrorCategory.InvalidProcessingDocument);
        }
        FrozenNode exact = exactNodesByBlueId.get(expectedBlueId);
        if (exact == null) {
            throw new ExecutionEvidenceUnavailableException(
                    "Managed read requires invocation-local exact content for "
                            + expectedBlueId + " at " + normalized,
                    Collections.singletonList(expectedBlueId));
        }
        try {
            return ExecutableBodyPathCatalog.validateMaterializedExact(
                    checked, exact, "Managed reference read");
        } catch (ProcessorFailureException mismatch) {
            throw new InvalidExecutionEvidenceException(
                    mismatch.getMessage(), mismatch.errorCategory());
        }
    }

    @Override
    public ProcessingSnapshotManager transientSequence() {
        return new ManagedDocumentOverlaySnapshotManager(
                delegate.transientSequence(),
                exactNodesByBlueId,
                expectedManagedBlueIdsByPath, admittedManagedBlueIds);
    }

    @Override
    public ProcessingSnapshotManager forkTransientSequence() {
        return new ManagedDocumentOverlaySnapshotManager(
                delegate.forkTransientSequence(),
                exactNodesByBlueId,
                expectedManagedBlueIdsByPath, admittedManagedBlueIds);
    }

    @Override
    public void retainTransientState(
            FrozenNode canonicalRoot,
            FrozenNode resolvedRoot) {
        delegate.retainTransientState(canonicalRoot, resolvedRoot);
    }

    @Override
    public void releaseTransientState() {
        delegate.releaseTransientState();
    }

    @Override
    public boolean isTransientStateCurrent() {
        return delegate.isTransientStateCurrent();
    }

    @Override
    public boolean supportsIncrementalValueResolution() {
        return delegate.supportsIncrementalValueResolution();
    }

    @Override
    public boolean supportsIncrementalValueResolution(
            IncrementalValueResolutionRequest request) {
        return delegate.supportsIncrementalValueResolution(request);
    }

    @Override
    public ConformanceEngine transientConformanceEngine(
            ConformanceEngine conformanceEngine) {
        return delegate.transientConformanceEngine(conformanceEngine);
    }

    @Override
    public ResolvedSnapshot applyPatch(
            ResolvedSnapshot snapshot,
            JsonPatch patch) {
        return delegate.applyPatch(snapshot, patch);
    }

    @Override
    public ResolvedSnapshot cacheSnapshot(ResolvedSnapshot snapshot) {
        return delegate.cacheSnapshot(snapshot);
    }

    private Set<String> union(Collection<String> supplied) {
        LinkedHashSet<String> result = new LinkedHashSet<String>();
        if (supplied != null) {
            result.addAll(supplied);
        }
        result.addAll(expectedManagedBlueIdsByPath.keySet());
        return result;
    }
}
