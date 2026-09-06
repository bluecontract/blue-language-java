package blue.language.processor.closure;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.processor.*;
import blue.language.processor.registry.RuntimeBlueIds;
import java.math.BigInteger;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SourceNestedInitializationRuntimeTest {
    @Test
    void externalCreationReplaysNestedBorrowedInitializationInItsPrivateSourceViews() {
        try (SourceInitializationAttachmentTest.Fixture fixture = new SourceInitializationAttachmentTest.Fixture()) {
            SourceObservationProgram zProgram = fixture.initialization.program();
            SourceObservationProgram.SourceState zBefore = zProgram.sourcePredecessors().get(0), zAfter = zProgram.sourceResults().get(0);
            ManagedDocumentSnapshot orderBefore = fixture.input.snapshot().managedDocument(fixture.binding.sourceDocumentId());
            Node yBody = new Node().name("Nested canonical source").properties("counter", number(0)).properties("counterB", number(0))
                    .properties("child", new Node().blueId(zBefore.blueId())).contracts(zBefore.document().getContracts().clone());
            yBody.getContracts().properties("embedded", typed(RuntimeBlueIds.PROCESS_EMBEDDED).properties("paths", new Node().items(new Node().value("/child"))))
                    .properties("events", orderBefore.document().getContracts().getProperties().get("events").clone())
                    .properties("observe", orderBefore.document().getContracts().getProperties().get("observe").clone());
            // The reused actual observer handler records its event count as well as the source read.
            yBody.properties("childEventCount", number(0));
            DocumentId y = new DocumentId(id(yBody));
            ManagedDocumentSnapshot yBefore = new ManagedDocumentSnapshot(y, y.value(), yBody, false, false, true, 0L, 0L);
            ManagedDocumentSnapshot z = new ManagedDocumentSnapshot(zAfter.documentId(), zAfter.blueId(), zAfter.document(), true, false, true, 0L, 0L);
            ManagedOccurrenceBinding yzBefore = ManagedOccurrenceBinding.derived(fixture.input.environment().managedBindingPolicyIdentity(), y,
                    ScopeAddress.embedded("/child", 1L), z.documentId(), zBefore.blueId(), true, null);
            ClosureInvocationInput admission = ClosureEvidenceFactory.admitClosure(snapshot(Arrays.asList(yBefore, z), Collections.singletonList(yzBefore),
                            Collections.singletonList(pin(zBefore))), ClosureEvidenceFactory.admissionCause(AdmissionKind.TOP_LEVEL_ADMISSION,
                            "canonical-source:" + y.value(), null, null, "FULL_HISTORY"), null, fixture.input.executionPolicy(), fixture.input.environment());
            SourceObservationProgram[] captured = new SourceObservationProgram[1];
            ClosureProcessResult yResult;
            try (BlueClosureContracts admitting = new BlueClosureContracts(fixture.owner, new ClosureExecutionObserver() {
                @Override public boolean capturesSourceObservationProgram() { return true; }
                @Override public void onSourceObservationProgram(SourceObservationProgram program) { captured[0] = program; }
                @Override public void onExecutionEvidence(ClosureImplementationEvidence evidence) { }
            })) {
                ClosureAttemptResult result = admitting.admitExternalScope(admission, Collections.singleton(y), Collections.singletonList(fixture.initialization));
                assertTrue(result.isComplete()); yResult = result.processResult(); assertEquals(ProcessorStatus.SUCCESS, yResult.status());
            }
            SourceInitialization yInitialization = SourceInitialization.fromProgram(captured[0]);
            assertEquals(2, fixture.initializerExecutions);
            assertEquals(1, yInitialization.program().borrowedPrograms().size());
            SourceObservationProgram.SourceState yAfter = yInitialization.program().sourceResults().stream()
                    .filter(state -> state.documentId().equals(y)).findFirst().get();
            assertEquals(BigInteger.valueOf(5), yAfter.document().getProperties().get("counterB").getValue());
            ManagedDocumentSnapshot yCurrent = new ManagedDocumentSnapshot(y, yAfter.blueId(), yAfter.document(), true, false, true, 0L, 0L);
            Node aBody = orderBefore.document(); aBody.properties("child", new Node().blueId(y.value()));
            ManagedDocumentSnapshot a = new ManagedDocumentSnapshot(orderBefore.documentId(), id(aBody), aBody, true, false, true, 0L, 0L);
            ManagedOccurrenceBinding ay = ManagedOccurrenceBinding.derived(fixture.input.environment().managedBindingPolicyIdentity(), a.documentId(),
                    fixture.binding.sourceAddress(), y, y.value(), false, null);
            List<ManagedOccurrenceBinding> rows = new ArrayList<>(yInitialization.program().sourceAfterBindings()); rows.add(ay);
            ClosureInvocationInput input = ClosureEvidenceFactory.processClosure(snapshot(Arrays.asList(a, yCurrent, z), rows,
                            Arrays.asList(ManagedReadPin.fromExactEvidence(y, y.value(), yBody, null), pin(zBefore))),
                    fixture.input.cause(), fixture.input.directDeliveries(), fixture.input.executionPolicy(), fixture.input.environment());
            SameOriginAttachmentPolicy policy = new SameOriginAttachmentPolicy(Collections.singleton(new SameOriginAttachmentPolicy.Selection(
                    SameOriginAttachmentPolicy.Mode.FULL_HISTORY, a.documentId(), ay.occurrenceIdentity(), y, y.value())));
            Map<String, byte[]> fragments = new HashMap<>();
            String hash = SourceObservationProgramCodec.encode(yInitialization.program(), fragments::put, FrozenNodeEvidenceCodec.Limits.defaults());
            SourceInitialization cold = SourceInitialization.fromProgram(SourceObservationProgramCodec.decode(hash, fragments::get, FrozenNodeEvidenceCodec.Limits.defaults()));
            SameOriginProcessAttempt attempt = fixture.contracts.processSameOrigin(input, policy, Collections.emptyList(), Collections.emptyMap(),
                    Collections.emptyList(), Collections.singletonList(cold));
            assertTrue(attempt.complete()); assertEquals(1, attempt.operations().size());
            SameOriginOperationResult operation = attempt.operations().get(0);
            assertEquals(ProcessorStatus.SUCCESS, operation.status());
            assertEquals(BigInteger.valueOf(5), operation.resultingDocuments().get(0).document().getProperties().get("creatorRead").getValue());
            assertEquals(BigInteger.valueOf(5), operation.resultingDocuments().get(0).document().getProperties().get("counterB").getValue());
            assertEquals(2, fixture.initializerExecutions, "Neither nested producer runs while interpreting the borrowed initialization DAG");
            assertTrue(operation.consumedSourceOperations().isEmpty());
        }
    }
    private static Node number(long value) { return new Node().value(BigInteger.valueOf(value)); }
    private static Node typed(String type) { return new Node().type(new Node().blueId(type)); }
    private static String id(Node body) { return DirectBlueIdCalculator.calculateBlueId(body); }
    private static ManagedReadPin pin(SourceObservationProgram.SourceState state) {
        return ManagedReadPin.fromExactEvidence(state.documentId(), state.blueId(), state.document(), null);
    }
    private static AffectedClosureSnapshot snapshot(List<ManagedDocumentSnapshot> members, List<ManagedOccurrenceBinding> rows, List<ManagedReadPin> pins) {
        Map<DocumentId, ManagedDocumentSnapshot> indexed = new TreeMap<>();
        for (ManagedDocumentSnapshot member : members) indexed.put(member.documentId(), member);
        List<ComponentSnapshot> components = new ArrayList<>();
        for (List<DocumentId> component : new SccPartitioner().partition(ManagedDocumentGraph.fromBindings(indexed.keySet(), rows)))
            components.add(ClosureEvidenceFactory.acyclicComponent(indexed.get(component.get(0))));
        return ClosureEvidenceFactory.affectedClosure(0L, members, rows, components, new ArrayList<>(indexed.keySet()), pins);
    }
}
