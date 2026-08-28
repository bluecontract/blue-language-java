package blue.language.processor.closure;

import blue.language.processor.ClosureRuntimeDescriptor;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.ExecutionEvidenceUnavailableException;
import blue.language.processor.GasLimitExceededException;
import blue.language.processor.GasSchedule;
import blue.language.processor.InvalidExecutionEvidenceException;
import blue.language.processor.PortableLimitExceededException;
import blue.language.processor.ProcessorDiagnostic;
import blue.language.processor.ProcessorFailureException;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.SubscriptionSurfaceInvalidException;
import blue.language.provider.ProviderUnavailableException;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Production affected-closure processor composed over the ordinary document
 * runtime.
 *
 * <p>The first executable lane processes already-initialized closures under an
 * external cause.  Unsupported admission, lifecycle, expansion, and
 * settlement branches fail closed with an exact rollback result rather than
 * projecting fixture output.</p>
 */
final class DefaultClosureProcessor implements ClosureProcessor {

    private static final ClosureExecutionObserver NO_OP_OBSERVER =
            new ClosureExecutionObserver() {
                @Override
                public void onExecutionEvidence(
                        ClosureImplementationEvidence evidence) {
                    // Deliberately empty.
                }
            };

    private final DocumentProcessor owner;
    private final ClosureRuntimeDescriptor runtimeDescriptor;
    private final ClosureExecutionObserver observer;

    /**
     * Creates a processor with no implementation-evidence observer.
     *
     * @param owner configured ordinary document processor
     */
    DefaultClosureProcessor(DocumentProcessor owner) {
        this(owner, NO_OP_OBSERVER);
    }

    /**
     * Creates a processor that publishes supplemental execution evidence.
     *
     * @param owner configured ordinary document processor
     * @param observer attempt evidence observer
     */
    DefaultClosureProcessor(
            DocumentProcessor owner,
            ClosureExecutionObserver observer) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.runtimeDescriptor = ClosureRuntimeDescriptor.capture(owner);
        this.observer = Objects.requireNonNull(observer, "observer");
    }

    /** {@inheritDoc} */
    @Override
    public ClosureAttemptResult processClosure(
            ClosureInvocationInput input) {
        ClosureInvocationInput admitted = Objects.requireNonNull(
                input, "input");
        ClosureInvocationVerifier.Verification verification =
                ClosureInvocationVerifier.verify(admitted);
        verifyRuntimeBinding(admitted, verification);
        ClosureExecutionRecorder recorder =
                new ClosureExecutionRecorder(
                        verification.invocationIdentity());
        ClosureExecutionSession session = null;
        List<blue.language.processor.GasTraceEntry> trace =
                Collections.emptyList();
        try {
            session = new ClosureExecutionSession(
                    owner,
                    admitted,
                    recorder,
                    ClosureExecutionSession.ExecutionMode.PROCESSING);
            ClosureExecutionState state = session.execute();
            ClosureProcessResult result;
            long assemblyStarted =
                    recorder.beginSuccessfulResultAssembly();
            boolean assembled = false;
            try {
                result = ClosureSuccessResultAssembler.assemble(
                        admitted, state);
                assembled = true;
            } finally {
                recorder.endSuccessfulResultAssembly(
                        assemblyStarted, assembled);
            }
            observer.onExecutionEvidence(recorder.snapshot(null));
            return ClosureAttemptResult.complete(result);
        } catch (ClosureResourceDemandException suspension) {
            return ClosureAttemptResult.needsResources(
                    suspension.demands());
        } catch (ExecutionEvidenceUnavailableException unavailable) {
            if (unavailable.requiredExactBlueIds().isEmpty()) {
                throw unavailable;
            }
            return ClosureAttemptResult.needsExactResources(
                    unavailable.requiredExactBlueIds());
        } catch (ProviderUnavailableException unavailable) {
            return providerSuspension(unavailable);
        } catch (GasLimitExceededException rejection) {
            if (session != null) {
                trace = session.state().gasTrace();
            }
            ClosureImplementationEvidence evidence =
                    recorder.snapshot(null);
            observer.onExecutionEvidence(evidence);
            return ClosureAttemptResult.complete(
                    ClosureRollbackResultAssembler.gasFailure(
                            admitted,
                            trace,
                            rejection,
                            evidence.workTrace()));
        } catch (ProcessorFailureException failure) {
            if (session != null) {
                trace = session.state().gasTrace();
            }
            observer.onExecutionEvidence(recorder.snapshot(null));
            return ClosureAttemptResult.complete(
                    ClosureRollbackResultAssembler.deterministicFailure(
                            admitted,
                            trace,
                            ProcessorStatus.RUNTIME_FATAL,
                            ProcessorDiagnostic.of(
                                    failure.errorCategory(),
                                    failure.getMessage())));
        } catch (InvalidExecutionEvidenceException invalid) {
            if (session != null) {
                trace = session.state().gasTrace();
            }
            observer.onExecutionEvidence(recorder.snapshot(null));
            return ClosureAttemptResult.complete(
                    ClosureRollbackResultAssembler.deterministicFailure(
                            admitted,
                            trace,
                            ProcessorStatus.INVALID_PROCESSING_DOCUMENT,
                            ProcessorDiagnostic.of(
                                    invalid.errorCategory(),
                                    invalid.getMessage())));
        } catch (PortableLimitExceededException limit) {
            if (session != null) {
                trace = session.state().gasTrace();
            }
            observer.onExecutionEvidence(recorder.snapshot(null));
            return ClosureAttemptResult.complete(
                    ClosureRollbackResultAssembler.deterministicFailure(
                            admitted,
                            trace,
                            ProcessorStatus.PORTABLE_LIMIT_EXCEEDED,
                            limit.diagnostic()));
        } catch (SubscriptionSurfaceInvalidException invalid) {
            if (session != null) {
                trace = session.state().gasTrace();
            }
            observer.onExecutionEvidence(recorder.snapshot(null));
            return ClosureAttemptResult.complete(
                    ClosureRollbackResultAssembler.deterministicFailure(
                            admitted,
                            trace,
                            ProcessorStatus.SUBSCRIPTION_SURFACE_INVALID,
                            invalid.diagnostic()));
        } catch (ClosureCapabilityGapException gap) {
            if (session != null) {
                trace = session.state().gasTrace();
            }
            observer.onExecutionEvidence(
                    recorder.snapshot(gap.code()));
            return ClosureAttemptResult.complete(
                    ClosureRollbackResultAssembler.capabilityFailure(
                            admitted,
                            trace,
                            gap.code(),
                            gap.getMessage()));
        } finally {
            if (session != null) {
                session.close();
            }
        }
    }

    /** Executes one deterministic resolution-bound processing retry. */
    ClosureAttemptResult processClosureRetry(ClosureProcessRetryInput input) {
        ClosureProcessRetryInput selected = Objects.requireNonNull(
                input, "input");
        ClosureInvocationVerifier.Verification verification =
                ClosureInvocationVerifier.verifyRetry(selected);
        ClosureInvocationInput admitted = selected.baseInvocation()
                .withInvocationIdentity(
                        selected.retryInvocationIdentity());
        verifyRuntimeBinding(admitted, verification);
        ClosureExecutionRecorder recorder =
                new ClosureExecutionRecorder(
                        verification.invocationIdentity());
        ClosureExecutionSession session = null;
        List<blue.language.processor.GasTraceEntry> trace =
                Collections.emptyList();
        try {
            session = new ClosureExecutionSession(
                    owner,
                    admitted,
                    recorder,
                    ClosureExecutionSession.ExecutionMode.PROCESSING,
                    selected.resolutions());
            ClosureExecutionState state = session.execute();
            ClosureProcessResult result;
            long assemblyStarted =
                    recorder.beginSuccessfulResultAssembly();
            boolean assembled = false;
            try {
                result = ClosureSuccessResultAssembler.assemble(
                        admitted, state, selected.resolutions());
                assembled = true;
            } finally {
                recorder.endSuccessfulResultAssembly(
                        assemblyStarted, assembled);
            }
            observer.onExecutionEvidence(recorder.snapshot(null));
            return ClosureAttemptResult.complete(result);
        } catch (ClosureResourceDemandException suspension) {
            return ClosureAttemptResult.needsResources(
                    suspension.demands());
        } catch (ExecutionEvidenceUnavailableException unavailable) {
            if (unavailable.requiredExactBlueIds().isEmpty()) {
                throw unavailable;
            }
            return ClosureAttemptResult.needsExactResources(
                    unavailable.requiredExactBlueIds());
        } catch (ProviderUnavailableException unavailable) {
            return providerSuspension(unavailable);
        } catch (GasLimitExceededException rejection) {
            if (session != null) {
                trace = session.state().gasTrace();
            }
            ClosureImplementationEvidence evidence =
                    recorder.snapshot(null);
            observer.onExecutionEvidence(evidence);
            return ClosureAttemptResult.complete(
                    ClosureRollbackResultAssembler.gasFailure(
                            admitted,
                            trace,
                            rejection,
                            evidence.workTrace()));
        } catch (ProcessorFailureException failure) {
            if (session != null) {
                trace = session.state().gasTrace();
            }
            observer.onExecutionEvidence(recorder.snapshot(null));
            return ClosureAttemptResult.complete(
                    ClosureRollbackResultAssembler.deterministicFailure(
                            admitted,
                            trace,
                            ProcessorStatus.RUNTIME_FATAL,
                            ProcessorDiagnostic.of(
                                    failure.errorCategory(),
                                    failure.getMessage())));
        } catch (InvalidExecutionEvidenceException invalid) {
            if (session != null) {
                trace = session.state().gasTrace();
            }
            observer.onExecutionEvidence(recorder.snapshot(null));
            return ClosureAttemptResult.complete(
                    ClosureRollbackResultAssembler.deterministicFailure(
                            admitted,
                            trace,
                            ProcessorStatus.INVALID_PROCESSING_DOCUMENT,
                            ProcessorDiagnostic.of(
                                    invalid.errorCategory(),
                                    invalid.getMessage())));
        } catch (PortableLimitExceededException limit) {
            if (session != null) {
                trace = session.state().gasTrace();
            }
            observer.onExecutionEvidence(recorder.snapshot(null));
            return ClosureAttemptResult.complete(
                    ClosureRollbackResultAssembler.deterministicFailure(
                            admitted,
                            trace,
                            ProcessorStatus.PORTABLE_LIMIT_EXCEEDED,
                            limit.diagnostic()));
        } catch (SubscriptionSurfaceInvalidException invalid) {
            if (session != null) {
                trace = session.state().gasTrace();
            }
            observer.onExecutionEvidence(recorder.snapshot(null));
            return ClosureAttemptResult.complete(
                    ClosureRollbackResultAssembler.deterministicFailure(
                            admitted,
                            trace,
                            ProcessorStatus.SUBSCRIPTION_SURFACE_INVALID,
                            invalid.diagnostic()));
        } catch (ClosureCapabilityGapException gap) {
            if (session != null) {
                trace = session.state().gasTrace();
            }
            observer.onExecutionEvidence(
                    recorder.snapshot(gap.code()));
            return ClosureAttemptResult.complete(
                    ClosureRollbackResultAssembler.capabilityFailure(
                            admitted,
                            trace,
                            gap.code(),
                            gap.getMessage()));
        } finally {
            if (session != null) {
                session.close();
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    public ClosureAttemptResult admitClosure(
            ClosureInvocationInput input) {
        ClosureInvocationInput admitted = Objects.requireNonNull(
                input, "input");
        ClosureInvocationVerifier.Verification verification =
                ClosureInvocationVerifier.verify(admitted);
        verifyRuntimeBinding(admitted, verification);
        ClosureExecutionRecorder recorder =
                new ClosureExecutionRecorder(
                        verification.invocationIdentity());
        if (verification.candidateDisposition()
                == ClosureInvocationVerifier.CandidateDisposition
                        .SEMANTICALLY_INVALID
                && ClosureAdmissionRejectionProcessor.supports(
                        admitted, verification)) {
            ClosureAdmissionRejectionProcessor.Rejection rejection =
                    ClosureAdmissionRejectionProcessor.reject(
                            owner, admitted, verification);
            observer.onExecutionEvidence(
                    recorder.snapshot(null));
            return rejection.attempt();
        }
        ClosureAdmissionExecutionSession session = null;
        List<blue.language.processor.GasTraceEntry> trace =
                Collections.emptyList();
        try {
            ClosureAdmissionPortableLimits.verify(admitted);
            session = new ClosureAdmissionExecutionSession(
                    owner, admitted, recorder);
            ClosureExecutionState state = session.execute();
            ClosureProcessResult result;
            long assemblyStarted =
                    recorder.beginSuccessfulResultAssembly();
            boolean assembled = false;
            try {
                result = ClosureSuccessResultAssembler.assemble(
                        admitted, state);
                assembled = true;
            } finally {
                recorder.endSuccessfulResultAssembly(
                        assemblyStarted, assembled);
            }
            observer.onExecutionEvidence(recorder.snapshot(null));
            return ClosureAttemptResult.complete(result);
        } catch (ClosureResourceDemandException suspension) {
            return ClosureAttemptResult.needsResources(
                    suspension.demands());
        } catch (ExecutionEvidenceUnavailableException unavailable) {
            if (unavailable.requiredExactBlueIds().isEmpty()) {
                throw unavailable;
            }
            return ClosureAttemptResult.needsExactResources(
                    unavailable.requiredExactBlueIds());
        } catch (ProviderUnavailableException unavailable) {
            return providerSuspension(unavailable);
        } catch (GasLimitExceededException rejection) {
            if (session != null) {
                trace = session.state().gasTrace();
            }
            ClosureImplementationEvidence evidence = recorder.snapshot(null);
            observer.onExecutionEvidence(evidence);
            return ClosureAttemptResult.complete(
                    ClosureRollbackResultAssembler.gasFailure(
                            admitted,
                            trace,
                            rejection,
                            evidence.workTrace()));
        } catch (ProcessorFailureException failure) {
            if (session != null) {
                trace = session.state().gasTrace();
            }
            observer.onExecutionEvidence(recorder.snapshot(null));
            return ClosureAttemptResult.complete(
                    ClosureRollbackResultAssembler.deterministicFailure(
                            admitted,
                            trace,
                            ProcessorStatus.RUNTIME_FATAL,
                            ProcessorDiagnostic.of(
                                    failure.errorCategory(),
                                    failure.getMessage())));
        } catch (InvalidExecutionEvidenceException invalid) {
            if (session != null) {
                trace = session.state().gasTrace();
            }
            observer.onExecutionEvidence(recorder.snapshot(null));
            return ClosureAttemptResult.complete(
                    ClosureRollbackResultAssembler.deterministicFailure(
                            admitted,
                            trace,
                            ProcessorStatus.INVALID_PROCESSING_DOCUMENT,
                            ProcessorDiagnostic.of(
                                    invalid.errorCategory(),
                                    invalid.getMessage())));
        } catch (PortableLimitExceededException limit) {
            if (session != null) {
                trace = session.state().gasTrace();
            }
            observer.onExecutionEvidence(recorder.snapshot(null));
            return ClosureAttemptResult.complete(
                    ClosureRollbackResultAssembler.deterministicFailure(
                            admitted,
                            trace,
                            ProcessorStatus.PORTABLE_LIMIT_EXCEEDED,
                            limit.diagnostic()));
        } catch (SubscriptionSurfaceInvalidException invalid) {
            if (session != null) {
                trace = session.state().gasTrace();
            }
            observer.onExecutionEvidence(recorder.snapshot(null));
            return ClosureAttemptResult.complete(
                    ClosureRollbackResultAssembler.deterministicFailure(
                            admitted,
                            trace,
                            ProcessorStatus.SUBSCRIPTION_SURFACE_INVALID,
                            invalid.diagnostic()));
        } catch (ClosureCapabilityGapException gap) {
            if (session != null) {
                trace = session.state().gasTrace();
            }
            observer.onExecutionEvidence(recorder.snapshot(gap.code()));
            return ClosureAttemptResult.complete(
                    ClosureRollbackResultAssembler.capabilityFailure(
                            admitted,
                            trace,
                            gap.code(),
                            gap.getMessage()));
        } finally {
            if (session != null) {
                session.close();
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    public ClosureAttemptResult admitClosureWithLifecycleQueue(
            ClosureInvocationInput input) {
        ClosureInvocationInput admitted = Objects.requireNonNull(
                input, "input");
        ClosureInvocationVerifier.Verification verification =
                ClosureInvocationVerifier.verify(admitted);
        verifyRuntimeBinding(admitted, verification);
        ClosureExecutionRecorder recorder =
                new ClosureExecutionRecorder(
                        verification.invocationIdentity());
        if (verification.candidateDisposition()
                == ClosureInvocationVerifier.CandidateDisposition
                        .SEMANTICALLY_INVALID
                && ClosureAdmissionRejectionProcessor.supports(
                        admitted, verification)) {
            ClosureAdmissionRejectionProcessor.Rejection rejection =
                    ClosureAdmissionRejectionProcessor.reject(
                            owner, admitted, verification);
            observer.onExecutionEvidence(
                    recorder.snapshot(null));
            return rejection.attempt();
        }
        ClosureExecutionSession session = null;
        List<blue.language.processor.GasTraceEntry> trace =
                Collections.emptyList();
        try {
            session = new ClosureExecutionSession(
                    owner,
                    admitted,
                    recorder,
                    ClosureExecutionSession.ExecutionMode.ADMISSION);
            ClosureExecutionState state = session.execute();
            ClosureProcessResult result;
            long assemblyStarted =
                    recorder.beginSuccessfulResultAssembly();
            boolean assembled = false;
            try {
                result = ClosureSuccessResultAssembler.assemble(
                        admitted, state);
                assembled = true;
            } finally {
                recorder.endSuccessfulResultAssembly(
                        assemblyStarted, assembled);
            }
            observer.onExecutionEvidence(recorder.snapshot(null));
            return ClosureAttemptResult.complete(result);
        } catch (ClosureResourceDemandException suspension) {
            return ClosureAttemptResult.needsResources(
                    suspension.demands());
        } catch (ExecutionEvidenceUnavailableException unavailable) {
            if (unavailable.requiredExactBlueIds().isEmpty()) {
                throw unavailable;
            }
            return ClosureAttemptResult.needsExactResources(
                    unavailable.requiredExactBlueIds());
        } catch (ProviderUnavailableException unavailable) {
            return providerSuspension(unavailable);
        } catch (GasLimitExceededException rejection) {
            if (session != null) {
                trace = session.state().gasTrace();
            }
            ClosureImplementationEvidence evidence = recorder.snapshot(null);
            observer.onExecutionEvidence(evidence);
            return ClosureAttemptResult.complete(
                    ClosureRollbackResultAssembler.gasFailure(
                            admitted,
                            trace,
                            rejection,
                            evidence.workTrace()));
        } catch (ProcessorFailureException failure) {
            if (session != null) {
                trace = session.state().gasTrace();
            }
            observer.onExecutionEvidence(recorder.snapshot(null));
            return ClosureAttemptResult.complete(
                    ClosureRollbackResultAssembler.deterministicFailure(
                            admitted,
                            trace,
                            ProcessorStatus.RUNTIME_FATAL,
                            ProcessorDiagnostic.of(
                                    failure.errorCategory(),
                                    failure.getMessage())));
        } catch (InvalidExecutionEvidenceException invalid) {
            if (session != null) {
                trace = session.state().gasTrace();
            }
            observer.onExecutionEvidence(recorder.snapshot(null));
            return ClosureAttemptResult.complete(
                    ClosureRollbackResultAssembler.deterministicFailure(
                            admitted,
                            trace,
                            ProcessorStatus.INVALID_PROCESSING_DOCUMENT,
                            ProcessorDiagnostic.of(
                                    invalid.errorCategory(),
                                    invalid.getMessage())));
        } catch (PortableLimitExceededException limit) {
            if (session != null) {
                trace = session.state().gasTrace();
            }
            observer.onExecutionEvidence(recorder.snapshot(null));
            return ClosureAttemptResult.complete(
                    ClosureRollbackResultAssembler.deterministicFailure(
                            admitted,
                            trace,
                            ProcessorStatus.PORTABLE_LIMIT_EXCEEDED,
                            limit.diagnostic()));
        } catch (SubscriptionSurfaceInvalidException invalid) {
            if (session != null) {
                trace = session.state().gasTrace();
            }
            observer.onExecutionEvidence(recorder.snapshot(null));
            return ClosureAttemptResult.complete(
                    ClosureRollbackResultAssembler.deterministicFailure(
                            admitted,
                            trace,
                            ProcessorStatus.SUBSCRIPTION_SURFACE_INVALID,
                            invalid.diagnostic()));
        } catch (ClosureCapabilityGapException gap) {
            if (session != null) {
                trace = session.state().gasTrace();
            }
            observer.onExecutionEvidence(recorder.snapshot(gap.code()));
            return ClosureAttemptResult.complete(
                    ClosureRollbackResultAssembler.capabilityFailure(
                            admitted,
                            trace,
                            gap.code(),
                            gap.getMessage()));
        } finally {
            if (session != null) {
                session.close();
            }
        }
    }

    private static ClosureAttemptResult providerSuspension(
            ProviderUnavailableException unavailable) {
        String blueId = unavailable.requiredExactBlueId().orElseThrow(
                () -> unavailable);
        return ClosureAttemptResult.needsExactResources(
                Collections.singletonList(blueId));
    }

    private void verifyRuntimeBinding(
            ClosureInvocationInput input,
            ClosureInvocationVerifier.Verification verification) {
        if (!verification.invocationIdentity().equals(
                    input.invocationIdentity())) {
            throw new IllegalArgumentException(
                    "Verified invocation identity changed before composition");
        }
        ClosureEnvironment environment = input.environment();
        if (!environment.runtimeRegistryIdentity().equals(
                    runtimeDescriptor.runtimeRegistryIdentity())) {
            throw new IllegalArgumentException(
                    "Invocation runtime registry is not the configured implementation");
        }
        if (!environment.gasManifestIdentity().equals(
                    runtimeDescriptor.gasManifestIdentity())) {
            throw new IllegalArgumentException(
                    "Invocation gas manifest is not the configured implementation");
        }
        if (!environment.portableLimitPolicy().limits().equals(
                    GasSchedule.contracts10().portableLimits())) {
            throw new IllegalArgumentException(
                    "Invocation portable limits are not the frozen "
                            + "Contracts 1.0 policy");
        }
        if (!environment.cyclicFinalizerIdentity().equals(
                    ClosureRuntimeDescriptor.CYCLIC_FINALIZER_IDENTITY)
                || !environment.cyclicProofVerifierIdentity().equals(
                    ClosureRuntimeDescriptor
                            .CYCLIC_PROOF_VERIFIER_IDENTITY)) {
            throw new IllegalArgumentException(
                    "Invocation cyclic implementation identities are not production");
        }
    }
}
