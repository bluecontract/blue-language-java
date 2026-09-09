package blue.language.processor.closure;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.GasSchedule;
import blue.language.processor.GasTraceEntry;
import blue.language.processor.ManagedCheckpointSettlementBatch;
import blue.language.processor.ManagedRootChannelOccurrence;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Focused public-receipt proofs for one active different-lineage retarget. */
final class DifferentLineageRetargetReceiptTest {

    private static final DocumentId A = new DocumentId("a");
    private static final DocumentId B = new DocumentId("b");
    private static final DocumentId C = new DocumentId("c");
    private static final String PATH = "/peer";

    @Test
    void reservedHistoricalSelectionKeepsItsGenerationAndRequiresExactRetryEvidence() {
        try (DocumentProcessor owner = DocumentProcessor.builder().build()) {
            ClosureEnvironment environment = environment(owner);
            Scenario scenario = reservedHistoricalScenario(true, 4L, B);
            ClosureInvocationInput input = invocation(scenario.input.snapshot, environment);
            ManagedOccurrenceEvidenceDemand demand = historicalDemand(input, scenario.after.expectedTargetBlueId());
            ManagedOccurrenceEvidenceResolution resolution = ManagedOccurrenceEvidenceResolution.derived(demand, B, -1L);
            ClosureProcessRetryInput retry = ClosureProcessRetryInput.derived(input, Collections.singletonList(resolution));
            assertEquals(retry.retryInvocationIdentity(), ClosureInvocationVerifier.verifyRetry(
                    retry, owner.administration()::runtimeAccess).invocationIdentity());
            ClosureProcessResult result = ClosureSuccessResultAssembler.assemble(
                    input, executionState(scenario.output), Collections.singletonList(resolution));
            assertTrue(result.commits());
            assertEquals(7L, result.graphGeneration());
            assertTrue(result.graphChanges().isEmpty());
            assertEquals(scenario.before.occurrenceIdentity(), result.occurrenceBindings().get(0).occurrenceIdentity());
            assertEquals(4L, result.occurrenceBindings().get(0).activationGeneration());
            assertEquals(Long.valueOf(-1L), result.occurrenceBindings().get(0).pendingHistoricalEpoch());
            assertThrows(IllegalArgumentException.class, () -> ClosureSuccessResultAssembler.assemble(input, executionState(scenario.output)));
            for (ManagedOccurrenceEvidenceResolution wrong : Arrays.asList(
                    ManagedOccurrenceEvidenceResolution.derived(demand, C, -1L),
                    ManagedOccurrenceEvidenceResolution.derived(demand, B, 1L),
                    ManagedOccurrenceEvidenceResolution.derived(historicalDemand(input, scenario.before.expectedTargetBlueId()), B, -1L))) {
                assertThrows(IllegalArgumentException.class, () -> ClosureSuccessResultAssembler.assemble(
                        input, executionState(scenario.output), Collections.singletonList(wrong)));
            }
            assertThrows(IllegalArgumentException.class, () -> ClosureInvocationVerifier.verifyRetry(
                    ClosureProcessRetryInput.derived(input, Collections.singletonList(
                            ManagedOccurrenceEvidenceResolution.derived(demand, C, -1L))), owner.administration()::runtimeAccess));
            assertThrows(IllegalArgumentException.class, () -> ClosureInvocationVerifier.verifyRetry(
                    ClosureProcessRetryInput.derived(input, Collections.singletonList(
                            ManagedOccurrenceEvidenceResolution.derived(demand, B, 1L))), owner.administration()::runtimeAccess));
            for (Scenario changed : Arrays.asList(reservedHistoricalScenario(true, 5L, B), reservedHistoricalScenario(true, 4L, C))) {
                assertThrows(IllegalArgumentException.class, () -> ClosureSuccessResultAssembler.assemble(
                        input, executionState(changed.output), Collections.singletonList(resolution)));
            }
            assertFalse(resolution.selectsInactiveReservation(input.snapshot(), ManagedOccurrenceBinding.derived(
                    scenario.before.bindingPolicyIdentity(), A, ScopeAddress.embedded(PATH, 5L), B,
                    scenario.before.expectedTargetBlueId(), false, null)), "A row retired in this invocation is not the input reservation");
            Scenario draft = reservedHistoricalScenario(false, 4L, B);
            ClosureInvocationInput draftInput = invocation(draft.input.snapshot, environment);
            assertThrows(IllegalArgumentException.class, () -> ClosureInvocationVerifier.verifyRetry(
                    ClosureProcessRetryInput.derived(draftInput, Collections.singletonList(
                            ManagedOccurrenceEvidenceResolution.derived(historicalDemand(draftInput, draft.after.expectedTargetBlueId()), B, -1L))),
                    owner.administration()::runtimeAccess));
        }
    }

    private static Scenario reservedHistoricalScenario(boolean initialized, long afterGeneration, DocumentId target) {
        Node current = new Node().name("Current reserved source");
        if (initialized) current.contracts(new Node().properties(
                blue.language.processor.util.ProcessorContractConstants.KEY_INITIALIZED,
                new Node().type(new Node().blueId(blue.language.processor.registry.RuntimeBlueIds.PROCESSING_INITIALIZED_MARKER))
                        .properties("document", new Node().blueId(blueId(new Node().name("Saved source"))))));
        Node foreign = new Node().name("Foreign source");
        String old = blueId(new Node().name("Saved source"));
        ManagedOccurrenceBinding before = ManagedOccurrenceBinding.derived(bindingPolicyIdentity(), A,
                ScopeAddress.embedded(PATH, 4L), B, blueId(current), false, null);
        ManagedOccurrenceBinding after = ManagedOccurrenceBinding.derived(bindingPolicyIdentity(), A,
                ScopeAddress.embedded(PATH, afterGeneration), target, old, false, -1L);
        SnapshotEvidence input = initializedTarget(finalizedSnapshot(7L,
                bodies(new Node().name("Parent"), current, foreign), Collections.singletonList(before), generations()), initialized);
        SnapshotEvidence output = initializedTarget(finalizedSnapshot(7L,
                bodies(new Node().name("Parent").properties("peer", new Node().blueId(old)), current, foreign),
                Collections.singletonList(after), generations()), initialized);
        return new Scenario(input, output, before, after);
    }

    private static SnapshotEvidence initializedTarget(SnapshotEvidence evidence, boolean initialized) {
        List<ManagedDocumentSnapshot> documents = new ArrayList<>();
        for (ManagedDocumentSnapshot d : evidence.snapshot.managedDocuments()) {
            documents.add(new ManagedDocumentSnapshot(d.documentId(), d.blueId(), d.document(),
                    initialized && d.documentId().equals(B), d.terminated(), d.publicRoot(), d.epoch(), d.componentGeneration()));
        }
        return new SnapshotEvidence(ClosureEvidenceFactory.affectedClosure(evidence.snapshot.graphGeneration(),
                documents, evidence.snapshot.occurrences(), evidence.snapshot.components(), evidence.snapshot.publicRootDocumentIds()), evidence.finalization);
    }

    @Test
    void assemblesOneAtomicRebindWithFreshNextGenerationIdentity() {
        try (DocumentProcessor owner = DocumentProcessor.builder().build()) {
            ClosureEnvironment environment = environment(owner);
            Scenario scenario = scenario(
                    environment.managedBindingPolicyIdentity());

            ClosureProcessResult result = ClosureSuccessResultAssembler
                    .assemble(
                            invocation(
                                    scenario.input.snapshot,
                                    environment),
                            executionState(scenario.output));

            assertEquals(8L, result.graphGeneration());
            assertEquals(1, result.graphChanges().size());
            GraphChange change = result.graphChanges().get(0);
            assertEquals(GraphChange.Kind.REBIND, change.changeKind());
            assertEquals(A, change.sourceDocumentId());
            assertEquals(PATH, change.sourcePath());
            assertEquals(4L, change.before().activationGeneration());
            assertEquals(5L, change.after().activationGeneration());
            assertEquals(B, change.before().targetDocumentId());
            assertEquals(C, change.after().targetDocumentId());
            assertNotEquals(change.before().occurrenceIdentity(),
                    change.after().occurrenceIdentity());
            assertEquals(scenario.before.occurrenceIdentity(),
                    change.before().occurrenceIdentity());
            assertEquals(scenario.after.occurrenceIdentity(),
                    change.after().occurrenceIdentity());
            assertEquals(1, result.occurrenceBindings().size());
            ManagedOccurrenceBinding resulting =
                    result.occurrenceBindings().get(0);
            assertEquals(scenario.after.occurrenceIdentity(),
                    resulting.occurrenceIdentity());
            assertEquals(scenario.after.bindingIdentity(),
                    resulting.bindingIdentity());
            assertEquals(5L, resulting.activationGeneration());
            assertEquals(C, resulting.targetDocumentId());
            assertTrue(resulting.active());
        }
    }

    @Test
    void rejectsMixedRetargetShapesAtTheReceiptBoundary() {
        Node oldTarget = new Node().name("Old target");
        Node newTarget = new Node().name("New target");
        String oldBlueId = blueId(oldTarget);
        String newBlueId = blueId(newTarget);
        GraphChange.Side before = new GraphChange.Side(
                4L, hash('1'), hash('2'), B, oldBlueId);

        assertThrows(IllegalArgumentException.class,
                () -> new GraphChange(
                        0L,
                        GraphChange.Kind.REBIND,
                        A,
                        PATH,
                        before,
                        new GraphChange.Side(
                                5L, hash('3'), hash('4'), B, newBlueId)));
        assertThrows(IllegalArgumentException.class,
                () -> new GraphChange(
                        0L,
                        GraphChange.Kind.REBIND,
                        A,
                        PATH,
                        before,
                        new GraphChange.Side(
                                4L, hash('1'), hash('4'), C, newBlueId)));
        assertThrows(IllegalArgumentException.class,
                () -> new GraphChange(
                        0L,
                        GraphChange.Kind.REBIND,
                        A,
                        PATH,
                        before,
                        new GraphChange.Side(
                                6L, hash('3'), hash('4'), C, newBlueId)));
        assertThrows(IllegalArgumentException.class,
                () -> new GraphChange(
                        0L,
                        GraphChange.Kind.REBIND,
                        A,
                        PATH,
                        before,
                        new GraphChange.Side(
                                5L, hash('3'), hash('2'), C, newBlueId)));
    }

    @Test
    void verifierRejectsRetargetAcrossBindingPolicies() {
        try (DocumentProcessor owner = DocumentProcessor.builder().build()) {
            ClosureEnvironment environment = environment(owner);
            Scenario scenario = scenario(hash('9'));

            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> ClosureSuccessResultAssembler.assemble(
                            invocation(
                                    scenario.input.snapshot,
                                    environment),
                            executionState(scenario.output)));

            assertTrue(failure.getMessage().contains(
                    "Different-lineage REBIND"));
        }
    }

    @Test
    void acceptsHistoricalDifferentLineageRetargetOnlyWithExactResolution() {
        try (DocumentProcessor owner = DocumentProcessor.builder().build()) {
            ClosureEnvironment environment = environment(owner);
            Scenario scenario = historicalScenario(
                    environment.managedBindingPolicyIdentity());
            ClosureInvocationInput input = invocation(
                    scenario.input.snapshot, environment);
            ManagedOccurrenceEvidenceDemand demand =
                    historicalDemand(input, scenario.after.expectedTargetBlueId());
            ManagedOccurrenceEvidenceResolution resolution =
                    ManagedOccurrenceEvidenceResolution.derived(
                            demand, C, -1L);

            ClosureProcessResult result = ClosureSuccessResultAssembler
                    .assemble(
                            input,
                            executionState(scenario.output),
                            Collections.singletonList(resolution));

            assertEquals(8L, result.graphGeneration());
            assertEquals(1, result.graphChanges().size());
            assertEquals(GraphChange.Kind.REMOVE,
                    result.graphChanges().get(0).changeKind());
            assertEquals(scenario.before.occurrenceIdentity(),
                    result.graphChanges().get(0).before()
                            .occurrenceIdentity());
            assertEquals(1, result.occurrenceBindings().size());
            ManagedOccurrenceBinding retained =
                    result.occurrenceBindings().get(0);
            assertEquals(C, retained.targetDocumentId());
            assertEquals(5L, retained.activationGeneration());
            assertEquals(Long.valueOf(-1L),
                    retained.pendingHistoricalEpoch());
            assertEquals(scenario.after.occurrenceIdentity(),
                    retained.occurrenceIdentity());
        }
    }

    @Test
    void rejectsHistoricalDifferentLineageRetargetWithoutExactResolution() {
        try (DocumentProcessor owner = DocumentProcessor.builder().build()) {
            ClosureEnvironment environment = environment(owner);
            Scenario scenario = historicalScenario(
                    environment.managedBindingPolicyIdentity());
            ClosureInvocationInput input = invocation(
                    scenario.input.snapshot, environment);

            assertThrows(
                    IllegalArgumentException.class,
                    () -> ClosureSuccessResultAssembler.assemble(
                            input, executionState(scenario.output)));

            ManagedOccurrenceEvidenceDemand wrongDemand = historicalDemand(
                    input, scenario.before.expectedTargetBlueId());
            ManagedOccurrenceEvidenceResolution wrongResolution =
                    ManagedOccurrenceEvidenceResolution.derived(
                            wrongDemand, C, -1L);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ClosureSuccessResultAssembler.assemble(
                            input,
                            executionState(scenario.output),
                            Collections.singletonList(wrongResolution)));
        }
    }

    @Test
    void retryIdentityIsDeterministicAndBoundToTheSuspendedInvocation() {
        try (DocumentProcessor owner = DocumentProcessor.builder().build()) {
            ClosureEnvironment environment = environment(owner);
            Scenario scenario = historicalScenario(
                    environment.managedBindingPolicyIdentity());
            ClosureInvocationInput input = invocation(
                    scenario.input.snapshot, environment);
            ManagedOccurrenceEvidenceResolution resolution =
                    ManagedOccurrenceEvidenceResolution.derived(
                            historicalDemand(
                                    input,
                                    scenario.after.expectedTargetBlueId()),
                            C,
                            -1L);

            ClosureProcessRetryInput first = ClosureProcessRetryInput.derived(
                    input, Collections.singletonList(resolution));
            ClosureProcessRetryInput reconstructed =
                    ClosureProcessRetryInput.derived(
                            input, Collections.singletonList(resolution));

            assertEquals(first.retryInvocationIdentity(),
                    reconstructed.retryInvocationIdentity());
            assertEquals(first.resolutionSetIdentity(),
                    reconstructed.resolutionSetIdentity());
            assertNotEquals(input.invocationIdentity(),
                    first.retryInvocationIdentity());
            assertEquals(first.retryInvocationIdentity(),
                    ClosureInvocationVerifier.verifyRetry(
                            first,
                            owner.administration()::runtimeAccess)
                            .invocationIdentity());
            assertThrows(UnsupportedOperationException.class,
                    () -> first.resolutions().clear());

            ManagedOccurrenceEvidenceDemand foreignDemand =
                    ManagedOccurrenceEvidenceDemand.derived(
                            input.cause().causeIdentity(),
                            hash('f'),
                            input.snapshot().graphGeneration(),
                            A,
                            PATH,
                            blueId(new Node().name(
                                    "process-embedded declaration")),
                            scenario.after.expectedTargetBlueId(),
                            0L);
            ClosureProcessRetryInput foreign =
                    ClosureProcessRetryInput.derived(
                            input,
                            Collections.singletonList(
                                    ManagedOccurrenceEvidenceResolution
                                            .derived(
                                                    foreignDemand,
                                                    C,
                                                    -1L)));
            assertThrows(IllegalArgumentException.class,
                    () -> ClosureInvocationVerifier.verifyRetry(
                            foreign,
                            owner.administration()::runtimeAccess));
        }
    }

    @Test
    void sameLineageHistoricalReplacementRequiresItsExactResolution() {
        // given
        try (DocumentProcessor owner = DocumentProcessor.builder().build()) {
            ClosureEnvironment environment = environment(owner);
            Scenario scenario = historicalScenario(environment.managedBindingPolicyIdentity(), B);
            ClosureInvocationInput input = invocation(scenario.input.snapshot, environment);
            ManagedOccurrenceEvidenceDemand demand = historicalDemand(input, scenario.after.expectedTargetBlueId());
            ManagedOccurrenceEvidenceResolution resolution = ManagedOccurrenceEvidenceResolution.derived(demand, B, -1L);
            ClosureProcessRetryInput retry = ClosureProcessRetryInput.derived(input, Collections.singletonList(resolution));

            // when
            ClosureInvocationVerifier.Verification verified = ClosureInvocationVerifier.verifyRetry(retry, owner.administration()::runtimeAccess);
            ClosureProcessResult result = ClosureSuccessResultAssembler.assemble(input, executionState(scenario.output), Collections.singletonList(resolution));

            // then
            assertEquals(retry.retryInvocationIdentity(), verified.invocationIdentity());
            assertEquals(1, result.graphChanges().size());
            assertEquals(GraphChange.Kind.REMOVE, result.graphChanges().get(0).changeKind());
            ManagedOccurrenceBinding retained = result.occurrenceBindings().get(0);
            assertEquals(B, retained.targetDocumentId());
            assertEquals(5L, retained.activationGeneration());
            assertEquals(Long.valueOf(-1L), retained.pendingHistoricalEpoch());
            assertFalse(retained.active());
            assertNotEquals(scenario.before.occurrenceIdentity(), retained.occurrenceIdentity());
            assertTrue(result.publicEvents().isEmpty());
            assertThrows(IllegalArgumentException.class, () -> ClosureSuccessResultAssembler.assemble(input, executionState(scenario.output)));
            assertThrows(IllegalArgumentException.class, () -> ClosureSuccessResultAssembler.assemble(input, executionState(scenario.output), Collections.singletonList(ManagedOccurrenceEvidenceResolution.derived(demand, C, -1L))));
            assertThrows(IllegalArgumentException.class, () -> ClosureSuccessResultAssembler.assemble(input, executionState(scenario.output), Collections.singletonList(ManagedOccurrenceEvidenceResolution.derived(demand, B, 0L))));
            ManagedOccurrenceEvidenceDemand wrongExact = historicalDemand(input, scenario.before.expectedTargetBlueId());
            assertThrows(IllegalArgumentException.class, () -> ClosureSuccessResultAssembler.assemble(input, executionState(scenario.output), Collections.singletonList(ManagedOccurrenceEvidenceResolution.derived(wrongExact, B, -1L))));
        }
    }

    private static Scenario scenario(String afterBindingPolicyIdentity) {
        Node oldTarget = new Node().name("Old target");
        Node newTarget = new Node().name("New target");
        String oldBlueId = blueId(oldTarget);
        String newBlueId = blueId(newTarget);
        String beforePolicy = bindingPolicyIdentity();
        ManagedOccurrenceBinding before = ManagedOccurrenceBinding.derived(
                beforePolicy,
                A,
                ScopeAddress.embedded(PATH, 4L),
                B,
                oldBlueId,
                true,
                null);
        ManagedOccurrenceBinding after = ManagedOccurrenceBinding.derived(
                afterBindingPolicyIdentity,
                A,
                ScopeAddress.embedded(PATH, 5L),
                C,
                newBlueId,
                true,
                null);

        LinkedHashMap<DocumentId, Node> inputBodies = bodies(
                new Node()
                        .name("Retarget source")
                        .properties("peer", new Node().blueId(oldBlueId)),
                oldTarget,
                newTarget);
        LinkedHashMap<DocumentId, Long> initialGenerations = generations();
        SnapshotEvidence input = finalizedSnapshot(
                7L,
                inputBodies,
                Collections.singletonList(before),
                initialGenerations);

        LinkedHashMap<DocumentId, Node> outputBodies = bodies(
                new Node()
                        .name("Retarget source")
                        .properties("peer", new Node().blueId(newBlueId)),
                oldTarget,
                newTarget);
        ManagedDocumentGraph outputGraph = ManagedDocumentGraph.fromBindings(
                Arrays.asList(A, B, C),
                Collections.singletonList(after));
        Map<DocumentId, Long> outputGenerations =
                ComponentGenerationTransition.assign(
                        ManagedDocumentGraph.fromBindings(
                                Arrays.asList(A, B, C),
                                Collections.singletonList(before)),
                        componentGenerations(input.snapshot),
                        outputGraph);
        SnapshotEvidence output = finalizedSnapshot(
                8L,
                outputBodies,
                Collections.singletonList(after),
                outputGenerations);
        return new Scenario(input, output, before, after);
    }

    private static Scenario historicalScenario(
            String afterBindingPolicyIdentity) {
        return historicalScenario(afterBindingPolicyIdentity, C);
    }

    private static Scenario historicalScenario(
            String afterBindingPolicyIdentity, DocumentId selectedTarget) {
        Node oldTarget = new Node().name("Old target");
        Node historicalTarget = new Node().name("Authored target");
        Node currentTarget = new Node().name("Current target");
        String oldBlueId = blueId(oldTarget);
        String historicalBlueId = blueId(historicalTarget);
        ManagedOccurrenceBinding before = ManagedOccurrenceBinding.derived(
                bindingPolicyIdentity(),
                A,
                ScopeAddress.embedded(PATH, 4L),
                B,
                oldBlueId,
                true,
                null);
        ManagedOccurrenceBinding after = ManagedOccurrenceBinding.derived(
                afterBindingPolicyIdentity,
                A,
                ScopeAddress.embedded(PATH, 5L),
                selectedTarget,
                historicalBlueId,
                false,
                Long.valueOf(-1L));

        SnapshotEvidence input = finalizedSnapshot(
                7L,
                bodies(
                        new Node()
                                .name("Retarget source")
                                .properties("peer", new Node().blueId(oldBlueId)),
                        oldTarget,
                        currentTarget),
                Collections.singletonList(before),
                generations());

        ManagedDocumentGraph outputGraph = ManagedDocumentGraph.fromBindings(
                Arrays.asList(A, B, C), Collections.singletonList(after));
        Map<DocumentId, Long> outputGenerations =
                ComponentGenerationTransition.assign(
                        ManagedDocumentGraph.fromBindings(
                                Arrays.asList(A, B, C),
                                Collections.singletonList(before)),
                        componentGenerations(input.snapshot),
                        outputGraph);
        SnapshotEvidence output = finalizedSnapshot(
                8L,
                bodies(
                        new Node()
                                .name("Retarget source")
                                .properties(
                                        "peer",
                                        new Node().blueId(historicalBlueId)),
                        oldTarget,
                        currentTarget),
                Collections.singletonList(after),
                outputGenerations);
        return new Scenario(input, output, before, after);
    }

    private static ManagedOccurrenceEvidenceDemand historicalDemand(
            ClosureInvocationInput input,
            String suppliedBlueId) {
        return ManagedOccurrenceEvidenceDemand.derived(
                input.cause().causeIdentity(),
                input.snapshot().closureIdentity(),
                input.snapshot().graphGeneration(),
                A,
                PATH,
                blueId(new Node().name("process-embedded declaration")),
                suppliedBlueId,
                0L);
    }

    private static SnapshotEvidence finalizedSnapshot(
            long graphGeneration,
            Map<DocumentId, Node> bodies,
            List<ManagedOccurrenceBinding> bindings,
            Map<DocumentId, Long> componentGenerations) {
        List<DocumentId> documentIds = Arrays.asList(A, B, C);
        ManagedDocumentGraph graph = ManagedDocumentGraph.fromBindings(
                documentIds, bindings);
        ComponentFinalizationResult finalized =
                new ComponentFinalizationKernel().finalizeComponents(
                        new ComponentFinalizationInput(
                                graph,
                                componentGenerations,
                                bodies,
                                bindings));
        ArrayList<ManagedDocumentSnapshot> documents =
                new ArrayList<ManagedDocumentSnapshot>();
        for (DocumentId documentId : documentIds) {
            FinalizedDocumentEvidence exact = finalized.document(documentId);
            documents.add(new ManagedDocumentSnapshot(
                    documentId,
                    exact.blueId(),
                    exact.document(),
                    false,
                    false,
                    false,
                    0L,
                    exact.componentGeneration()));
        }
        ArrayList<ComponentSnapshot> components =
                new ArrayList<ComponentSnapshot>();
        for (FinalizedComponentEvidence component : finalized.components()) {
            components.add(component.component());
        }
        AffectedClosureSnapshot snapshot = ClosureEvidenceFactory
                .affectedClosure(
                        graphGeneration,
                        documents,
                        finalized.finalizedGraph().bindings(),
                        components,
                        Collections.<DocumentId>emptyList());
        return new SnapshotEvidence(snapshot, finalized);
    }

    private static ClosureInvocationInput invocation(
            AffectedClosureSnapshot snapshot,
            ClosureEnvironment environment) {
        Node event = new Node().properties(
                "kind", new Node().value("retarget"));
        String eventBlueId = blueId(event);
        ExternalEventCause cause = ClosureEvidenceFactory.externalCause(
                event,
                eventBlueId,
                ExternalOrderKey.of(Collections.<Object>singletonList(
                        BigInteger.ONE)),
                environment.externalOrderPolicyIdentity());
        ExecutionPolicy policy = ClosureEvidenceFactory.executionPolicy(
                100_000L,
                Collections.<DocumentId, Long>emptyMap(),
                "different-lineage-retarget-test");
        return ClosureEvidenceFactory.processClosure(
                snapshot,
                cause,
                Collections.<DirectLogicalDelivery>emptyList(),
                policy,
                environment);
    }

    private static ClosureExecutionState executionState(
            SnapshotEvidence output) {
        return new ClosureExecutionState(
                output.snapshot,
                output.finalization,
                Collections.<PublicEventOccurrence>emptyList(),
                Collections.<GasTraceEntry>emptyList(),
                Collections
                        .<DocumentId, List<ManagedRootChannelOccurrence>>
                                emptyMap(),
                Collections
                        .<DocumentId, List<ManagedRootChannelOccurrence>>
                                emptyMap(),
                Collections
                        .<ManagedCheckpointSettlementBatch.Mutation>
                                emptyList(),
                Collections.singleton(A));
    }

    private static ClosureEnvironment environment(DocumentProcessor owner) {
        return ClosureEvidenceFactory.environment(
                owner,
                hash('a'),
                hash('b'),
                "retarget-document-lineage-v1",
                "retarget-binding-lineage-v1",
                "retarget-provider-domain-v1",
                "retarget-external-order-v1",
                "retarget-portable-limits-v1",
                GasSchedule.contracts10().portableLimits());
    }

    private static String bindingPolicyIdentity() {
        return ClosureIdentityService.INSTANCE.labeledIdentity(
                ClosureIdentityService.Constructor.MANAGED_BINDING_POLICY,
                "retarget-binding-lineage-v1");
    }

    private static LinkedHashMap<DocumentId, Node> bodies(
            Node source,
            Node oldTarget,
            Node newTarget) {
        LinkedHashMap<DocumentId, Node> result =
                new LinkedHashMap<DocumentId, Node>();
        result.put(A, source);
        result.put(B, oldTarget);
        result.put(C, newTarget);
        return result;
    }

    private static LinkedHashMap<DocumentId, Long> generations() {
        LinkedHashMap<DocumentId, Long> result =
                new LinkedHashMap<DocumentId, Long>();
        result.put(A, Long.valueOf(1L));
        result.put(B, Long.valueOf(1L));
        result.put(C, Long.valueOf(1L));
        return result;
    }

    private static Map<DocumentId, Long> componentGenerations(
            AffectedClosureSnapshot snapshot) {
        LinkedHashMap<DocumentId, Long> result =
                new LinkedHashMap<DocumentId, Long>();
        for (ManagedDocumentSnapshot document : snapshot.managedDocuments()) {
            result.put(document.documentId(), Long.valueOf(
                    document.componentGeneration()));
        }
        return result;
    }

    private static String blueId(Node node) {
        return DirectBlueIdCalculator.calculateBlueId(node);
    }

    private static String hash(char value) {
        StringBuilder result = new StringBuilder("sha256:");
        for (int index = 0; index < 64; index++) {
            result.append(value);
        }
        return result.toString();
    }

    private static final class SnapshotEvidence {
        private final AffectedClosureSnapshot snapshot;
        private final ComponentFinalizationResult finalization;

        private SnapshotEvidence(
                AffectedClosureSnapshot snapshot,
                ComponentFinalizationResult finalization) {
            this.snapshot = snapshot;
            this.finalization = finalization;
        }
    }

    private static final class Scenario {
        private final SnapshotEvidence input;
        private final SnapshotEvidence output;
        private final ManagedOccurrenceBinding before;
        private final ManagedOccurrenceBinding after;

        private Scenario(
                SnapshotEvidence input,
                SnapshotEvidence output,
                ManagedOccurrenceBinding before,
                ManagedOccurrenceBinding after) {
            this.input = input;
            this.output = output;
            this.before = before;
            this.after = after;
        }
    }
}
