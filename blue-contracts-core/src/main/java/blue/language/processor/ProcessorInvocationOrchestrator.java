package blue.language.processor;

import blue.language.model.Node;
import blue.language.merge.ResolvedSnapshot;
import blue.language.model.wire.JsonPointer;
import java.util.Objects;

/**
 * Admits public processor invocations and maps deterministic failures to their
 * non-committing result shape.
 *
 * <p>This boundary deliberately owns the mutable-document and immutable-
 * snapshot variants together. Both variants run the same phase pipeline and
 * differ only in how an early failure retains the caller's input.</p>
 */
final class ProcessorInvocationOrchestrator {

    private ProcessorInvocationOrchestrator() {
    }

    static DocumentProcessingResult initialize(
            ProcessorInvocationServices owner,
            Node document) {
        Objects.requireNonNull(document, "document");
        DocumentProcessingResult invalid =
                ProcessingInputAdmission.validateDocument(document);
        if (invalid != null) {
            return invalid;
        }
        if (ProcessorMarkerStore.isInitialized(document)) {
            throw new IllegalStateException("Document already initialized");
        }
        ProcessorInvocationState execution = null;
        try {
            execution = new ProcessorInvocationState(owner, document.clone());
            execution.initializeScope(JsonPointer.ROOT, true);
        } catch (RunTerminationException ignored) {
            // Initialization terminated after establishing deterministic run state.
            if (execution == null) {
                return DocumentProcessingResult.runtimeFatal(
                        document.clone(),
                        "Initialization terminated before run state was available",
                        ProcessorErrorCategory.RuntimeExecutionFailure);
            }
        } catch (MustUnderstandFailureException exception) {
            return DocumentProcessingResult.capabilityFailure(
                    document.clone(),
                    exception.getMessage(),
                    exception.errorCategory());
        } catch (SubscriptionSurfaceInvalidException exception) {
            if (execution == null) {
                return DocumentProcessingResult.nonCommitting(
                        document.clone(),
                        0L,
                        ProcessorStatus.SUBSCRIPTION_SURFACE_INVALID,
                        exception.diagnostic());
            }
            execution.fail(
                    ProcessorStatus.SUBSCRIPTION_SURFACE_INVALID,
                    exception.diagnostic());
        } catch (IllegalArgumentException exception) {
            if (ScopeIdentityErrorMapper.isProviderIdentityFailure(exception)) {
                throw exception;
            }
            return DocumentProcessingResult.capabilityFailure(
                    document.clone(),
                    ProcessorEngine.deterministicMessage(
                            exception,
                            "Invalid initialization document"),
                    ProcessorErrorCategory.InvalidProcessingDocument);
        }
        return execution.result();
    }

    static DocumentProcessingResult initialize(
            ProcessorInvocationServices owner,
            ResolvedSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        DocumentProcessingResult invalid = ProcessingInputAdmission
                .validateDocument(snapshot.frozenResolvedRoot());
        if (invalid != null) {
            return invalid;
        }
        if (ProcessorMarkerStore.isInitialized(snapshot)) {
            throw new IllegalStateException("Document already initialized");
        }
        ProcessorInvocationState execution = null;
        try {
            execution = new ProcessorInvocationState(owner, snapshot);
            execution.initializeScope(JsonPointer.ROOT, true);
        } catch (RunTerminationException ignored) {
            // Initialization terminated after establishing deterministic run state.
            if (execution == null) {
                return DocumentProcessingResult.runtimeFatal(
                        snapshot.resolvedRoot(),
                        "Initialization terminated before run state was available",
                        ProcessorErrorCategory.RuntimeExecutionFailure);
            }
        } catch (MustUnderstandFailureException exception) {
            return DocumentProcessingResult.capabilityFailure(
                    snapshot.resolvedRoot(),
                    exception.getMessage(),
                    exception.errorCategory());
        } catch (SubscriptionSurfaceInvalidException exception) {
            if (execution == null) {
                return DocumentProcessingResult.nonCommitting(
                        snapshot.canonicalRoot(),
                        0L,
                        ProcessorStatus.SUBSCRIPTION_SURFACE_INVALID,
                        exception.diagnostic());
            }
            execution.fail(
                    ProcessorStatus.SUBSCRIPTION_SURFACE_INVALID,
                    exception.diagnostic());
        } catch (IllegalArgumentException exception) {
            if (ScopeIdentityErrorMapper.isProviderIdentityFailure(exception)) {
                throw exception;
            }
            return DocumentProcessingResult.capabilityFailure(
                    snapshot.resolvedRoot(),
                    ProcessorEngine.deterministicMessage(
                            exception,
                            "Invalid initialization document"),
                    ProcessorErrorCategory.InvalidProcessingDocument);
        }
        return execution.result();
    }

    static ProcessingDebugResult process(
            ProcessorInvocationServices owner,
            Node document,
            Node event,
            VerifiedExecutionEvidence evidence) {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(event, "event");
        ProcessingObserver observer = owner.observer();
        long processStart = System.nanoTime();
        long preprocessStart = System.nanoTime();
        ProcessorInvocationState execution = null;
        try {
            DocumentProcessingResult invalid =
                    ProcessingInputAdmission.validateDocument(document);
            if (invalid != null) {
                return new ProcessingDebugResult(
                        invalid,
                        ProcessingConformanceTrace.empty());
            }
            Node admitted = document.clone();
            ProcessorMarkerStore.collapseInitializationDocuments(admitted);
            execution = new ProcessorInvocationState(
                    owner,
                    admitted,
                    event,
                    evidence);
            execution.runtime().chargeProcessInvocation();
            if (execution.admitDirectRootState()) {
                recordPreprocessing(observer, preprocessStart);
                return execution.debugResult();
            }
            return new ProcessingPhasePipeline().execute(
                    execution,
                    event,
                    () -> recordPreprocessing(observer, preprocessStart));
        } catch (RunTerminationException ignored) {
            // Graceful Root termination or deterministic failure ends work.
        } catch (GasLimitExceededException exception) {
            if (execution == null) {
                return mutableEarlyFailure(
                        document,
                        exception.admittedGas(),
                        ProcessorStatus.GAS_LIMIT_EXCEEDED,
                        exception.diagnostic());
            }
            execution.fail(
                    ProcessorStatus.GAS_LIMIT_EXCEEDED,
                    exception.diagnostic());
        } catch (PortableLimitExceededException exception) {
            if (execution == null) {
                return mutableEarlyFailure(
                        document,
                        0L,
                        ProcessorStatus.PORTABLE_LIMIT_EXCEEDED,
                        exception.diagnostic());
            }
            execution.fail(
                    ProcessorStatus.PORTABLE_LIMIT_EXCEEDED,
                    exception.diagnostic());
        } catch (SubscriptionSurfaceInvalidException exception) {
            if (execution == null) {
                return mutableEarlyFailure(
                        document,
                        0L,
                        ProcessorStatus.SUBSCRIPTION_SURFACE_INVALID,
                        exception.diagnostic());
            }
            execution.fail(
                    ProcessorStatus.SUBSCRIPTION_SURFACE_INVALID,
                    exception.diagnostic());
        } catch (InvalidExecutionEvidenceException exception) {
            ProcessorDiagnostic diagnostic = ProcessorDiagnostic.of(
                    exception.errorCategory(),
                    ProcessorEngine.deterministicMessage(
                            exception,
                            "Invalid external delivery evidence"));
            if (execution == null) {
                return mutableEarlyFailure(
                        document,
                        0L,
                        ProcessorStatus.INVALID_PROCESSING_DOCUMENT,
                        diagnostic);
            }
            execution.fail(
                    ProcessorStatus.INVALID_PROCESSING_DOCUMENT,
                    diagnostic);
        } catch (MustUnderstandFailureException exception) {
            recordProcessDuration(observer, processStart);
            if (execution == null) {
                DocumentProcessingResult result =
                        DocumentProcessingResult.capabilityFailure(
                                document.clone(),
                                exception.getMessage(),
                                exception.errorCategory());
                return new ProcessingDebugResult(
                        result,
                        ProcessingConformanceTrace.empty());
            }
            execution.fail(
                    ProcessorStatus.CAPABILITY_FAILURE,
                    ProcessorDiagnostic.of(
                            exception.errorCategory(),
                            exception.getMessage()));
        } catch (RuntimeException exception) {
            rethrowIdentityBoundaryFailure(exception);
            if (execution == null) {
                return mutableEarlyFailure(
                        document,
                        0L,
                        ProcessorStatus.RUNTIME_FATAL,
                        ProcessorDiagnostic.of(
                                ProcessorErrorCategory.RuntimeExecutionFailure,
                                ProcessorEngine.deterministicMessage(
                                        exception,
                                        "Runtime processing failed")));
            }
            execution.fail(
                    ProcessorStatus.RUNTIME_FATAL,
                    ProcessorDiagnostic.of(
                            execution.fatalCategory(
                                    exception,
                                    ProcessorErrorCategory.RuntimeExecutionFailure),
                            ProcessorEngine.deterministicMessage(
                                    exception,
                                    "Runtime processing failed")));
        }
        return completeMutableResult(
                execution,
                observer,
                processStart);
    }

    static ProcessingDebugResult process(
            ProcessorInvocationServices owner,
            ResolvedSnapshot snapshot,
            Node event,
            VerifiedExecutionEvidence evidence) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(event, "event");
        ProcessingObserver observer = owner.observer();
        long processStart = System.nanoTime();
        long preprocessStart = System.nanoTime();
        ProcessorInvocationState execution = null;
        ProcessingDebugResult completedResult = null;
        try {
            DocumentProcessingResult invalid = ProcessingInputAdmission
                    .validateDocument(snapshot.frozenResolvedRoot());
            if (invalid != null) {
                return snapshotEarlyFailure(
                        snapshot,
                        invalid.totalGas(),
                        invalid.status(),
                        invalid.diagnostic());
            }
            execution = new ProcessorInvocationState(
                    owner,
                    snapshot,
                    event,
                    evidence);
            execution.runtime().chargeProcessInvocation();
            if (execution.admitDirectRootState()) {
                recordPreprocessing(observer, preprocessStart);
                return execution.debugResult();
            }
            completedResult = new ProcessingPhasePipeline().execute(
                    execution,
                    event,
                    () -> recordPreprocessing(observer, preprocessStart));
        } catch (RunTerminationException ignored) {
            // Processing terminated early; the execution still owns its result.
        } catch (GasLimitExceededException exception) {
            if (execution == null) {
                return snapshotEarlyFailure(
                        snapshot,
                        exception.admittedGas(),
                        ProcessorStatus.GAS_LIMIT_EXCEEDED,
                        exception.diagnostic());
            }
            execution.fail(
                    ProcessorStatus.GAS_LIMIT_EXCEEDED,
                    exception.diagnostic());
        } catch (PortableLimitExceededException exception) {
            if (execution == null) {
                return snapshotEarlyFailure(
                        snapshot,
                        0L,
                        ProcessorStatus.PORTABLE_LIMIT_EXCEEDED,
                        exception.diagnostic());
            }
            execution.fail(
                    ProcessorStatus.PORTABLE_LIMIT_EXCEEDED,
                    exception.diagnostic());
        } catch (SubscriptionSurfaceInvalidException exception) {
            if (execution == null) {
                return snapshotEarlyFailure(
                        snapshot,
                        0L,
                        ProcessorStatus.SUBSCRIPTION_SURFACE_INVALID,
                        exception.diagnostic());
            }
            execution.fail(
                    ProcessorStatus.SUBSCRIPTION_SURFACE_INVALID,
                    exception.diagnostic());
        } catch (InvalidExecutionEvidenceException exception) {
            ProcessorDiagnostic diagnostic = ProcessorDiagnostic.of(
                    exception.errorCategory(),
                    ProcessorEngine.deterministicMessage(
                            exception,
                            "Invalid external delivery evidence"));
            if (execution == null) {
                return snapshotEarlyFailure(
                        snapshot,
                        0L,
                        ProcessorStatus.INVALID_PROCESSING_DOCUMENT,
                        diagnostic);
            }
            execution.fail(
                    ProcessorStatus.INVALID_PROCESSING_DOCUMENT,
                    diagnostic);
        } catch (MustUnderstandFailureException exception) {
            recordProcessDuration(observer, processStart);
            if (execution == null) {
                return snapshotEarlyFailure(
                        snapshot,
                        0L,
                        ProcessorStatus.CAPABILITY_FAILURE,
                        ProcessorDiagnostic.of(
                                exception.errorCategory(),
                                exception.getMessage()));
            }
            execution.fail(
                    ProcessorStatus.CAPABILITY_FAILURE,
                    ProcessorDiagnostic.of(
                            exception.errorCategory(),
                            exception.getMessage()));
        } catch (RuntimeException exception) {
            rethrowIdentityBoundaryFailure(exception);
            if (execution == null) {
                return snapshotEarlyFailure(
                        snapshot,
                        0L,
                        ProcessorStatus.RUNTIME_FATAL,
                        ProcessorDiagnostic.of(
                                ProcessorErrorCategory.RuntimeExecutionFailure,
                                ProcessorEngine.deterministicMessage(
                                        exception,
                                        "Runtime processing failed")));
            }
            execution.fail(
                    ProcessorStatus.RUNTIME_FATAL,
                    ProcessorDiagnostic.of(
                            execution.fatalCategory(
                                    exception,
                                    ProcessorErrorCategory.RuntimeExecutionFailure),
                            ProcessorEngine.deterministicMessage(
                                    exception,
                                    "Runtime processing failed")));
        }
        return completeSnapshotResult(
                execution,
                completedResult,
                observer,
                processStart);
    }

    private static ProcessingDebugResult mutableEarlyFailure(
            Node document,
            long admittedGas,
            ProcessorStatus status,
            ProcessorDiagnostic diagnostic) {
        DocumentProcessingResult result = DocumentProcessingResult.nonCommitting(
                document.clone(),
                admittedGas,
                status,
                diagnostic);
        return new ProcessingDebugResult(
                result,
                ProcessingConformanceTrace.empty());
    }

    private static ProcessingDebugResult snapshotEarlyFailure(
            ResolvedSnapshot snapshot,
            long admittedGas,
            ProcessorStatus status,
            ProcessorDiagnostic diagnostic) {
        DocumentProcessingResult result = DocumentProcessingResult.nonCommitting(
                snapshot.canonicalRoot(),
                admittedGas,
                status,
                diagnostic);
        return new ProcessingDebugResult(
                result,
                ProcessingConformanceTrace.empty(),
                null,
                snapshot);
    }

    private static ProcessingDebugResult completeMutableResult(
            ProcessorInvocationState execution,
            ProcessingObserver observer,
            long processStart) {
        long postStart = System.nanoTime();
        try {
            return execution.debugResult();
        } finally {
            recordCompletion(observer, postStart, processStart);
        }
    }

    private static ProcessingDebugResult completeSnapshotResult(
            ProcessorInvocationState execution,
            ProcessingDebugResult completedResult,
            ProcessingObserver observer,
            long processStart) {
        long postStart = System.nanoTime();
        try {
            return completedResult != null
                    ? completedResult
                    : execution.debugResult();
        } finally {
            recordCompletion(observer, postStart, processStart);
        }
    }

    private static void rethrowIdentityBoundaryFailure(
            RuntimeException exception) {
        if (exception instanceof ExecutionEvidenceUnavailableException
                || ScopeIdentityErrorMapper
                .isProviderIdentityFailure(exception)) {
            throw exception;
        }
    }

    private static void recordPreprocessing(
            ProcessingObserver observer,
            long preprocessStart) {
        ProcessingObservations.record(
                observer,
                ProcessingMetricId.EVENT_PREPROCESS_NANOS,
                System.nanoTime() - preprocessStart);
    }

    private static void recordProcessDuration(
            ProcessingObserver observer,
            long processStart) {
        ProcessingObservations.record(
                observer,
                ProcessingMetricId.PROCESS_DOCUMENT_NANOS,
                System.nanoTime() - processStart);
    }

    private static void recordCompletion(
            ProcessingObserver observer,
            long postStart,
            long processStart) {
        ProcessingObservations.record(
                observer,
                ProcessingMetricId.POST_PROCESSING_NANOS,
                System.nanoTime() - postStart);
        recordProcessDuration(observer, processStart);
    }
}
