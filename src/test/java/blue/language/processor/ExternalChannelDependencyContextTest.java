package blue.language.processor;

import blue.language.Blue;
import blue.language.provider.NodeProvider;
import blue.language.model.Node;
import blue.language.processor.model.ChannelContract;
import blue.language.processor.model.ChannelEventCheckpoint;
import blue.language.processor.model.HandlerContract;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.merge.ResolvedSnapshot;
import blue.language.identity.DirectBlueIdCalculator;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static blue.language.processor.FailureCapture.captureFailure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ExternalChannelDependencyContextTest {

    private static final Node LEAF_TYPE =
            new Node().name("Dependency Leaf Channel");
    private static final String LEAF_TYPE_BLUE_ID =
            DirectBlueIdCalculator.calculateBlueId(LEAF_TYPE);
    private static final Node ASSIGNABLE_BASE_TYPE =
            new Node()
                    .name("Dependency Assignable Base Channel")
                    .type(reference(
                            RuntimeBlueIds.EXTERNAL_CHANNEL))
                    .properties(
                            "assignableFamilyMarker",
                            new Node().value("dependency-family"));
    private static final String ASSIGNABLE_BASE_TYPE_BLUE_ID =
            DirectBlueIdCalculator.calculateBlueId(
                    ASSIGNABLE_BASE_TYPE);
    private static final Node ASSIGNABLE_DIRECT_TYPE =
            new Node()
                    .name("Dependency Assignable Direct Channel")
                    .type(reference(
                            ASSIGNABLE_BASE_TYPE_BLUE_ID));
    private static final String ASSIGNABLE_DIRECT_TYPE_BLUE_ID =
            DirectBlueIdCalculator.calculateBlueId(
                    ASSIGNABLE_DIRECT_TYPE);
    private static final Node ASSIGNABLE_DEEP_TYPE =
            new Node()
                    .name("Dependency Assignable Deep Channel")
                    .type(reference(
                            ASSIGNABLE_DIRECT_TYPE_BLUE_ID));
    private static final String ASSIGNABLE_DEEP_TYPE_BLUE_ID =
            DirectBlueIdCalculator.calculateBlueId(
                    ASSIGNABLE_DEEP_TYPE);
    private static final Node AGGREGATE_TYPE =
            new Node().name("Dependency Aggregate Channel");
    private static final String AGGREGATE_TYPE_BLUE_ID =
            DirectBlueIdCalculator.calculateBlueId(AGGREGATE_TYPE);
    private static final Node OTHER_TYPE =
            new Node().name("Dependency Other Channel");
    private static final String OTHER_TYPE_BLUE_ID =
            DirectBlueIdCalculator.calculateBlueId(OTHER_TYPE);
    private static final Node HANDLER_TYPE =
            new Node().name("Dependency Deferred Handler");
    private static final String HANDLER_TYPE_BLUE_ID =
            DirectBlueIdCalculator.calculateBlueId(HANDLER_TYPE);
    private static final Node RECORDING_HANDLER_TYPE =
            new Node().name("Dependency Recording Handler");
    private static final String RECORDING_HANDLER_TYPE_BLUE_ID =
            DirectBlueIdCalculator.calculateBlueId(
                    RECORDING_HANDLER_TYPE);
    private static final ExternalOrderKey TEST_ORDER =
            ExternalOrderKey.of(
                    Collections.<Object>singletonList(
                            "dependency-test-order"));

    @Test
    void shouldVerifyExplicitAndTransitiveMemberReplacementRotateOuterSnapshots() {
        // given
        Node before = root(
                aggregate("outer", "middle", "explicit"),
                aggregate("middle", "leaf", "explicit"),
                leaf("leaf", "old-topic", "old-domain", "timeline-a"));
        Node after = root(
                aggregate("outer", "middle", "explicit"),
                aggregate("middle", "leaf", "explicit"),
                leaf("leaf", "old-topic", "new-domain", "timeline-a"));

        // when
        try (Blue blue = runtime()) {
            SubscriptionDelta delta = validate(
                    blue,
                    before,
                    after,
                    "/contracts/leaf");
            SubscriptionDelta.Entry outerBefore =
                    entry(delta.removed(), "outer");
            SubscriptionDelta.Entry outerAfter =
                    entry(delta.added(), "outer");

            // then
            for (String key : Arrays.asList(
                    "leaf", "middle", "outer")) {
                assertNotNull(entry(delta.removed(), key));
                assertNotNull(entry(delta.added(), key));
            }
            assertEquals(
                    Collections.singletonList("old-topic"),
                    outerBefore.subscriptionKeys());
            assertEquals(
                    Collections.singletonList("old-topic"),
                    outerAfter.subscriptionKeys());
            assertNotEquals(
                    outerBefore.checkpointDomainBlueId(),
                    outerAfter.checkpointDomainBlueId());
            assertEquals(
                    Arrays.asList("middle", "leaf"),
                    dependencyKeys(
                            outerAfter.dependencies()));
        }
    }

    @Test
    void shouldVerifyFilteredFamilyIsShallowTracksEmptyAdditionAndIgnoresOtherTypes() {
        // given
        Node beforeEmpty = root(
                aggregate("all-a", null, "family"),
                aggregate("all-b", null, "family"));
        Node afterAddition = root(
                aggregate("all-a", null, "family"),
                aggregate("all-b", null, "family"),
                leaf("leaf", "topic", "leaf-domain", "timeline-a"));
        Node beforeOther = root(
                aggregate("all", null, "family"),
                leaf("leaf", "topic", "leaf-domain", "timeline-a"),
                other("other", "old-other", "old-domain"));
        Node afterOther = root(
                aggregate("all", null, "family"),
                leaf("leaf", "topic", "leaf-domain", "timeline-a"),
                other("other", "new-other", "new-domain"));
        try (Blue blue = runtime()) {
            // when
            SubscriptionDelta addition = validate(
                    blue,
                    beforeEmpty,
                    afterAddition,
                    "/contracts/leaf");
            SubscriptionDelta unrelated = validate(
                    blue,
                    beforeOther,
                    afterOther,
                    "/contracts/other");
            List<SubscriptionEntryObservation> entryObservations =
                    new ArrayList<>();
            for (String key : Arrays.asList(
                    "all-a", "all-b")) {
                entryObservations.add(
                        new SubscriptionEntryObservation(
                                entry(addition.removed(), key),
                                entry(addition.added(), key)));
            }

            // then
            for (SubscriptionEntryObservation observation
                    : entryObservations) {
                assertNotNull(observation.removed);
                assertNotNull(observation.added);
                assertTrue(observation.removed
                        .dependencies().entries().isEmpty());
                assertEquals(
                        1,
                        observation.removed.dependencies()
                                .typeFamilies().size());
                assertTrue(observation.removed.dependencies()
                        .typeFamilies().get(0)
                        .members().isEmpty());
                assertEquals(
                        Collections.singletonList("leaf"),
                        familyMemberKeys(
                                observation.added.dependencies()
                                        .typeFamilies().get(0)));
            }

            assertFalse(hasEntry(unrelated.removed(), "all"));
            assertFalse(hasEntry(unrelated.added(), "all"));
            assertNotNull(entry(unrelated.removed(), "other"));
            assertNotNull(entry(unrelated.added(), "other"));
        }
    }

    @Test
    void shouldVerifyAssignableFamilyIncludesVerifiedDeepAndInheritedHeadersOnly() {
        // given
        String unavailableBodyBlueId =
                DirectBlueIdCalculator.calculateBlueId(
                        new Node().value(
                                "assignable-unavailable-handler-body"));
        AtomicInteger bodyDemands = new AtomicInteger();
        Node inheritedDeep = typedLeaf(
                "deep",
                ASSIGNABLE_DEEP_TYPE_BLUE_ID,
                "deep-topic",
                "deep-domain",
                "timeline-deep",
                3);
        inheritedDeep.name(null);
        Node scopeType = new Node().contracts(
                new Node().properties(
                        "deep",
                        inheritedDeep));
        String scopeTypeBlueId =
                DirectBlueIdCalculator.calculateBlueId(scopeType);
        NodeProvider provider = blueId -> {
            if (scopeTypeBlueId.equals(blueId)) {
                return Collections.singletonList(
                        scopeType.clone());
            }
            if (unavailableBodyBlueId.equals(blueId)) {
                bodyDemands.incrementAndGet();
            }
            return null;
        };

        try (Blue blue = runtime(provider, true)) {
            Node document = root(
                    aggregate(
                            "all",
                            null,
                            "assignable-headers"),
                    typedLeaf(
                            "base",
                            ASSIGNABLE_BASE_TYPE_BLUE_ID,
                            "base-topic",
                            "base-domain",
                            "timeline-base",
                            1),
                    typedLeaf(
                            "direct",
                            ASSIGNABLE_DIRECT_TYPE_BLUE_ID,
                            "direct-topic",
                            "direct-domain",
                            "timeline-direct",
                            2),
                    other(
                            "unrelated",
                            "other-topic",
                            "other-domain"),
                    new Node()
                            .name("handler")
                            .type(reference(
                                    HANDLER_TYPE_BLUE_ID))
                            .properties(
                                    "channel",
                                    new Node().value("never"))
                            .properties(
                                    "program",
                                    reference(
                                            unavailableBodyBlueId)));
            document.type(reference(scopeTypeBlueId));

            // when
            SubscriptionDelta delta = validate(
                    blue,
                    new Node(),
                    document,
                    "/");
            SubscriptionDelta.Entry aggregate =
                    entry(delta.added(), "all");
            ExternalChannelDependencySnapshot.TypeFamily family =
                    aggregate.dependencies()
                            .typeFamilies().get(0);

            // then
            assertNotNull(aggregate);
            assertTrue(aggregate.dependencies().entries().isEmpty());
            assertEquals(
                    1,
                    aggregate.dependencies()
                            .typeFamilies().size());
            assertEquals(
                    ExternalChannelDependencySnapshot.TypeMatchMode
                            .ASSIGNABLE,
                    family.matchMode());
            assertTrue(family.includesSubtypes());
            assertEquals(
                    ASSIGNABLE_BASE_TYPE_BLUE_ID,
                    family.baseTypeBlueId());
            assertEquals(
                    Arrays.asList("base", "direct", "deep"),
                    familyMemberKeys(family));
            assertEquals(
                    Arrays.asList(
                            ASSIGNABLE_BASE_TYPE_BLUE_ID,
                            ASSIGNABLE_DIRECT_TYPE_BLUE_ID,
                            ASSIGNABLE_DEEP_TYPE_BLUE_ID),
                    familyMemberTypes(family));
            assertEquals(0, bodyDemands.get());
        }
    }

    @Test
    void shouldVerifyAssignableFamilyUsesTheGenericChannelBaseIdentity() {
        // given
        try (Blue blue = runtime()) {
            Node document = root(
                    aggregate(
                            "all",
                            null,
                            "assignable-channel-base"),
                    typedLeaf(
                            "base",
                            ASSIGNABLE_BASE_TYPE_BLUE_ID,
                            "base-topic",
                            "base-domain",
                            "timeline-base",
                            1),
                    typedLeaf(
                            "direct",
                            ASSIGNABLE_DIRECT_TYPE_BLUE_ID,
                            "direct-topic",
                            "direct-domain",
                            "timeline-direct",
                            2),
                    typedLeaf(
                            "deep",
                            ASSIGNABLE_DEEP_TYPE_BLUE_ID,
                            "deep-topic",
                            "deep-domain",
                            "timeline-deep",
                            3),
                    other(
                            "unrelated",
                            "other-topic",
                            "other-domain"));

            // when
            SubscriptionDelta delta = validate(
                    blue,
                    new Node(),
                    document,
                    "/");
            ExternalChannelDependencySnapshot.TypeFamily family =
                    entry(delta.added(), "all")
                            .dependencies()
                            .typeFamilies()
                            .get(0);

            // then
            assertEquals(
                    RuntimeBlueIds.CHANNEL,
                    family.baseTypeBlueId());
            assertEquals(
                    ExternalChannelDependencySnapshot.TypeMatchMode
                            .ASSIGNABLE,
                    family.matchMode());
            assertEquals(
                    Arrays.asList(
                            "base", "direct", "deep"),
                    familyMemberKeys(family));
        }
    }

    @Test
    void shouldRotateAssignableFamilyWhenMemberHeaderChanges() {
        // given
        Node before = assignableFamilyDocument(
                assignableFamilyMember("domain-a", 1));
        Node headerChanged = assignableFamilyDocument(
                assignableFamilyMember("domain-b", 1));

        try (Blue blue = runtime()) {
            // when
            SubscriptionDelta headerChange = validate(
                    blue,
                    before,
                    headerChanged,
                    "/contracts/member");

            // then
            assertAggregateRotates(headerChange);
        }
    }

    @Test
    void shouldRotateAssignableFamilyWhenMemberOrderChanges() {
        // given
        Node before = assignableFamilyDocument(
                assignableFamilyMember("domain-a", 1));
        Node orderChanged = assignableFamilyDocument(
                assignableFamilyMember("domain-a", 9));

        try (Blue blue = runtime()) {
            // when
            SubscriptionDelta orderChange = validate(
                    blue,
                    before,
                    orderChanged,
                    "/contracts/member/order");

            // then
            assertAggregateRotates(orderChange);
        }
    }

    @Test
    void shouldRotateAssignableFamilyWhenMemberIsRetyped() {
        // given
        Node before = assignableFamilyDocument(
                assignableFamilyMember("domain-a", 1));
        Node retyped = assignableFamilyDocument(
                other(
                        "member",
                        "other-topic",
                        "other-domain"));

        try (Blue blue = runtime()) {
            // when
            SubscriptionDelta retyping = validate(
                    blue,
                    before,
                    retyped,
                    "/contracts/member");

            // then
            assertAggregateRotates(retyping);
        }
    }

    @Test
    void shouldRotateAssignableFamilyWhenMemberIsRemoved() {
        // given
        Node before = assignableFamilyDocument(
                assignableFamilyMember("domain-a", 1));
        Node removed = assignableFamilyDocument(null);

        try (Blue blue = runtime()) {
            // when
            SubscriptionDelta removal = validate(
                    blue,
                    before,
                    removed,
                    "/contracts/member");

            // then
            assertAggregateRotates(removal);
        }
    }

    @Test
    void shouldVerifyFamilyReplacementAndRetypingRotateExactMembership() {
        // given
        Node before = root(
                aggregate("all", null, "family"),
                leaf(
                        "member",
                        "topic",
                        "old-domain",
                        "timeline-a"));
        Node replaced = root(
                aggregate("all", null, "family"),
                leaf(
                        "member",
                        "topic",
                        "new-domain",
                        "timeline-a"));
        Node retyped = root(
                aggregate("all", null, "family"),
                other(
                        "member",
                        "other-topic",
                        "other-domain"));

        try (Blue blue = runtime()) {
            // when
            SubscriptionDelta replacement = validate(
                    blue,
                    before,
                    replaced,
                    "/contracts/member");
            SubscriptionDelta.Entry removed =
                    entry(replacement.removed(), "all");
            SubscriptionDelta.Entry added =
                    entry(replacement.added(), "all");
            SubscriptionDelta retyping = validate(
                    blue,
                    replaced,
                    retyped,
                    "/contracts/member");
            SubscriptionDelta.Entry afterRetype =
                    entry(retyping.added(), "all");

            // then
            assertNotNull(removed);
            assertNotNull(added);
            assertEquals(
                    removed.subscriptionKeys(),
                    added.subscriptionKeys());
            assertNotEquals(
                    removed.checkpointDomainBlueId(),
                    added.checkpointDomainBlueId());
            assertEquals(
                    Collections.singletonList("member"),
                    familyMemberKeys(
                            added.dependencies()
                                    .typeFamilies().get(0)));

            assertNotNull(
                    entry(retyping.removed(), "all"));
            assertNotNull(afterRetype);
            assertTrue(afterRetype.dependencies()
                    .typeFamilies().get(0)
                    .members().isEmpty());
            assertEquals(
                    Collections.singletonList(
                            "empty-family:all"),
                    afterRetype.subscriptionKeys());
        }
    }

    @Test
    void shouldVerifySelectedMemberEvaluationPropagatesMinimalCheckpointSubject() {
        // given
        Node document = root(
                aggregate("all", null, "family"),
                leaf("leaf", "topic", "leaf-domain", "timeline-a"));
        Node event = new Node()
                .properties(
                        "subscriptionKey",
                        new Node().value("topic"))
                .properties(
                        "timeline",
                        new Node().value("raw-timeline-is-ignored"))
                .properties(
                        "timestamp",
                        new Node().value(BigInteger.valueOf(12L)))
                .properties(
                        "unrelated",
                        new Node().value("must-not-survive"));

        // when
        try (Blue blue = runtime()) {
            ResolvedSnapshot snapshot =
                    blue.getDocumentProcessor()
                            .snapshotManager()
                            .fromDocumentTransient(
                                    document);
            DocumentProcessor processor =
                    blue.getDocumentProcessor();
            ContractBundle bundle =
                    processor.contractLoader().load(
                            snapshot, "/");
            EffectiveContractSnapshot aggregate =
                    bundle.effectiveContractSnapshot("all");
            ExternalChannelFunctionEvaluation evaluation =
                    ExternalChannelFunctionEvaluation.evaluate(
                            processor.registry(),
                            processor.contractConverter(),
                            ExternalChannelFunctionEvaluation
                                    .verifiedMatcherSessions(
                                            processor.snapshotManager()),
                            bundle,
                            aggregate,
                            event);
            Node subject =
                    evaluation.checkpointSubject().toNode();

            // then
            assertTrue(evaluation.accepts());
            assertEquals(
                    new LinkedHashSet<>(
                            Arrays.asList(
                                    "timeline",
                                    "timestamp")),
                    subject.getProperties().keySet());
            assertEquals(
                    "timeline-a",
                    subject.get("/timeline"));
            assertEquals(
                    BigInteger.valueOf(12L),
                    subject.get("/timestamp"));
            assertEquals(
                    Collections.singletonList("leaf"),
                    dependencyKeys(
                            evaluation.dependencies()));
        }
    }

    @Test
    void shouldRejectMissingExternalChannelDependency() {
        // given
        Node missing = root(
                aggregate(
                        "outer",
                        "absent",
                        "explicit"));

        // when
        SubscriptionSurfaceInvalidException failure;
        try (Blue blue = runtime()) {
            failure = captureFailure(
                            () -> validate(
                                    blue,
                                    new Node(),
                                    missing,
                                    "/contracts/outer"));
        }

        // then
        assertEquals(SubscriptionSurfaceInvalidException.class,
                failure.getClass());
        assertTrue(failure.getMessage().contains(
                "Missing same-scope External Channel dependency"));
    }

    @Test
    void shouldRejectCyclicExternalChannelDependency() {
        // given
        Node cycle = root(
                aggregate("left", "right", "explicit"),
                aggregate("right", "left", "explicit"));

        // when
        SubscriptionSurfaceInvalidException failure;
        try (Blue blue = runtime()) {
            failure = captureFailure(
                            () -> validate(
                                    blue,
                                    new Node(),
                                    cycle,
                                    "/contracts/left"));
        }

        // then
        assertEquals(SubscriptionSurfaceInvalidException.class,
                failure.getClass());
        assertTrue(failure.getMessage().contains(
                "Cyclic same-scope External Channel dependency"));
    }

    @Test
    void shouldRejectNonChannelExternalDependencyTarget() {
        // given
        Node invalid = root(
                aggregate(
                        "outer",
                        "handler",
                        "explicit"),
                recordingHandler(
                        "handler",
                        "outer"));

        // when
        SubscriptionSurfaceInvalidException failure;
        try (Blue blue = runtime()) {
            blue.registerExternalContractType(
                    RECORDING_HANDLER_TYPE_BLUE_ID,
                    RECORDING_HANDLER_TYPE,
                    new RecordingHandlerProcessor());
            failure = captureFailure(
                            () -> validate(
                                    blue,
                                    new Node(),
                                    invalid,
                                    "/contracts/outer"));
        }

        // then
        assertEquals(SubscriptionSurfaceInvalidException.class,
                failure.getClass());
        assertTrue(failure.getMessage().contains(
                "not an External Channel"));
    }

    @Test
    void shouldVerifyDependencySnapshotHasPublicCanonicalRoundTrip() {
        // given
        ExternalChannelDependencySnapshot.Member member =
                new ExternalChannelDependencySnapshot.Member(
                        "leaf",
                        2,
                        Collections.singletonList("source-leaf"),
                        Collections.singletonList("intrinsic-leaf"));
        ExternalChannelDependencySnapshot.TypeFamily family =
                new ExternalChannelDependencySnapshot.TypeFamily(
                        "outer",
                        LEAF_TYPE_BLUE_ID,
                        Collections.singletonList(member));
        ExternalChannelDependencySnapshot.Entry entry =
                new ExternalChannelDependencySnapshot.Entry(
                        "leaf",
                        2,
                        LEAF_TYPE_BLUE_ID,
                        Collections.singletonList("source-leaf"),
                        Collections.singletonList("intrinsic-leaf"),
                        "domain-leaf");
        ExternalChannelDependencySnapshot original =
                new ExternalChannelDependencySnapshot(
                        Collections.singletonList("intrinsic-outer"),
                        Collections.singletonList(entry),
                        Collections.singletonList(family),
                        true);
        // when
        ExternalChannelDependencySnapshot reconstructed =
                new ExternalChannelDependencySnapshot(
                        original.intrinsicNodeBlueIds(),
                        original.entries(),
                        original.typeFamilies(),
                        original.wholeSameScopeExternalSurface());
        ExternalChannelDependencySnapshot.TypeFamily assignable =
                new ExternalChannelDependencySnapshot.TypeFamily(
                        "outer",
                        LEAF_TYPE_BLUE_ID,
                        ExternalChannelDependencySnapshot.TypeMatchMode
                                .ASSIGNABLE,
                        Collections.singletonList(
                                new ExternalChannelDependencySnapshot.Member(
                                        "leaf",
                                        2,
                                        LEAF_TYPE_BLUE_ID,
                                        Collections.singletonList(
                                                "source-leaf"),
                                        Collections.singletonList(
                                                "intrinsic-leaf"))));

        // then
        assertEquals(original, reconstructed);
        assertEquals(
                        original.deterministicDependencyNodeBlueIds(),
                reconstructed
                        .deterministicDependencyNodeBlueIds());

        assertNotEquals(
                family.identityBlueId(),
                assignable.identityBlueId());
        assertEquals(
                LEAF_TYPE_BLUE_ID,
                family.members().get(0)
                        .effectiveTypeBlueId());
        assertEquals(
                ExternalChannelDependencySnapshot.TypeMatchMode.EXACT,
                family.matchMode());
    }

    @Test
    void shouldVerifySparseVerifierRejectsFalseAbsenceForEmptyEnumerations() {
        // given
        for (String mode : Arrays.asList(
                "family",
                "assignable-headers",
                "whole")) {
            try (Blue blue = runtime()) {
                Node emptyEnumeration = root(
                        aggregate("outer", null, mode));
                SubscriptionDelta initial = validate(
                        blue,
                        new Node(),
                        emptyEnumeration,
                        "/contracts/outer");
                SubscriptionDelta.Entry stale =
                        entry(initial.added(), "outer")
                                .activatedAt(1L, TEST_ORDER);
                ExternalDeliveryPlan plan =
                        ExternalDeliveryPlan.builder()
                                .revisions(1L, 1L)
                                .eventOrderKey(TEST_ORDER)
                                .activeSubscriptionInterval(stale)
                                .exactRuntimeState()
                                .build();
                DocumentProcessor verifier =
                        processorForPlan(blue, plan, false);
                Node addedMember =
                        "assignable-headers".equals(mode)
                                ? typedLeaf(
                                        "leaf",
                                        ASSIGNABLE_DIRECT_TYPE_BLUE_ID,
                                        "topic",
                                        "leaf-domain",
                                        "timeline-a",
                                        0)
                                : leaf(
                                        "leaf",
                                        "topic",
                                        "leaf-domain",
                                        "timeline-a");
                Node actual = root(
                        aggregate("outer", null, mode),
                        addedMember);

                // when
                DocumentProcessingResult result =
                        verifier.processDocument(
                                actual,
                                nonMatchingEvent());

                // then
                assertEquals(
                        ProcessorStatus.INVALID_PROCESSING_DOCUMENT,
                        result.status());
            }
        }
    }

    @Test
    void shouldVerifyInheritedUnselectedHandlerBodyIsNotDemandedBySelectorProof() {
        // given
        String unavailableBodyBlueId =
                DirectBlueIdCalculator.calculateBlueId(
                        new Node().value(
                                "unavailable-handler-body"));
        Node handler = new Node()
                .type(reference(HANDLER_TYPE_BLUE_ID))
                .properties(
                        "channel",
                        new Node().value("never-selected"))
                .properties(
                        "program",
                        reference(unavailableBodyBlueId));
        Node scopeType = new Node()
                .contracts(
                        new Node().properties(
                                "unrelatedHandler",
                                handler));
        String scopeTypeBlueId =
                DirectBlueIdCalculator.calculateBlueId(scopeType);
        AtomicInteger unavailableBodyDemands =
                new AtomicInteger();
        NodeProvider provider = blueId -> {
            if (scopeTypeBlueId.equals(blueId)) {
                return Collections.singletonList(
                        scopeType.clone());
            }
            if (unavailableBodyBlueId.equals(blueId)) {
                unavailableBodyDemands.incrementAndGet();
            }
            return null;
        };

        try (Blue blue = runtime(provider, true)) {
            Node direct = root(
                    aggregate("outer", null, "family"),
                    leaf(
                            "leaf",
                            "topic",
                            "leaf-domain",
                            "timeline-a"));
            SubscriptionDelta initial = validate(
                    blue,
                    new Node(),
                    direct,
                    "/contracts/outer");
            SubscriptionDelta.Entry active =
                    entry(initial.added(), "outer")
                            .activatedAt(1L, TEST_ORDER);
            ExternalDeliveryPlan plan =
                    ExternalDeliveryPlan.builder()
                            .revisions(1L, 1L)
                            .eventOrderKey(TEST_ORDER)
                            .activeSubscriptionInterval(active)
                            .exactRuntimeState()
                            .build();
            DocumentProcessor verifier =
                    processorForPlan(blue, plan, true);
            Node inherited = direct.clone()
                    .type(reference(scopeTypeBlueId));

            // when
            DocumentProcessingResult result =
                    verifier.processDocument(
                            inherited,
                            nonMatchingEvent());

            // then
            assertEquals(
                    ProcessorStatus.NO_MATCH,
                    result.status());
            assertEquals(0, unavailableBodyDemands.get());
        }
    }

    @Test
    void shouldVerifyOuterCheckpointUsesSelectedSubjectAndDispatchesOnlyOuterHandlers() {
        // given
        try (Blue language = runtime()) {
            language.registerExternalContractType(
                    RECORDING_HANDLER_TYPE_BLUE_ID,
                    RECORDING_HANDLER_TYPE,
                    new RecordingHandlerProcessor());
            AggregateProcessor aggregateProcessor =
                    new AggregateProcessor();
            RecordingHandlerProcessor handlerProcessor =
                    new RecordingHandlerProcessor();
            DocumentProcessor owner =
                    DocumentProcessor.builder()
                            .registerContractProcessor(
                                    LEAF_TYPE_BLUE_ID,
                                    LEAF_TYPE,
                                    new LeafProcessor())
                            .registerContractProcessor(
                                    AGGREGATE_TYPE_BLUE_ID,
                                    AGGREGATE_TYPE,
                                    aggregateProcessor)
                            .registerContractProcessor(
                                    RECORDING_HANDLER_TYPE_BLUE_ID,
                                    RECORDING_HANDLER_TYPE,
                                    handlerProcessor)
                            .matchingService(
                                    new ContractMatchingService(
                                            language))
                            .snapshotStore(
                                    language.getDocumentProcessor()
                                            .snapshotManager())
                            .build();
            Node document = root(
                    aggregate("outer", null, "family"),
                    leaf(
                            "leaf",
                            "topic",
                            "leaf-domain",
                            "timeline-a"),
                    recordingHandler(
                            "outerHandler", "outer"),
                    recordingHandler(
                            "leafHandler", "leaf"));
            Node first = event("topic", 10L);
            Node second = event("topic", 11L);
            ResolvedSnapshot snapshot =
                    owner.snapshotManager()
                            .fromDocumentTransient(document);
            ContractBundle initialBundle =
                    owner.contractLoader().load(snapshot, "/");
            EffectiveContractSnapshot outerSnapshot =
                    initialBundle.effectiveContractSnapshot(
                            "outer");
            ExternalChannelFunctionEvaluation firstEvaluation =
                    ExternalChannelFunctionEvaluation.evaluate(
                            owner.registry(),
                            owner.contractConverter(),
                            ExternalChannelFunctionEvaluation
                                    .verifiedMatcherSessions(
                                            owner.snapshotManager()),
                            initialBundle,
                            outerSnapshot,
                            first);
            ExternalDeliverySnapshot delivery =
                    delivery(
                            outerSnapshot,
                            firstEvaluation);
            VerifiedExecutionEvidence evidence =
                    VerifiedExecutionEvidence.builder(
                                    DirectBlueIdCalculator.calculateBlueId(
                                            document),
                                    DirectBlueIdCalculator.calculateBlueId(
                                            first))
                            .revisions(0L, 0L)
                            .runtimeRegistryIdentity(
                                    owner.runtimeRegistryIdentity())
                            .eventOrderKey(TEST_ORDER)
                            .delivery(delivery)
                            .build();
            ProcessorInvocationState execution =
                    new ProcessorInvocationState(
                            owner,
                            document.clone(),
                            first,
                            evidence);
            execution.preflightScope("/");
            ContractBundle bundle =
                    execution.bundleForScope("/");
            CheckpointManager checkpointManager =
                    new CheckpointManager(
                            execution.runtime(),
                            ProcessorEngine::canonicalSignature);
            ChannelRunner runner =
                    new ChannelRunner(
                            owner,
                            execution,
                            execution.runtime(),
                            checkpointManager);

            runner.runExternalChannel(
                    "/",
                    bundle,
                    bundle.channelBinding("outer"),
                    first);
            runner.persistPendingCheckpoints("/");
            execution.preflightScope("/");
            bundle = execution.bundleForScope("/");

            // when
            runner.runExternalChannel(
                    "/",
                    bundle,
                    bundle.channelBinding("outer"),
                    second);
            runner.persistPendingCheckpoints("/");
            execution.preflightScope("/");
            bundle = execution.bundleForScope("/");
            ChannelEventCheckpoint checkpoint =
                    (ChannelEventCheckpoint) bundle.marker(
                            "checkpoint");
            Node stored = checkpoint == null
                    || checkpoint.entry("outer") == null
                    ? null
                    : checkpoint.entry("outer")
                    .getSubject();

            // then
            assertNotNull(checkpoint);
            assertNotNull(checkpoint.entry("outer"));
            assertEquals(null, checkpoint.entry("leaf"));
            assertNotNull(stored);
            assertFalse(stored.isReferenceOnly());
            assertEquals(
                    new LinkedHashSet<>(
                            Arrays.asList(
                                    "timeline",
                                    "timestamp")),
                    stored.getProperties().keySet());
            assertEquals(
                    "timeline-a",
                    stored.get("/timeline"));
            assertEquals(
                    BigInteger.valueOf(11L),
                    stored.get("/timestamp"));
            assertEquals(
                    Arrays.asList(
                            "outerHandler",
                            "outerHandler"),
                    handlerProcessor.executedKeys);
            assertEquals(
                    Arrays.asList(null, BigInteger.TEN),
                    aggregateProcessor
                            .previousTimestamps);
            assertEquals(
                    Arrays.asList(
                            BigInteger.TEN,
                            BigInteger.valueOf(11L)),
                    aggregateProcessor
                            .currentTimestamps);
        }
    }

    private static SubscriptionDelta validate(
            Blue blue,
            Node before,
            Node after,
            String changedPath) {
        return blue.getDocumentProcessor()
                .subscriptionSurfaceValidator()
                .validate(
                        SubscriptionSurfaceValidationContext
                                .builder(
                                        before,
                                        after,
                                        Collections.singleton(
                                                changedPath),
                                        GasSchedule.contracts10())
                                .snapshots(
                                        blue.getDocumentProcessor()
                                                .snapshotManager()
                                                .fromDocumentTransient(
                                                        before),
                                        blue.getDocumentProcessor()
                                                .snapshotManager()
                                                .fromDocumentTransient(
                                                        after))
                                .build());
    }

    private static Blue runtime() {
        return runtime(null, false);
    }

    private static Blue runtime(
            NodeProvider provider,
            boolean registerHandler) {
        Blue blue = provider != null
                ? ProcessorTestSupport.blue(provider)
                : ProcessorTestSupport.blue();
        blue.registerExternalContractType(
                LEAF_TYPE_BLUE_ID,
                LEAF_TYPE,
                new LeafProcessor());
        blue.registerExternalContractType(
                ASSIGNABLE_BASE_TYPE_BLUE_ID,
                ASSIGNABLE_BASE_TYPE,
                new LeafProcessor());
        blue.registerExternalContractType(
                ASSIGNABLE_DIRECT_TYPE_BLUE_ID,
                ASSIGNABLE_DIRECT_TYPE,
                new LeafProcessor());
        blue.registerExternalContractType(
                ASSIGNABLE_DEEP_TYPE_BLUE_ID,
                ASSIGNABLE_DEEP_TYPE,
                new LeafProcessor());
        blue.registerExternalContractType(
                AGGREGATE_TYPE_BLUE_ID,
                AGGREGATE_TYPE,
                new AggregateProcessor());
        blue.registerExternalContractType(
                OTHER_TYPE_BLUE_ID,
                OTHER_TYPE,
                new OtherProcessor());
        if (registerHandler) {
            blue.registerExternalContractType(
                    HANDLER_TYPE_BLUE_ID,
                    HANDLER_TYPE,
                    new DeferredHandlerProcessor());
        }
        return blue;
    }

    private static DocumentProcessor processorForPlan(
            Blue language,
            ExternalDeliveryPlan plan,
            boolean registerHandler) {
        DocumentProcessor.Builder builder =
                DocumentProcessor.builder()
                        .registerContractProcessor(
                                LEAF_TYPE_BLUE_ID,
                                LEAF_TYPE,
                                new LeafProcessor())
                        .registerContractProcessor(
                                ASSIGNABLE_BASE_TYPE_BLUE_ID,
                                ASSIGNABLE_BASE_TYPE,
                                new LeafProcessor())
                        .registerContractProcessor(
                                ASSIGNABLE_DIRECT_TYPE_BLUE_ID,
                                ASSIGNABLE_DIRECT_TYPE,
                                new LeafProcessor())
                        .registerContractProcessor(
                                ASSIGNABLE_DEEP_TYPE_BLUE_ID,
                                ASSIGNABLE_DEEP_TYPE,
                                new LeafProcessor())
                        .registerContractProcessor(
                                AGGREGATE_TYPE_BLUE_ID,
                                AGGREGATE_TYPE,
                                new AggregateProcessor())
                        .registerContractProcessor(
                                OTHER_TYPE_BLUE_ID,
                                OTHER_TYPE,
                                new OtherProcessor())
                        .matchingService(
                                new ContractMatchingService(
                                        language))
                        .snapshotStore(
                                language.getDocumentProcessor()
                                        .snapshotManager())
                        .deliveryPlanDeriver(
                                (root, event) -> plan);
        if (registerHandler) {
            builder.registerContractProcessor(
                    HANDLER_TYPE_BLUE_ID,
                    HANDLER_TYPE,
                    new DeferredHandlerProcessor());
        }
        return builder.build();
    }

    private static Node nonMatchingEvent() {
        return new Node()
                .properties(
                        "subscriptionKey",
                        new Node().value(
                                "not-a-subscription"))
                .properties(
                        "timestamp",
                        new Node().value(BigInteger.ONE));
    }

    private static Node event(
            String subscriptionKey,
            long timestamp) {
        return new Node()
                .properties(
                        "subscriptionKey",
                        new Node().value(
                                subscriptionKey))
                .properties(
                        "timestamp",
                        new Node().value(
                                BigInteger.valueOf(timestamp)))
                .properties(
                        "raw",
                        new Node().value(
                                "not-checkpointed"));
    }

    private static ExternalDeliverySnapshot delivery(
            EffectiveContractSnapshot snapshot,
            ExternalChannelFunctionEvaluation evaluation) {
        ExternalDeliverySnapshot.Builder builder =
                ExternalDeliverySnapshot.builder(
                                snapshot.scopePath(),
                                snapshot.key())
                        .effectiveTypeBlueId(
                                snapshot.effectiveTypeBlueId())
                        .order(snapshot.order())
                        .checkpointDomainBlueId(
                                evaluation
                                        .checkpointDomainBlueId())
                        .checkpointSubjectBlueId(
                                evaluation
                                        .checkpointSubjectBlueId());
        for (String contribution
                : snapshot.sourceContributionNodeBlueIds()) {
            builder.sourceContribution(contribution);
        }
        for (String key : evaluation.channelKeys()) {
            builder.subscriptionKey(key);
        }
        return builder.build();
    }

    private static Node assignableFamilyDocument(Node member) {
        Node family = aggregate(
                "all",
                null,
                "assignable-headers");
        return member == null
                ? root(family)
                : root(family, member);
    }

    private static Node assignableFamilyMember(
            String domain,
            int order) {
        return typedLeaf(
                "member",
                ASSIGNABLE_DIRECT_TYPE_BLUE_ID,
                "topic",
                domain,
                "timeline-a",
                order);
    }

    private static Node root(Node... contracts) {
        Node map = new Node();
        for (int index = 0; index < contracts.length; index++) {
            String key = contracts[index].getName();
            Node contract = contracts[index].clone();
            contract.name(null);
            map.properties(key, contract);
        }
        return new Node().contracts(map);
    }

    private static Node leaf(
            String key,
            String subscriptionKey,
            String domain,
            String timeline) {
        return typedLeaf(
                key,
                LEAF_TYPE_BLUE_ID,
                subscriptionKey,
                domain,
                timeline,
                0);
    }

    private static Node typedLeaf(
            String key,
            String typeBlueId,
            String subscriptionKey,
            String domain,
            String timeline,
            int order) {
        return new Node()
                .name(key)
                .type(reference(typeBlueId))
                .properties(
                        "order",
                        new Node().value(
                                BigInteger.valueOf(order)))
                .properties(
                        "subscriptionKey",
                        new Node().value(subscriptionKey))
                .properties(
                        "domain",
                        new Node().value(domain))
                .properties(
                        "timeline",
                        new Node().value(timeline));
    }

    private static Node aggregate(
            String key,
            String memberKey,
            String mode) {
        Node aggregate = new Node()
                .name(key)
                .type(reference(
                        AGGREGATE_TYPE_BLUE_ID))
                .properties(
                        "mode",
                        new Node().value(mode));
        if (memberKey != null) {
            aggregate.properties(
                    "memberKey",
                    new Node().value(memberKey));
        }
        return aggregate;
    }

    private static Node other(
            String key,
            String subscriptionKey,
            String domain) {
        return new Node()
                .name(key)
                .type(reference(OTHER_TYPE_BLUE_ID))
                .properties(
                        "subscriptionKey",
                        new Node().value(subscriptionKey))
                .properties(
                        "domain",
                        new Node().value(domain));
    }

    private static Node recordingHandler(
            String key,
            String channelKey) {
        return new Node()
                .name(key)
                .type(reference(
                        RECORDING_HANDLER_TYPE_BLUE_ID))
                .properties(
                        "channel",
                        new Node().value(channelKey));
    }

    private static Node reference(String blueId) {
        return new Node().blueId(blueId);
    }

    private static SubscriptionDelta.Entry entry(
            List<SubscriptionDelta.Entry> entries,
            String key) {
        for (SubscriptionDelta.Entry entry : entries) {
            if (key.equals(entry.channelKey())) {
                return entry;
            }
        }
        return null;
    }

    private static boolean hasEntry(
            List<SubscriptionDelta.Entry> entries,
            String key) {
        return entry(entries, key) != null;
    }

    private static List<String> dependencyKeys(
            ExternalChannelDependencySnapshot snapshot) {
        List<String> keys = new ArrayList<>();
        for (ExternalChannelDependencySnapshot.Entry entry
                : snapshot.entries()) {
            keys.add(entry.channelKey());
        }
        return keys;
    }

    private static List<String> familyMemberKeys(
            ExternalChannelDependencySnapshot.TypeFamily family) {
        List<String> keys = new ArrayList<>();
        for (ExternalChannelDependencySnapshot.Member member
                : family.members()) {
            keys.add(member.channelKey());
        }
        return keys;
    }

    private static List<String> familyMemberTypes(
            ExternalChannelDependencySnapshot.TypeFamily family) {
        List<String> types = new ArrayList<>();
        for (ExternalChannelDependencySnapshot.Member member
                : family.members()) {
            types.add(member.effectiveTypeBlueId());
        }
        return types;
    }

    private static void assertAggregateRotates(
            SubscriptionDelta delta) {
        assertNotNull(entry(delta.removed(), "all"));
        assertNotNull(entry(delta.added(), "all"));
    }

    public static final class DependencyLeafChannel
            extends ChannelContract {
        private String subscriptionKey;
        private String domain;
        private String timeline;

        public String getSubscriptionKey() {
            return subscriptionKey;
        }

        public void setSubscriptionKey(String subscriptionKey) {
            this.subscriptionKey = subscriptionKey;
        }

        public String getDomain() {
            return domain;
        }

        public void setDomain(String domain) {
            this.domain = domain;
        }

        public String getTimeline() {
            return timeline;
        }

        public void setTimeline(String timeline) {
            this.timeline = timeline;
        }
    }

    public static final class DependencyAggregateChannel
            extends ChannelContract {
        private String memberKey;
        private String mode;

        public String getMemberKey() {
            return memberKey;
        }

        public void setMemberKey(String memberKey) {
            this.memberKey = memberKey;
        }

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode;
        }
    }

    public static final class DependencyOtherChannel
            extends ChannelContract {
        private String subscriptionKey;
        private String domain;

        public String getSubscriptionKey() {
            return subscriptionKey;
        }

        public void setSubscriptionKey(String subscriptionKey) {
            this.subscriptionKey = subscriptionKey;
        }

        public String getDomain() {
            return domain;
        }

        public void setDomain(String domain) {
            this.domain = domain;
        }
    }

    public static final class DependencyDeferredHandler
            extends HandlerContract {
        private Node program;

        public Node getProgram() {
            return program;
        }

        public void setProgram(Node program) {
            this.program = program;
        }
    }

    public static final class DependencyRecordingHandler
            extends HandlerContract {
    }

    private static final class SubscriptionEntryObservation {
        private final SubscriptionDelta.Entry removed;
        private final SubscriptionDelta.Entry added;

        private SubscriptionEntryObservation(
                SubscriptionDelta.Entry removed,
                SubscriptionDelta.Entry added) {
            this.removed = removed;
            this.added = added;
        }
    }

    private static final class LeafProcessor
            implements ChannelProcessor<DependencyLeafChannel> {
        private final ExternalChannelSubscriptionFunctions<
                DependencyLeafChannel> functions =
                new ExternalChannelSubscriptionFunctions<
                        DependencyLeafChannel>() {
                    @Override
                    public List<String> channelKeys(
                            DependencyLeafChannel contract) {
                        return Collections.singletonList(
                                contract.getSubscriptionKey());
                    }

                    @Override
                    public String checkpointDomainDiscriminator(
                            DependencyLeafChannel contract) {
                        return contract.getDomain();
                    }

                    @Override
                    public Node checkpointSubject(
                            DependencyLeafChannel contract,
                            Node exactEvent,
                            Node exactPayload) {
                        Node timestamp =
                                exactEvent.getProperties() != null
                                        ? exactEvent.getProperties()
                                        .get("timestamp")
                                        : null;
                        if (timestamp == null) {
                            throw new IllegalArgumentException(
                                    "timestamp is required");
                        }
                        return new Node()
                                .properties(
                                        "timeline",
                                        new Node().value(
                                                contract.getTimeline()))
                                .properties(
                                        "timestamp",
                                        timestamp.clone());
                    }
                };

        @Override
        public Class<DependencyLeafChannel> contractType() {
            return DependencyLeafChannel.class;
        }

        @Override
        public ExternalChannelSubscriptionFunctions<
                DependencyLeafChannel>
        externalSubscriptionFunctions() {
            return functions;
        }
    }

    private static final class AggregateProcessor
            implements ChannelProcessor<DependencyAggregateChannel> {
        private final List<BigInteger> currentTimestamps =
                new ArrayList<>();
        private final List<BigInteger> previousTimestamps =
                new ArrayList<>();
        private final ExternalChannelSubscriptionFunctions<
                DependencyAggregateChannel> functions =
                new ExternalChannelSubscriptionFunctions<
                        DependencyAggregateChannel>() {
                    @Override
                    public List<String> channelKeys(
                            DependencyAggregateChannel contract,
                            ExternalChannelFunctionContext context) {
                        Set<String> keys = new LinkedHashSet<>();
                        if ("assignable-headers".equals(
                                contract.getMode())
                                || "assignable-channel-base".equals(
                                contract.getMode())) {
                            for (ExternalChannelMemberSnapshot member
                                    : selected(contract, context)) {
                                keys.add(
                                        "member:"
                                                + member.channelKey());
                            }
                            if (keys.isEmpty()) {
                                keys.add(
                                        "empty-family:"
                                                + context.channelKey());
                            }
                            return new ArrayList<>(keys);
                        }
                        for (ExternalChannelMemberSnapshot member
                                : selected(contract, context)) {
                            keys.addAll(member.channelKeys());
                        }
                        if (keys.isEmpty()) {
                            keys.add(
                                    "empty-family:"
                                            + context.channelKey());
                        }
                        return new ArrayList<>(keys);
                    }

                    @Override
                    public boolean accepts(
                            DependencyAggregateChannel contract,
                            Node exactEvent,
                            ExternalChannelFunctionContext context) {
                        for (ExternalChannelMemberSnapshot member
                                : selected(contract, context)) {
                            if (member.evaluate(exactEvent).accepts()) {
                                return true;
                            }
                        }
                        return false;
                    }

                    @Override
                    public Node payload(
                            DependencyAggregateChannel contract,
                            Node exactEvent,
                            ExternalChannelFunctionContext context) {
                        for (ExternalChannelMemberSnapshot member
                                : selected(contract, context)) {
                            ExternalChannelMemberEvaluation evaluation =
                                    member.evaluate(exactEvent);
                            if (evaluation.accepts()) {
                                return evaluation.payload();
                            }
                        }
                        throw new IllegalStateException(
                                "No accepting member");
                    }

                    @Override
                    public Node checkpointSubject(
                            DependencyAggregateChannel contract,
                            Node exactEvent,
                            Node exactPayload,
                            ExternalChannelFunctionContext context) {
                        for (ExternalChannelMemberSnapshot member
                                : selected(contract, context)) {
                            ExternalChannelMemberEvaluation evaluation =
                                    member.evaluate(exactEvent);
                            if (evaluation.accepts()) {
                                return evaluation
                                        .checkpointSubject();
                            }
                        }
                        throw new IllegalStateException(
                                "No accepting member");
                    }

                    @Override
                    public String checkpointDomainDiscriminator(
                            DependencyAggregateChannel contract,
                            ExternalChannelFunctionContext context) {
                        return "aggregate-v1";
                    }

                    private List<ExternalChannelMemberSnapshot> selected(
                            DependencyAggregateChannel contract,
                            ExternalChannelFunctionContext context) {
                        if ("family".equals(contract.getMode())) {
                            return context.membersByEffectiveType(
                                    LEAF_TYPE_BLUE_ID);
                        }
                        if ("assignable-headers".equals(
                                contract.getMode())) {
                            return context
                                    .membersAssignableToType(
                                            ASSIGNABLE_BASE_TYPE_BLUE_ID);
                        }
                        if ("assignable-channel-base".equals(
                                contract.getMode())) {
                            return context
                                    .membersAssignableToType(
                                            RuntimeBlueIds.CHANNEL);
                        }
                        if ("whole".equals(contract.getMode())) {
                            return context.members();
                        }
                        return Collections.singletonList(
                                context.member(
                                        contract.getMemberKey()));
                    }
                };

        @Override
        public Class<DependencyAggregateChannel> contractType() {
            return DependencyAggregateChannel.class;
        }

        @Override
        public ExternalChannelSubscriptionFunctions<
                DependencyAggregateChannel>
        externalSubscriptionFunctions() {
            return functions;
        }

        @Override
        public boolean isNewerEvent(
                DependencyAggregateChannel contract,
                ChannelCheckpointContext context) {
            Node current = context.currentSubject();
            Node previous = context.lastEvent();
            BigInteger currentTimestamp =
                    (BigInteger) current.get("/timestamp");
            BigInteger previousTimestamp =
                    previous != null
                            ? (BigInteger) previous.get(
                            "/timestamp")
                            : null;
            currentTimestamps.add(currentTimestamp);
            previousTimestamps.add(previousTimestamp);
            return previousTimestamp == null
                    || currentTimestamp.compareTo(
                    previousTimestamp) > 0;
        }
    }

    private static final class OtherProcessor
            implements ChannelProcessor<DependencyOtherChannel> {
        private final ExternalChannelSubscriptionFunctions<
                DependencyOtherChannel> functions =
                new ExternalChannelSubscriptionFunctions<
                        DependencyOtherChannel>() {
                    @Override
                    public List<String> channelKeys(
                            DependencyOtherChannel contract) {
                        return Collections.singletonList(
                                contract.getSubscriptionKey());
                    }

                    @Override
                    public String checkpointDomainDiscriminator(
                            DependencyOtherChannel contract) {
                        return contract.getDomain();
                    }
                };

        @Override
        public Class<DependencyOtherChannel> contractType() {
            return DependencyOtherChannel.class;
        }

        @Override
        public ExternalChannelSubscriptionFunctions<
                DependencyOtherChannel>
        externalSubscriptionFunctions() {
            return functions;
        }
    }

    private static final class DeferredHandlerProcessor
            implements HandlerProcessor<
            DependencyDeferredHandler> {
        @Override
        public Class<DependencyDeferredHandler> contractType() {
            return DependencyDeferredHandler.class;
        }

        @Override
        public List<String> executableBodyFields() {
            return Collections.singletonList("program");
        }

        @Override
        public void execute(
                DependencyDeferredHandler contract,
                ProcessorExecutionContext context) {
            throw new AssertionError(
                    "Unselected Handler must not execute");
        }
    }

    private static final class RecordingHandlerProcessor
            implements HandlerProcessor<
            DependencyRecordingHandler> {
        private final List<String> executedKeys =
                new ArrayList<>();

        @Override
        public Class<DependencyRecordingHandler> contractType() {
            return DependencyRecordingHandler.class;
        }

        @Override
        public void execute(
                DependencyRecordingHandler contract,
                ProcessorExecutionContext context) {
            executedKeys.add(context.contractKey());
        }
    }
}
