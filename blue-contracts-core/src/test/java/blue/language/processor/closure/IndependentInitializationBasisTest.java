package blue.language.processor.closure;

import blue.language.processor.ProcessorStatus;
import blue.language.model.Node;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.processor.registry.RuntimeBlueIds;
import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class IndependentInitializationBasisTest {
    @Test void interpretedInitializationChangesOnlySettlementIdentityAndSurvivesColdRestoration() {
        try (SourceInitializationAttachmentTest.Fixture f = new SourceInitializationAttachmentTest.Fixture()) {
            ExecutionPolicy alternatePolicy = ClosureEvidenceFactory.executionPolicy(90_000L, Collections.emptyMap(), "source-policy");
            SourceInitialization alternate = initialize(f, alternatePolicy);
            assertEquals(f.initialization.program().sourceResults().get(0).blueId(), alternate.program().sourceResults().get(0).blueId());
            assertNotEquals(f.initialization.program().invocationIdentity(), alternate.program().invocationIdentity());
            Map<String, byte[]> fragments = new HashMap<>();
            SameOriginOperationResult first = null;
            Map<String, String> operations = new HashMap<>();
            Map<String, List<String>> works = new HashMap<>(), events = new HashMap<>();
            for (boolean cold : Arrays.asList(false, true)) for (SourceInitialization original : Arrays.asList(f.initialization, alternate)) {
                SourceObservationProgram program = original.program();
                String root = SourceObservationProgramCodec.encode(program, fragments::put, FrozenNodeEvidenceCodec.Limits.defaults());
                SourceInitialization offered = cold ? SourceInitialization.fromProgram(SourceObservationProgramCodec.decode(root, fragments::get,
                        FrozenNodeEvidenceCodec.Limits.defaults())) : original;
                ExecutionPolicy authorized = original == f.initialization ? f.input.executionPolicy() : alternatePolicy;
                SameOriginOperationResult result = f.contracts.processSameOrigin(f.input, f.attachments, Collections.emptyList(),
                        Collections.emptyMap(), Collections.emptyList(), Collections.singletonList(offered), Collections.emptyList(),
                        Collections.singletonMap(f.canonicalSourceHead.documentId(), SourceExecutionBasis.identity(f.canonicalSourceHead.documentId(),
                                f.input.environment(), authorized))).operations().get(0);
                assertEquals(ProcessorStatus.SUCCESS, result.status());
                assertTrue(result.consumedSourceOperations().isEmpty(), "An interpreted initializer is not a committed business dependency");
                assertEquals(Collections.singletonList(new SameOriginGroupEvidence.SourceEvidence(
                        SameOriginGroupEvidence.SourceEvidence.Kind.INITIALIZATION, program.invocationIdentity())), result.interpretedSourceEvidence());
                String restoredRoot = SourceObservationProgramCodec.encode(result.sourceProgram().get(), fragments::put, FrozenNodeEvidenceCodec.Limits.defaults());
                SourceObservationProgram restored = SourceObservationProgramCodec.decode(restoredRoot, fragments::get, FrozenNodeEvidenceCodec.Limits.defaults());
                assertEquals(result.interpretedSourceEvidence(), restored.interpretedSourceEvidence());
                String previous = operations.putIfAbsent(program.invocationIdentity(), result.operationIdentity());
                if (previous != null) assertEquals(previous, result.operationIdentity(), "Hot and cold evidence have one settlement identity");
                List<String> priorWorks = works.putIfAbsent(program.invocationIdentity(), workIdentities(result));
                List<String> priorEvents = events.putIfAbsent(program.invocationIdentity(), eventIdentities(result));
                if (priorWorks != null) assertEquals(priorWorks, workIdentities(result), "Hot and cold interpretation preserves all work identities");
                if (priorEvents != null) assertEquals(priorEvents, eventIdentities(result), "Hot and cold interpretation preserves all event identities");
                if (first != null) {
                    assertEquals(first.originalSeedByMember(), result.originalSeedByMember());
                    assertEquals(first.totalGas(), result.totalGas());
                    assertEquals(first.resultingDocuments().get(0).afterBlueId(), result.resultingDocuments().get(0).afterBlueId());
                    assertEquals(workIdentities(first).get(0), workIdentities(result).get(0), "Preparation evidence does not reanchor the creator's original direct work");
                } else first = result;
            }
            assertEquals(2, new HashSet<>(operations.values()).size(), "Different original source initializations cannot alias one creator operation");
            assertEquals(2, f.initializerExecutions, "Neither hot nor cold interpretation reexecutes its producer");
        }
    }

    @Test void explicitCanonicalAdmissionRequiresTheImportedInitializationInItsInputIdentity() {
        try (SourceInitializationAttachmentTest.Fixture f = new SourceInitializationAttachmentTest.Fixture()) {
            ExecutionPolicy alternatePolicy = ClosureEvidenceFactory.executionPolicy(90_000L, Collections.emptyMap(), "source-policy");
            SourceInitialization alternate = initialize(f, alternatePolicy);
            SourceObservationProgram.SourceState before = f.initialization.program().sourcePredecessors().get(0);
            SourceObservationProgram.SourceState after = f.initialization.program().sourceResults().get(0);
            Node authored = new Node().name("Independent canonical parent").properties("child", new Node().blueId(before.blueId()))
                    .contracts(new Node().properties("embedded", new Node().type(new Node().blueId(RuntimeBlueIds.PROCESS_EMBEDDED))
                            .properties("paths", new Node().items(new Node().value("/child")))));
            DocumentId parent = new DocumentId(DirectBlueIdCalculator.calculateBlueId(authored));
            ManagedDocumentSnapshot own = new ManagedDocumentSnapshot(parent, parent.value(), authored, false, false, true, 0L, 0L);
            ManagedDocumentSnapshot child = new ManagedDocumentSnapshot(after.documentId(), after.blueId(), after.document(), true, false, true, 0L, 0L);
            ManagedOccurrenceBinding binding = ManagedOccurrenceBinding.derived(f.input.environment().managedBindingPolicyIdentity(), parent,
                    ScopeAddress.embedded("/child", 1L), child.documentId(), before.blueId(), true, null);
            List<DocumentId> ids = new ArrayList<>(Arrays.asList(parent, child.documentId())); Collections.sort(ids);
            AffectedClosureSnapshot snapshot = ClosureEvidenceFactory.affectedClosure(0L, Arrays.asList(own, child), Collections.singletonList(binding),
                    Arrays.asList(ClosureEvidenceFactory.acyclicComponent(child), ClosureEvidenceFactory.acyclicComponent(own)), ids,
                    Collections.singletonList(ManagedReadPin.fromExactEvidence(before.documentId(), before.blueId(), before.document(), null)));
            ClosureInvocationInput input = ClosureEvidenceFactory.admitClosure(snapshot, SourceInitialization.canonicalAdmissionCause(Collections.singleton(parent)),
                    null, f.input.executionPolicy(), f.input.environment());
            Set<String> operations = new HashSet<>();
            for (SourceInitialization original : Arrays.asList(f.initialization, alternate)) {
                Map<DocumentId, String> bases = Collections.singletonMap(child.documentId(), SourceExecutionBasis.identity(child.documentId(), input.environment(),
                        original == f.initialization ? input.executionPolicy() : alternatePolicy));
                SourceObservationProgram[] captured = new SourceObservationProgram[1];
                try (BlueClosureContracts admitting = capturing(f, captured)) {
                    assertThrows(IllegalArgumentException.class, () -> admitting.admitExternalScope(input, Collections.singleton(parent), Collections.singletonList(original), bases));
                    assertNull(captured[0], "Unbound imported preparation must be rejected before execution");
                    ClosureInvocationInput bound = ClosureEvidenceFactory.withSemanticPredecessors(input, Collections.singletonMap(child.documentId(), original.program().invocationIdentity()));
                    ClosureAttemptResult result = admitting.admitExternalScope(bound, Collections.singleton(parent), Collections.singletonList(original), bases);
                    assertTrue(result.isComplete()); assertEquals(ProcessorStatus.SUCCESS, result.processResult().status());
                    operations.add(captured[0].invocationIdentity());
                }
            }
            assertEquals(2, operations.size());
            assertEquals(ProcessorStatus.SUCCESS, f.contracts.admitExternalScope(input, Collections.singleton(parent), Collections.singletonList(f.initialization)).processResult().status(),
                    "The existing fixed-policy overload retains its explicit compatibility contract");
            assertEquals(2, f.initializerExecutions);
        }
    }

    private static List<String> workIdentities(SameOriginOperationResult result) {
        List<String> ids = new ArrayList<>(); for (SourceObservationProgram.Step step : result.sourceProgram().get().steps()) ids.add(step.workIdentity()); return ids;
    }
    private static List<String> eventIdentities(SameOriginOperationResult result) {
        List<String> ids = new ArrayList<>(); for (SameOriginOperationResult.Event event : result.events()) ids.add(event.occurrenceIdentity()); return ids;
    }
    private static SourceInitialization initialize(SourceInitializationAttachmentTest.Fixture f, ExecutionPolicy policy) {
        ManagedDocumentSnapshot before = f.canonicalSourceHead;
        AffectedClosureSnapshot snapshot = ClosureEvidenceFactory.affectedClosure(0L, Collections.singletonList(before), Collections.emptyList(),
                Collections.singletonList(ClosureEvidenceFactory.acyclicComponent(before)), Collections.singletonList(before.documentId()), Collections.emptyList());
        ClosureInvocationInput input = ClosureEvidenceFactory.admitClosure(snapshot, SourceInitialization.canonicalAdmissionCause(Collections.singleton(before.documentId())), null, policy, f.input.environment());
        SourceObservationProgram[] captured = new SourceObservationProgram[1];
        try (BlueClosureContracts admitting = capturing(f, captured)) {
            ClosureAttemptResult result = admitting.admitExternalScope(input, Collections.singleton(before.documentId()));
            assertTrue(result.isComplete()); assertEquals(ProcessorStatus.SUCCESS, result.processResult().status());
        }
        return SourceInitialization.fromProgram(captured[0]);
    }
    private static BlueClosureContracts capturing(SourceInitializationAttachmentTest.Fixture f, SourceObservationProgram[] captured) {
        return new BlueClosureContracts(f.owner, new ClosureExecutionObserver() {
            public boolean capturesSourceObservationProgram() { return true; }
            public void onSourceObservationProgram(SourceObservationProgram program) { captured[0] = program; }
            public void onExecutionEvidence(ClosureImplementationEvidence evidence) { }
        });
    }

    @Test void runtimeInstallationUsesAnIndependentProducerWithoutRerunningItsInitializer() {
        try (SourceInitializationAttachmentTest.Fixture f = new SourceInitializationAttachmentTest.Fixture()) {
            ClosureInvocationInput consumer = withPolicy(f.input, 20_000L);
            DocumentId source = f.canonicalSourceHead.documentId();
            Map<DocumentId, String> bases = Collections.singletonMap(source,
                    SourceExecutionBasis.identity(source, f.input.environment(), f.initialization.program().executionPolicy()));
            Map<String, byte[]> fragments = new HashMap<String, byte[]>();
            String root = SourceObservationProgramCodec.encode(f.initialization.program(), fragments::put, FrozenNodeEvidenceCodec.Limits.defaults());
            SourceInitialization cold = SourceInitialization.fromProgram(SourceObservationProgramCodec.decode(root, fragments::get,
                    FrozenNodeEvidenceCodec.Limits.defaults()));
            String operation = null; long gas = -1;
            for (SourceInitialization offered : Arrays.asList(f.initialization, cold)) {
                SameOriginProcessAttempt attempt = f.contracts.processSameOrigin(consumer, f.attachments, Collections.emptyList(),
                        Collections.emptyMap(), Collections.emptyList(), Collections.singletonList(offered), Collections.emptyList(), bases);
                assertTrue(attempt.complete()); assertEquals(1, attempt.operations().size());
                SameOriginOperationResult result = attempt.operations().get(0);
                assertEquals(ProcessorStatus.SUCCESS, result.status());
                assertEquals(BigInteger.valueOf(5), result.resultingDocuments().get(0).document().getProperties().get("creatorRead").getValue());
                assertEquals(1, f.initializerExecutions, "Observation never reexecutes the original producer handler");
                assertEquals(100_000L, result.sourceProgram().get().borrowedPrograms().get(0).executionPolicy().sharedLimit());
                assertEquals(20_000L, result.sourceProgram().get().executionPolicy().sharedLimit());
                if (operation != null) { assertEquals(operation, result.operationIdentity()); assertEquals(gas, result.totalGas()); }
                operation = result.operationIdentity(); gas = result.totalGas();
            }
            assertThrows(IllegalArgumentException.class, () -> f.contracts.processSameOrigin(consumer, f.attachments,
                    Collections.emptyList(), Collections.emptyMap(), Collections.emptyList(), Collections.singletonList(cold), Collections.emptyList(),
                    Collections.singletonMap(source, SourceExecutionBasis.identity(source, consumer.environment(), consumer.executionPolicy()))));
            assertEquals(1, f.initializerExecutions);
        }
    }

    @Test void aColdExternalProducerCanBorrowAnInitializationWithAThirdPolicy() {
        try (SourceInitializationAttachmentTest.Fixture f = new SourceInitializationAttachmentTest.Fixture()) {
            ClosureInvocationInput creator = withPolicy(f.input, 20_000L);
            DocumentId source = f.canonicalSourceHead.documentId(), order = new DocumentId("order");
            Map<DocumentId, String> bases = new HashMap<DocumentId, String>();
            bases.put(source, SourceExecutionBasis.identity(source, creator.environment(), f.initialization.program().executionPolicy()));
            SameOriginOperationResult created = f.contracts.processSameOrigin(creator, f.attachments, Collections.emptyList(), Collections.emptyMap(),
                    Collections.emptyList(), Collections.singletonList(f.initialization), Collections.emptyList(), bases).operations().get(0);
            assertEquals(ProcessorStatus.SUCCESS, created.status());
            Map<String, byte[]> fragments = new HashMap<String, byte[]>();
            String root = SourceObservationProgramCodec.encode(created.sourceProgram().get(), fragments::put, FrozenNodeEvidenceCodec.Limits.defaults());
            SourceObservationProgram cold = SourceObservationProgramCodec.decode(root, fragments::get, FrozenNodeEvidenceCodec.Limits.defaults());
            int creatorRuns = f.creatorExecutions;
            // Direct Language substitution exercises recursive borrowed initialization even
            // without a new observer handler: the producer tape is interpreted, never rerun.
            bases.put(order, SourceExecutionBasis.identity(order, creator.environment(), creator.executionPolicy()));
            ClosureInvocationInput importing = withPolicy(f.input, 30_000L);
            assertThrows(IllegalArgumentException.class, () -> f.contracts.processSameOrigin(importing, f.attachments,
                    Collections.singletonList(cold), Collections.emptyMap(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                    Collections.singletonMap(order, bases.get(order))));
            SameOriginProcessAttempt replay = f.contracts.processSameOrigin(importing, f.attachments, Collections.singletonList(cold),
                    Collections.emptyMap(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), bases);
            assertTrue(replay.complete()); assertTrue(replay.operations().isEmpty());
            assertEquals(creatorRuns, f.creatorExecutions); assertEquals(1, f.initializerExecutions);
        }
    }

    private static ClosureInvocationInput withPolicy(ClosureInvocationInput input, long limit) {
        return ClosureEvidenceFactory.processClosure(input.snapshot(), input.cause(), input.directDeliveries(),
                ClosureEvidenceFactory.executionPolicy(limit, Collections.emptyMap(), "consumer-" + limit), input.environment());
    }
}
