package blue.language.conformance.contracts;

import blue.language.api.BlueOperationOutcome;
import blue.language.api.BlueOperationResult;
import blue.language.conformance.ConformanceEngine;
import blue.language.identity.CanonicalTypeIdentityEvidence;
import blue.language.merge.IncrementalValueResolutionRequest;
import blue.language.merge.ResolvedSnapshot;
import blue.language.merge.TypeEvidenceResolution;
import blue.language.model.Node;
import blue.language.processor.ExecutionEvidenceUnavailableException;
import blue.language.processor.InvalidExecutionEvidenceException;
import blue.language.processor.ProcessingSnapshotManager;
import blue.language.processor.model.JsonPatch;
import blue.language.runtime.LanguageProcessing;
import blue.language.runtime.LanguageProcessing.ExactResolutionOverlay;
import blue.language.snapshot.FrozenNode;

import java.util.Collection;
import java.util.Objects;

/**
 * Conformance ownership adapter over the public Language processing scope.
 *
 * <p>Every semantic operation delegates to {@link LanguageProcessing.Scope}.
 * This class has no provider capability and performs no identity calculation,
 * graph resolution, patching, caching, or evidence reconstruction.</p>
 */
final class LanguageProcessingScopeSnapshotManager
        implements ProcessingSnapshotManager {

    private final LanguageProcessing.Scope scope;

    LanguageProcessingScopeSnapshotManager(LanguageProcessing.Scope scope) {
        this.scope = Objects.requireNonNull(scope, "scope");
    }

    @Override
    public ResolvedSnapshot fromDocument(Node document) {
        return scope.resolve(document);
    }

    @Override
    public ResolvedSnapshot fromDocumentTransient(Node document) {
        return scope.resolveTransient(document);
    }

    @Override
    public ResolvedSnapshot fromDocumentTransientForCanonicalIdentity(
            Node document,
            ExactResolutionOverlay exactResolutionOverlay) {
        return scope.resolveTransientForCanonicalIdentity(
                document, exactResolutionOverlay);
    }

    @Override
    public CanonicalTypeIdentityEvidence resolveTypeDeclarationIdentity(
            Node declaration) {
        return scope.resolveTypeDeclarationIdentity(declaration);
    }

    @Override
    public CanonicalTypeIdentityEvidence resolveTypeDeclarationIdentity(
            Node declaration,
            ExactResolutionOverlay exactResolutionOverlay) {
        return scope.resolveTypeDeclarationIdentity(
                declaration, exactResolutionOverlay);
    }

    @Override
    public ResolvedSnapshot fromDocumentPreservingPaths(
            Node document,
            Collection<String> preservedPaths) {
        return scope.resolvePreservingPaths(document, preservedPaths);
    }

    @Override
    public ResolvedSnapshot fromDocumentTransientPreservingPaths(
            Node document,
            Collection<String> preservedPaths) {
        return scope.resolveTransientPreservingPaths(
                document, preservedPaths);
    }

    @Override
    public FrozenNode materializeVerifiedExactReference(
            FrozenNode reference) {
        BlueOperationResult<FrozenNode> result =
                scope.materializeVerifiedExactReference(reference);
        BlueOperationOutcome outcome = result.outcome();
        if (outcome == BlueOperationOutcome.ESTABLISHED) {
            return result.requireEstablished();
        }
        if (outcome == BlueOperationOutcome.ABSENT) {
            return null;
        }
        String reason = result.reason().orElse(
                "Exact execution evidence could not be established");
        if (outcome == BlueOperationOutcome.INCOMPLETE) {
            throw new ExecutionEvidenceUnavailableException(
                    reason, result.outstandingBlueIds());
        }
        throw new InvalidExecutionEvidenceException(reason);
    }

    @Override
    public TypeEvidenceResolution materializeVerifiedTypeReference(
            FrozenNode reference) {
        return scope.runtimeAccess()
                .materializeTypeReferenceForMatching(reference);
    }

    @Override
    public ProcessingSnapshotManager transientSequence() {
        return new LanguageProcessingScopeSnapshotManager(
                scope.transientSequence());
    }

    @Override
    public ProcessingSnapshotManager forkTransientSequence() {
        return new LanguageProcessingScopeSnapshotManager(
                scope.forkTransientSequence());
    }

    @Override
    public void retainTransientState(
            FrozenNode canonicalRoot,
            FrozenNode resolvedRoot) {
        scope.retainTransientState(canonicalRoot, resolvedRoot);
    }

    @Override
    public void releaseTransientState() {
        scope.close();
    }

    @Override
    public boolean isTransientStateCurrent() {
        return scope.isTransientStateCurrent();
    }

    @Override
    public boolean supportsIncrementalValueResolution() {
        return scope.supportsIncrementalValueResolution();
    }

    @Override
    public boolean supportsIncrementalValueResolution(
            IncrementalValueResolutionRequest request) {
        return scope.supportsIncrementalValueResolution(request);
    }

    @Override
    public ConformanceEngine transientConformanceEngine(
            ConformanceEngine conformanceEngine) {
        return scope.transientConformanceEngine(conformanceEngine);
    }

    @Override
    public ResolvedSnapshot applyPatch(
            ResolvedSnapshot snapshot,
            JsonPatch patch) {
        return scope.applyPatch(snapshot, patch);
    }

    @Override
    public ResolvedSnapshot cacheSnapshot(ResolvedSnapshot snapshot) {
        return scope.publish(snapshot);
    }
}
