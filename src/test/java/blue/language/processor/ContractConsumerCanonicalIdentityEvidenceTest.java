package blue.language.processor;

import blue.language.Blue;
import blue.language.conformance.ConformancePlan;
import blue.language.identity.CanonicalTypeIdentityEvidence;
import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.identity.NodeToBlueIdInput;
import blue.language.merge.ResolvedSnapshot;
import blue.language.model.Node;
import blue.language.processor.model.EmbeddedNodeChannel;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.model.TriggeredEventChannel;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ContractConsumerCanonicalIdentityEvidenceTest {

    @Test
    void shouldHashExactInlineEventSourceWithoutCollapsingMixedType() {
        // given
        String unrelatedBlueId = DirectBlueIdCalculator.calculateBlueId(
                new Node().name("Unrelated dispatch event type"));
        Node eventPattern = new Node()
                .type(new Node()
                        .blueId(unrelatedBlueId)
                        .name("Inline dispatch event type"))
                .properties("payload", new Node().value("stable"));
        try (Blue blue = ProcessorTestSupport.blue()) {
            ProcessingSnapshotManager manager =
                    blue.getDocumentProcessor().snapshotManager();
            String expectedBlueId = manager
                    .fromDocumentTransientForCanonicalIdentity(
                            NodeToBlueIdInput.stripResolvedBlueIdMetadata(
                                    eventPattern.clone()))
                    .blueId();
            ContractSnapshotFactory factory =
                    new ContractSnapshotFactory(manager);
            EffectiveContractSnapshot.Builder builder = factory.begin(
                    "/", "events", expectedBlueId, 0,
                    Collections.<String>emptyList());
            builder.role("test-channel");

            // when
            factory.addEventDispatch(builder, eventPattern);
            EffectiveContractSnapshot snapshot = builder.build();

            // then
            assertEquals(
                    expectedBlueId,
                    snapshot.dispatchFields().get(
                            EffectiveContractSnapshotConstants
                                    .DispatchField.EVENT));
            assertTrue(snapshot.deterministicDependencyNodeBlueIds()
                    .contains(expectedBlueId));
        }
    }

    @Test
    void shouldRetainExactSourceEventForManagedChannels() {
        // given
        Node eventSource = new Node()
                .properties("kind", new Node().value("managed-event"));
        String eventBlueId = DirectBlueIdCalculator.calculateBlueId(
                eventSource);
        Node triggered = managedChannel(
                RuntimeBlueIds.TRIGGERED_EVENT_CHANNEL,
                eventBlueId);
        Node embedded = managedChannel(
                RuntimeBlueIds.EMBEDDED_NODE_CHANNEL,
                eventBlueId)
                .properties("sourcePath", new Node().value("/child"));
        Node document = new Node().contracts(
                new Node().properties(
                        "triggered", triggered,
                        "embeddedChannel", embedded));

        try (Blue blue = ProcessorTestSupport.blue(blueId ->
                eventBlueId.equals(blueId)
                        ? Collections.singletonList(eventSource.clone())
                        : null)) {
            DocumentProcessor processor = blue.getDocumentProcessor();
            ResolvedSnapshot resolved = processor.snapshotManager()
                    .fromDocumentTransient(document);

            // when
            ContractBundle bundle = processor.contractLoader().load(
                    resolved, "/");

            // then
            assertFalse(resolved.resolvedAt(
                    "/contracts/triggered/event").isReferenceOnly());
            TriggeredEventChannel retainedTriggered =
                    (TriggeredEventChannel) bundle.channel("triggered");
            EmbeddedNodeChannel retainedEmbedded =
                    (EmbeddedNodeChannel) bundle.channel(
                            "embeddedChannel");
            assertEquals(eventBlueId, retainedTriggered.getEvent().getBlueId());
            assertTrue(retainedTriggered.getEvent().isReferenceOnly());
            assertEquals(eventBlueId, retainedEmbedded.getEvent().getBlueId());
            assertTrue(retainedEmbedded.getEvent().isReferenceOnly());
            assertTrue(bundle.channelBinding("triggered")
                    .node().getProperties().get("event").isReferenceOnly());
            assertTrue(bundle.channelBinding("embeddedChannel")
                    .node().getProperties().get("event").isReferenceOnly());
            assertEquals(
                    eventBlueId,
                    bundle.effectiveContractSnapshot("triggered")
                            .dispatchFields().get(
                                    EffectiveContractSnapshotConstants
                                            .DispatchField.EVENT));
            assertEquals(
                    eventBlueId,
                    bundle.effectiveContractSnapshot("embeddedChannel")
                            .dispatchFields().get(
                                    EffectiveContractSnapshotConstants
                                            .DispatchField.EVENT));
        }
    }

    @Test
    void shouldTreatInheritedEventDocumentationAsNoMatcher() {
        // given
        Node triggered = new Node().type(new Node().blueId(
                RuntimeBlueIds.TRIGGERED_EVENT_CHANNEL));
        Node embedded = new Node()
                .type(new Node().blueId(
                        RuntimeBlueIds.EMBEDDED_NODE_CHANNEL))
                .properties("sourcePath", new Node().value("/child"));
        Node document = new Node().contracts(
                new Node().properties(
                        "triggered", triggered,
                        "embeddedChannel", embedded));

        try (Blue blue = ProcessorTestSupport.blue()) {
            DocumentProcessor processor = blue.getDocumentProcessor();
            ResolvedSnapshot resolved = processor.snapshotManager()
                    .fromDocumentTransient(document);

            // when
            ContractBundle bundle = processor.contractLoader().load(
                    resolved, "/");

            // then
            assertEquals(
                    "Optional event matcher.",
                    resolved.resolvedAt("/contracts/triggered/event")
                            .getDescription());
            assertNull(((TriggeredEventChannel) bundle.channel("triggered"))
                    .getEvent());
            assertNull(((EmbeddedNodeChannel) bundle.channel(
                    "embeddedChannel")).getEvent());
            assertFalse(bundle.effectiveContractSnapshot("triggered")
                    .dispatchFields().containsKey(
                            EffectiveContractSnapshotConstants.DispatchField
                                    .EVENT));
            assertFalse(bundle.effectiveContractSnapshot("embeddedChannel")
                    .dispatchFields().containsKey(
                            EffectiveContractSnapshotConstants.DispatchField
                                    .EVENT));
        }
    }

    @Test
    void shouldRecoverInheritedManagedEventFromSourceContribution() {
        // given
        Node eventSource = new Node().properties(
                "kind", new Node().value("inherited-managed-event"));
        String eventBlueId = DirectBlueIdCalculator.calculateBlueId(
                eventSource);
        Node inheritedChannel = new Node()
                .type(new Node().blueId(
                        RuntimeBlueIds.TRIGGERED_EVENT_CHANNEL))
                .properties("event", eventSource.clone());
        Node scopeType = new Node()
                .name("Scope With Inherited Managed Event")
                .contracts(new Node().properties(
                        "triggered", inheritedChannel));
        String scopeTypeBlueId = DirectBlueIdCalculator.calculateBlueId(
                scopeType);
        Node document = new Node().type(
                new Node().blueId(scopeTypeBlueId));

        try (Blue blue = ProcessorTestSupport.blue(blueId ->
                scopeTypeBlueId.equals(blueId)
                        ? Collections.singletonList(scopeType.clone())
                        : null)) {
            DocumentProcessor processor = blue.getDocumentProcessor();
            ResolvedSnapshot resolved = processor.snapshotManager()
                    .fromDocumentTransient(document);

            // when
            ContractBundle bundle = processor.contractLoader().load(
                    resolved, "/");

            // then
            TriggeredEventChannel channel = (TriggeredEventChannel)
                    bundle.channel("triggered");
            assertNull(channel.getEvent().getBlueId());
            assertEquals(
                    "inherited-managed-event",
                    channel.getEvent().getProperties().get("kind")
                            .getValue());
            assertEquals(
                    Collections.singletonList(
                            DirectBlueIdCalculator.calculateBlueId(
                                    inheritedChannel)),
                    bundle.effectiveContractSnapshot("triggered")
                            .sourceContributionNodeBlueIds());
            assertEquals(
                    eventBlueId,
                    bundle.effectiveContractSnapshot("triggered")
                            .dispatchFields().get(
                                    EffectiveContractSnapshotConstants
                                            .DispatchField.EVENT));
            assertEquals(
                    "inherited-managed-event",
                    bundle.channelBinding("triggered").node()
                            .getProperties().get("event")
                            .getProperties().get("kind").getValue());
        }
    }

    @Test
    void shouldRejectEffectiveManagedEventWithoutExactSourceContribution() {
        // given
        Node source = new Node().contracts(new Node().properties(
                "triggered",
                new Node().type(new Node().blueId(
                        RuntimeBlueIds.TRIGGERED_EVENT_CHANNEL))));
        FrozenNode canonicalRoot = FrozenNode.fromNode(source);
        Node resolved = source.clone();
        resolved.getContracts().getProperties().get("triggered")
                .properties(
                        "event",
                        new Node().properties(
                                "kind", new Node().value("unproved")));
        ResolvedSnapshot mismatched = new ResolvedSnapshot(
                canonicalRoot,
                FrozenNode.fromResolvedNode(resolved),
                canonicalRoot.blueId());

        try (Blue blue = ProcessorTestSupport.blue()) {
            // when
            Executable load = () -> blue.getDocumentProcessor()
                    .contractLoader()
                    .load(mismatched, "/");

            // then
            MustUnderstandFailureException failure = assertThrows(
                    MustUnderstandFailureException.class,
                    load);
            assertEquals(
                    ProcessorErrorCategory.InvalidContractBinding,
                    failure.errorCategory());
            assertTrue(failure.getMessage().contains(
                    "Cannot establish exact event Source"));
        }
    }

    @Test
    void shouldRejectEventPatternWithoutCanonicalizationBoundary() {
        // given
        ContractSnapshotFactory factory =
                new ContractSnapshotFactory(null);
        EffectiveContractSnapshot.Builder builder = factory.begin(
                "/", "events", "type", 0,
                Collections.<String>emptyList());

        // when
        Executable dispatch = () -> factory.addEventDispatch(
                builder,
                new Node().type(
                        new Node().name("Unproved inline type")));

        // then
        IllegalStateException failure = assertThrows(
                IllegalStateException.class, dispatch);
        assertTrue(failure.getMessage().contains(
                "requires Language canonicalization evidence"));
    }

    @Test
    void shouldAttributeResolvedMetadataThroughCanonicalTypeEvidence() {
        // given
        Node completedType = new Node().name("Completed generated type");
        String canonicalTypeBlueId = DirectBlueIdCalculator.calculateBlueId(
                new Node().name("Canonical generated type"));
        FrozenNode canonicalRoot = FrozenNode.empty();
        FrozenNode resolvedRoot = FrozenNode.fromResolvedNode(
                new Node().type(completedType.clone()));
        BatchPatchRecord record = record(canonicalRoot, resolvedRoot);
        ConformancePlan prefix = ConformancePlan.generalized(
                canonicalRoot,
                resolvedRoot,
                Collections.emptyList(),
                Collections.singletonList("/type"),
                true);

        // when
        List<BatchPatchResult.GeneralizationMetadataWrite> writes =
                GeneralizationMetadataAttributor.attribute(
                        canonicalRoot,
                        resolvedRoot,
                        Collections.singletonList("/type"),
                        Collections.singletonList(record),
                        lookup(completedType, canonicalTypeBlueId),
                        ignored -> prefix);

        // then
        assertEquals(1, writes.size());
        assertTrue(writes.get(0).value().isReferenceOnly());
        assertEquals(
                canonicalTypeBlueId,
                writes.get(0).value().getReferenceBlueId());
        assertEquals(0, writes.get(0).requiringPatchIndex());
    }

    @Test
    void shouldRejectResolvedMetadataWithoutCanonicalTypeEvidence() {
        // given
        Node completedType = new Node().name("Unproved generated type");
        FrozenNode canonicalRoot = FrozenNode.empty();
        FrozenNode resolvedRoot = FrozenNode.fromResolvedNode(
                new Node().type(completedType));
        BatchPatchRecord record = record(canonicalRoot, resolvedRoot);
        ConformancePlan prefix = ConformancePlan.generalized(
                canonicalRoot,
                resolvedRoot,
                Collections.emptyList(),
                Collections.singletonList("/type"),
                true);

        // when
        Executable attribution = () -> GeneralizationMetadataAttributor.attribute(
                canonicalRoot,
                resolvedRoot,
                Collections.singletonList("/type"),
                Collections.singletonList(record),
                CanonicalTypeIdentityLookup.incomplete(),
                ignored -> prefix);

        // then
        assertThrows(
                IllegalStateException.class,
                attribution);
    }

    private static BatchPatchRecord record(
            FrozenNode canonicalRoot,
            FrozenNode resolvedRoot) {
        ImmutableJsonPatch patch = ImmutableJsonPatch.from(
                JsonPatch.add("/authored", new Node().value("value")),
                canonicalRoot,
                resolvedRoot);
        return new BatchPatchRecord(
                patch,
                ImmutablePatchPlanner.forFrozen(canonicalRoot)
                        .plan("/", patch),
                ImmutablePatchPlanner.forFrozen(resolvedRoot)
                        .plan("/", patch),
                null,
                FrozenNode.fromNode(new Node().value("value")),
                true,
                null,
                false);
    }

    private static Node managedChannel(
            String typeBlueId,
            String eventBlueId) {
        return new Node()
                .type(new Node().blueId(typeBlueId))
                .properties("event", new Node().blueId(eventBlueId));
    }

    private static CanonicalTypeIdentityLookup lookup(
            Node completedType,
            String canonicalTypeBlueId) {
        FrozenNode.ResolvedStructuralKey structuralKey =
                FrozenNode.fromResolvedNode(completedType)
                        .resolvedStructuralKey();
        return new CanonicalTypeIdentityLookup() {
            @Override
            public boolean hasCompleteCoverage() {
                return true;
            }

            @Override
            public Optional<String> findCanonicalTypeBlueId(Node type) {
                if (type.isReferenceOnly()) {
                    return Optional.of(type.getBlueId());
                }
                return structuralKey.equals(
                        FrozenNode.fromResolvedNode(type)
                                .resolvedStructuralKey())
                        ? Optional.of(canonicalTypeBlueId)
                        : Optional.empty();
            }

            @Override
            public Optional<CanonicalTypeIdentityEvidence>
            findCanonicalTypeIdentityEvidence(Node type) {
                return findCanonicalTypeBlueId(type).map(blueId ->
                        type.isReferenceOnly()
                                ? CanonicalTypeIdentityEvidence
                                .referenceSource(blueId)
                                : CanonicalTypeIdentityEvidence
                                .identityOnly(blueId));
            }

            @Override
            public String requireCanonicalTypeBlueId(Node type) {
                return findCanonicalTypeBlueId(type)
                        .orElseThrow(() -> new IllegalStateException(
                                "Missing test identity evidence"));
            }
        };
    }
}
