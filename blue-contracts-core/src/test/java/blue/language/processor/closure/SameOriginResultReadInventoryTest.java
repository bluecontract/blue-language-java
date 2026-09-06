package blue.language.processor.closure;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.registry.RuntimeBlueIds;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Actual owning interpreter results, not assembled or transport-only operation fixtures. */
final class SameOriginResultReadInventoryTest {
    @Test void inactiveProspectiveReferenceDoesNotObserveTheTargetsAmbientHeadOrUnusedCachedPins() {
        compareHeads(false);
    }

    @Test void activeHistoricalReadKeepsTheSelectedValueWithoutClaimingTheTargetsAmbientEpoch() {
        compareHeads(true);
    }

    @Test void terminalScopedFailureDoesNotDemandOrExportAnUnownedBody() {
        try (SourceInitializationAttachmentTest.Fixture fixture = new SourceInitializationAttachmentTest.Fixture()) {
            Set<DocumentId> owned = Collections.singleton(fixture.binding.sourceDocumentId());
            AffectedClosureSnapshot sparse = fixture.input.snapshot().retainResidentBodies(owned,
                    fixture.contracts.captureRootMetadata(fixture.input.snapshot()));
            assertFalse(sparse.managedDocument(fixture.binding.targetDocumentId()).hasResidentBody());
            ClosureInvocationInput input = ClosureEvidenceFactory.processClosure(sparse, fixture.input.cause(),
                    fixture.input.directDeliveries(), ClosureEvidenceFactory.executionPolicy(0L, Collections.emptyMap(), "zero allowance"),
                    fixture.input.environment());
            ClosureAttemptResult failed = fixture.contracts.processExternalScope(input, owned, Collections.emptyList());
            assertTrue(failed.isComplete(), () -> failed.requiredExactBlueIds().toString());
            assertEquals(ProcessorStatus.GAS_LIMIT_EXCEEDED, failed.processResult().status());
            ResultingDocument untouched = failed.processResult().resultingDocuments().stream()
                    .filter(value -> value.documentId().equals(fixture.binding.targetDocumentId())).findFirst().get();
            assertFalse(untouched.hasResidentBody()); assertEquals(0L, untouched.epoch());
            assertThrows(blue.language.processor.ExecutionEvidenceUnavailableException.class, untouched::document);
            assertThrows(IllegalArgumentException.class, () -> ResultingDocument.unchangedVerified(
                    sparse.managedDocument(fixture.binding.targetDocumentId()), sparse.component(fixture.binding.sourceDocumentId())));
            SourceOperationFailure failure = SourceOperationFailure.fromProcessClosure(input, failed.processResult(), owned);
            assertEquals(owned, stateIds(failure.sourcePredecessors()));
            Map<String, byte[]> fragments = new HashMap<>();
            String root = SourceOperationFailureCodec.encode(failure, fragments::put, FrozenNodeEvidenceCodec.Limits.defaults());
            SourceOperationFailure cold = SourceOperationFailureCodec.decode(root, fragments::get, FrozenNodeEvidenceCodec.Limits.defaults());
            assertEquals(owned, stateIds(cold.sourcePredecessors()));
            assertEquals(failure.gasTraceIdentity(), cold.gasTraceIdentity());
            assertFalse(sparse.managedDocument(fixture.binding.targetDocumentId()).hasResidentBody());
        }
    }

    @Test void successfulScopedNoopCapturesOnlyOwnedStatesAndPreservesNonresidentDependencyAuthority() {
        try (SourceInitializationAttachmentTest.Fixture fixture = new SourceInitializationAttachmentTest.Fixture()) {
            DocumentId owner = fixture.binding.sourceDocumentId(), dependency = fixture.binding.targetDocumentId();
            Node body = fixture.input.snapshot().managedDocument(owner).document();
            for (String handler : Arrays.asList("create", "read", "retire")) body.getContracts().getProperties().remove(handler);
            Node initializedDependency = fixture.initialization.program().sourceResults().get(0).document();
            ManagedDocumentSnapshot b = new ManagedDocumentSnapshot(dependency, id(initializedDependency), initializedDependency,
                    true, false, true, 0, 0);
            body.properties("child", new Node().blueId(b.blueId()));
            ManagedDocumentSnapshot a = new ManagedDocumentSnapshot(owner, id(body), body, true, false, true, 0, 0);
            ManagedOccurrenceBinding binding = ManagedOccurrenceBinding.derived(fixture.input.environment().managedBindingPolicyIdentity(),
                    owner, fixture.binding.sourceAddress(), dependency, b.blueId(), false, null);
            AffectedClosureSnapshot full = ClosureEvidenceFactory.affectedClosure(0, Arrays.asList(a, b), Collections.singletonList(binding),
                    Arrays.asList(ClosureEvidenceFactory.acyclicComponent(b), ClosureEvidenceFactory.acyclicComponent(a)),
                    fixture.input.snapshot().publicRootDocumentIds());
            AffectedClosureSnapshot sparse = full.retainResidentBodies(Collections.singleton(owner), fixture.contracts.captureRootMetadata(full));
            ClosureInvocationInput input = ClosureEvidenceFactory.processClosure(sparse, fixture.input.cause(), fixture.input.directDeliveries(),
                    fixture.input.executionPolicy(), fixture.input.environment());
            SourceObservationProgram[] captured = new SourceObservationProgram[1];
            try (BlueClosureContracts contracts = new BlueClosureContracts(fixture.owner, new ClosureExecutionObserver() {
                @Override public boolean capturesSourceObservationProgram() { return true; }
                @Override public void onSourceObservationProgram(SourceObservationProgram program) { captured[0] = program; }
                @Override public void onExecutionEvidence(ClosureImplementationEvidence evidence) { }
            })) {
                ClosureAttemptResult attempt = contracts.processExternalScope(input, Collections.singleton(owner), Collections.emptyList());
                assertTrue(attempt.isComplete(), () -> attempt.requiredExactBlueIds().toString());
                assertEquals(ProcessorStatus.SUCCESS, attempt.processResult().status(), () -> attempt.processResult().diagnostic() == null
                        ? "No diagnostic" : attempt.processResult().diagnostic().message());
                ResultingDocument untouched = attempt.processResult().resultingDocuments().stream()
                        .filter(value -> value.documentId().equals(dependency)).findFirst().get();
                assertFalse(untouched.hasResidentBody()); assertEquals(0L, untouched.epoch());
                assertEquals(sparse.managedDocument(dependency).blueId(), untouched.afterBlueId());
                assertSame(sparse.managedDocument(dependency).reusableAuthority().get(), untouched.asSnapshot().reusableAuthority().get());
                assertNotNull(captured[0]);
                assertEquals(Collections.singleton(owner), stateIds(captured[0].sourcePredecessors()));
                assertEquals(Collections.singleton(owner), stateIds(captured[0].sourceResults()));
                assertTrue(captured[0].sourceReadPins().isEmpty());
                Map<String, byte[]> fragments = new HashMap<>();
                String digest = SourceObservationProgramCodec.encode(captured[0], fragments::put, FrozenNodeEvidenceCodec.Limits.defaults());
                assertEquals(Collections.singleton(owner), stateIds(SourceObservationProgramCodec.decode(digest, fragments::get,
                        FrozenNodeEvidenceCodec.Limits.defaults()).sourceResults()));
            }
        }
    }

    private static void compareHeads(boolean activeRead) {
        try (SourceInitializationAttachmentTest.Fixture fixture = new SourceInitializationAttachmentTest.Fixture()) {
            DocumentId source = fixture.binding.targetDocumentId(), owner = fixture.binding.sourceDocumentId();
            Node initialized = fixture.initialization.program().sourceResults().get(0).document();
            Node atFive = initialized.clone().properties("counter", new Node().value(BigInteger.valueOf(77)));
            Node atHundred = initialized.clone().properties("counter", new Node().value(BigInteger.valueOf(999)));
            Node selected = activeRead ? atFive : fixture.initialization.program().sourcePredecessors().get(0).document();
            String selectedId = id(selected);
            Node ownBody = fixture.input.snapshot().managedDocument(owner).document();
            ownBody.properties("child", new Node().blueId(selectedId));
            ownBody.getContracts().getProperties().remove("create");
            ownBody.getContracts().getProperties().remove("read");
            ownBody.getContracts().getProperties().remove("retire");
            if (activeRead) {
                ownBody.getContracts().getProperties().get("observe").properties("channel", new Node().value("external"));
                ownBody.getContracts().properties("embedded", new Node().type(new Node().blueId(RuntimeBlueIds.PROCESS_EMBEDDED))
                        .properties("paths", new Node().items(new Node().value("/child"))));
            }
            ManagedDocumentSnapshot a = new ManagedDocumentSnapshot(owner, id(ownBody), ownBody, true, false, true, 0, 0);
            ManagedOccurrenceBinding binding = ManagedOccurrenceBinding.derived(fixture.input.environment().managedBindingPolicyIdentity(),
                    owner, fixture.binding.sourceAddress(), source, selectedId, activeRead, null);
            ManagedReadPin selectedPin = ManagedReadPin.fromExactEvidence(source, selectedId, selected, null);
            SameOriginOperationResult first = execute(fixture, a, source, atFive, 5L, binding,
                    activeRead ? Collections.emptyList() : Collections.singletonList(selectedPin));
            SameOriginOperationResult later = execute(fixture, a, source, atHundred, 100L, binding,
                    Arrays.asList(selectedPin, ManagedReadPin.fromExactEvidence(source, id(atHundred), atHundred, null)));
            assertEquals(first.operationIdentity(), later.operationIdentity());
            assertEquals(first.gasTraceIdentity(), later.gasTraceIdentity());
            assertEquals(first.resultingDocuments().get(0).afterBlueId(), later.resultingDocuments().get(0).afterBlueId());
            assertEquals(activeRead ? BigInteger.valueOf(77) : BigInteger.ZERO,
                    later.resultingDocuments().get(0).document().getProperties().get("counterB").getValue());
            assertTrue(later.consumedSourceOperations().isEmpty());
            SourceObservationProgram left = first.sourceProgram().get(), right = later.sourceProgram().get();
            assertEquals(Collections.singleton(owner), stateIds(left.sourcePredecessors()));
            assertEquals(Collections.singleton(owner), stateIds(right.sourceResults()));
            assertTrue(right.sourceReadPins().isEmpty(), "Optional host cache inventory is not semantic source evidence");
            Map<String, byte[]> leftBytes = new HashMap<>(), rightBytes = new HashMap<>();
            String firstRoot = SourceObservationProgramCodec.encode(left, leftBytes::put, FrozenNodeEvidenceCodec.Limits.defaults());
            String secondRoot = SourceObservationProgramCodec.encode(right, rightBytes::put, FrozenNodeEvidenceCodec.Limits.defaults());
            assertEquals(firstRoot, secondRoot, "Exact source receipt bytes must not depend on an unobserved mutable target head");
            SourceObservationProgram cold = SourceObservationProgramCodec.decode(secondRoot, rightBytes::get,
                    FrozenNodeEvidenceCodec.Limits.defaults());
            assertEquals(selectedId, cold.sourceAfterBindings().get(0).expectedTargetBlueId());
            assertEquals(Collections.singleton(owner), stateIds(cold.sourceResults()));
            if (activeRead) verifyBorrowedReplayRequiresItsSelectedBody(fixture, first, cold, atFive);
            assertEquals(1, fixture.initializerExecutions);
        }
    }

    private static void verifyBorrowedReplayRequiresItsSelectedBody(SourceInitializationAttachmentTest.Fixture fixture,
            SameOriginOperationResult original, SourceObservationProgram coldProgram, Node selectedBody) {
        ManagedDocumentSnapshot producer = original.predecessors().get(0);
        DocumentId dependency = fixture.binding.targetDocumentId(), observer = new DocumentId("root-observer");
        ManagedDocumentSnapshot b = new ManagedDocumentSnapshot(dependency, id(selectedBody), selectedBody, true, false, true, 5L, 0L);
        Node rootBody = producer.document().name("Observer of retained producer")
                .properties("producer", new Node().blueId(producer.blueId()));
        rootBody.getContracts().getProperties().get("embedded").properties("paths", new Node().items(
                Arrays.asList(new Node().value("/child"), new Node().value("/producer"))));
        ManagedDocumentSnapshot root = new ManagedDocumentSnapshot(observer, id(rootBody), rootBody, true, false, true, 0, 0);
        String policy = fixture.input.environment().managedBindingPolicyIdentity();
        List<ManagedOccurrenceBinding> bindings = new ArrayList<>(coldProgram.sourceBeforeBindings());
        bindings.add(ManagedOccurrenceBinding.derived(policy, observer, ScopeAddress.embedded("/child", 1),
                dependency, b.blueId(), true, null));
        bindings.add(ManagedOccurrenceBinding.derived(policy, observer, ScopeAddress.embedded("/producer", 1),
                producer.documentId(), producer.blueId(), true, null));
        AffectedClosureSnapshot full = ClosureEvidenceFactory.affectedClosure(0, Arrays.asList(producer, b, root), bindings,
                Arrays.asList(ClosureEvidenceFactory.acyclicComponent(b), ClosureEvidenceFactory.acyclicComponent(producer),
                        ClosureEvidenceFactory.acyclicComponent(root)), Arrays.asList(producer.documentId(), dependency, observer));
        AffectedClosureSnapshot sparse = full.retainResidentBodies(new HashSet<>(Arrays.asList(producer.documentId(), observer)),
                fixture.contracts.captureRootMetadata(full));
        List<DirectLogicalDelivery> deliveries = Arrays.asList(
                new DirectLogicalDelivery(ManagedScopeKey.root(producer.documentId()), "external", "logical", 0),
                new DirectLogicalDelivery(ManagedScopeKey.root(observer), "external", "logical", 1));
        ClosureInvocationInput missingInput = ClosureEvidenceFactory.processClosure(sparse, fixture.input.cause(), deliveries,
                fixture.input.executionPolicy(), fixture.input.environment());
        SameOriginProcessAttempt missing = fixture.contracts.processSameOrigin(missingInput, SameOriginAttachmentPolicy.empty(),
                Collections.singletonList(coldProgram), Collections.emptyMap(), Collections.emptyList());
        assertFalse(missing.complete(), "A retained producer must not import a physical dependency cache into its observer");
        assertEquals(Collections.singletonList(b.blueId()), missing.requiredExactBlueIds());
        AffectedClosureSnapshot hydrated = sparse.withResidentBody(ManagedReadPin.fromExactEvidence(dependency, b.blueId(), selectedBody, null));
        ClosureInvocationInput readyInput = ClosureEvidenceFactory.processClosure(hydrated, fixture.input.cause(), deliveries,
                fixture.input.executionPolicy(), fixture.input.environment());
        SameOriginProcessAttempt ready = fixture.contracts.processSameOrigin(readyInput, SameOriginAttachmentPolicy.empty(),
                Collections.singletonList(coldProgram), Collections.emptyMap(), Collections.emptyList());
        assertTrue(ready.complete(), () -> ready.requiredExactBlueIds().toString());
        assertEquals(1, ready.operations().size());
        assertEquals(Collections.singleton(observer), ready.operations().get(0).ownedDocumentIds());
        assertEquals(BigInteger.valueOf(77), ready.operations().get(0).resultingDocuments().get(0).document()
                .getProperties().get("counterB").getValue());
    }

    private static SameOriginOperationResult execute(SourceInitializationAttachmentTest.Fixture fixture,
            ManagedDocumentSnapshot a, DocumentId source, Node sourceBody, long sourceEpoch,
            ManagedOccurrenceBinding binding, List<ManagedReadPin> pins) {
        ManagedDocumentSnapshot b = new ManagedDocumentSnapshot(source, id(sourceBody), sourceBody, true, false, true, sourceEpoch, 0);
        Map<DocumentId, ManagedDocumentSnapshot> byId = new HashMap<>(); byId.put(a.documentId(), a); byId.put(b.documentId(), b);
        List<ComponentSnapshot> components = new ArrayList<>();
        for (List<DocumentId> members : new SccPartitioner().partition(ManagedDocumentGraph.fromBindings(byId.keySet(), Collections.singletonList(binding))))
            components.add(ClosureEvidenceFactory.acyclicComponent(byId.get(members.get(0))));
        AffectedClosureSnapshot snapshot = ClosureEvidenceFactory.affectedClosure(0, Arrays.asList(a, b),
                Collections.singletonList(binding), components, Arrays.asList(a.documentId(), b.documentId()), pins);
        ClosureInvocationInput input = ClosureEvidenceFactory.processClosure(snapshot, fixture.input.cause(),
                fixture.input.directDeliveries(), fixture.input.executionPolicy(), fixture.input.environment());
        SameOriginProcessAttempt attempt = fixture.contracts.processSameOrigin(input);
        assertTrue(attempt.complete(), () -> attempt.requiredExactBlueIds().toString());
        assertEquals(1, attempt.operations().size());
        SameOriginOperationResult result = attempt.operations().get(0);
        assertEquals(ProcessorStatus.SUCCESS, result.status());
        return result;
    }

    private static Set<DocumentId> stateIds(List<SourceObservationProgram.SourceState> values) {
        Set<DocumentId> ids = new TreeSet<>();
        for (SourceObservationProgram.SourceState state : values) ids.add(state.documentId());
        return ids;
    }
    private static String id(Node value) { return DirectBlueIdCalculator.calculateBlueId(value); }
}
