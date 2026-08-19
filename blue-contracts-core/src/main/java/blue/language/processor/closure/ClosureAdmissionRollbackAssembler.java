package blue.language.processor.closure;

import blue.language.processor.ProcessorDiagnostic;
import blue.language.processor.ProcessorErrorCategory;
import blue.language.processor.ProcessorStatus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

/** Builds the exact non-committing result for semantic admission rejection. */
final class ClosureAdmissionRollbackAssembler {

    private static final String ADMISSION_CANDIDATE_KIND =
            "admissionCandidateKind";
    private static final ClosureIdentityService IDENTITIES =
            ClosureIdentityService.INSTANCE;

    private ClosureAdmissionRollbackAssembler() {
    }

    static ClosureAttemptResult reject(
            ClosureInvocationInput input,
            List<blue.language.processor.GasTraceEntry> processorTrace,
            ProcessorErrorCategory diagnosticCategory,
            String message) {
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
        String gasTraceIdentity = ClosureResultAssemblySupport.sequenceIdentity(
                ClosureIdentityService.Constructor.GAS_TRACE,
                gasTrace);
        ProcessorDiagnostic diagnostic = ProcessorDiagnostic.builder(
                        diagnosticCategory)
                .message(message)
                .detail(ADMISSION_CANDIDATE_KIND,
                        input.admissionCandidate().kind().name())
                .build();
        ClosureProcessResult result = new ClosureProcessResult(
                snapshot,
                ProcessorStatus.INVALID_PROCESSING_DOCUMENT,
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
                ClosureResultAssemblySupport.totalGas(gasTrace),
                gasTrace,
                gasTraceIdentity,
                null,
                null,
                null,
                diagnostic,
                input.admissionCandidate());
        return ClosureAttemptResult.complete(result);
    }

    private static List<ResultingDocument> rollbackDocuments(
            AffectedClosureSnapshot snapshot) {
        LinkedHashMap<DocumentId, ComponentSnapshot> owners =
                new LinkedHashMap<DocumentId, ComponentSnapshot>();
        for (ComponentSnapshot component : snapshot.components()) {
            for (DocumentId member : component.orderedMemberDocumentIds()) {
                owners.put(member, component);
            }
        }
        ArrayList<ResultingDocument> result =
                new ArrayList<ResultingDocument>();
        for (ManagedDocumentSnapshot document : snapshot.managedDocuments()) {
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
        return IDENTITIES.identity(constructor, Collections.emptyList());
    }
}
