package blue.language.processor.closure;

import blue.language.model.Node;
import blue.language.processor.*;
import blue.language.processor.model.ChannelContract;
import blue.language.processor.model.HandlerContract;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.registry.RuntimeBlueIds;
import org.junit.jupiter.api.Test;
import java.util.*;
import static blue.language.processor.closure.CompositionCampaignFixture.*;
import static org.junit.jupiter.api.Assertions.*;

/** Detached paths may select a new lineage without rewriting input evidence. */
final class RootedInactiveRetargetTest {
    private static final DocumentId P = new DocumentId("P");
    private static final DocumentId B = new DocumentId("B");
    private static final DocumentId C = new DocumentId("C");
    private static final Node CHANNEL = new Node().name("Retarget test channel");
    private static final Node HANDLER = new Node().name("Retarget test handler");

    @Test void knownForeignResolutionActivatesTheReservedGeneration() {
        run(true, false, false);
        run(true, true, false);
    }

    @Test void sameBytesMayStillSelectTheReservedLineagesHistoricalEpoch() {
        run(false, false, false);
        run(false, true, false);
    }

    @Test void savedForeignSelectionCatchesUpWithoutReplacingAnAlreadyFrozenNewerSource() {
        run(true, false, true);
        run(true, true, true);
    }

    @Test void savedAliasStillSelectsTheReservedLineageAlongsideANewerForeignSource() {
        run(false, false, true);
        run(false, true, true);
    }

    @Test void committedDetachThenCurrentForeignAttachActivatesWithoutAZeroLengthHistoryStep() {
        run(true, false, false, true);
        run(true, true, false, true);
    }

    @Test void committedDetachThenHistoricalForeignAttachUsesOnlyTheNewLineagesHistory() {
        run(true, false, true, true);
        run(true, true, true, true);
    }

    @Test void removingBAndAttachingCInTheSameInvocationCannotBorrowTheRetiredReservation() {
        run(true, false, false, false, true);
        run(true, true, false, false, true);
    }

    private static void run(boolean foreignSelection, boolean reference, boolean newerForeign) {
        run(foreignSelection, reference, newerForeign, false);
    }

    private static void run(boolean foreignSelection, boolean reference, boolean newerForeign, boolean detachFirst) {
        run(foreignSelection, reference, newerForeign, detachFirst, false);
    }

    private static void run(boolean foreignSelection, boolean reference, boolean newerForeign,
            boolean detachFirst, boolean sameInvocation) {
        Map<String, Node> exactValues = new LinkedHashMap<>();
        try (DocumentProcessor owner = DocumentProcessor.builder()
                .registerContractProcessor(id(CHANNEL), CHANNEL, new RetargetChannelProcessor())
                .registerContractProcessor(id(HANDLER), HANDLER, new HandlerProcessor<RetargetHandler>() {
                    public Class<RetargetHandler> contractType() { return RetargetHandler.class; }
                    public void execute(RetargetHandler handler, ProcessorExecutionContext context) {
                        if ("detach-and-replace".equals(context.occurrenceEvent().getName())) {
                            context.applyPatch(JsonPatch.remove("/children/slot"));
                        }
                        context.applyPatch("detach".equals(context.occurrenceEvent().getName())
                                ? JsonPatch.remove("/children/slot")
                                : JsonPatch.add("/children/slot", context.documentAt("/replacement")));
                    }
                }).nodeProvider(id -> exactValues.containsKey(id)
                        ? Collections.singletonList(exactValues.get(id).clone()) : Collections.emptyList()).build()) {
            ClosureEnvironment environment = ClosureEvidenceFactory.environment(owner, hash('a'),
                    RootedProcessingContext.CONTRACTS_SPECIFICATION_IDENTITY, "retarget-lineage", "retarget-binding",
                    "retarget-provider", "retarget-order", "retarget-limits", GasSchedule.contracts10().portableLimits());
            Node saved = initialized(new Node().name("shared initial state").properties("count", new Node().value(0L)));
            Node current = saved.clone().properties("count", new Node().value(1L));
            Node foreign = newerForeign ? saved.clone().properties("count", new Node().value(2L)) : saved;
            exactValues.put(id(saved), saved);
            exactValues.put(id(current), current);
            exactValues.put(id(foreign), foreign);
            Node children = new Node().properties(Collections.emptyMap());
            if (detachFirst || sameInvocation) children.properties("slot", current.clone());
            if (newerForeign) children.properties("other", foreign.clone());
            Node parent = initialized(new Node().name("parent")
                    .properties("children", children)
                    .properties("replacement", reference ? new Node().blueId(id(saved)) : saved.clone())
                    .contracts(new Node().properties("source", typed(id(CHANNEL)))
                            .properties("retarget", typed(id(HANDLER)).properties("channel", new Node().value("source")))
                            .properties("embedded", process("collectionPaths", "/children"))));
            ManagedOccurrenceBinding reserved = ManagedOccurrenceBinding.derived(environment.managedBindingPolicyIdentity(),
                    P, ScopeAddress.embedded("/children/slot", detachFirst || sameInvocation ? 1L : 2L),
                    B, id(current), detachFirst || sameInvocation, null);
            ManagedOccurrenceBinding other = ManagedOccurrenceBinding.derived(environment.managedBindingPolicyIdentity(),
                    P, ScopeAddress.embedded("/children/other", 1L), C, id(foreign), newerForeign, null);
            Map<DocumentId, Node> bodies = new LinkedHashMap<>();
            bodies.put(P, parent); bodies.put(B, current); bodies.put(C, foreign);
            AffectedClosureSnapshot raw = snapshot(bodies, Arrays.asList(reserved, other), P);
            List<ManagedDocumentSnapshot> documents = new ArrayList<>();
            for (ManagedDocumentSnapshot doc : raw.managedDocuments()) documents.add(new ManagedDocumentSnapshot(
                    doc.documentId(), doc.blueId(), doc.document(), true, false, doc.publicRoot(),
                    doc.documentId().equals(B) || newerForeign && doc.documentId().equals(C) ? 1L : 0L,
                    doc.componentGeneration()));
            AffectedClosureSnapshot state = ClosureEvidenceFactory.affectedClosure(raw.graphGeneration(), documents,
                    raw.occurrences(), raw.components(), raw.publicRootDocumentIds());
            if (detachFirst) {
                try (BlueClosureContracts contracts = new BlueClosureContracts(owner)) {
                    ClosureAttemptResult detached = contracts.processClosure(externalInput(state, environment, "detach", 0L));
                    assertTrue(detached.isComplete());
                    ClosureProcessResult result = detached.processResult();
                    assertTrue(result.commits(), diagnostic(result));
                    assertEquals(GraphChange.Kind.REMOVE, result.graphChanges().get(0).changeKind());
                    state = outputSnapshot(result);
                    reserved = state.occurrences().stream().filter(row -> row.sourcePath().equals("/children/slot")).findFirst().get();
                    assertFalse(reserved.active());
                    assertEquals(2L, reserved.activationGeneration());
                    assertEquals(B, reserved.targetDocumentId());
                }
            }
            Node event = new Node().name(sameInvocation ? "detach-and-replace" : "install saved state");
            ExternalEventCause cause = ClosureEvidenceFactory.externalCause(event, id(event),
                    ExternalOrderKey.of(Collections.singletonList(1L)), environment.externalOrderPolicyIdentity());
            ClosureInvocationInput input = ClosureEvidenceFactory.processClosure(state, cause,
                    Collections.singletonList(new DirectLogicalDelivery(ManagedScopeKey.root(P), "source", "retarget", 0L)),
                    ClosureEvidenceFactory.executionPolicy(100_000L, Collections.emptyMap(), "retarget-gas"), environment)
                    .withRootedContext(RootedProcessingContext.derive(state, P, Collections.singletonMap(P, hash('c'))), hash('d'));
            try (BlueClosureContracts contracts = new BlueClosureContracts(owner)) {
                ClosureAttemptResult first = contracts.processClosure(input);
                assertFalse(first.isComplete(), "Bytes alone cannot choose between saved B and current C");
                assertEquals(1, first.resourceDemands().size());
                ManagedOccurrenceEvidenceDemand demand = (ManagedOccurrenceEvidenceDemand) first.resourceDemands().get(0);
                ManagedOccurrenceEvidenceResolution exact = ManagedOccurrenceEvidenceResolution.derived(
                        demand, foreignSelection ? C : B, 0L);
                if (sameInvocation) {
                    ClosureAttemptResult rejected = contracts.processClosureRetry(
                            ClosureProcessRetryInput.derived(input, Collections.singletonList(exact)));
                    assertTrue(rejected.isComplete());
                    ClosureProcessResult rollback = rejected.processResult();
                    assertEquals(ProcessorStatus.RUNTIME_FATAL, rollback.status());
                    assertEquals("Managed occurrence resolution is not an eligible historical reference replacement",
                            rollback.diagnostic().message());
                    assertFalse(rollback.commits());
                    assertTrue(rollback.rollbackToInput());
                    assertEquals(input.snapshot().closureIdentity(), rollback.outputClosureIdentity());
                    assertEquals(input.snapshot().graphGeneration(), rollback.graphGeneration());
                    assertTrue(rollback.graphChanges().isEmpty());
                    assertTrue(rollback.subscriptionDeltas().isEmpty());
                    assertTrue(rollback.checkpointWrites().isEmpty());
                    assertTrue(rollback.publicEvents().isEmpty());
                    assertTrue(rollback.managedTransitionReceipts().isEmpty());
                    assertNull(rollback.commitCompanion());
                    for (ManagedDocumentSnapshot before : input.snapshot().managedDocuments()) {
                        ResultingDocument after = rollback.resultingDocuments().stream()
                                .filter(document -> document.documentId().equals(before.documentId())).findFirst().get();
                        assertEquals(before.blueId(), after.beforeBlueId());
                        assertEquals(before.blueId(), after.afterBlueId());
                        assertEquals(before.epoch(), after.epoch());
                    }
                    ManagedOccurrenceBinding original = input.snapshot().occurrences().stream()
                            .filter(row -> row.sourcePath().equals("/children/slot")).findFirst().get();
                    assertTrue(original.active());
                    assertEquals(1L, original.activationGeneration());
                    assertEquals(B, original.targetDocumentId());
                    assertEquals(reserved.bindingIdentity(), original.bindingIdentity());
                    assertEquals(original.bindingIdentity(), rollback.occurrenceBindings().stream()
                            .filter(row -> row.sourcePath().equals("/children/slot")).findFirst().get().bindingIdentity());
                    assertEquals(id(parent), input.snapshot().managedDocument(P).blueId());
                    return;
                }
                if (foreignSelection) {
                    long foreignEpoch = newerForeign ? 1L : 0L;
                    assertThrows(IllegalArgumentException.class, () -> contracts.processClosureRetry(
                            ClosureProcessRetryInput.derived(input, Collections.singletonList(
                                    ManagedOccurrenceEvidenceResolution.derived(demand, C, foreignEpoch + 1L)))));
                    assertThrows(IllegalArgumentException.class, () -> contracts.processClosureRetry(
                            ClosureProcessRetryInput.derived(input, Collections.singletonList(
                                    ManagedOccurrenceEvidenceResolution.derived(demand, new DocumentId("absent-source"), 0L)))));
                    ManagedOccurrenceEvidenceDemand wrongValue = ManagedOccurrenceEvidenceDemand.derived(
                            demand.logicalCauseIdentity(), demand.inputClosureIdentity(), demand.inputGraphGeneration(),
                            P, demand.sourcePath(), demand.processEmbeddedDeclarationIdentity(), id(current), demand.demandOrdinal());
                    assertThrows(IllegalArgumentException.class, () -> contracts.processClosureRetry(
                            ClosureProcessRetryInput.derived(input, Collections.singletonList(
                                    ManagedOccurrenceEvidenceResolution.derived(wrongValue, C, foreignEpoch)))));
                    ManagedOccurrenceEvidenceDemand wrongPath = ManagedOccurrenceEvidenceDemand.derived(
                            demand.logicalCauseIdentity(), demand.inputClosureIdentity(), demand.inputGraphGeneration(),
                            P, "/children/absent", demand.processEmbeddedDeclarationIdentity(), id(saved), demand.demandOrdinal());
                    assertThrows(IllegalArgumentException.class, () -> contracts.processClosureRetry(
                            ClosureProcessRetryInput.derived(input, Collections.singletonList(
                                    ManagedOccurrenceEvidenceResolution.derived(wrongPath, C, 0L)))));
                    assertThrows(IllegalArgumentException.class, () -> new ManagedOccurrenceEvidenceDemand(hash('f'),
                            demand.logicalCauseIdentity(), demand.inputClosureIdentity(), demand.inputGraphGeneration(),
                            P, demand.sourcePath(), demand.processEmbeddedDeclarationIdentity(), id(saved), demand.demandOrdinal()));
                    ManagedOccurrenceEvidenceDemand wrongCause = ManagedOccurrenceEvidenceDemand.derived(
                            hash('e'), demand.inputClosureIdentity(), demand.inputGraphGeneration(),
                            P, demand.sourcePath(), demand.processEmbeddedDeclarationIdentity(), id(saved), demand.demandOrdinal());
                    assertThrows(IllegalArgumentException.class, () -> contracts.processClosureRetry(
                            ClosureProcessRetryInput.derived(input, Collections.singletonList(
                                    ManagedOccurrenceEvidenceResolution.derived(wrongCause, C, 0L)))));
                }
                ClosureAttemptResult attempt = contracts.processClosureRetry(
                        ClosureProcessRetryInput.derived(input, Collections.singletonList(exact)));
                assertTrue(attempt.isComplete());
                ClosureProcessResult result = attempt.processResult();
                ManagedOccurrenceBinding after = result.occurrenceBindings().stream()
                        .filter(row -> row.sourcePath().equals("/children/slot")).findFirst().get();
                if (foreignSelection) {
                    assertTrue(result.commits(), diagnostic(result));
                    assertEquals(C, after.targetDocumentId());
                    assertEquals(reserved.activationGeneration(), after.activationGeneration());
                    assertNotEquals(reserved.occurrenceIdentity(), after.occurrenceIdentity());
                    assertNotEquals(reserved.bindingIdentity(), after.bindingIdentity());
                    assertEquals(id(foreign), input.snapshot().managedDocument(C).blueId());
                    assertEquals(other.bindingIdentity(), result.occurrenceBindings().stream()
                            .filter(row -> row.sourcePath().equals("/children/other")).findFirst().get().bindingIdentity());
                    if (newerForeign) {
                        assertFalse(after.active());
                        assertEquals(Long.valueOf(0L), after.pendingHistoricalEpoch());
                        assertTrue(result.graphChanges().isEmpty());
                        assertEquals(input.snapshot().graphGeneration(), result.graphGeneration());
                        AffectedClosureSnapshot selected = forwardCatchUpSnapshot(result);
                        ManagedRevisionCause revision = ClosureEvidenceFactory.managedRevisionCause(
                                after.occurrenceIdentity(), C, 0L, 1L, id(saved), id(foreign), foreign, hash('e'));
                        ClosureInvocationInput catchUp = ClosureEvidenceFactory.processClosure(selected, revision,
                                Collections.emptyList(), input.executionPolicy(), environment)
                                .withRootedContext(RootedProcessingContext.derive(selected, P,
                                        Collections.singletonMap(P, hash('c'))), hash('d'));
                        ClosureAttemptResult caughtUp = contracts.processClosure(catchUp);
                        assertTrue(caughtUp.isComplete());
                        ClosureProcessResult completed = caughtUp.processResult();
                        assertTrue(completed.commits(), diagnostic(completed));
                        ManagedOccurrenceBinding active = completed.occurrenceBindings().stream()
                                .filter(row -> row.sourcePath().equals("/children/slot")).findFirst().get();
                        assertTrue(active.active());
                        assertNull(active.pendingHistoricalEpoch());
                        assertEquals(after.occurrenceIdentity(), active.occurrenceIdentity());
                        assertEquals(GraphChange.Kind.ADD, completed.graphChanges().get(0).changeKind());
                        assertEquals(selected.graphGeneration() + 1L, completed.graphGeneration());
                        assertEquals(id(foreign), outputSnapshot(completed).managedDocument(C).blueId());
                        assertEquals(1L, outputSnapshot(completed).managedDocument(C).epoch());
                    } else {
                        assertTrue(after.active(), "Current epoch zero needs no fictitious 0-to-0 history step");
                        assertNull(after.pendingHistoricalEpoch());
                        assertEquals(1, result.graphChanges().size());
                        assertEquals(GraphChange.Kind.ADD, result.graphChanges().get(0).changeKind());
                        assertEquals(input.snapshot().graphGeneration() + 1L, result.graphGeneration());
                    }
                    assertEquals(B, input.snapshot().occurrences().stream()
                            .filter(row -> row.sourcePath().equals("/children/slot")).findFirst().get().targetDocumentId());
                    assertEquals(id(current), outputSnapshot(result).managedDocument(B).blueId());
                } else {
                    assertTrue(result.commits(), diagnostic(result));
                    assertEquals(B, after.targetDocumentId());
                    assertEquals(reserved.occurrenceIdentity(), after.occurrenceIdentity());
                    assertEquals(2L, after.activationGeneration());
                    assertEquals(Long.valueOf(0L), after.pendingHistoricalEpoch());
                    assertFalse(after.active());
                }
                assertTrue(result.totalGas() > 0L);
            }
        }
    }

    private static ClosureInvocationInput externalInput(AffectedClosureSnapshot state,
            ClosureEnvironment environment, String name, long order) {
        Node event = new Node().name(name);
        ExternalEventCause cause = ClosureEvidenceFactory.externalCause(event, id(event),
                ExternalOrderKey.of(Collections.singletonList(order)), environment.externalOrderPolicyIdentity());
        return ClosureEvidenceFactory.processClosure(state, cause,
                Collections.singletonList(new DirectLogicalDelivery(ManagedScopeKey.root(P), "source", "retarget", 0L)),
                ClosureEvidenceFactory.executionPolicy(100_000L, Collections.emptyMap(), "retarget-gas"), environment)
                .withRootedContext(RootedProcessingContext.derive(state, P, Collections.singletonMap(P, hash('c'))), hash('d'));
    }

    private static AffectedClosureSnapshot outputSnapshot(ClosureProcessResult result) {
        List<ManagedDocumentSnapshot> documents = new ArrayList<>();
        List<DocumentId> roots = new ArrayList<>();
        for (ResultingDocument document : result.resultingDocuments()) {
            documents.add(document.asSnapshot());
            if (document.publicRoot()) roots.add(document.documentId());
        }
        return ClosureEvidenceFactory.affectedClosure(result.graphGeneration(), documents,
                result.occurrenceBindings(), result.resultingComponents(), roots);
    }

    private static AffectedClosureSnapshot forwardCatchUpSnapshot(ClosureProcessResult result) {
        AffectedClosureSnapshot complete = outputSnapshot(result);
        List<ManagedDocumentSnapshot> documents = new ArrayList<>();
        List<ComponentSnapshot> components = new ArrayList<>();
        // The next rooted capture contains P and its selected C occurrences.
        // B was required for the preceding input reservation, but is now detached.
        for (ManagedDocumentSnapshot document : complete.managedDocuments()) {
            if (!document.documentId().equals(B)) documents.add(document);
        }
        for (ComponentSnapshot component : complete.components()) {
            if (component.orderedMemberDocumentIds().contains(B)) {
                assertEquals(Collections.singletonList(B), component.orderedMemberDocumentIds());
            } else components.add(component);
        }
        assertTrue(complete.occurrences().stream().noneMatch(row -> row.sourceDocumentId().equals(B)
                || row.targetDocumentId().equals(B)));
        return ClosureEvidenceFactory.affectedClosure(complete.graphGeneration(), documents,
                complete.occurrences(), components, complete.publicRootDocumentIds());
    }

    private static Node initialized(Node node) {
        Node copy = node.clone();
        if (copy.getContracts() == null) copy.contracts(new Node());
        copy.getContracts().properties("initialized", typed(RuntimeBlueIds.PROCESSING_INITIALIZED_MARKER)
                .properties("document", new Node().blueId(id(node))));
        return copy;
    }

    public static final class RetargetHandler extends HandlerContract { }
    public static final class RetargetChannel extends ChannelContract { }
    private static final class RetargetChannelProcessor implements ChannelProcessor<RetargetChannel> {
        public Class<RetargetChannel> contractType() { return RetargetChannel.class; }
        public ExternalChannelSubscriptionFunctions<RetargetChannel> externalSubscriptionFunctions() {
            return new ExternalChannelSubscriptionFunctions<RetargetChannel>() {
                public List<String> channelKeys(RetargetChannel channel) { return Collections.singletonList("retarget"); }
                public boolean preselects(RetargetChannel channel, Node event, ExternalChannelFunctionContext context) { return true; }
                public boolean accepts(RetargetChannel channel, Node event, ExternalChannelFunctionContext context) { return true; }
                public String logicalDeliveryKey(RetargetChannel channel, Node event, Node payload,
                        ExternalChannelFunctionContext context) { return "retarget"; }
                public String checkpointDomainDiscriminator(RetargetChannel channel) { return "retarget-test"; }
            };
        }
    }
}
