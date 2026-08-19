package blue.language.processor.closure;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.NodePathEditor;
import blue.language.processor.ProcessorStatus;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Cross-field tests for complete affected-closure result evidence. */
final class ClosureResultEvidenceTest {

    private static final ClosureIdentityService IDENTITIES =
            ClosureIdentityService.INSTANCE;
    private static final DocumentId ROOT = new DocumentId("root");

    @Test
    void shouldCreateAtomicSuccessWithFullCompanionShape() {
        Fixture fixture = fixture();
        ClosureCommitCompanion companion = companion(fixture);
        ClosureProcessResult result = result(
                fixture,
                ProcessorStatus.SUCCESS,
                Collections.<GasTraceEntry>emptyList(),
                0L,
                null,
                companion);

        assertTrue(result.atomic());
        assertTrue(result.commits());
        assertFalse(result.rollbackToInput());
        assertEquals(28, companionSerializedShape(companion).size());
        assertEquals(fixture.snapshot.closureIdentity(),
                companion.outputClosureIdentity());
        assertEquals(1, companion.resultingDocuments().size());
        assertEquals(fixture.blueId,
                companion.resultingDocuments().get(0).beforeBlueId());
        assertEquals(fixture.blueId,
                companion.resultingDocuments().get(0).afterBlueId());

        ClosureAttemptResult attempt = ClosureAttemptResult.complete(result);
        assertTrue(attempt.isComplete());
        assertEquals(Long.valueOf(0L), attempt.totalGas());
    }

    @Test
    void shouldRequireLiteralRollbackAndCompleteRejectedGasEvidence() {
        Fixture fixture = fixture();
        GasTraceEntry admitted = new GasTraceEntry(
                0L,
                GasTraceEntry.Namespace.PROCESSOR,
                "admitted",
                2L,
                3L,
                6L,
                ROOT,
                "/",
                Long.valueOf(0L),
                Long.valueOf(1L),
                null,
                null,
                null,
                "before rejection");
        RejectedCharge rejected = RejectedCharge.identified(
                GasTraceEntry.Namespace.PROCESSOR,
                "next",
                1L,
                5L,
                RejectedCharge.ApplicableCap.shared(),
                0L,
                RejectedCharge.Owner.invocation());
        ClosureProcessResult failed = result(
                fixture,
                ProcessorStatus.GAS_LIMIT_EXCEEDED,
                Collections.singletonList(admitted),
                6L,
                rejected,
                null);

        assertTrue(failed.rollbackToInput());
        assertFalse(failed.commits());
        assertEquals(fixture.snapshot.closureIdentity(),
                failed.outputClosureIdentity());
        assertEquals(RejectionOwnerKind.INVOCATION,
                rejectionKind(failed.rejectedCharge()));

        GraphChange change = new GraphChange(
                0L,
                GraphChange.Kind.ADD,
                ROOT,
                "/child",
                null,
                new GraphChange.Side(
                        1L, hash('1'), hash('2'), ROOT, fixture.blueId));
        assertThrows(IllegalArgumentException.class,
                () -> failedResultWithGraphChange(fixture, change));
        assertThrows(IllegalArgumentException.class,
                () -> result(
                        fixture,
                        ProcessorStatus.GAS_LIMIT_EXCEEDED,
                        Collections.<GasTraceEntry>emptyList(),
                        0L,
                        null,
                        null));
    }

    @Test
    void shouldCloseTypedSequencesAndDefendBlueNodes() {
        Fixture fixture = fixture();
        ResultingDocument resulting = resultingDocument(fixture);
        Node read = resulting.document();
        read.name("mutated");
        assertEquals("root-before", resulting.document().getName());
        assertThrows(IllegalArgumentException.class,
                () -> new ResultingDocument(
                        ROOT,
                        fixture.blueId,
                        fixture.blueId,
                        fixture.document,
                        true,
                        false,
                        true,
                        0L,
                        1L,
                        fixture.component.componentIdentity(),
                        fixture.component.componentStateIdentity(),
                        Long.valueOf(0L)));

        LinkedHashMap<DocumentId, String> members =
                new LinkedHashMap<DocumentId, String>();
        members.put(ROOT, "master#0");
        TentativeFinalization finalization = new TentativeFinalization(
                0L,
                TentativeFinalization.Boundary.checkpointSettlement(),
                "master",
                members,
                1L);
        assertNull(finalization.boundary().afterWorkOrdinal());
        assertThrows(IllegalArgumentException.class,
                () -> new TentativeFinalization(
                        0L,
                        TentativeFinalization.Boundary.work(0L),
                        "master",
                        Collections.<DocumentId, String>emptyMap(),
                        1L));

        GraphChange.Side side = new GraphChange.Side(
                1L, hash('1'), hash('2'), ROOT, fixture.blueId);
        assertThrows(IllegalArgumentException.class,
                () -> new GraphChange(
                        0L,
                        GraphChange.Kind.REBIND,
                        ROOT,
                        "/child",
                        side,
                        side));
    }

    @Test
    void shouldValidateCheckpointSubscriptionAndEventEvidence() {
        Fixture fixture = fixture();
        CheckpointDomainValue domain = new CheckpointDomainValue(
                fixture.blueId,
                Collections.singletonList(fixture.blueId),
                Collections.<String>emptyList(),
                null);
        CheckpointWrite.State state = new CheckpointWrite.State(
                domain.blueId(), domain, fixture.blueId);
        String rootScopeIdentity = IDENTITIES.managedScopeKeyIdentity(
                ManagedScopeKey.root(ROOT));
        assertThrows(IllegalArgumentException.class,
                () -> new CheckpointWrite(
                        0L,
                        ManagedScopeKey.root(ROOT),
                        rootScopeIdentity,
                        "source",
                        state,
                        state));

        ChannelOccurrence occurrence = ChannelOccurrence.root(
                ROOT, "source", fixture.blueId, fixture.blueId);
        SubscriptionState subscription = SubscriptionState.identified(
                occurrence, fixture.blueId, 0L, 1L);
        assertThrows(IllegalArgumentException.class,
                () -> new SubscriptionDelta(
                        0L,
                        SubscriptionDelta.Operation.REPLACE,
                        rootScopeIdentity,
                        occurrence.channelOccurrenceIdentity(),
                        subscription,
                        subscription));

        Node event = new Node().name("public-event");
        String eventBlueId = DirectBlueIdCalculator.calculateBlueId(event);
        String eventOccurrenceIdentity = IDENTITIES.eventOccurrenceIdentity(
                hash('a'), 3L, eventBlueId);
        PublicEventOccurrence publicEvent = new PublicEventOccurrence(
                0L,
                3L,
                ROOT,
                eventOccurrenceIdentity,
                eventBlueId,
                event);
        event.name("caller mutation");
        assertEquals("public-event", publicEvent.event().getName());
        publicEvent.event().name("return mutation");
        assertEquals("public-event", publicEvent.event().getName());
    }

    @Test
    void shouldRejectUnsafeOrdinalsAndMalformedGasProducts() {
        assertThrows(IllegalArgumentException.class,
                () -> TentativeFinalization.Boundary.work(-1L));
        assertThrows(IllegalArgumentException.class,
                () -> new GasTraceEntry(
                        0L,
                        GasTraceEntry.Namespace.SEMANTIC,
                        "counter",
                        2L,
                        3L,
                        5L,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null));
        assertThrows(IllegalArgumentException.class,
                () -> RejectedCharge.ApplicableCap.local(
                        new DocumentId("bad\u0000id")));
        assertThrows(IllegalArgumentException.class,
                () -> new GasTraceEntry(
                        0L,
                        GasTraceEntry.Namespace.SEMANTIC,
                        "counter",
                        0L,
                        3L,
                        0L,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null));
        assertThrows(IllegalArgumentException.class,
                () -> RejectedCharge.identified(
                        GasTraceEntry.Namespace.PROCESSOR,
                        "fits",
                        1L,
                        5L,
                        RejectedCharge.ApplicableCap.shared(),
                        5L,
                        RejectedCharge.Owner.invocation()));
    }

    @Test
    void shouldRejectClaimedDocumentAndOccurrenceIdentities() {
        Fixture fixture = fixture();
        ManagedDocumentSnapshot wrongBody = new ManagedDocumentSnapshot(
                ROOT,
                fixture.blueId,
                new Node().name("unhashed mutation"),
                false,
                false,
                true,
                0L,
                1L);
        AffectedClosureSnapshot bodySnapshot = snapshot(
                0L,
                Collections.singletonList(wrongBody),
                Collections.<ManagedOccurrenceBinding>emptyList(),
                Collections.singletonList(fixture.component));
        assertThrows(IllegalArgumentException.class,
                () -> ClosureEvidenceVerifier.verifySnapshot(bodySnapshot));

        ManagedDocumentSnapshot wrongMarker = new ManagedDocumentSnapshot(
                ROOT,
                fixture.blueId,
                fixture.document,
                true,
                false,
                true,
                0L,
                1L);
        AffectedClosureSnapshot markerSnapshot = snapshot(
                0L,
                Collections.singletonList(wrongMarker),
                Collections.<ManagedOccurrenceBinding>emptyList(),
                Collections.singletonList(fixture.component));
        assertThrows(IllegalArgumentException.class,
                () -> ClosureEvidenceVerifier.verifySnapshot(markerSnapshot));

        ManagedOccurrenceBinding claimed = new ManagedOccurrenceBinding(
                hash('b'),
                hash('c'),
                hash('d'),
                ROOT,
                ScopeAddress.embedded("/child", 1L),
                ROOT,
                fixture.blueId,
                false,
                null);
        AffectedClosureSnapshot occurrenceSnapshot = snapshot(
                0L,
                fixture.snapshot.managedDocuments(),
                Collections.singletonList(claimed),
                Collections.singletonList(fixture.component));
        assertThrows(IllegalArgumentException.class,
                () -> ClosureEvidenceVerifier.verifySnapshot(
                        occurrenceSnapshot));
    }

    @Test
    void shouldRequireCompleteTentativeMemberSuffixRange() {
        LinkedHashMap<DocumentId, String> members =
                new LinkedHashMap<DocumentId, String>();
        members.put(ROOT, "master#0");
        members.put(new DocumentId("z"), "master#2");

        assertThrows(IllegalArgumentException.class,
                () -> new TentativeFinalization(
                        0L,
                        TentativeFinalization.Boundary
                                .checkpointSettlement(),
                        "master",
                        members,
                        1L));
    }

    @Test
    void shouldVerifyCyclicBodiesThroughLanguageProof() {
        DocumentId a = new DocumentId("a");
        DocumentId b = new DocumentId("b");
        String policy = hash('e');
        ManagedOccurrenceBinding aToB = binding(
                a, "/b", b, "old-b", policy);
        ManagedOccurrenceBinding bToA = binding(
                b, "/a", a, "old-a", policy);
        List<ManagedOccurrenceBinding> inputRows = Arrays.asList(aToB, bToA);
        ManagedDocumentGraph inputGraph = ManagedDocumentGraph.fromBindings(
                Arrays.asList(a, b), inputRows);
        LinkedHashMap<DocumentId, Long> generations =
                new LinkedHashMap<DocumentId, Long>();
        generations.put(a, Long.valueOf(1L));
        generations.put(b, Long.valueOf(1L));
        LinkedHashMap<DocumentId, Node> bodies =
                new LinkedHashMap<DocumentId, Node>();
        bodies.put(a, new Node().properties(
                "b", new Node().blueId("old-b")));
        bodies.put(b, new Node().properties(
                "a", new Node().blueId("old-a")));
        ComponentFinalizationResult finalized =
                new ComponentFinalizationKernel().finalizeComponents(
                        new ComponentFinalizationInput(
                                inputGraph,
                                generations,
                                bodies,
                                inputRows));
        AffectedClosureSnapshot exact = snapshot(finalized);

        assertDoesNotThrow(
                () -> ClosureEvidenceVerifier.verifySnapshot(exact));

        ArrayList<ManagedDocumentSnapshot> materialized =
                new ArrayList<ManagedDocumentSnapshot>();
        Node exactB = exact.managedDocument(b).document();
        for (ManagedDocumentSnapshot document : exact.managedDocuments()) {
            Node body = document.document();
            if (document.documentId().equals(a)) {
                NodePathEditor.put(body, "/b", exactB);
            }
            materialized.add(new ManagedDocumentSnapshot(
                    document.documentId(),
                    document.blueId(),
                    body,
                    document.initialized(),
                    document.terminated(),
                    document.publicRoot(),
                    document.epoch(),
                    document.componentGeneration()));
        }
        AffectedClosureSnapshot materializedSnapshot = snapshot(
                exact.graphGeneration(),
                materialized,
                exact.occurrences(),
                exact.components());
        assertDoesNotThrow(
                () -> ClosureEvidenceVerifier.verifySnapshot(
                        materializedSnapshot));

        List<ManagedDocumentSnapshot> tampered =
                new ArrayList<ManagedDocumentSnapshot>();
        for (ManagedDocumentSnapshot document : exact.managedDocuments()) {
            Node body = document.document();
            if (document.documentId().equals(a)) {
                body.name("not in proof");
            }
            tampered.add(new ManagedDocumentSnapshot(
                    document.documentId(),
                    document.blueId(),
                    body,
                    document.initialized(),
                    document.terminated(),
                    document.publicRoot(),
                    document.epoch(),
                    document.componentGeneration()));
        }
        AffectedClosureSnapshot falseClaim = snapshot(
                exact.graphGeneration(),
                tampered,
                exact.occurrences(),
                exact.components());
        assertThrows(IllegalArgumentException.class,
                () -> ClosureEvidenceVerifier.verifySnapshot(falseClaim));
    }

    @Test
    void shouldEnforceTransitionGenerationOrderingAndTargetMembership() {
        Fixture fixture = fixture();
        AffectedClosureSnapshot wrongGeneration = snapshot(
                1L,
                fixture.snapshot.managedDocuments(),
                fixture.snapshot.occurrences(),
                fixture.snapshot.components());
        assertThrows(IllegalArgumentException.class,
                () -> verifyTransition(fixture, wrongGeneration,
                        Collections.<CheckpointWrite>emptyList(),
                        Collections.<PublicEventOccurrence>emptyList(),
                        null));

        ManagedDocumentSnapshot unchangedHeadWithAdvancedEpoch =
                new ManagedDocumentSnapshot(
                        ROOT,
                        fixture.blueId,
                        fixture.document,
                        false,
                        false,
                        true,
                        1L,
                        1L);
        AffectedClosureSnapshot wrongEpoch = snapshot(
                0L,
                Collections.singletonList(
                        unchangedHeadWithAdvancedEpoch),
                Collections.<ManagedOccurrenceBinding>emptyList(),
                Collections.singletonList(fixture.component));
        assertThrows(IllegalArgumentException.class,
                () -> verifyTransition(fixture, wrongEpoch,
                        Collections.<CheckpointWrite>emptyList(),
                        Collections.<PublicEventOccurrence>emptyList(),
                        null));

        DocumentId outsider = new DocumentId("outside");
        CheckpointDomainValue domain = new CheckpointDomainValue(
                fixture.blueId,
                Collections.singletonList(fixture.blueId),
                Collections.<String>emptyList(),
                null);
        CheckpointWrite.State state = new CheckpointWrite.State(
                domain.blueId(), domain, fixture.blueId);
        ManagedScopeKey outsideRoot = ManagedScopeKey.root(outsider);
        CheckpointWrite outsideWrite = new CheckpointWrite(
                0L,
                outsideRoot,
                IDENTITIES.managedScopeKeyIdentity(outsideRoot),
                "source",
                null,
                state);
        assertThrows(IllegalArgumentException.class,
                () -> verifyTransition(fixture, fixture.snapshot,
                        Collections.singletonList(outsideWrite),
                        Collections.<PublicEventOccurrence>emptyList(),
                        null));

        Node first = new Node().name("first");
        Node second = new Node().name("second");
        String firstBlueId = DirectBlueIdCalculator.calculateBlueId(first);
        String secondBlueId = DirectBlueIdCalculator.calculateBlueId(second);
        List<PublicEventOccurrence> reversed = Arrays.asList(
                new PublicEventOccurrence(
                        0L,
                        2L,
                        ROOT,
                        IDENTITIES.eventOccurrenceIdentity(
                                fixture.invocationIdentity,
                                2L,
                                firstBlueId),
                        firstBlueId,
                        first),
                new PublicEventOccurrence(
                        1L,
                        1L,
                        ROOT,
                        IDENTITIES.eventOccurrenceIdentity(
                                fixture.invocationIdentity,
                                1L,
                                secondBlueId),
                        secondBlueId,
                        second));
        assertThrows(IllegalArgumentException.class,
                () -> verifyTransition(fixture, fixture.snapshot,
                        Collections.<CheckpointWrite>emptyList(),
                        reversed,
                        null));

        ComponentFinalizationResult reusable =
                new ComponentFinalizationKernel().finalizeComponents(
                        new ComponentFinalizationInput(
                                ManagedDocumentGraph.fromBindings(
                                        Collections.singletonList(ROOT),
                                        Collections
                                                .<ManagedOccurrenceBinding>emptyList()),
                                Collections.singletonMap(
                                        ROOT, Long.valueOf(1L)),
                                Collections.singletonMap(
                                        ROOT, fixture.document),
                                Collections
                                        .<ManagedOccurrenceBinding>emptyList()));
        assertDoesNotThrow(
                () -> verifyTransition(fixture, fixture.snapshot,
                        Collections.<CheckpointWrite>emptyList(),
                        Collections.<PublicEventOccurrence>emptyList(),
                        reusable));
    }

    private static ManagedOccurrenceBinding binding(
            DocumentId source,
            String path,
            DocumentId target,
            String targetBlueId,
            String policy) {
        ScopeAddress address = ScopeAddress.embedded(path, 1L);
        String occurrenceIdentity = IDENTITIES.managedOccurrenceIdentity(
                source, address, target, policy);
        String bindingIdentity =
                IDENTITIES.managedOccurrenceBindingIdentity(
                        source, address, target, targetBlueId, policy);
        return new ManagedOccurrenceBinding(
                occurrenceIdentity,
                bindingIdentity,
                policy,
                source,
                address,
                target,
                targetBlueId,
                true,
                null);
    }

    private static AffectedClosureSnapshot snapshot(
            ComponentFinalizationResult finalized) {
        ArrayList<ManagedDocumentSnapshot> documents =
                new ArrayList<ManagedDocumentSnapshot>();
        for (DocumentId documentId
                : finalized.finalizedGraph().documentIds()) {
            FinalizedDocumentEvidence document = finalized.document(documentId);
            documents.add(new ManagedDocumentSnapshot(
                    documentId,
                    document.blueId(),
                    document.document(),
                    false,
                    false,
                    false,
                    0L,
                    document.componentGeneration()));
        }
        ArrayList<ComponentSnapshot> components =
                new ArrayList<ComponentSnapshot>();
        for (FinalizedComponentEvidence component : finalized.components()) {
            components.add(component.component());
        }
        return snapshot(
                0L,
                documents,
                finalized.finalizedGraph().bindings(),
                components);
    }

    private static AffectedClosureSnapshot snapshot(
            long graphGeneration,
            List<ManagedDocumentSnapshot> documents,
            List<ManagedOccurrenceBinding> occurrences,
            List<ComponentSnapshot> components) {
        String bindingSetIdentity = IDENTITIES.occurrenceBindingSetIdentity(
                occurrences);
        ArrayList<DocumentId> publicRoots = new ArrayList<DocumentId>();
        for (ManagedDocumentSnapshot document : documents) {
            if (document.publicRoot()) {
                publicRoots.add(document.documentId());
            }
        }
        AffectedClosureSnapshot provisional = new AffectedClosureSnapshot(
                hash('f'),
                graphGeneration,
                documents,
                occurrences,
                bindingSetIdentity,
                components,
                publicRoots);
        return new AffectedClosureSnapshot(
                IDENTITIES.affectedClosureIdentity(provisional),
                graphGeneration,
                documents,
                occurrences,
                bindingSetIdentity,
                components,
                publicRoots);
    }

    private static void verifyTransition(
            Fixture fixture,
            AffectedClosureSnapshot output,
            List<CheckpointWrite> writes,
            List<PublicEventOccurrence> events,
            ComponentFinalizationResult reusable) {
        ClosureEvidenceVerifier.verifyTransition(
                fixture.snapshot,
                output,
                Collections.singletonList(resultingDocument(fixture)),
                Collections.<GraphChange>emptyList(),
                Collections.<SubscriptionDelta>emptyList(),
                writes,
                events,
                Collections.<GasTraceEntry>emptyList(),
                reusable);
    }

    private static ClosureProcessResult result(
            Fixture fixture,
            ProcessorStatus status,
            List<GasTraceEntry> gasTrace,
            long totalGas,
            RejectedCharge rejectedCharge,
            ClosureCommitCompanion companion) {
        return new ClosureProcessResult(
                fixture.snapshot,
                status,
                fixture.invocationIdentity,
                fixture.snapshot.closureIdentity(),
                fixture.snapshot.graphGeneration(),
                Collections.singletonList(resultingDocument(fixture)),
                Collections.singletonList(fixture.component),
                Collections.<ManagedOccurrenceBinding>emptyList(),
                fixture.bindingSetIdentity,
                Collections.<GraphChange>emptyList(),
                emptyIdentity(ClosureIdentityService.Constructor.GRAPH_CHANGES),
                Collections.<SubscriptionDelta>emptyList(),
                emptyIdentity(
                        ClosureIdentityService.Constructor.SUBSCRIPTION_DELTAS),
                Collections.<CheckpointWrite>emptyList(),
                emptyIdentity(
                        ClosureIdentityService.Constructor.CHECKPOINT_WRITES),
                Collections.<PublicEventOccurrence>emptyList(),
                emptyIdentity(ClosureIdentityService.Constructor.PUBLIC_EVENTS),
                totalGas,
                gasTrace,
                sequenceIdentity(
                        ClosureIdentityService.Constructor.GAS_TRACE,
                        gasValues(gasTrace)),
                rejectedCharge,
                null,
                companion,
                null);
    }

    private static void failedResultWithGraphChange(
            Fixture fixture,
            GraphChange change) {
        List<Object> values = Collections.<Object>singletonList(
                change.identityValue());
        new ClosureProcessResult(
                fixture.snapshot,
                ProcessorStatus.NO_MATCH,
                fixture.invocationIdentity,
                fixture.snapshot.closureIdentity(),
                fixture.snapshot.graphGeneration(),
                Collections.singletonList(resultingDocument(fixture)),
                Collections.singletonList(fixture.component),
                Collections.<ManagedOccurrenceBinding>emptyList(),
                fixture.bindingSetIdentity,
                Collections.singletonList(change),
                sequenceIdentity(
                        ClosureIdentityService.Constructor.GRAPH_CHANGES,
                        values),
                Collections.<SubscriptionDelta>emptyList(),
                emptyIdentity(
                        ClosureIdentityService.Constructor.SUBSCRIPTION_DELTAS),
                Collections.<CheckpointWrite>emptyList(),
                emptyIdentity(
                        ClosureIdentityService.Constructor.CHECKPOINT_WRITES),
                Collections.<PublicEventOccurrence>emptyList(),
                emptyIdentity(ClosureIdentityService.Constructor.PUBLIC_EVENTS),
                0L,
                Collections.<GasTraceEntry>emptyList(),
                emptyIdentity(ClosureIdentityService.Constructor.GAS_TRACE),
                null,
                null,
                null,
                null);
    }

    private static ResultingDocument resultingDocument(Fixture fixture) {
        return new ResultingDocument(
                ROOT,
                fixture.blueId,
                fixture.blueId,
                fixture.document,
                false,
                false,
                true,
                0L,
                1L,
                fixture.component.componentIdentity(),
                fixture.component.componentStateIdentity(),
                null);
    }

    private static Fixture fixture() {
        Node document = new Node().name("root-before");
        String blueId = DirectBlueIdCalculator.calculateBlueId(document);
        String componentIdentity = IDENTITIES.componentIdentity(
                ComponentKind.ACYCLIC,
                1L,
                Collections.singletonList(ROOT));
        ComponentSnapshot provisional = new ComponentSnapshot(
                componentIdentity,
                hash('0'),
                1L,
                ComponentKind.ACYCLIC,
                Collections.singletonList(ROOT),
                Collections.singletonList(blueId),
                null,
                null,
                null);
        String componentStateIdentity =
                IDENTITIES.componentStateIdentity(provisional);
        ComponentSnapshot component = new ComponentSnapshot(
                componentIdentity,
                componentStateIdentity,
                1L,
                ComponentKind.ACYCLIC,
                Collections.singletonList(ROOT),
                Collections.singletonList(blueId),
                null,
                null,
                null);
        ManagedDocumentSnapshot managed = new ManagedDocumentSnapshot(
                ROOT, blueId, document, false, false, true, 0L, 1L);
        String bindingSetIdentity = IDENTITIES.occurrenceBindingSetIdentity(
                Collections.<ManagedOccurrenceBinding>emptyList());
        AffectedClosureSnapshot provisionalSnapshot =
                new AffectedClosureSnapshot(
                        hash('1'),
                        0L,
                        Collections.singletonList(managed),
                        Collections.<ManagedOccurrenceBinding>emptyList(),
                        bindingSetIdentity,
                        Collections.singletonList(component),
                        Collections.singletonList(ROOT));
        String closureIdentity = IDENTITIES.affectedClosureIdentity(
                provisionalSnapshot);
        AffectedClosureSnapshot snapshot = new AffectedClosureSnapshot(
                closureIdentity,
                0L,
                Collections.singletonList(managed),
                Collections.<ManagedOccurrenceBinding>emptyList(),
                bindingSetIdentity,
                Collections.singletonList(component),
                Collections.singletonList(ROOT));
        return new Fixture(
                document,
                blueId,
                component,
                bindingSetIdentity,
                snapshot,
                hash('a'));
    }

    private static ClosureCommitCompanion companion(Fixture fixture) {
        ClosureEnvironment environment = environment();
        List<ClosureCommitCompanion.InputDocument> inputDocuments =
                Collections.singletonList(
                        new ClosureCommitCompanion.InputDocument(
                                ROOT, fixture.blueId));
        List<ClosureCommitCompanion.InputComponent> inputComponents =
                Collections.singletonList(
                        new ClosureCommitCompanion.InputComponent(
                                fixture.component.componentIdentity(),
                                fixture.component.componentStateIdentity(),
                                fixture.component.componentGeneration(),
                                null));
        List<ClosureCommitCompanion.ResultComponent> resultComponents =
                Collections.singletonList(
                        new ClosureCommitCompanion.ResultComponent(
                                fixture.component.componentIdentity(),
                                fixture.component.componentStateIdentity(),
                                null));
        List<ClosureCommitCompanion.DocumentDelta> resultDocuments =
                Collections.singletonList(
                        new ClosureCommitCompanion.DocumentDelta(
                                ROOT, fixture.blueId, fixture.blueId));
        Map<String, Object> value = companionIdentityValue(
                fixture,
                inputDocuments,
                inputComponents,
                resultDocuments,
                resultComponents,
                environment);
        String companionIdentity = IDENTITIES.identity(
                ClosureIdentityService.Constructor.PLATFORM_COMMIT_COMPANION,
                value);
        return new ClosureCommitCompanion(
                companionIdentity,
                fixture.invocationIdentity,
                fixture.snapshot.closureIdentity(),
                fixture.snapshot.closureIdentity(),
                fixture.snapshot.graphGeneration(),
                inputDocuments,
                inputComponents,
                fixture.bindingSetIdentity,
                fixture.snapshot.graphGeneration(),
                resultDocuments,
                resultComponents,
                fixture.bindingSetIdentity,
                emptyIdentity(ClosureIdentityService.Constructor.GRAPH_CHANGES),
                emptyIdentity(
                        ClosureIdentityService.Constructor.CHECKPOINT_WRITES),
                emptyIdentity(
                        ClosureIdentityService.Constructor.SUBSCRIPTION_DELTAS),
                emptyIdentity(ClosureIdentityService.Constructor.PUBLIC_EVENTS),
                emptyIdentity(ClosureIdentityService.Constructor.GAS_TRACE),
                environment);
    }

    private static Map<String, Object> companionIdentityValue(
            Fixture fixture,
            List<ClosureCommitCompanion.InputDocument> inputDocuments,
            List<ClosureCommitCompanion.InputComponent> inputComponents,
            List<ClosureCommitCompanion.DocumentDelta> resultDocuments,
            List<ClosureCommitCompanion.ResultComponent> resultComponents,
            ClosureEnvironment environment) {
        LinkedHashMap<String, Object> value =
                new LinkedHashMap<String, Object>();
        value.put("invocationIdentity", fixture.invocationIdentity);
        value.put("inputClosureIdentity", fixture.snapshot.closureIdentity());
        value.put("outputClosureIdentity", fixture.snapshot.closureIdentity());
        value.put("expectedInputGraphGeneration", Long.valueOf(0L));
        value.put("expectedInputDocuments", Collections.<Object>singletonList(
                object("documentId", ROOT.value(), "blueId", fixture.blueId)));
        value.put("expectedInputComponents", Collections.<Object>singletonList(
                object(
                        "componentIdentity",
                        inputComponents.get(0).componentIdentity(),
                        "componentStateIdentity",
                        inputComponents.get(0).componentStateIdentity(),
                        "componentGeneration",
                        Long.valueOf(1L),
                        "masterBlueId",
                        null)));
        value.put("inputOccurrenceBindingSetIdentity",
                fixture.bindingSetIdentity);
        value.put("outputGraphGeneration", Long.valueOf(0L));
        value.put("resultingDocuments", Collections.<Object>singletonList(
                object(
                        "documentId",
                        resultDocuments.get(0).documentId().value(),
                        "beforeBlueId",
                        resultDocuments.get(0).beforeBlueId(),
                        "afterBlueId",
                        resultDocuments.get(0).afterBlueId())));
        value.put("resultingComponents", Collections.<Object>singletonList(
                object(
                        "componentIdentity",
                        resultComponents.get(0).componentIdentity(),
                        "componentStateIdentity",
                        resultComponents.get(0).componentStateIdentity(),
                        "cyclicProofIdentity",
                        null)));
        value.put("occurrenceBindingSetIdentity", fixture.bindingSetIdentity);
        value.put("graphChangesIdentity",
                emptyIdentity(ClosureIdentityService.Constructor.GRAPH_CHANGES));
        value.put("checkpointWritesIdentity",
                emptyIdentity(
                        ClosureIdentityService.Constructor.CHECKPOINT_WRITES));
        value.put("subscriptionDeltasIdentity",
                emptyIdentity(
                        ClosureIdentityService.Constructor.SUBSCRIPTION_DELTAS));
        value.put("publicEventsIdentity",
                emptyIdentity(ClosureIdentityService.Constructor.PUBLIC_EVENTS));
        value.put("gasTraceIdentity",
                emptyIdentity(ClosureIdentityService.Constructor.GAS_TRACE));
        value.put("blueLanguageSpecificationIdentity",
                environment.blueLanguageSpecificationIdentity());
        value.put("contractsSpecificationIdentity",
                environment.contractsSpecificationIdentity());
        value.put("managedDocumentIdentityPolicyIdentity",
                environment.managedDocumentIdentityPolicyIdentity());
        value.put("managedBindingPolicyIdentity",
                environment.managedBindingPolicyIdentity());
        value.put("exactNodeProviderDomainIdentity",
                environment.exactNodeProviderDomainIdentity());
        value.put("externalOrderPolicyIdentity",
                environment.externalOrderPolicyIdentity());
        value.put("runtimeRegistryIdentity",
                environment.runtimeRegistryIdentity());
        value.put("gasManifestIdentity", environment.gasManifestIdentity());
        value.put("portableLimitPolicyIdentity",
                environment.portableLimitPolicyIdentity());
        value.put("cyclicFinalizerIdentity",
                environment.cyclicFinalizerIdentity());
        value.put("cyclicProofVerifierIdentity",
                environment.cyclicProofVerifierIdentity());
        return value;
    }

    private static Map<String, Object> companionSerializedShape(
            ClosureCommitCompanion companion) {
        LinkedHashMap<String, Object> shape = new LinkedHashMap<String, Object>(
                companion.identityConstructorValue());
        shape.put("companionIdentity", companion.companionIdentity());
        return shape;
    }

    private static Map<String, Object> object(Object... fields) {
        LinkedHashMap<String, Object> value =
                new LinkedHashMap<String, Object>();
        for (int index = 0; index < fields.length; index += 2) {
            value.put((String) fields[index], fields[index + 1]);
        }
        return value;
    }

    private static List<Object> gasValues(List<GasTraceEntry> gasTrace) {
        ArrayList<Object> values = new ArrayList<Object>();
        for (GasTraceEntry entry : gasTrace) {
            values.add(entry.identityValue());
        }
        return values;
    }

    private static String emptyIdentity(
            ClosureIdentityService.Constructor constructor) {
        return sequenceIdentity(constructor, Collections.emptyList());
    }

    private static String sequenceIdentity(
            ClosureIdentityService.Constructor constructor,
            List<Object> values) {
        return IDENTITIES.identity(constructor, values);
    }

    private static ClosureEnvironment environment() {
        return new ClosureEnvironment(
                hash('1'), hash('2'), hash('3'), hash('4'),
                labeled(hash('5'), "document-policy"),
                labeled(hash('6'), "binding-policy"),
                labeled(hash('7'), "provider-domain"),
                labeled(hash('8'), "external-order-policy"),
                portable(hash('9')),
                hash('a'),
                hash('b'));
    }

    private static ClosureEnvironment.LabeledIdentityEvidence labeled(
            String identity, String label) {
        return new ClosureEnvironment.LabeledIdentityEvidence(identity, label);
    }

    private static ClosureEnvironment.PortableLimitPolicyEvidence portable(
            String identity) {
        return new ClosureEnvironment.PortableLimitPolicyEvidence(
                identity,
                "portable-limits",
                Collections.<String, Long>emptyMap());
    }

    private static RejectionOwnerKind rejectionKind(RejectedCharge charge) {
        return RejectionOwnerKind.valueOf(charge.owner().kind().name());
    }

    private static String hash(char digit) {
        char[] digits = new char[64];
        Arrays.fill(digits, digit);
        return "sha256:" + new String(digits);
    }

    private enum RejectionOwnerKind {
        INVOCATION,
        WORK,
        FINALIZATION
    }

    private static final class Fixture {
        private final Node document;
        private final String blueId;
        private final ComponentSnapshot component;
        private final String bindingSetIdentity;
        private final AffectedClosureSnapshot snapshot;
        private final String invocationIdentity;

        private Fixture(
                Node document,
                String blueId,
                ComponentSnapshot component,
                String bindingSetIdentity,
                AffectedClosureSnapshot snapshot,
                String invocationIdentity) {
            this.document = document;
            this.blueId = blueId;
            this.component = component;
            this.bindingSetIdentity = bindingSetIdentity;
            this.snapshot = snapshot;
            this.invocationIdentity = invocationIdentity;
        }
    }
}
