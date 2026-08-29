package blue.language.processor;

import blue.language.conformance.ConformanceEngine;
import blue.language.merge.IncrementalValueResolutionRequest;
import blue.language.merge.ResolvedSnapshot;
import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import blue.language.snapshot.FrozenNode;

import java.util.Collection;
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
    private final Set<String> opaqueManagedPaths;

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
        this.opaqueManagedPaths = new LinkedHashSet<String>(
                admitted.opaqueManagedPaths());
    }

    private ManagedDocumentOverlaySnapshotManager(
            ProcessingSnapshotManager delegate,
            Map<String, FrozenNode> exactNodesByBlueId,
            Set<String> opaqueManagedPaths) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.exactNodesByBlueId = exactNodesByBlueId;
        this.opaqueManagedPaths = opaqueManagedPaths;
    }

    @Override
    public ResolvedSnapshot fromDocument(Node document) {
        return opaqueManagedPaths.isEmpty()
                ? delegate.fromDocument(document)
                : delegate.fromDocumentPreservingPaths(
                        document, opaqueManagedPaths);
    }

    @Override
    public ResolvedSnapshot fromDocumentTransient(Node document) {
        return opaqueManagedPaths.isEmpty()
                ? delegate.fromDocumentTransient(document)
                : delegate.fromDocumentTransientPreservingPaths(
                        document, opaqueManagedPaths);
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
        return current != null
                ? current
                : delegate.materializeVerifiedExactReference(checked);
    }

    /** Whether one selected Root path remains owned by closure processing. */
    boolean isOpaqueManagedPath(String absolutePointer) {
        return opaqueManagedPaths.contains(
                blue.language.processor.util.PointerUtils.normalizePointer(
                        absolutePointer));
    }

    @Override
    public ProcessingSnapshotManager transientSequence() {
        return new ManagedDocumentOverlaySnapshotManager(
                delegate.transientSequence(),
                exactNodesByBlueId,
                opaqueManagedPaths);
    }

    @Override
    public ProcessingSnapshotManager forkTransientSequence() {
        return new ManagedDocumentOverlaySnapshotManager(
                delegate.forkTransientSequence(),
                exactNodesByBlueId,
                opaqueManagedPaths);
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
        result.addAll(opaqueManagedPaths);
        return result;
    }
}
