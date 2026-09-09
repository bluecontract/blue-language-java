package blue.language.processor;

import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.merge.ResolvedSnapshot;
import blue.language.model.Node;
import blue.language.model.Nodes;
import blue.language.preprocess.provider.BasicNodeProvider;
import blue.language.provider.SequentialNodeProvider;
import blue.language.processor.model.ChannelContract;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.registry.BlueRuntimeTypeRegistry;
import blue.language.runtime.BlueLanguage;
import blue.language.runtime.LanguageProcessing;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** C34-shaped characterization of Root projection and checkpoint settlement. */
final class ManagedRootSettlementServiceTest {

    private static final Node SOURCE_TYPE =
            new Node().name("Managed Root Settlement Source Channel");
    private static final String SOURCE_BLUE_ID =
            DirectBlueIdCalculator.calculateBlueId(SOURCE_TYPE);

    @Test
    void freezesManagerlessNormalizedChannelsAsCanonicalSource() {
        // given
        Node exactChannel = sourceContract();

        // when
        FrozenNode normalized = NormalizedRuntimeContribution.channel(
                FrozenNode.fromNode(exactChannel),
                SOURCE_BLUE_ID,
                Collections.<String>emptyList(),
                CanonicalTypeIdentityLookup.incomplete(),
                null);

        // then
        assertEquals(
                DirectBlueIdCalculator.calculateBlueId(exactChannel),
                normalized.blueId());
        assertEquals(
                FrozenNode.fromNode(exactChannel).resolvedStructuralKey(),
                normalized.resolvedStructuralKey());
    }

    @Test
    void preservesExplicitEmptyHeaderWhileKeepingMissingHeaderAbsent() {
        // given
        Node channelType = new Node()
                .name("Channel with optional Node header")
                .properties(
                        "payload",
                        new Node().description("Optional payload"));
        String channelTypeBlueId =
                DirectBlueIdCalculator.calculateBlueId(channelType);
        BasicNodeProvider provider = new BasicNodeProvider(channelType);
        Node absentSource = new Node()
                .type(new Node().blueId(channelTypeBlueId));
        Node emptySource = absentSource.clone()
                .properties("payload", Nodes.emptyObject());

        try (BlueLanguage language = BlueLanguage.builder()
                .nodeProvider(provider)
                .build();
             LanguageProcessing.Scope scope =
                     language.processing().openScope()) {
            LanguageProcessingSnapshotManager manager =
                    new LanguageProcessingSnapshotManager(scope);

            // when
            FrozenNode absent = NormalizedRuntimeContribution.channel(
                    FrozenNode.fromNode(absentSource),
                    channelTypeBlueId,
                    Collections.singletonList("payload"),
                    CanonicalTypeIdentityLookup.incomplete(),
                    manager);
            FrozenNode explicitEmpty = NormalizedRuntimeContribution.channel(
                    FrozenNode.fromNode(emptySource),
                    channelTypeBlueId,
                    Collections.singletonList("payload"),
                    CanonicalTypeIdentityLookup.incomplete(),
                    manager);

            // then
            assertFalse(absent.getProperties().containsKey("payload"));
            FrozenNode retained = explicitEmpty.getProperties().get("payload");
            assertNotNull(retained);
            assertNotNull(retained.getProperties());
            assertTrue(retained.getProperties().isEmpty());
            assertNotEquals(absent.blueId(), explicitEmpty.blueId());
        }
    }

    @Test
    void normalizesWholeChannelReferencesWithoutChangingSourceEvidence() {
        // given
        Node exactChannel = sourceContract();
        String exactChannelBlueId =
                DirectBlueIdCalculator.calculateBlueId(exactChannel);
        BasicNodeProvider provider = new BasicNodeProvider(
                SOURCE_TYPE, exactChannel);
        ContractProcessorRegistry registry =
                ContractProcessorRegistryBuilder.create()
                        .register(
                                SOURCE_BLUE_ID,
                                SOURCE_TYPE,
                                new SourceProcessor())
                        .build();
        try (BlueLanguage language = BlueLanguage.builder()
                .nodeProvider(provider)
                .build();
             LanguageProcessing.Scope scope =
                     language.processing().openScope()) {
            LanguageProcessingSnapshotManager manager =
                    new LanguageProcessingSnapshotManager(scope);
            DocumentProcessor processor = DocumentProcessor.builder()
                    .runtimeRegistry(registry)
                    .nodeProvider(scope.runtimeAccess().getNodeProvider())
                    .snapshotStore(manager)
                    .matchingService(new ContractMatchingService(
                            scope.runtimeAccess()))
                    .build();
            try (ManagedDocumentStepRuntime runtime =
                         new ManagedDocumentStepRuntime(processor)) {
                Node inline = documentWithSource(exactChannel.clone());
                Node reference = documentWithSource(
                        new Node().blueId(exactChannelBlueId));

                // when
                ManagedRootChannelOccurrence inlineOccurrence = runtime
                        .projectRootChannelSurface(inline).get(0);
                ManagedRootChannelOccurrence referenceOccurrence = runtime
                        .projectRootChannelSurface(reference).get(0);

                // then
                assertEquals(
                        DirectBlueIdCalculator.calculateBlueId(inline),
                        DirectBlueIdCalculator.calculateBlueId(reference));
                assertEquals(
                        inlineOccurrence.effectiveRuntimeContributionBlueId(),
                        referenceOccurrence
                                .effectiveRuntimeContributionBlueId());
                assertEquals(
                        inlineOccurrence.subscriptionHeaderBlueId(),
                        referenceOccurrence.subscriptionHeaderBlueId());
                assertEquals(
                        Collections.singletonList(exactChannelBlueId),
                        inlineOccurrence.sourceContributionNodeBlueIds());
                assertEquals(
                        inlineOccurrence.sourceContributionNodeBlueIds(),
                        referenceOccurrence.sourceContributionNodeBlueIds());
            } finally {
                processor.close();
            }
        }
    }

    @Test
    void managedPayloadMemoRequiresExactIdentityBodyProvenanceAndInvocation() {
        DocumentProcessor processor = payloadMemoProcessor();
        Node event = event("canonical memo").properties("payload", new Node()
                .type(new Node().blueId(blue.language.model.wire.BlueLanguageConstants.TEXT_TYPE_BLUE_ID))
                .value("unchanged").inlineValue(false)
                .schema(new blue.language.model.Schema().minLength(new Node().value(java.math.BigInteger.ONE))));
        ExactEventIdentityEvidence evidence = ExactEventIdentityEvidence.verify(null, event,
                DirectBlueIdCalculator.calculateBlueId(event), null);
        ManagedDocumentStepRequest request = payloadRequest(event, evidence.eventBlueId(),
                ManagedDocumentWorkKind.EXTERNAL_DELIVERY);
        try (ManagedDocumentStepRuntime runtime = new ManagedDocumentStepRuntime(processor)) {
            org.junit.jupiter.api.Assertions.assertNull(runtime.carriedExternalPayload(request),
                    "A direct isolated SPI request has no inherited closure admission capability");
            ManagedCheckpointCandidate candidate = runtime.classifyExternalDelivery(document(), "source", evidence,
                    context("source", "payload-memo", "direct-admission.payload")).candidate();
            assertNotNull(candidate);
            FrozenNode carried = candidate.frozenPayload();
            assertTrue(carried.isStrictCanonical());
            long gasBeforeLookup = runtime.totalGas();
            org.junit.jupiter.api.Assertions.assertSame(carried, runtime.carriedExternalPayload(request));
            org.junit.jupiter.api.Assertions.assertSame(carried, runtime.carriedExternalPayload(payloadRequest(
                    event.clone(), evidence.eventBlueId(), ManagedDocumentWorkKind.EXTERNAL_DELIVERY)));
            org.junit.jupiter.api.Assertions.assertNull(runtime.carriedExternalPayload(payloadRequest(
                    event.clone().properties("tampered", new Node().value(true)), evidence.eventBlueId(),
                    ManagedDocumentWorkKind.EXTERNAL_DELIVERY)));
            org.junit.jupiter.api.Assertions.assertNull(runtime.carriedExternalPayload(payloadRequest(event,
                    DirectBlueIdCalculator.calculateBlueId(new Node().name("different identity")),
                    ManagedDocumentWorkKind.EXTERNAL_DELIVERY)));
            org.junit.jupiter.api.Assertions.assertNull(runtime.carriedExternalPayload(payloadRequest(event,
                    evidence.eventBlueId(), ManagedDocumentWorkKind.TRIGGERED_EVENT)));

            Node changedInline = event.clone();
            changedInline.getProperties().get("payload").inlineValue(true);
            assertTrue(FrozenNode.fromResolvedNode(event).sameResolvedStructure(FrozenNode.fromResolvedNode(changedInline)));
            assertEquals(evidence.eventBlueId(), FrozenNode.fromNode(changedInline).blueId());
            org.junit.jupiter.api.Assertions.assertNull(runtime.carriedExternalPayload(payloadRequest(changedInline,
                    evidence.eventBlueId(), ManagedDocumentWorkKind.EXTERNAL_DELIVERY)),
                    "Equal semantic identity cannot erase inline source provenance at the memo boundary");
            Node changedKeywordType = event.clone();
            changedKeywordType.getProperties().get("payload").getSchema().getMinLength().type(new Node()
                    .blueId(blue.language.model.wire.BlueLanguageConstants.INTEGER_TYPE_BLUE_ID));
            assertTrue(FrozenNode.fromResolvedNode(event).sameResolvedStructure(FrozenNode.fromResolvedNode(changedKeywordType)));
            assertEquals(evidence.eventBlueId(), FrozenNode.fromNode(changedKeywordType).blueId());
            org.junit.jupiter.api.Assertions.assertNull(runtime.carriedExternalPayload(payloadRequest(changedKeywordType,
                    evidence.eventBlueId(), ManagedDocumentWorkKind.EXTERNAL_DELIVERY)),
                    "Equivalent schema sugar is not the recorded complete handoff representation");
            org.junit.jupiter.api.Assertions.assertSame(carried, runtime.carriedExternalPayload(request));
            assertEquals(gasBeforeLookup, runtime.totalGas(), "Lookup is not another semantic admission or work item");
            runtime.close();
            assertThrows(IllegalStateException.class, () -> runtime.carriedExternalPayload(request));
            try (ManagedDocumentStepRuntime fresh = new ManagedDocumentStepRuntime(processor)) {
                org.junit.jupiter.api.Assertions.assertNull(fresh.carriedExternalPayload(request),
                        "The admission capability cannot leak into another composition session");
            }
        } finally {
            processor.close();
        }
    }

    @Test
    void managedPayloadMemoRejectsConflictingInternalRepresentationCapabilities() {
        DocumentProcessor processor = payloadMemoProcessor();
        Node event = event("conflicting source mode");
        ExactEventIdentityEvidence strict = ExactEventIdentityEvidence.verify(null, event,
                DirectBlueIdCalculator.calculateBlueId(event), null);
        try (ManagedDocumentStepRuntime runtime = new ManagedDocumentStepRuntime(processor)) {
            ManagedCheckpointCandidate first = runtime.classifyExternalDelivery(document(), "source", strict,
                    context("source", "memo-first", "direct-admission.first")).candidate();
            assertNotNull(first);
            // Test-only internal injection: do not claim public Source admission
            // constructs this incompatible resolved capability for ordinary Source.
            ExactEventIdentityEvidence incompatible = ExactEventIdentityEvidence.fromAdmitted(
                    new ExactBlueValue(FrozenNode.fromResolvedNode(event), strict.eventBlueId()));
            InvalidExecutionEvidenceException rejected = assertThrows(InvalidExecutionEvidenceException.class,
                    () -> runtime.classifyExternalDelivery(document(), "source", incompatible,
                            context("source", "memo-conflict", "direct-admission.conflict")));
            assertEquals("Managed external payload admission representations disagree", rejected.getMessage());
            org.junit.jupiter.api.Assertions.assertSame(first.frozenPayload(), runtime.carriedExternalPayload(
                    payloadRequest(event, strict.eventBlueId(), ManagedDocumentWorkKind.EXTERNAL_DELIVERY)),
                    "The conflicting capability must not replace the retained original");
        } finally {
            processor.close();
        }
    }

    @Test
    void managedPayloadMemoPreservesCompleteCyclicMemberEvidenceWithoutRehashing() {
        DocumentProcessor processor = payloadMemoProcessor();
        Node placeholder = event("cyclic memo").properties("self", new Node().blueId("this#0"));
        String memberId = blue.language.identity.CircularSetIdentityCalculator.calculateCircularSetFinalization(
                Collections.singletonList(placeholder)).membersInInputOrder().get(0).finalBlueId();
        Node materialized = placeholder.clone();
        materialized.getProperties().get("self").blueId(memberId);
        ExactEventIdentityEvidence evidence = ExactEventIdentityEvidence.verify(null, materialized, memberId,
                blue.language.provider.CyclicSetProof.fromDeclaredPlaceholderSet(Collections.singletonList(placeholder)));
        assertNotEquals(memberId, DirectBlueIdCalculator.calculateBlueId(materialized));
        try (ManagedDocumentStepRuntime runtime = new ManagedDocumentStepRuntime(processor)) {
            ManagedCheckpointCandidate candidate = runtime.classifyExternalDelivery(document(), "source", evidence,
                    context("source", "memo-cyclic", "direct-admission.cyclic")).candidate();
            assertNotNull(candidate);
            assertEquals(memberId, candidate.payloadBlueId());
            org.junit.jupiter.api.Assertions.assertSame(candidate.frozenPayload(), runtime.carriedExternalPayload(
                    payloadRequest(materialized, memberId, ManagedDocumentWorkKind.EXTERNAL_DELIVERY)));
            org.junit.jupiter.api.Assertions.assertNull(runtime.carriedExternalPayload(payloadRequest(
                    new Node().blueId(memberId), memberId, ManagedDocumentWorkKind.EXTERNAL_DELIVERY)),
                    "A materialized capability does not admit a different bare-reference handoff");
            org.junit.jupiter.api.Assertions.assertNull(runtime.carriedExternalPayload(payloadRequest(
                    materialized.clone().properties("tampered", new Node().value(true)), memberId,
                    ManagedDocumentWorkKind.EXTERNAL_DELIVERY)));
        } finally {
            processor.close();
        }
    }

    private static DocumentProcessor payloadMemoProcessor() {
        ContractProcessorRegistry registry = ContractProcessorRegistryBuilder.create()
                .register(SOURCE_BLUE_ID, SOURCE_TYPE, new SourceProcessor()).build();
        return DocumentProcessor.builder().runtimeRegistry(registry)
                .snapshotStore(new ExactDomainSnapshotManager()).build();
    }

    private static ManagedDocumentStepRequest payloadRequest(Node payload, String blueId,
            ManagedDocumentWorkKind kind) {
        return new ManagedDocumentStepRequest(document(), true, false, kind, "source", payload, blueId,
                null, null, GasChargeContext.empty());
    }

    @Test
    void preservesAdmissionProvedCyclicMemberPayloadIdentity() {
        // given
        ExactDomainSnapshotManager domainStore =
                new ExactDomainSnapshotManager();
        ContractProcessorRegistry registry =
                ContractProcessorRegistryBuilder.create()
                        .register(
                                SOURCE_BLUE_ID,
                                SOURCE_TYPE,
                                new SourceProcessor())
                        .build();
        DocumentProcessor processor = DocumentProcessor.builder()
                .runtimeRegistry(registry)
                .snapshotStore(domainStore)
                .build();
        Node exactEvent = event("cyclic-member");
        String directEventBlueId =
                DirectBlueIdCalculator.calculateBlueId(exactEvent);
        String admittedEventBlueId =
                DirectBlueIdCalculator.calculateBlueId(
                        new Node().name("event-cycle")) + "#0";

        try (ManagedDocumentStepRuntime runtime =
                     new ManagedDocumentStepRuntime(processor)) {
            // when
            ManagedExternalDeliveryClassification classification =
                    runtime.classifyExternalDelivery(
                            document(),
                            "source",
                            ExactEventIdentityEvidence.fromAdmitted(
                                    new ExactBlueValue(
                                            FrozenNode.fromResolvedNode(
                                                    exactEvent),
                                            admittedEventBlueId)),
                            context(
                                    "source",
                                    "direct-source-cyclic",
                                    "direct-admission.cyclic.classification"));

            // then
            assertEquals(
                    ManagedExternalDeliveryClassification.State.ACCEPTED_NEW,
                    classification.state());
            assertNotEquals(directEventBlueId, admittedEventBlueId);
            assertEquals(
                    admittedEventBlueId,
                    classification.candidate().payloadBlueId());
        } finally {
            processor.close();
            domainStore.close();
        }
    }

    @Test
    void projectsRootChannelsAndSettlesAddThenReplaceOnTheSharedLedger() {
        ExactDomainSnapshotManager domainStore =
                new ExactDomainSnapshotManager();
        ContractProcessorRegistry registry =
                ContractProcessorRegistryBuilder.create()
                        .register(
                                SOURCE_BLUE_ID,
                                SOURCE_TYPE,
                                new SourceProcessor())
                        .build();
        DocumentProcessor processor = DocumentProcessor.builder()
                .runtimeRegistry(registry)
                .snapshotStore(domainStore)
                .build();
        try (ManagedDocumentStepRuntime runtime =
                     new ManagedDocumentStepRuntime(processor)) {
            Node input = document();
            long beforeProjection = runtime.totalGas();
            List<ManagedRootChannelOccurrence> surface =
                    runtime.projectRootChannelSurface(input);

            assertEquals(1, surface.size());
            ManagedRootChannelOccurrence source = surface.get(0);
            assertEquals("source", source.rawChannelKey());
            assertTrue(source.externalSource());
            assertEquals(SOURCE_BLUE_ID, source.effectiveTypeBlueId());
            assertEquals(
                    DirectBlueIdCalculator.calculateBlueId(
                            input.getContracts().getProperties().get("source")),
                    source.effectiveRuntimeContributionBlueId());
            assertNotNull(source.subscriptionHeaderBlueId());
            assertEquals(beforeProjection, runtime.totalGas());

            ManagedExternalDeliveryClassification first =
                    classify(runtime,
                            input,
                            "source",
                            event("first"),
                            context(
                                    "source",
                                    "direct-source-0",
                                    "direct-admission.0.checkpoint-compare"));
            assertEquals(
                    ManagedExternalDeliveryClassification.State.ACCEPTED_NEW,
                    first.state());
            assertTrue(first.preselected());
            assertTrue(first.accepted());
            assertTrue(first.handlerMatched());
            assertNotNull(first.candidate());
            assertFalse(first.candidate().beforeState().present());
            assertEquals(1L, counter(runtime.gasTrace(),
                    GasScheduleConstants.ProcessorCounter
                            .CHECKPOINT_COMPARED));
            GasTraceEntry comparison = lastTrace(
                    runtime.gasTrace(),
                    GasScheduleConstants.ProcessorCounter
                            .CHECKPOINT_COMPARED);
            assertEquals("direct-admission.0.checkpoint-compare",
                    comparison.reason());
            assertEquals("source", comparison.contractKey());
            assertEquals("a", comparison.documentId());
            assertEquals("direct-source-0",
                    comparison.workOccurrenceId());

            ManagedCheckpointSettlement added = runtime.settleCheckpoints(
                    input,
                    Collections.singletonList(
                            new ManagedCheckpointSettlementEntry(
                                    first.candidate(),
                                    0L,
                                    context(
                                            "source",
                                            null,
                                            "checkpoint-settlement.0.write.0"))),
                    cleanupContexts(),
                    context(
                            null,
                            null,
                            "checkpoint-settlement.0.revalidation"));
            assertEquals(1, added.mutations().size());
            ManagedCheckpointMutation add = added.mutations().get(0);
            assertEquals(ManagedCheckpointMutation.Operation.ADD,
                    add.operation());
            assertFalse(add.beforeState().present());
            assertTrue(add.afterState().present());
            assertEquals(first.candidate().domain().blueId(),
                    add.afterState().domainBlueId());
            assertEquals(first.candidate().subjectBlueId(),
                    add.afterState().subjectBlueId());
            assertEquals("/contracts/checkpoint/entries/source",
                    add.processorPatch().getPath());
            assertNotNull(added.resultingBody().getAsNode(
                    "/contracts/checkpoint/entries/source"));
            assertEquals(1L, counter(runtime.gasTrace(),
                    GasScheduleConstants.ProcessorCounter
                            .CHECKPOINT_WRITTEN));
            assertEquals(0L, counter(runtime.gasTrace(),
                    GasScheduleConstants.ProcessorCounter
                            .PROCESSOR_MARKER_WRITTEN));
            assertEquals("checkpoint-settlement.0.write.0",
                    lastTrace(
                            runtime.gasTrace(),
                            GasScheduleConstants.ProcessorCounter
                                    .CHECKPOINT_WRITTEN).reason());

            ManagedExternalDeliveryClassification second =
                    classify(runtime,
                            added.resultingBody(),
                            "source",
                            event("second"),
                            context(
                                    "source",
                                    "direct-source-1",
                                    "direct-admission.1.checkpoint-compare"));
            assertEquals(
                    ManagedExternalDeliveryClassification.State.ACCEPTED_NEW,
                    second.state());
            assertTrue(second.candidate().beforeState().present());
            assertEquals(first.candidate().domain().blueId(),
                    second.candidate().beforeState().domainBlueId());

            ManagedCheckpointSettlement replaced = runtime.settleCheckpoints(
                    added.resultingBody(),
                    Collections.singletonList(
                            new ManagedCheckpointSettlementEntry(
                                    second.candidate(),
                                    0L,
                                    context(
                                            "source",
                                            null,
                                            "checkpoint-settlement.0.write.1"))),
                    cleanupContexts(),
                    context(
                            null,
                            null,
                            "checkpoint-settlement.0.revalidation"));
            assertEquals(1, replaced.mutations().size());
            ManagedCheckpointMutation replace = replaced.mutations().get(0);
            assertEquals(ManagedCheckpointMutation.Operation.REPLACE,
                    replace.operation());
            assertEquals(first.candidate().subjectBlueId(),
                    replace.beforeState().subjectBlueId());
            assertEquals(second.candidate().subjectBlueId(),
                    replace.afterState().subjectBlueId());
            assertEquals(2L, counter(runtime.gasTrace(),
                    GasScheduleConstants.ProcessorCounter
                            .CHECKPOINT_WRITTEN));
            assertEquals("checkpoint-settlement.0.write.1",
                    lastTrace(
                            runtime.gasTrace(),
                            GasScheduleConstants.ProcessorCounter
                                    .CHECKPOINT_WRITTEN).reason());
            assertTrue(replaced.resultingBody().getProperties() == null
                    || !replaced.resultingBody().getProperties().containsKey(
                    "ambientContainingDocument"));

            assertEquals(processor.runtimeRegistryIdentity(),
                    runtime.runtimeRegistryIdentity());
            assertEquals(
                    "sha256:" + GasSchedule.CONTRACTS_1_0_RESOURCE_SHA256,
                    runtime.gasManifestIdentity());

            domainStore.provide(second.candidate().domain());
            Node retired = replaced.resultingBody();
            retired.getContracts().getProperties().remove("source");
            ManagedCheckpointSettlement removed = runtime.settleCheckpoints(
                    retired,
                    Collections.<ManagedCheckpointSettlementEntry>emptyList(),
                    cleanupContexts(),
                    context(
                            null,
                            null,
                            "checkpoint-settlement.0.revalidation"));
            assertEquals(1, removed.mutations().size());
            assertEquals(ManagedCheckpointMutation.Operation.REMOVE,
                    removed.mutations().get(0).operation());
            assertEquals("source",
                    removed.mutations().get(0).rawChannelKey());
            assertEquals(3L, counter(runtime.gasTrace(),
                    GasScheduleConstants.ProcessorCounter
                            .CHECKPOINT_WRITTEN));
            GasTraceEntry cleanup = lastTrace(
                    runtime.gasTrace(),
                    GasScheduleConstants.ProcessorCounter
                            .CHECKPOINT_WRITTEN);
            assertEquals("checkpoint-settlement.0.cleanup.0",
                    cleanup.reason());
            assertEquals("source", cleanup.contractKey());
            assertEquals("a", cleanup.documentId());
            Node remainingEntries = removed.resultingBody().getAsNode(
                    "/contracts/checkpoint/entries");
            assertTrue(remainingEntries.getProperties() == null
                    || !remainingEntries.getProperties().containsKey(
                    "source"));
        } finally {
            processor.close();
            domainStore.close();
        }
    }

    @Test
    void canonicalizesCompletedWritesByFrozenRawOccurrenceOrder() {
        ContractProcessorRegistry registry =
                ContractProcessorRegistryBuilder.create()
                        .register(
                                SOURCE_BLUE_ID,
                                SOURCE_TYPE,
                                new SourceProcessor())
                        .build();
        DocumentProcessor processor = DocumentProcessor.builder()
                .runtimeRegistry(registry)
                .build();
        try (ManagedDocumentStepRuntime runtime =
                     new ManagedDocumentStepRuntime(processor)) {
            Node input = twoSourceDocument();
            ManagedCheckpointCandidate zSource =
                    classify(runtime,
                            input,
                            "zSource",
                            event("z"),
                            context(
                                    "zSource",
                                    "direct-z",
                                    "direct-admission.0.checkpoint-compare"))
                            .candidate();
            ManagedCheckpointCandidate aSource =
                    classify(runtime,
                            input,
                            "aSource",
                            event("a"),
                            context(
                                    "aSource",
                                    "direct-a",
                                    "direct-admission.1.checkpoint-compare"))
                            .candidate();

            ManagedCheckpointSettlement settlement =
                    runtime.settleCheckpoints(
                            input,
                            Arrays.asList(
                                    new ManagedCheckpointSettlementEntry(
                                            aSource,
                                            1L,
                                            context(
                                                    "aSource",
                                                    null,
                                                    "checkpoint-settlement.0.write.1")),
                                    new ManagedCheckpointSettlementEntry(
                                            zSource,
                                            0L,
                                            context(
                                                    "zSource",
                                                    null,
                                                    "checkpoint-settlement.0.write.0"))),
                            cleanupContexts(),
                            context(
                                    null,
                                    null,
                                    "checkpoint-settlement.0.revalidation"));

            assertEquals(2, settlement.mutations().size());
            assertEquals("zSource",
                    settlement.mutations().get(0).rawChannelKey());
            assertEquals("aSource",
                    settlement.mutations().get(1).rawChannelKey());
            List<GasTraceEntry> writes = traces(
                    runtime.gasTrace(),
                    GasScheduleConstants.ProcessorCounter
                            .CHECKPOINT_WRITTEN);
            assertEquals(2, writes.size());
            assertEquals("checkpoint-settlement.0.write.0",
                    writes.get(0).reason());
            assertEquals("zSource", writes.get(0).contractKey());
            assertEquals("checkpoint-settlement.0.write.1",
                    writes.get(1).reason());
            assertEquals("aSource", writes.get(1).contractKey());
        } finally {
            processor.close();
        }
    }

    @Test
    void globallyOrdersReverseDocumentWritesThenLexicalCleanup() {
        ExactDomainSnapshotManager domainStore =
                new ExactDomainSnapshotManager();
        ContractProcessorRegistry registry =
                ContractProcessorRegistryBuilder.create()
                        .register(
                                SOURCE_BLUE_ID,
                                SOURCE_TYPE,
                                new SourceProcessor())
                        .build();
        DocumentProcessor processor = DocumentProcessor.builder()
                .runtimeRegistry(registry)
                .snapshotStore(domainStore)
                .build();
        try (ManagedDocumentStepRuntime runtime =
                     new ManagedDocumentStepRuntime(processor)) {
            Node a = sourceAndRetiredDocument("Batch Root A");
            Node b = sourceAndRetiredDocument("Batch Root B");
            ManagedExternalDeliveryClassification retiredA =
                    classify(runtime,
                            a,
                            "retired",
                            event("retired-a"),
                            context(
                                    "a",
                                    "retired",
                                    "retired-a",
                                    "setup.compare.a"));
            ManagedExternalDeliveryClassification retiredB =
                    classify(runtime,
                            b,
                            "retired",
                            event("retired-b"),
                            context(
                                    "b",
                                    "retired",
                                    "retired-b",
                                    "setup.compare.b"));
            ManagedCheckpointSettlement seededA = runtime.settleCheckpoints(
                    a,
                    Collections.singletonList(
                            new ManagedCheckpointSettlementEntry(
                                    retiredA.candidate(),
                                    0L,
                                    context(
                                            "a",
                                            "retired",
                                            null,
                                            "setup.write.a"))),
                    cleanupContexts(),
                    context("a", null, null, "setup.revalidate.a"));
            ManagedCheckpointSettlement seededB = runtime.settleCheckpoints(
                    b,
                    Collections.singletonList(
                            new ManagedCheckpointSettlementEntry(
                                    retiredB.candidate(),
                                    0L,
                                    context(
                                            "b",
                                            "retired",
                                            null,
                                            "setup.write.b"))),
                    cleanupContexts(),
                    context("b", null, null, "setup.revalidate.b"));
            domainStore.provide(retiredA.candidate().domain());
            domainStore.provide(retiredB.candidate().domain());

            Node finalA = seededA.resultingBody();
            Node finalB = seededB.resultingBody();
            finalA.getContracts().getProperties().remove("retired");
            finalB.getContracts().getProperties().remove("retired");
            ManagedCheckpointCandidate sourceA =
                    classify(runtime,
                            finalA,
                            "source",
                            event("source-a"),
                            context(
                                    "a",
                                    "source",
                                    "direct-a",
                                    "batch.compare.a"))
                            .candidate();
            ManagedCheckpointCandidate sourceB =
                    classify(runtime,
                            finalB,
                            "source",
                            event("source-b"),
                            context(
                                    "b",
                                    "source",
                                    "direct-b",
                                    "batch.compare.b"))
                            .candidate();
            int traceBefore = runtime.gasTrace().size();

            ManagedCheckpointSettlementBatch batch =
                    runtime.settleCheckpointBatch(
                            Arrays.asList(
                                    new ManagedCheckpointSettlementRequest(
                                            "scope-z",
                                            finalA,
                                            Collections.singletonList(
                                                    new ManagedCheckpointSettlementEntry(
                                                            sourceA,
                                                            1L,
                                                            context(
                                                                    "a",
                                                                    "source",
                                                                    null,
                                                                    "batch.write.a"))),
                                            context(
                                                    "a",
                                                    null,
                                                    null,
                                                    "batch.revalidate.a")),
                                    new ManagedCheckpointSettlementRequest(
                                            "scope-a",
                                            finalB,
                                            Collections.singletonList(
                                                    new ManagedCheckpointSettlementEntry(
                                                            sourceB,
                                                            0L,
                                                            context(
                                                                    "b",
                                                                    "source",
                                                                    null,
                                                                    "batch.write.b"))),
                                            context(
                                                    "b",
                                                    null,
                                                    null,
                                                    "batch.revalidate.b"))),
                            (target, rawKey, ordinal) -> context(
                                    "scope-a".equals(target) ? "b" : "a",
                                    rawKey,
                                    null,
                                    "batch.cleanup." + ordinal));

            assertEquals(2, batch.targets().size());
            assertEquals("scope-z",
                    batch.targets().get(0).targetManagedScopeIdentity());
            assertEquals("scope-a",
                    batch.targets().get(1).targetManagedScopeIdentity());
            assertEquals(4, batch.mutations().size());
            assertEquals(
                    Arrays.asList("scope-a", "scope-z", "scope-a", "scope-z"),
                    batchTargets(batch));
            assertEquals(
                    Arrays.asList("source", "source", "retired", "retired"),
                    batchRawKeys(batch));
            assertEquals(
                    Arrays.asList(
                            ManagedCheckpointMutation.Operation.ADD,
                            ManagedCheckpointMutation.Operation.ADD,
                            ManagedCheckpointMutation.Operation.REMOVE,
                            ManagedCheckpointMutation.Operation.REMOVE),
                    batchOperations(batch));

            List<GasTraceEntry> batchWrites = traces(
                    runtime.gasTrace().subList(
                            traceBefore, runtime.gasTrace().size()),
                    GasScheduleConstants.ProcessorCounter
                            .CHECKPOINT_WRITTEN);
            assertEquals(4, batchWrites.size());
            assertEquals(
                    Arrays.asList(
                            "batch.write.b",
                            "batch.write.a",
                            "batch.cleanup.2",
                            "batch.cleanup.3"),
                    reasons(batchWrites));
            assertEquals(
                    Arrays.asList("b", "a", "b", "a"),
                    documentIds(batchWrites));

            for (ManagedCheckpointSettlementBatch.TargetResult target
                    : batch.targets()) {
                assertNotNull(target.resultingBody().getAsNode(
                        "/contracts/checkpoint/entries/source"));
                Node entries = target.resultingBody().getAsNode(
                        "/contracts/checkpoint/entries");
                assertTrue(entries.getProperties() == null
                        || !entries.getProperties().containsKey("retired"));
            }
        } finally {
            processor.close();
            domainStore.close();
        }
    }

    @Test
    void chargesCheckpointCleanupFromTheImmutablePostRemoveSpine() {
        ExactDomainSnapshotManager domainStore =
                new ExactDomainSnapshotManager();
        ContractProcessorRegistry registry =
                ContractProcessorRegistryBuilder.create()
                        .register(
                                SOURCE_BLUE_ID,
                                SOURCE_TYPE,
                                new SourceProcessor())
                        .build();
        DocumentProcessor processor = DocumentProcessor.builder()
                .runtimeRegistry(registry)
                .snapshotStore(domainStore)
                .build();
        try (ManagedDocumentStepRuntime runtime =
                     new ManagedDocumentStepRuntime(processor)) {
            Node input = sourceAndOrphanDocument();
            ManagedCheckpointCandidate source = classify(
                    runtime,
                    input,
                    "source",
                    event("source"),
                    context(
                            "source",
                            "direct-source",
                            "setup.compare.source"))
                    .candidate();
            ManagedCheckpointCandidate orphan = classify(
                    runtime,
                    input,
                    "orphan",
                    event("orphan"),
                    context(
                            "orphan",
                            "direct-orphan",
                            "setup.compare.orphan"))
                    .candidate();
            ManagedCheckpointSettlement seeded = runtime.settleCheckpoints(
                    input,
                    Arrays.asList(
                            new ManagedCheckpointSettlementEntry(
                                    source,
                                    0L,
                                    context(
                                            "source",
                                            null,
                                            "setup.write.source")),
                            new ManagedCheckpointSettlementEntry(
                                    orphan,
                                    1L,
                                    context(
                                            "orphan",
                                            null,
                                            "setup.write.orphan"))),
                    cleanupContexts(),
                    context(null, null, "setup.revalidate"));
            domainStore.provide(orphan.domain());

            Node cleanupInput = seeded.resultingBody();
            cleanupInput.getContracts().getProperties().remove("orphan");
            assertNotNull(cleanupInput.getAsNode(
                    "/contracts/checkpoint/entries/source"));
            assertNotNull(cleanupInput.getAsNode(
                    "/contracts/checkpoint/entries/orphan"));
            int traceBefore = runtime.gasTrace().size();

            ManagedCheckpointSettlement cleaned = runtime.settleCheckpoints(
                    cleanupInput,
                    Collections.<ManagedCheckpointSettlementEntry>emptyList(),
                    cleanupContexts(),
                    context(null, null, "cleanup.revalidate"));

            List<GasTraceEntry> cleanupIdentity = semanticIdentityTrace(
                    runtime.gasTrace().subList(
                            traceBefore, runtime.gasTrace().size()),
                    "/contracts/checkpoint/entries/orphan");
            assertEquals(
                    Arrays.asList(
                            "nodeIdentityEstablished",
                            "objectMemberRebuilt",
                            "directIdentityHashBlock",
                            "nodeIdentityEstablished",
                            "objectMemberRebuilt",
                            "directIdentityHashBlock",
                            "nodeIdentityEstablished",
                            "objectMemberRebuilt",
                            "directIdentityHashBlock",
                            "nodeIdentityEstablished",
                            "objectMemberRebuilt",
                            "directIdentityHashBlock"),
                    counters(cleanupIdentity));
            assertEquals(
                    Arrays.asList(
                            1L, 1L, 2L,
                            1L, 2L, 3L,
                            1L, 2L, 3L,
                            1L, 2L, 2L),
                    quantities(cleanupIdentity));
            assertEquals(21L, subtotal(cleanupIdentity));
            assertNotNull(cleaned.resultingBody().getAsNode(
                    "/contracts/checkpoint/entries/source"));
            Node cleanedEntries = cleaned.resultingBody().getAsNode(
                    "/contracts/checkpoint/entries");
            assertTrue(cleanedEntries.getProperties() == null
                    || !cleanedEntries.getProperties().containsKey(
                    "orphan"));
            assertNotNull(cleanupInput.getAsNode(
                    "/contracts/checkpoint/entries/orphan"));

            List<GasTraceEntry> successfulCleanup = new ArrayList<GasTraceEntry>(
                    runtime.gasTrace().subList(
                            traceBefore, runtime.gasTrace().size()));
            long cleanupGas = subtotal(successfulCleanup);
            Node boundedInput = cleanupInput.clone();
            try (ManagedDocumentStepRuntime bounded =
                         new ManagedDocumentStepRuntime(
                                 processor,
                                 cleanupGas - 1L,
                                 Collections.<String, Long>emptyMap(),
                                 noOpContinuation())) {
                assertThrows(
                        GasLimitExceededException.class,
                        () -> bounded.settleCheckpoints(
                                boundedInput,
                                Collections
                                        .<ManagedCheckpointSettlementEntry>
                                                emptyList(),
                                cleanupContexts(),
                                context(
                                        null,
                                        null,
                                        "cleanup.revalidate")));
                assertTracePrefix(
                        successfulCleanup,
                        bounded.gasTrace());
                assertTrue(bounded.totalGas() < cleanupGas);
                assertNotNull(boundedInput.getAsNode(
                        "/contracts/checkpoint/entries/source"));
                assertNotNull(boundedInput.getAsNode(
                        "/contracts/checkpoint/entries/orphan"));
            }
        } finally {
            processor.close();
            domainStore.close();
        }
    }

    private static List<String> batchTargets(
            ManagedCheckpointSettlementBatch batch) {
        List<String> result = new ArrayList<String>();
        for (ManagedCheckpointSettlementBatch.Mutation mutation
                : batch.mutations()) {
            result.add(mutation.targetManagedScopeIdentity());
        }
        return result;
    }

    private static List<String> batchRawKeys(
            ManagedCheckpointSettlementBatch batch) {
        List<String> result = new ArrayList<String>();
        for (ManagedCheckpointSettlementBatch.Mutation mutation
                : batch.mutations()) {
            result.add(mutation.mutation().rawChannelKey());
        }
        return result;
    }

    private static List<ManagedCheckpointMutation.Operation> batchOperations(
            ManagedCheckpointSettlementBatch batch) {
        List<ManagedCheckpointMutation.Operation> result =
                new ArrayList<ManagedCheckpointMutation.Operation>();
        for (ManagedCheckpointSettlementBatch.Mutation mutation
                : batch.mutations()) {
            result.add(mutation.mutation().operation());
        }
        return result;
    }

    private static List<String> reasons(List<GasTraceEntry> trace) {
        List<String> result = new ArrayList<String>();
        for (GasTraceEntry entry : trace) {
            result.add(entry.reason());
        }
        return result;
    }

    private static List<String> documentIds(List<GasTraceEntry> trace) {
        List<String> result = new ArrayList<String>();
        for (GasTraceEntry entry : trace) {
            result.add(entry.documentId());
        }
        return result;
    }

    private static List<GasTraceEntry> semanticIdentityTrace(
            List<GasTraceEntry> trace,
            String logicalPath) {
        List<GasTraceEntry> result = new ArrayList<GasTraceEntry>();
        for (GasTraceEntry entry : trace) {
            if ("semantic".equals(entry.namespace())
                    && logicalPath.equals(entry.logicalPath())) {
                result.add(entry);
            }
        }
        return result;
    }

    private static List<String> counters(List<GasTraceEntry> trace) {
        List<String> result = new ArrayList<String>();
        for (GasTraceEntry entry : trace) {
            result.add(entry.counter());
        }
        return result;
    }

    private static List<Long> quantities(List<GasTraceEntry> trace) {
        List<Long> result = new ArrayList<Long>();
        for (GasTraceEntry entry : trace) {
            result.add(Long.valueOf(entry.quantity()));
        }
        return result;
    }

    private static long subtotal(List<GasTraceEntry> trace) {
        long result = 0L;
        for (GasTraceEntry entry : trace) {
            result += entry.subtotal();
        }
        return result;
    }

    private static void assertTracePrefix(
            List<GasTraceEntry> complete,
            List<GasTraceEntry> prefix) {
        assertTrue(prefix.size() < complete.size());
        for (int index = 0; index < prefix.size(); index++) {
            GasTraceEntry expected = complete.get(index);
            GasTraceEntry actual = prefix.get(index);
            assertEquals(expected.namespace(), actual.namespace());
            assertEquals(expected.counter(), actual.counter());
            assertEquals(expected.quantity(), actual.quantity());
            assertEquals(expected.weight(), actual.weight());
            assertEquals(expected.subtotal(), actual.subtotal());
            assertEquals(expected.documentId(), actual.documentId());
            assertEquals(expected.scopePath(), actual.scopePath());
            assertEquals(expected.activationGeneration(),
                    actual.activationGeneration());
            assertEquals(expected.componentGeneration(),
                    actual.componentGeneration());
            assertEquals(expected.contractKey(), actual.contractKey());
            assertEquals(expected.logicalPath(), actual.logicalPath());
            assertEquals(expected.workOccurrenceId(),
                    actual.workOccurrenceId());
            assertEquals(expected.reason(), actual.reason());
        }
    }

    private static long counter(
            List<GasTraceEntry> trace,
            String counter) {
        long quantity = 0L;
        for (GasTraceEntry entry : trace) {
            if (counter.equals(entry.counter())) {
                quantity += entry.quantity();
            }
        }
        return quantity;
    }

    private static GasTraceEntry lastTrace(
            List<GasTraceEntry> trace,
            String counter) {
        List<GasTraceEntry> matching = traces(trace, counter);
        return matching.get(matching.size() - 1);
    }

    private static List<GasTraceEntry> traces(
            List<GasTraceEntry> trace,
            String counter) {
        List<GasTraceEntry> result = new ArrayList<GasTraceEntry>();
        for (GasTraceEntry entry : trace) {
            if (counter.equals(entry.counter())) {
                result.add(entry);
            }
        }
        return result;
    }

    private static GasChargeContext context(
            String contractKey,
            String workOccurrenceId,
            String reason) {
        return context("a", contractKey, workOccurrenceId, reason);
    }

    private static GasChargeContext context(
            String documentId,
            String contractKey,
            String workOccurrenceId,
            String reason) {
        return GasChargeContext.closure(
                documentId,
                "/",
                Long.valueOf(0L),
                Long.valueOf(1L),
                contractKey,
                null,
                workOccurrenceId,
                reason);
    }

    private static ManagedCheckpointCleanupContextFactory cleanupContexts() {
        return (rawKey, ordinal) -> context(
                rawKey,
                null,
                "checkpoint-settlement.0.cleanup." + ordinal);
    }

    private static ManagedDocumentStepContinuation noOpContinuation() {
        return new ManagedDocumentStepContinuation() {
            @Override
            public void afterPatch(
                    String scopePath,
                    Node currentDocument,
                    FrozenJsonPatch patch,
                    List<DocumentUpdateOccurrence> updates) {
                // This characterization performs only processor Direct Writes.
            }

            @Override
            public void onApplicationEvent(
                    String scopePath,
                    String originContractKey,
                    ExactEventIdentityEvidence exactEvent) {
                throw new AssertionError("Unexpected application event");
            }

            @Override
            public void onTerminationRequested(
                    String scopePath,
                    String cause,
                    String reason) {
                throw new AssertionError("Unexpected termination");
            }
        };
    }

    private static Node document() {
        return new Node()
                .name("C34-shaped Root A")
                .contracts(new Node().properties(
                        "source",
                        new Node()
                                .type(new Node().blueId(SOURCE_BLUE_ID))
                                .properties(
                                        "subscriptionKey",
                                        new Node().value("root"))));
    }

    private static Node documentWithSource(Node source) {
        return new Node()
                .name("Channel representation parity Root")
                .contracts(new Node().properties("source", source));
    }

    private static Node twoSourceDocument() {
        return new Node()
                .name("Canonical source-order Root")
                .contracts(new Node()
                        .properties("zSource", sourceContract())
                        .properties("aSource", sourceContract()));
    }

    private static Node sourceAndRetiredDocument(String name) {
        return new Node()
                .name(name)
                .contracts(new Node()
                        .properties("source", sourceContract())
                        .properties("retired", sourceContract()));
    }

    private static Node sourceAndOrphanDocument() {
        return new Node()
                .name("Checkpoint cleanup projection Root")
                .contracts(new Node()
                        .properties("source", sourceContract())
                        .properties("orphan", sourceContract()));
    }

    private static Node sourceContract() {
        return new Node()
                .type(new Node().blueId(SOURCE_BLUE_ID))
                .properties(
                        "subscriptionKey",
                        new Node().value("root"));
    }

    private static Node event(String value) {
        return new Node()
                .properties("value", new Node().value(value))
                .properties(
                        "subscriptionKey",
                        new Node().value("root"));
    }

    private static ManagedExternalDeliveryClassification classify(
            ManagedDocumentStepRuntime runtime,
            Node exactRoot,
            String rawChannelKey,
            Node exactEvent,
            GasChargeContext context) {
        return runtime.classifyExternalDelivery(
                exactRoot,
                rawChannelKey,
                ExactEventIdentityEvidence.verify(
                        null,
                        exactEvent,
                        DirectBlueIdCalculator.calculateBlueId(exactEvent),
                        null),
                context);
    }

    /** Mutable conversion model used by the focused processor registry. */
    public static final class SourceChannel extends ChannelContract {
        private String subscriptionKey;

        /** @return configured deterministic subscription key */
        public String getSubscriptionKey() { return subscriptionKey; }

        /** @param subscriptionKey deterministic subscription key */
        public void setSubscriptionKey(String subscriptionKey) {
            this.subscriptionKey = subscriptionKey;
        }
    }

    private static final class SourceProcessor
            implements ChannelProcessor<SourceChannel> {

        private final ExternalChannelSubscriptionFunctions<SourceChannel>
                functions =
                new ExternalChannelSubscriptionFunctions<SourceChannel>() {
                    @Override
                    public List<String> channelKeys(SourceChannel channel) {
                        return Collections.singletonList(
                                channel.getSubscriptionKey());
                    }

                    @Override
                    public boolean preselects(
                            SourceChannel channel,
                            Node exactEvent) {
                        return true;
                    }

                    @Override
                    public boolean accepts(
                            SourceChannel channel,
                            Node exactEvent) {
                        return true;
                    }

                    @Override
                    public Node payload(
                            SourceChannel channel,
                            Node exactEvent) {
                        return exactEvent.clone();
                    }

                    @Override
                    public String checkpointDomainDiscriminator(
                            SourceChannel channel) {
                        return "managed-root-source-v1";
                    }
                };

        @Override
        public Class<SourceChannel> contractType() {
            return SourceChannel.class;
        }

        @Override
        public ExternalChannelSubscriptionFunctions<SourceChannel>
        externalSubscriptionFunctions() {
            return functions;
        }
    }

    private static final class ExactDomainSnapshotManager
            implements ProcessingSnapshotManager {

        private final Map<String, FrozenNode> exactValues =
                new LinkedHashMap<String, FrozenNode>();
        private final BlueLanguage language;
        private final LanguageProcessing.Scope scope;
        private final ProcessingSnapshotManager delegate;

        private ExactDomainSnapshotManager() {
            language = BlueLanguage.builder()
                    .nodeProvider(new SequentialNodeProvider(
                            new BasicNodeProvider(SOURCE_TYPE),
                            BlueRuntimeTypeRegistry.getDefault()
                                    .asProcessorSnapshotProvider()))
                    .build();
            scope = language.processing().openScope();
            delegate = new LanguageProcessingSnapshotManager(scope);
        }

        private void provide(ManagedCheckpointDomain domain) {
            exactValues.put(
                    domain.blueId(),
                    FrozenNode.fromNode(domain.exactValue()));
        }

        @Override
        public FrozenNode materializeVerifiedExactReference(
                FrozenNode reference) {
            FrozenNode exact = exactValues.get(reference.getReferenceBlueId());
            return exact != null
                    ? exact
                    : delegate.materializeVerifiedExactReference(reference);
        }

        @Override
        public ResolvedSnapshot fromDocument(Node document) {
            return delegate.fromDocument(document);
        }

        @Override
        public ResolvedSnapshot fromDocumentTransient(Node document) {
            return delegate.fromDocumentTransient(document);
        }

        @Override
        public blue.language.identity.CanonicalTypeIdentityEvidence
        resolveTypeDeclarationIdentity(Node declaration) {
            return delegate.resolveTypeDeclarationIdentity(declaration);
        }

        @Override
        public ResolvedSnapshot fromDocumentPreservingPaths(
                Node document,
                java.util.Collection<String> preservedPaths) {
            return delegate.fromDocumentPreservingPaths(
                    document, preservedPaths);
        }

        @Override
        public ResolvedSnapshot fromDocumentTransientPreservingPaths(
                Node document,
                java.util.Collection<String> preservedPaths) {
            return delegate.fromDocumentTransientPreservingPaths(
                    document, preservedPaths);
        }

        @Override
        public blue.language.merge.TypeEvidenceResolution
        materializeVerifiedTypeReference(FrozenNode reference) {
            return delegate.materializeVerifiedTypeReference(reference);
        }

        @Override
        public ResolvedSnapshot applyPatch(
                ResolvedSnapshot snapshot,
                JsonPatch patch) {
            return delegate.applyPatch(snapshot, patch);
        }

        private void close() {
            scope.close();
            language.close();
        }
    }
}
