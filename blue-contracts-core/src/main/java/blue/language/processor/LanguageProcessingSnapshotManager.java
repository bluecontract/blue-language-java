package blue.language.processor;

import blue.language.api.BlueOperationOutcome;
import blue.language.api.BlueOperationResult;
import blue.language.conformance.ConformanceEngine;
import blue.language.identity.CanonicalTypeIdentityEvidence;
import blue.language.merge.IncrementalValueResolutionRequest;
import blue.language.merge.ResolvedSnapshot;
import blue.language.merge.TypeEvidenceResolution;
import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import blue.language.runtime.LanguageProcessing;
import blue.language.runtime.LanguageProcessing.ExactResolutionOverlay;
import blue.language.snapshot.FrozenNode;

import java.util.Collection;
import java.util.Objects;

/** Contracts adapter over the Language-owned processing bridge. */
final class LanguageProcessingSnapshotManager
        implements ProcessingSnapshotManager {

    private final LanguageProcessing.Scope scope;

    LanguageProcessingSnapshotManager(
            LanguageProcessing.Scope scope) {
        this.scope = Objects.requireNonNull(scope, "scope");
    }

    static LanguageProcessing.Observer observer(
            ProcessingObserver observer) {
        ProcessingObserver target = Objects.requireNonNull(
                observer, "observer");
        return new LanguageProcessing.Observer() {
            @Override
            public void snapshotCacheHit() {
                record(target,
                        ProcessingMetricId
                                .PROCESSING_SNAPSHOT_CACHE_HITS,
                        1L);
            }

            @Override
            public void snapshotCacheMiss() {
                record(target,
                        ProcessingMetricId
                                .PROCESSING_SNAPSHOT_CACHE_MISSES,
                        1L);
            }

            @Override
            public void snapshotCacheLookupNanos(long nanos) {
                record(target,
                        ProcessingMetricId
                                .PROCESSING_SNAPSHOT_CACHE_LOOKUP_NANOS,
                        nanos);
            }
        };
    }

    @Override
    public ResolvedSnapshot fromDocument(Node document) {
        return scope.resolve(document);
    }

    @Override
    public ResolvedSnapshot fromCanonicalTransient(
            FrozenNode canonicalRoot, Collection<String> preservedPaths) {
        return scope.resolveCanonicalTransient(canonicalRoot, preservedPaths);
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
        return scope.resolvePreservingPaths(
                document, preservedPaths);
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
        return new LanguageProcessingSnapshotManager(
                scope.transientSequence());
    }

    @Override
    public ProcessingSnapshotManager forkTransientSequence() {
        return new LanguageProcessingSnapshotManager(
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
        return scope.transientConformanceEngine(
                conformanceEngine);
    }

    @Override
    public ResolvedSnapshot applyPatch(
            ResolvedSnapshot snapshot,
            JsonPatch patch) {
        return scope.applyPatch(snapshot, patch);
    }

    @Override
    public ResolvedSnapshot cacheSnapshot(
            ResolvedSnapshot snapshot) {
        return scope.publish(snapshot);
    }

    private static void record(
            ProcessingObserver observer,
            ProcessingMetricId metric,
            long value) {
        try {
            observer.record(ProcessingObservation.of(metric, value));
        } catch (ThreadDeath failure) {
            throw failure;
        } catch (VirtualMachineError failure) {
            throw failure;
        } catch (Throwable ignored) {
            // Operational telemetry cannot change Contracts semantics.
        }
    }
}
