package blue.language.processor.closure;

import blue.language.processor.GasChargeContext;
import blue.language.processor.GasLimitExceededException;
import blue.language.processor.ProcessorDiagnostic;
import blue.language.processor.ProcessorErrorCategory;
import blue.language.processor.ProcessorStatus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

/** Builds an exact input-state rollback for a completed non-success outcome. */
final class ClosureRollbackResultAssembler {

    private static final String CLOSURE_CAPABILITY = "closureCapability";
    private static final ClosureIdentityService IDENTITIES =
            ClosureIdentityService.INSTANCE;

    private ClosureRollbackResultAssembler() {
    }

    static ClosureProcessResult capabilityFailure(
            ClosureInvocationInput input,
            List<blue.language.processor.GasTraceEntry> processorTrace,
            String capabilityCode,
            String message) {
        ProcessorDiagnostic diagnostic = ProcessorDiagnostic.builder(
                        ProcessorErrorCategory.UnsupportedRuntimeRole)
                .message(message)
                .detail(CLOSURE_CAPABILITY, capabilityCode)
                .build();
        return failure(
                input,
                processorTrace,
                ProcessorStatus.CAPABILITY_FAILURE,
                diagnostic,
                null,
                null);
    }

    static ClosureProcessResult deterministicFailure(
            ClosureInvocationInput input,
            List<blue.language.processor.GasTraceEntry> processorTrace,
            ProcessorStatus status,
            ProcessorDiagnostic diagnostic) {
        if (status == ProcessorStatus.SUCCESS
                || status == ProcessorStatus.GAS_LIMIT_EXCEEDED) {
            throw new IllegalArgumentException(
                    "Deterministic rollback status must be noncommitting and non-gas");
        }
        return failure(
                input,
                processorTrace,
                status,
                diagnostic,
                null,
                null);
    }

    static ClosureProcessResult gasFailure(
            ClosureInvocationInput input,
            List<blue.language.processor.GasTraceEntry> processorTrace,
            GasLimitExceededException rejection,
            List<ClosureWorkOccurrence> acceptedWork) {
        GasLimitExceededException exact = java.util.Objects.requireNonNull(
                rejection, "rejection");
        RejectedCharge.Owner owner = rejectedOwner(
                exact.chargeContext());
        ClosureWorkOccurrence rejectedWork = owner.kind()
                == RejectedCharge.Owner.Kind.WORK
                ? findWork(
                        acceptedWork,
                        owner.workOccurrenceIdentity())
                : null;
        RejectedCharge.ApplicableCap cap = exact.applicableCapKind()
                == GasLimitExceededException.ApplicableCapKind.LOCAL
                ? RejectedCharge.ApplicableCap.local(
                        new DocumentId(exact.localDocumentId()))
                : RejectedCharge.ApplicableCap.shared();
        RejectedCharge rejectedCharge = RejectedCharge.identified(
                ClosureResultAssemblySupport.namespace(exact.namespace()),
                exact.counter(),
                exact.quantity(),
                exact.weight(),
                cap,
                exact.remainingBeforeCharge(),
                owner);
        return failure(
                input,
                processorTrace,
                ProcessorStatus.GAS_LIMIT_EXCEEDED,
                exact.diagnostic(),
                rejectedCharge,
                rejectedWork);
    }

    private static ClosureProcessResult failure(
            ClosureInvocationInput input,
            List<blue.language.processor.GasTraceEntry> processorTrace,
            ProcessorStatus status,
            ProcessorDiagnostic diagnostic,
            RejectedCharge rejectedCharge,
            ClosureWorkOccurrence rejectedWork) {
        AffectedClosureSnapshot snapshot = input.snapshot();
        List<ResultingDocument> documents = rollbackDocuments(snapshot);
        List<GasTraceEntry> gasTrace =
                ClosureResultAssemblySupport.gasTrace(processorTrace);
        String graphChangesIdentity = emptyIdentity(
                ClosureIdentityService.Constructor.GRAPH_CHANGES);
        String subscriptionDeltasIdentity = emptyIdentity(
                ClosureIdentityService.Constructor.SUBSCRIPTION_DELTAS);
        String checkpointWritesIdentity = emptyIdentity(
                ClosureIdentityService.Constructor.CHECKPOINT_WRITES);
        String publicEventsIdentity = emptyIdentity(
                ClosureIdentityService.Constructor.PUBLIC_EVENTS);
        String gasTraceIdentity =
                ClosureResultAssemblySupport.sequenceIdentity(
                        ClosureIdentityService.Constructor.GAS_TRACE,
                        gasTrace);
        long totalGas = ClosureResultAssemblySupport.totalGas(gasTrace);
        return new ClosureProcessResult(
                snapshot,
                status,
                input.invocationIdentity(),
                snapshot.closureIdentity(),
                snapshot.graphGeneration(),
                documents,
                snapshot.components(),
                snapshot.occurrences(),
                snapshot.occurrenceBindingSetIdentity(),
                Collections.<GraphChange>emptyList(),
                graphChangesIdentity,
                Collections.<SubscriptionDelta>emptyList(),
                subscriptionDeltasIdentity,
                Collections.<CheckpointWrite>emptyList(),
                checkpointWritesIdentity,
                Collections.<PublicEventOccurrence>emptyList(),
                publicEventsIdentity,
                totalGas,
                gasTrace,
                gasTraceIdentity,
                rejectedCharge,
                rejectedWork,
                null,
                diagnostic);
    }

    private static RejectedCharge.Owner rejectedOwner(
            GasChargeContext context) {
        GasChargeContext exact = java.util.Objects.requireNonNull(
                context, "context");
        if (exact.finalizationOrdinal() != null) {
            return RejectedCharge.Owner.finalization(
                    exact.finalizationOrdinal().longValue(),
                    exact.finalizationComponentIdentity(),
                    exact.finalizationComponentGeneration().longValue());
        }
        if (exact.workOccurrenceId() != null) {
            return RejectedCharge.Owner.work(
                    exact.workOccurrenceId());
        }
        return RejectedCharge.Owner.invocation();
    }

    private static ClosureWorkOccurrence findWork(
            List<ClosureWorkOccurrence> acceptedWork,
            String workIdentity) {
        for (ClosureWorkOccurrence work
                : java.util.Objects.requireNonNull(
                        acceptedWork, "acceptedWork")) {
            if (work.workIdentity().equals(workIdentity)) {
                return work;
            }
        }
        throw new IllegalStateException(
                "Rejected WORK charge has no accepted work occurrence");
    }

    private static List<ResultingDocument> rollbackDocuments(
            AffectedClosureSnapshot snapshot) {
        LinkedHashMap<DocumentId, ComponentSnapshot> owners =
                new LinkedHashMap<DocumentId, ComponentSnapshot>();
        for (ComponentSnapshot component : snapshot.components()) {
            for (DocumentId member
                    : component.orderedMemberDocumentIds()) {
                owners.put(member, component);
            }
        }
        ArrayList<ResultingDocument> result =
                new ArrayList<ResultingDocument>();
        for (ManagedDocumentSnapshot document
                : snapshot.managedDocuments()) {
            ComponentSnapshot component = owners.get(document.documentId());
            result.add(new ResultingDocument(
                    document.documentId(),
                    document.blueId(),
                    document.blueId(),
                    document.document(),
                    document.initialized(),
                    document.terminated(),
                    document.publicRoot(),
                    document.epoch(),
                    document.componentGeneration(),
                    component.componentIdentity(),
                    component.componentStateIdentity(),
                    ClosureResultAssemblySupport.memberIndex(
                            document.blueId())));
        }
        return result;
    }

    private static String emptyIdentity(
            ClosureIdentityService.Constructor constructor) {
        return IDENTITIES.identity(
                constructor, Collections.emptyList());
    }

}
