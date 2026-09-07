package blue.language.processor;

import blue.language.conformance.ConformanceEngine;
import blue.language.identity.CanonicalTypeIdentityEvidence;
import blue.language.merge.IncrementalValueResolutionRequest;
import blue.language.merge.ResolvedSnapshot;
import blue.language.merge.TypeEvidenceResolution;
import blue.language.model.Node;
import blue.language.model.NodePathEditor;
import blue.language.processor.model.JsonPatch;
import blue.language.runtime.LanguageProcessing.ExactResolutionOverlay;
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
    private final ExactResolutionOverlay exactResolutionOverlay;

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
        this.exactNodesByBlueId = Collections.unmodifiableMap(nodes);
        this.expectedManagedBlueIdsByPath =
                Collections.unmodifiableMap(new LinkedHashMap<String, String>(
                        admitted.expectedManagedBlueIdsByPath()));
        this.exactResolutionOverlay = admitted.exactResolutionOverlay();
    }

    private ManagedDocumentOverlaySnapshotManager(
            ProcessingSnapshotManager delegate,
            Map<String, FrozenNode> exactNodesByBlueId,
            Map<String, String> expectedManagedBlueIdsByPath,
            ExactResolutionOverlay exactResolutionOverlay) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.exactNodesByBlueId = exactNodesByBlueId;
        this.expectedManagedBlueIdsByPath = expectedManagedBlueIdsByPath;
        this.exactResolutionOverlay = Objects.requireNonNull(
                exactResolutionOverlay, "exactResolutionOverlay");
    }

    @Override
    public ProcessingSnapshotManager forValueIdentity() {
        return expectedManagedBlueIdsByPath.isEmpty() ? this
                : new ManagedDocumentOverlaySnapshotManager(
                        delegate.forValueIdentity(), exactNodesByBlueId,
                        Collections.<String, String>emptyMap(), exactResolutionOverlay);
    }

    @Override
    public ResolvedSnapshot fromDocument(Node document) {
        return expectedManagedBlueIdsByPath.isEmpty()
                ? delegate.fromDocument(document)
                : delegate.fromDocumentPreservingPaths(
                        document, expectedManagedBlueIdsByPath.keySet());
    }

    @Override
    public ResolvedSnapshot fromCanonicalTransient(
            FrozenNode canonicalRoot, Collection<String> preservedPaths) {
        return delegate.fromCanonicalTransient(canonicalRoot, union(preservedPaths));
    }

    @Override
    public ResolvedSnapshot fromDocumentTransient(Node document) {
        return expectedManagedBlueIdsByPath.isEmpty()
                ? delegate.fromDocumentTransient(document)
                : delegate.fromDocumentTransientPreservingPaths(
                        document, expectedManagedBlueIdsByPath.keySet());
    }

    @Override
    public ResolvedSnapshot fromDocumentTransientForCanonicalIdentity(
            Node document) {
        if (!expectedManagedBlueIdsByPath.isEmpty()) {
            validateManagedBindings(document);
        }
        if (exactNodesByBlueId.isEmpty()) {
            return delegate.fromDocumentTransientForCanonicalIdentity(
                    document);
        }
        return requireCompleteCanonicalIdentity(
                delegate.fromDocumentTransientForCanonicalIdentity(
                        document, exactResolutionOverlay()));
    }

    @Override
    public ResolvedSnapshot fromDocumentTransientForCanonicalIdentity(
            Node document,
            ExactResolutionOverlay exactResolutionOverlay) {
        Objects.requireNonNull(
                exactResolutionOverlay, "exactResolutionOverlay");
        if (!expectedManagedBlueIdsByPath.isEmpty()) {
            validateManagedBindings(document);
        }
        if (exactNodesByBlueId.isEmpty()) {
            return delegate.fromDocumentTransientForCanonicalIdentity(
                    document, exactResolutionOverlay);
        }
        return requireCompleteCanonicalIdentity(
                delegate.fromDocumentTransientForCanonicalIdentity(
                        document,
                        exactResolutionOverlay()
                                .followedBy(exactResolutionOverlay)));
    }

    @Override
    public CanonicalTypeIdentityEvidence resolveTypeDeclarationIdentity(
            Node declaration) {
        return exactNodesByBlueId.isEmpty()
                ? delegate.resolveTypeDeclarationIdentity(declaration)
                : delegate.resolveTypeDeclarationIdentity(
                        declaration, exactResolutionOverlay());
    }

    @Override
    public CanonicalTypeIdentityEvidence resolveTypeDeclarationIdentity(
            Node declaration,
            ExactResolutionOverlay exactResolutionOverlay) {
        Objects.requireNonNull(exactResolutionOverlay, "exactResolutionOverlay");
        return exactNodesByBlueId.isEmpty()
                ? delegate.resolveTypeDeclarationIdentity(
                        declaration, exactResolutionOverlay)
                : delegate.resolveTypeDeclarationIdentity(
                        declaration,
                        exactResolutionOverlay()
                                .followedBy(exactResolutionOverlay));
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

    @Override
    public TypeEvidenceResolution materializeVerifiedTypeReference(
            FrozenNode reference) {
        return delegate.materializeVerifiedTypeReference(reference);
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
                expectedManagedBlueIdsByPath,
                exactResolutionOverlay);
    }

    @Override
    public ProcessingSnapshotManager forkTransientSequence() {
        return new ManagedDocumentOverlaySnapshotManager(
                delegate.forkTransientSequence(),
                exactNodesByBlueId,
                expectedManagedBlueIdsByPath,
                exactResolutionOverlay);
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

    private void validateManagedBindings(Node document) {
        Node checked = Objects.requireNonNull(document, "document");
        for (Map.Entry<String, String> entry
                : expectedManagedBlueIdsByPath.entrySet()) {
            String path = entry.getKey();
            String expectedBlueId = entry.getValue();
            Node reference = NodePathEditor.getOrNull(checked, path);
            if (reference == null || !reference.isReferenceOnly()
                    || !expectedBlueId.equals(reference.getBlueId())) {
                throw new InvalidExecutionEvidenceException(
                        "Canonical identity rebuild requires the exact managed "
                                + "reference bound at " + path,
                        ProcessorErrorCategory.InvalidProcessingDocument);
            }
            if (!exactNodesByBlueId.containsKey(expectedBlueId)) {
                throw new ExecutionEvidenceUnavailableException(
                        "Canonical identity rebuild requires invocation-local "
                                + "exact content for " + expectedBlueId
                                + " at " + path,
                        Collections.singletonList(expectedBlueId));
            }
        }
    }

    private ExactResolutionOverlay exactResolutionOverlay() {
        return exactResolutionOverlay;
    }

    private static ResolvedSnapshot requireCompleteCanonicalIdentity(
            ResolvedSnapshot snapshot) {
        ResolvedSnapshot checked = Objects.requireNonNull(
                snapshot, "canonicalIdentitySnapshot");
        checked.canonicalTypeIdentities().requireCompleteCoverage();
        if (!checked.hasCanonicalIdentity()) {
            throw new IllegalStateException(
                    "Managed canonical identity resolution did not establish "
                            + "a whole-document identity");
        }
        return checked;
    }
}
