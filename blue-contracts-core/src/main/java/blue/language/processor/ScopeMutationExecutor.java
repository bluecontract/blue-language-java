package blue.language.processor;

import blue.language.processor.model.JsonPatch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Executes an ordered patch sequence as one tentative mutation transaction.
 *
 * <p>Each patch is preflighted before gas is charged and applied. The
 * underlying prepared sequence owns commit/rollback publication, while this
 * coordinator preserves cut-off checks, failure classification, and update
 * routing after every committed semantic change.</p>
 */
final class ScopeMutationExecutor {

    private final ProcessorInvocationServices owner;
    private final ProcessorInvocationState execution;
    private final DocumentProcessingRuntime runtime;
    private final PatchPreflight preflight;
    private final UpdateContinuation updateContinuation;

    ScopeMutationExecutor(
            ProcessorInvocationServices owner,
            ProcessorInvocationState execution,
            DocumentProcessingRuntime runtime,
            PatchPreflight preflight,
            DocumentUpdateRouter updateRouter) {
        this(owner,
                execution,
                runtime,
                preflight,
                new UpdateContinuation() {
                    @Override
                    public void continueAfterPatch(
                            String scopePath,
                            ContractBundle bundle,
                            FrozenJsonPatch patch,
                            List<DocumentUpdateData> updates) {
                        for (DocumentUpdateData update : updates) {
                            updateRouter.route(scopePath, bundle, update);
                            if (execution.shouldStopScopeWork(scopePath)) {
                                return;
                            }
                        }
                    }
                });
    }

    ScopeMutationExecutor(
            ProcessorInvocationServices owner,
            ProcessorInvocationState execution,
            DocumentProcessingRuntime runtime,
            PatchPreflight preflight,
            UpdateContinuation updateContinuation) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.execution = Objects.requireNonNull(execution, "execution");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.preflight = Objects.requireNonNull(preflight, "preflight");
        this.updateContinuation = Objects.requireNonNull(
                updateContinuation, "updateContinuation");
    }

    void execute(String scopePath,
                 ContractBundle bundle,
                 List<PatchInput> patches,
                 boolean allowReservedMutation,
                 WorkingDocument.Preview preview) {
        if (execution.shouldStopScopeWork(scopePath)
                || patches == null
                || patches.isEmpty()) {
            return;
        }
        try (PreparedPatchTransaction sequence =
                     runtime.preparePatchInputSequence(
                             scopePath, patches, preview)) {
            for (int index = 0; index < sequence.size(); index++) {
                PatchInput patch = sequence.patchInputForValidation(index);
                if (execution.shouldStopScopeWork(scopePath)) {
                    return;
                }
                preflight(scopePath, bundle, patch, allowReservedMutation);
                if (execution.shouldStopScopeWork(scopePath)) {
                    return;
                }
                apply(scopePath, bundle, sequence, index, patch);
            }
        } catch (GasLimitExceededException
                 | PortableLimitExceededException
                 | SubscriptionSurfaceInvalidException
                 | DocumentStepRuntimeGapException exception) {
            throw exception;
        } catch (RunTerminationException exception) {
            // Root fatal termination is processor control flow, not a
            // snapshot-publication failure.
            throw exception;
        } catch (RuntimeException exception) {
            execution.abortRuntimeFailure(
                    scopePath,
                    bundle,
                    execution.fatalCategory(
                            exception,
                            ProcessorErrorCategory.RuntimeExecutionFailure),
                    execution.fatalReason(
                            exception,
                            "Snapshot publication failed"));
        }
    }

    private void preflight(String scopePath,
                           ContractBundle bundle,
                           PatchInput patch,
                           boolean allowReservedMutation) {
        if (!allowReservedMutation) {
            runtime.chargeBoundaryCheck();
        }
        try {
            long started = System.nanoTime();
            preflight.validate(
                    scopePath, bundle, patch, allowReservedMutation);
            ProcessingObservations.record(
                    owner.observer(),
                    ProcessingMetricId.PATCH_BOUNDARY_NANOS,
                    System.nanoTime() - started);
        } catch (ProcessorEngine.BoundaryViolationException exception) {
            execution.abortRuntimeFailure(
                    scopePath,
                    bundle,
                    ProcessorErrorCategory.PatchBoundaryViolation,
                    execution.fatalReason(
                            exception, "Boundary violation"));
        } catch (ProcessorFailureException exception) {
            execution.abortRuntimeFailure(
                    scopePath,
                    bundle,
                    exception.errorCategory(),
                    execution.fatalReason(exception, "Runtime fatal"));
        } catch (IllegalArgumentException exception) {
            execution.abortRuntimeFailure(
                    scopePath,
                    bundle,
                    ProcessorErrorCategory.InvalidPatch,
                    execution.fatalReason(
                            exception, "Boundary violation"));
        }
    }

    private void apply(
            String scopePath,
            ContractBundle bundle,
            PreparedPatchTransaction sequence,
            int index,
            PatchInput patch) {
        try {
            long gasStarted = System.nanoTime();
            runtime.recordPatchSemanticDemands(patch.authoredPath());
            chargePatchGas(patch);
            ProcessingObservations.record(
                    owner.observer(),
                    ProcessingMetricId.PATCH_GAS_NANOS,
                    System.nanoTime() - gasStarted);

            FrozenJsonPatch authoredPatch =
                    patch.frozenAuthoredPatch();
            List<DocumentUpdateData> updates =
                    sequence.applyNext(index);
            List<DocumentUpdateData> exactUpdates =
                    Collections.unmodifiableList(
                            new ArrayList<DocumentUpdateData>(updates));
            long routingStarted = System.nanoTime();
            updateContinuation.continueAfterPatch(
                    scopePath, bundle, authoredPatch, exactUpdates);
            if (execution.shouldStopScopeWork(scopePath)) {
                return;
            }
            ProcessingObservations.record(
                    owner.observer(),
                    ProcessingMetricId.DOCUMENT_UPDATE_ROUTING_NANOS,
                    System.nanoTime() - routingStarted);
        } catch (ProcessorEngine.BoundaryViolationException exception) {
            execution.abortRuntimeFailure(
                    scopePath,
                    bundle,
                    ProcessorErrorCategory.PatchBoundaryViolation,
                    execution.fatalReason(
                            exception, "Boundary violation"));
        } catch (MustUnderstandFailureException exception) {
            execution.abortRuntimeFailure(
                    scopePath,
                    bundle,
                    exception.errorCategory(),
                    execution.fatalReason(
                            exception,
                            "Unsupported runtime contract"));
        } catch (DocumentStepRuntimeGapException exception) {
            throw exception;
        } catch (ProcessorFailureException exception) {
            execution.abortRuntimeFailure(
                    scopePath,
                    bundle,
                    exception.errorCategory(),
                    execution.fatalReason(exception, "Runtime fatal"));
        } catch (IllegalArgumentException
                 | IllegalStateException exception) {
            execution.abortRuntimeFailure(
                    scopePath,
                    bundle,
                    execution.fatalCategory(
                            exception,
                            ProcessorErrorCategory.RuntimeExecutionFailure),
                    execution.fatalReason(exception, "Runtime fatal"));
        }
    }

    private void chargePatchGas(PatchInput patch) {
        switch (patch.op()) {
            case ADD:
            case REPLACE:
                if (patch.isFrozen()) {
                    runtime.chargeFrozenPatchAddOrReplace(
                            patch.frozenAuthoredCanonicalSizeBytes());
                } else {
                    runtime.chargePatchAddOrReplace(
                            patch.mutableValue());
                }
                break;
            case REMOVE:
                runtime.chargePatchRemove();
                break;
            default:
                break;
        }
    }

    /** Exact post-patch continuation used by ordinary and closure runtimes. */
    interface UpdateContinuation {
        void continueAfterPatch(
                String scopePath,
                ContractBundle bundle,
                FrozenJsonPatch patch,
                List<DocumentUpdateData> updates);
    }
}
