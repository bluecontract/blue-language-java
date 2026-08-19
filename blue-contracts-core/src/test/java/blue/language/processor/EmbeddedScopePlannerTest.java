package blue.language.processor;

import blue.language.api.NodeProviderOutcome;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.wire.BlueLanguageConstants;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.provider.NodeProvider;
import blue.language.provider.NodeProviderResult;
import blue.language.runtime.BlueLanguage;
import blue.language.runtime.LanguageProcessing;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class EmbeddedScopePlannerTest {

    private static final String CYCLIC_MEMBER_BLUE_ID =
            "GX7CFU287wrZ7qw3LQG7gQi6UUoy1FFpM3tzupQJKi3N#0";
    private static final String ROOT_SCOPE_PATH = "/root";
    private static final String LESSONS_DECLARATION = "/lessons";

    @Test
    void shouldProjectCollectionMembersInCodePointOrderAndEscapeKeys() {
        // given
        String privateUse = "\uE000";
        String supplementary = "\uD800\uDC00";
        Map<String, Node> lessons = new LinkedHashMap<>();
        lessons.put(supplementary, object());
        lessons.put("lesson~b", object());
        lessons.put(privateUse, object());
        lessons.put("lesson/a", object());
        Node scope = new Node().properties(
                "payment", object(),
                "lessons", new Node().properties(lessons));

        // when
        EmbeddedScopePlan plan = new EmbeddedScopePlanner().plan(
                scope,
                "/course",
                Collections.singletonList("/payment"),
                Collections.singletonList("/lessons"),
                GasSchedule.contracts10());

        // then
        assertEquals(
                Arrays.asList("lesson/a", "lesson~b", privateUse, supplementary),
                plan.collectionMemberKeysByDeclaration().get("/lessons"));
        assertEquals(
                Arrays.asList(
                        "/course/lessons/lesson~0b",
                        "/course/lessons/lesson~1a",
                        "/course/lessons/" + privateUse,
                        "/course/lessons/" + supplementary,
                        "/course/payment"),
                plan.concreteChildPaths());
        assertEquals(
                EmbeddedPathOrigin.COLLECTION_MEMBER,
                plan.concretePathOrigins().get(
                        "/course/lessons/lesson~1a"));
        assertEquals(
                "lesson~b",
                plan.concretePaths().get(0).memberKey());
    }

    @Test
    void shouldPreserveUnicodeCodePointsAndEscapeRfc6901MemberKeys() {
        // given
        String decomposed = "e\u0301";
        String precomposed = "\u00E9";
        String privateUse = "\uE000";
        String supplementary = "\uD800\uDC00";
        Map<String, Node> members = new LinkedHashMap<>();
        members.put(supplementary, object());
        members.put(privateUse, object());
        members.put(precomposed, object());
        members.put("tilde~key", object());
        members.put("slash/key", object());
        members.put(decomposed, object());
        members.put("ascii", object());
        members.put("", object());
        Node scope = new Node().properties(
                "members", new Node().properties(members));

        // when
        EmbeddedScopePlan plan = new EmbeddedScopePlanner().plan(
                scope,
                "/scope",
                Collections.emptyList(),
                Collections.singletonList("/members"),
                GasSchedule.contracts10());

        // then
        assertEquals(
                Arrays.asList(
                        "",
                        "ascii",
                        decomposed,
                        "slash/key",
                        "tilde~key",
                        precomposed,
                        privateUse,
                        supplementary),
                plan.collectionMemberKeysByDeclaration().get("/members"));
        assertEquals(
                Arrays.asList(
                        "/scope/members/",
                        "/scope/members/ascii",
                        "/scope/members/e\u0301",
                        "/scope/members/slash~1key",
                        "/scope/members/tilde~0key",
                        "/scope/members/\u00E9",
                        "/scope/members/\uE000",
                        "/scope/members/\uD800\uDC00"),
                plan.concreteChildPaths());
    }

    @Test
    void shouldTreatListControlNamesAsOrdinaryObjectPathSegments() {
        // given
        Node scope = new Node().properties(
                BlueLanguageConstants.LIST_CONTROL_PREVIOUS, object(),
                BlueLanguageConstants.LIST_CONTROL_POS, object(),
                BlueLanguageConstants.LIST_CONTROL_REPLACE, object(),
                BlueLanguageConstants.LIST_CONTROL_EMPTY, object());
        List<String> paths = Arrays.asList(
                "/" + BlueLanguageConstants.LIST_CONTROL_PREVIOUS,
                "/" + BlueLanguageConstants.LIST_CONTROL_POS,
                "/" + BlueLanguageConstants.LIST_CONTROL_REPLACE,
                "/" + BlueLanguageConstants.LIST_CONTROL_EMPTY);

        // when
        EmbeddedScopePlan plan = new EmbeddedScopePlanner().plan(
                scope,
                "/",
                paths,
                Collections.emptyList(),
                GasSchedule.contracts10());

        // then
        assertEquals(4, plan.concreteChildPaths().size());
    }

    @Test
    void shouldRejectAbsentEmbeddedDeclarationLists() {
        // given
        EmbeddedScopePlanner planner = new EmbeddedScopePlanner();

        // when
        SubscriptionSurfaceInvalidException failure = assertThrows(
                SubscriptionSurfaceInvalidException.class,
                () -> planner.plan(
                        object(),
                        "/",
                        null,
                        null,
                        GasSchedule.contracts10()));

        // then
        assertEquals(
                ProcessorErrorCategory.SubscriptionSurfaceInvalid,
                failure.diagnostic().category());
    }

    @Test
    void shouldRejectEmptyEmbeddedDeclarationLists() {
        // given
        EmbeddedScopePlanner planner = new EmbeddedScopePlanner();

        // when
        SubscriptionSurfaceInvalidException failure = assertThrows(
                SubscriptionSurfaceInvalidException.class,
                () -> planner.plan(
                        object(),
                        "/",
                        Collections.emptyList(),
                        Collections.emptyList(),
                        GasSchedule.contracts10()));

        // then
        assertEquals(
                ProcessorErrorCategory.SubscriptionSurfaceInvalid,
                failure.diagnostic().category());
    }

    @Test
    void shouldTreatAbsentExactAndCollectionTargetsAsInactive() {
        // given
        FrozenNode scope = FrozenNode.fromResolvedNode(object());

        // when
        EmbeddedScopePlan plan = new EmbeddedScopePlanner().plan(
                scope,
                "/",
                Collections.singletonList("/payment"),
                Collections.singletonList("/lessons"),
                GasSchedule.contracts10());

        // then
        assertEquals(Collections.emptyList(), plan.concreteChildPaths());
        assertEquals(
                Collections.emptyList(),
                plan.collectionMemberKeysByDeclaration().get("/lessons"));
    }

    @Test
    void shouldRejectListCollectionTargetWithStableCategory() {
        // given
        Node scope = new Node().properties(
                "lessons",
                new Node().items(new Node().value("one")));

        // when
        SubscriptionSurfaceInvalidException failure = assertThrows(
                SubscriptionSurfaceInvalidException.class,
                () -> new EmbeddedScopePlanner().plan(
                        scope,
                        "/",
                        Collections.emptyList(),
                        Collections.singletonList("/lessons"),
                        GasSchedule.contracts10()));

        // then
        assertEquals(
                ProcessorErrorCategory.EmbeddedCollectionMustBeObject,
                failure.diagnostic().category());
    }

    @Test
    void shouldRejectNonObjectCollectionMemberWithStableCategory() {
        // given
        Node scope = new Node().properties(
                "lessons",
                new Node().properties(
                        "lesson-a", new Node().value(1)));

        // when
        SubscriptionSurfaceInvalidException failure = assertThrows(
                SubscriptionSurfaceInvalidException.class,
                () -> new EmbeddedScopePlanner().plan(
                        scope,
                        "/",
                        Collections.emptyList(),
                        Collections.singletonList("/lessons"),
                        GasSchedule.contracts10()));

        // then
        assertEquals(
                ProcessorErrorCategory
                        .EmbeddedCollectionMemberMustBeObject,
                failure.diagnostic().category());
    }

    @Test
    void shouldRejectSelectorSyntaxBeforeTraversal() {
        // given
        Node scope = new Node().properties(
                "lessons", new Node().properties("lesson-a", object()));

        // when
        SubscriptionSurfaceInvalidException failure = assertThrows(
                SubscriptionSurfaceInvalidException.class,
                () -> new EmbeddedScopePlanner().plan(
                        scope,
                        "/",
                        Collections.singletonList("/lessons/*"),
                        Collections.emptyList(),
                        GasSchedule.contracts10()));

        // then
        assertEquals(
                ProcessorErrorCategory.EmbeddedPathSelectorUnsupported,
                failure.diagnostic().category());
    }

    @Test
    void shouldRejectLanguageReservedCollectionPath() {
        // given
        Node scope = object();

        // when
        SubscriptionSurfaceInvalidException failure = assertThrows(
                SubscriptionSurfaceInvalidException.class,
                () -> new EmbeddedScopePlanner().plan(
                        scope,
                        "/",
                        Collections.emptyList(),
                        Collections.singletonList("/contracts"),
                        GasSchedule.contracts10()));

        // then
        assertEquals(
                ProcessorErrorCategory.InvalidEmbeddedCollectionPath,
                failure.diagnostic().category());
    }

    @Test
    void shouldRejectOverlappingExplicitAndCollectionDeclarations() {
        // given
        Node scope = new Node().properties(
                "lessons", new Node().properties("lesson-a", object()));

        // when
        SubscriptionSurfaceInvalidException failure = assertThrows(
                SubscriptionSurfaceInvalidException.class,
                () -> new EmbeddedScopePlanner().plan(
                        scope,
                        "/",
                        Collections.singletonList("/lessons/lesson-a"),
                        Collections.singletonList("/lessons"),
                        GasSchedule.contracts10()));

        // then
        assertEquals(
                ProcessorErrorCategory.OverlappingEmbeddedDeclaration,
                failure.diagnostic().category());
    }

    @Test
    void shouldRejectGraphEquivalentInlineCollectionDeclarations() {
        // given
        Node first = collectionWithOneMember();
        Node second = collectionWithOneMember();
        Node scope = new Node().properties(
                "first", first,
                "second", second);

        // when
        SubscriptionSurfaceInvalidException failure = assertThrows(
                SubscriptionSurfaceInvalidException.class,
                () -> new EmbeddedScopePlanner().plan(
                        scope,
                        "/",
                        Collections.emptyList(),
                        Arrays.asList("/first", "/second"),
                        GasSchedule.contracts10()));

        // then
        assertEquals(
                ProcessorErrorCategory.OverlappingEmbeddedDeclaration,
                failure.diagnostic().category());
    }

    @Test
    void shouldRejectGraphEquivalentPureReferenceCollectionDeclarations() {
        // given
        AtomicInteger materializations = new AtomicInteger();
        Node exactCollection = collectionWithOneMember();
        String collectionBlueId = DirectBlueIdCalculator.calculateBlueId(
                exactCollection);
        Node scope = new Node().properties(
                "first", new Node().blueId(collectionBlueId),
                "second", new Node().blueId(collectionBlueId));
        EmbeddedScopePlanner planner = new EmbeddedScopePlanner(reference -> {
            materializations.incrementAndGet();
            return FrozenNode.fromNode(exactCollection);
        });

        // when
        SubscriptionSurfaceInvalidException failure = assertThrows(
                SubscriptionSurfaceInvalidException.class,
                () -> planner.plan(
                        scope,
                        "/",
                        Collections.emptyList(),
                        Arrays.asList("/first", "/second"),
                        GasSchedule.contracts10()));

        // then
        assertEquals(
                ProcessorErrorCategory.OverlappingEmbeddedDeclaration,
                failure.diagnostic().category());
        assertEquals(2, materializations.get());
    }

    @Test
    void shouldKeepEqualReferenceMembersAsIndependentConcreteOccurrences() {
        // given
        Node exactMember = new Node().properties(
                "state", new Node().value(1));
        String memberBlueId = DirectBlueIdCalculator.calculateBlueId(
                exactMember);
        Node scope = new Node().properties(
                "members", new Node().properties(
                        "a", new Node().blueId(memberBlueId),
                        "b", new Node().blueId(memberBlueId)));
        EmbeddedScopePlanner planner = new EmbeddedScopePlanner(
                reference -> FrozenNode.fromNode(exactMember));

        // when
        EmbeddedScopePlan plan = planner.plan(
                scope,
                "/",
                Collections.emptyList(),
                Collections.singletonList("/members"),
                GasSchedule.contracts10());

        // then
        assertEquals(
                Arrays.asList("/members/a", "/members/b"),
                plan.concreteChildPaths());
    }

    @Test
    void shouldRejectConcreteAncestorEvenWithLexicalPeerBetweenPaths() {
        // given
        List<EmbeddedConcretePath> concrete = Arrays.asList(
                explicitConcrete("/root/a"),
                explicitConcrete("/root/a-b"),
                explicitConcrete("/root/a/b"));

        // when
        SubscriptionSurfaceInvalidException failure = assertThrows(
                SubscriptionSurfaceInvalidException.class,
                () -> new EmbeddedScopePlanner().rejectConcreteOverlap(
                        concrete, "/root"));

        // then
        assertEquals(
                ProcessorErrorCategory.OverlappingEmbeddedDeclaration,
                failure.diagnostic().category());
    }

    @Test
    void shouldValidatePortableMaximumConcreteSiblingSet() {
        // given
        List<EmbeddedConcretePath> concrete = new ArrayList<>(4096);
        for (int index = 0; index < 4096; index++) {
            concrete.add(explicitConcrete("/root/member-" + index));
        }

        // when
        Executable validation =
                () -> new EmbeddedScopePlanner().rejectConcreteOverlap(
                        concrete, "/root");

        // then
        assertDoesNotThrow(validation);
    }

    @Test
    void shouldRejectCyclicCollectionMemberBeforeProviderDemand() {
        // given
        AtomicInteger materializations = new AtomicInteger();
        Node scope = new Node().properties(
                "lessons",
                new Node().properties(
                        "lesson-a",
                        new Node().blueId(CYCLIC_MEMBER_BLUE_ID)));
        EmbeddedScopePlanner planner = new EmbeddedScopePlanner(reference -> {
            materializations.incrementAndGet();
            return objectFrozen();
        });

        // when
        SubscriptionSurfaceInvalidException failure = assertThrows(
                SubscriptionSurfaceInvalidException.class,
                () -> planner.plan(
                        scope,
                        "/",
                        Collections.emptyList(),
                        Collections.singletonList("/lessons"),
                        GasSchedule.contracts10()));

        // then
        assertEquals(
                ProcessorErrorCategory
                        .CyclicSetEmbeddedBoundaryUnsupported,
                failure.diagnostic().category());
        assertEquals(0, materializations.get());
    }

    @Test
    void shouldValidateMaterializedAcyclicOpaqueManagedTargetByExactIdentity() {
        Node child = new Node().properties(
                "value", new Node().value(1));
        String childBlueId = DirectBlueIdCalculator.calculateBlueId(child);
        Node scope = new Node().properties("child", child);
        Map<String, String> expected = new LinkedHashMap<String, String>();
        expected.put("/root/child", childBlueId);

        EmbeddedScopePlan plan = new EmbeddedScopePlanner()
                .planForOpaqueManagedRoot(
                        FrozenNode.fromNode(scope),
                        ROOT_SCOPE_PATH,
                        Collections.singletonList("/child"),
                        Collections.<String>emptyList(),
                        expected,
                        GasSchedule.contracts10());

        assertEquals(
                Collections.singletonList("/root/child"),
                plan.concreteChildPaths());
    }

    @Test
    void shouldRejectMaterializedCyclicOpaqueTargetWithoutOwningProof() {
        Node scope = new Node().properties(
                "child",
                new Node().properties("value", new Node().value(1)));
        Map<String, String> expected = new LinkedHashMap<String, String>();
        expected.put("/root/child", CYCLIC_MEMBER_BLUE_ID);

        InvalidExecutionEvidenceException failure = assertThrows(
                InvalidExecutionEvidenceException.class,
                () -> new EmbeddedScopePlanner()
                        .planForOpaqueManagedRoot(
                                FrozenNode.fromNode(scope),
                                ROOT_SCOPE_PATH,
                                Collections.singletonList("/child"),
                                Collections.<String>emptyList(),
                                expected,
                                GasSchedule.contracts10()));

        assertEquals(
                ProcessorErrorCategory.InvalidProcessingDocument,
                failure.errorCategory());
        assertTrue(failure.getMessage().contains(
                "owning cyclic-set proof"));
    }

    @Test
    void shouldPreserveUnavailablePlainReferenceAsRetryableEvidence() {
        // given
        String childBlueId = DirectBlueIdCalculator.calculateBlueId(
                new Node().properties("value", new Node().value(1)));
        Node scope = new Node().properties(
                "child", new Node().blueId(childBlueId));

        // when
        ExecutionEvidenceUnavailableException failure = assertThrows(
                ExecutionEvidenceUnavailableException.class,
                () -> new EmbeddedScopePlanner().plan(
                        scope,
                        "/",
                        Collections.singletonList("/child"),
                        Collections.emptyList(),
                        GasSchedule.contracts10()));

        // then
        assertEquals(
                Collections.singletonList(childBlueId),
                failure.requiredExactBlueIds());
    }

    @Test
    void shouldKeepUnselectedExplicitReferenceOpaqueForRevisionBoundEvent() {
        // given
        AtomicInteger materializations = new AtomicInteger();
        String childBlueId = DirectBlueIdCalculator.calculateBlueId(
                new Node().properties("value", new Node().value(1)));
        Node scope = new Node().properties(
                "child", new Node().blueId(childBlueId));
        EmbeddedScopePlanner planner = new EmbeddedScopePlanner(reference -> {
            materializations.incrementAndGet();
            return FrozenNode.fromNode(object());
        });

        // when
        EmbeddedScopePlan plan = planner.planForRevisionBoundEvent(
                scope,
                "/",
                Collections.singletonList("/child"),
                Collections.emptyList(),
                GasSchedule.contracts10());

        // then
        assertEquals(
                Collections.singletonList("/child"),
                plan.concreteChildPaths());
        assertEquals(0, materializations.get());
    }

    @Test
    void shouldAcceptVerifiedPureReferenceToObjectMember() {
        // given
        Node exactChild = new Node().properties(
                "value", new Node().value(1));
        String childBlueId = DirectBlueIdCalculator.calculateBlueId(exactChild);
        Node scope = new Node().properties(
                "children",
                new Node().properties(
                        "a", new Node().blueId(childBlueId)));
        EmbeddedScopePlanner planner = new EmbeddedScopePlanner(
                reference -> FrozenNode.fromNode(exactChild));

        // when
        EmbeddedScopePlan plan = planner.plan(
                scope,
                "/root",
                Collections.emptyList(),
                Collections.singletonList("/children"),
                GasSchedule.contracts10());

        // then
        assertEquals(
                Collections.singletonList("/root/children/a"),
                plan.concreteChildPaths());
        assertEquals(
                "a",
                plan.concretePaths().get(0).memberKey());
    }

    @Test
    void shouldEnforceCombinedDeclarationPortableLimitBeforeTraversal() {
        // given
        List<String> declarations = new ArrayList<>(
                Collections.nCopies(4097, "/child"));

        // when
        PortableLimitExceededException failure = assertThrows(
                PortableLimitExceededException.class,
                () -> new EmbeddedScopePlanner().plan(
                        object(),
                        "/",
                        declarations,
                        Collections.emptyList(),
                        GasSchedule.contracts10()));

        // then
        assertEquals(
                GasScheduleConstants.PortableLimit
                        .PROCESS_EMBEDDED_PATHS_PER_SCOPE,
                failure.limitName());
        assertEquals(4097L, failure.observed());
        assertEquals(4096L, failure.limit());
    }

    @Test
    void shouldRejectCombinedConcretePathSetAbovePortableLimit() {
        // given
        Map<String, Node> lessons = new LinkedHashMap<>();
        for (int index = 0; index < 4096; index++) {
            lessons.put("lesson-" + index, object());
        }
        Node scope = new Node().properties(
                "payment", object(),
                "lessons", new Node().properties(lessons));

        // when
        PortableLimitExceededException failure = assertThrows(
                PortableLimitExceededException.class,
                () -> new EmbeddedScopePlanner().plan(
                        scope,
                        ROOT_SCOPE_PATH,
                        Collections.singletonList("/payment"),
                        Collections.singletonList(LESSONS_DECLARATION),
                        GasSchedule.contracts10()));

        // then
        assertEquals(
                GasScheduleConstants.PortableLimit
                        .PROCESS_EMBEDDED_PATHS_PER_SCOPE,
                failure.limitName());
        assertEquals(4097L, failure.observed());
        assertEquals(4096L, failure.limit());
    }

    @Test
    void shouldPlanPureReferenceCollectionFromVerifiedProviderContent() {
        // given
        Node exactCollection = twoMemberCollection();
        String collectionBlueId = DirectBlueIdCalculator.calculateBlueId(
                exactCollection);
        Node scope = collectionReferenceScope(collectionBlueId);
        List<String> providerDemands = new ArrayList<>();
        NodeProvider provider = providerWithResult(
                collectionBlueId,
                NodeProviderResult.found(
                        Collections.singletonList(exactCollection)),
                providerDemands);

        // when
        EmbeddedScopePlan plan;
        try (BlueLanguage language = BlueLanguage.builder()
                .nodeProvider(provider)
                .build();
             LanguageProcessing.Scope processingScope =
                     language.processing().openScope()) {
            LanguageProcessingSnapshotManager manager =
                    new LanguageProcessingSnapshotManager(processingScope);
            plan = new EmbeddedScopePlanner(
                    manager::materializeVerifiedExactReference).plan(
                            scope,
                            ROOT_SCOPE_PATH,
                            Collections.emptyList(),
                            Collections.singletonList(LESSONS_DECLARATION),
                            GasSchedule.contracts10());
        }

        // then
        assertEquals(
                Arrays.asList(
                        "/root/lessons/lesson-a",
                        "/root/lessons/lesson-b"),
                plan.concreteChildPaths());
        assertEquals(
                Collections.singletonList(collectionBlueId),
                providerDemands);
    }

    @Test
    void shouldMapProviderNotFoundDuringCollectionPlanningToInvalidEvidence() {
        // given
        String collectionBlueId = DirectBlueIdCalculator.calculateBlueId(
                twoMemberCollection());
        Node scope = collectionReferenceScope(collectionBlueId);
        NodeProvider provider = providerWithResult(
                collectionBlueId,
                NodeProviderResult.notFound(),
                new ArrayList<>());

        // when
        InvalidExecutionEvidenceException failure;
        try (BlueLanguage language = BlueLanguage.builder()
                .nodeProvider(provider)
                .build();
             LanguageProcessing.Scope processingScope =
                     language.processing().openScope()) {
            LanguageProcessingSnapshotManager manager =
                    new LanguageProcessingSnapshotManager(processingScope);
            failure = assertThrows(
                    InvalidExecutionEvidenceException.class,
                    () -> new EmbeddedScopePlanner(
                            manager::materializeVerifiedExactReference).plan(
                                    scope,
                                    ROOT_SCOPE_PATH,
                                    Collections.emptyList(),
                                    Collections.singletonList(
                                            LESSONS_DECLARATION),
                                    GasSchedule.contracts10()));
        }

        // then
        assertEquals(
                ProcessorErrorCategory.InvalidProcessingDocument,
                failure.errorCategory());
    }

    @Test
    void shouldPreserveProviderUnavailabilityDuringCollectionPlanning() {
        // given
        String collectionBlueId = DirectBlueIdCalculator.calculateBlueId(
                twoMemberCollection());
        Node scope = collectionReferenceScope(collectionBlueId);
        NodeProvider provider = providerWithResult(
                collectionBlueId,
                NodeProviderResult.unavailable("collection provider offline"),
                new ArrayList<>());

        // when
        ExecutionEvidenceUnavailableException failure;
        try (BlueLanguage language = BlueLanguage.builder()
                .nodeProvider(provider)
                .build();
             LanguageProcessing.Scope processingScope =
                     language.processing().openScope()) {
            LanguageProcessingSnapshotManager manager =
                    new LanguageProcessingSnapshotManager(processingScope);
            failure = assertThrows(
                    ExecutionEvidenceUnavailableException.class,
                    () -> new EmbeddedScopePlanner(
                            manager::materializeVerifiedExactReference).plan(
                                    scope,
                                    ROOT_SCOPE_PATH,
                                    Collections.emptyList(),
                                    Collections.singletonList(
                                            LESSONS_DECLARATION),
                                    GasSchedule.contracts10()));
        }

        // then
        assertEquals("collection provider offline", failure.getMessage());
        assertEquals(
                Collections.singletonList(collectionBlueId),
                failure.requiredExactBlueIds());
    }

    @Test
    void shouldPreserveInvalidProviderEvidenceDuringCollectionPlanning() {
        // given
        String collectionBlueId = DirectBlueIdCalculator.calculateBlueId(
                twoMemberCollection());
        Node scope = collectionReferenceScope(collectionBlueId);
        NodeProvider provider = providerWithResult(
                collectionBlueId,
                NodeProviderResult.invalidEvidence(
                        "collection evidence is forged"),
                new ArrayList<>());

        // when
        InvalidExecutionEvidenceException failure;
        try (BlueLanguage language = BlueLanguage.builder()
                .nodeProvider(provider)
                .build();
             LanguageProcessing.Scope processingScope =
                     language.processing().openScope()) {
            LanguageProcessingSnapshotManager manager =
                    new LanguageProcessingSnapshotManager(processingScope);
            failure = assertThrows(
                    InvalidExecutionEvidenceException.class,
                    () -> new EmbeddedScopePlanner(
                            manager::materializeVerifiedExactReference).plan(
                                    scope,
                                    ROOT_SCOPE_PATH,
                                    Collections.emptyList(),
                                    Collections.singletonList(
                                            LESSONS_DECLARATION),
                                    GasSchedule.contracts10()));
        }

        // then
        assertEquals("collection evidence is forged", failure.getMessage());
        assertEquals(
                ProcessorErrorCategory.InvalidExternalChannelSnapshot,
                failure.errorCategory());
    }

    @Test
    void shouldNotDemandTransitiveDescendantsOrExecutableBodiesForEnumeration() {
        // given
        Node exactDescendant = new Node().properties(
                "state", new Node().value("descendant"));
        Node exactBody = new Node().properties(
                "patch", new Node().value("body"));
        String descendantBlueId = DirectBlueIdCalculator.calculateBlueId(
                exactDescendant);
        String bodyBlueId = DirectBlueIdCalculator.calculateBlueId(exactBody);
        Node lesson = new Node()
                .properties(
                        "descendant",
                        new Node().blueId(descendantBlueId))
                .contracts(new Node().properties(
                        "handler",
                        new Node().properties(
                                "result",
                                new Node().blueId(bodyBlueId))));
        Node scope = new Node().properties(
                "lessons",
                new Node().properties("lesson-a", lesson));
        AtomicInteger materializations = new AtomicInteger();
        EmbeddedScopePlanner planner = new EmbeddedScopePlanner(reference -> {
            materializations.incrementAndGet();
            throw new AssertionError(
                    "Enumeration must not materialize descendant content");
        });

        // when
        EmbeddedScopePlan plan = planner.plan(
                scope,
                ROOT_SCOPE_PATH,
                Collections.emptyList(),
                Collections.singletonList(LESSONS_DECLARATION),
                GasSchedule.contracts10());

        // then
        assertEquals(
                Collections.singletonList("/root/lessons/lesson-a"),
                plan.concreteChildPaths());
        assertEquals(0, materializations.get());
    }

    @Test
    void shouldProduceExactGasTraceForTwoMemberInlineCollection() {
        // given
        Node scope = new Node().properties(
                "lessons", twoMemberCollection());
        GasMeter meter = new GasMeter(GasSchedule.contracts10());

        // when
        new EmbeddedScopePlanner().plan(
                FrozenNode.fromResolvedNode(scope),
                ROOT_SCOPE_PATH,
                Collections.emptyList(),
                Collections.singletonList(LESSONS_DECLARATION),
                meter);

        // then
        assertEquals(twoMemberCollectionTrace(), traceSignatures(meter));
        assertEquals(19L, meter.totalGas());
    }

    @Test
    void shouldProduceExactGasTraceForTwoMemberReferencedCollection() {
        // given
        Node exactCollection = twoMemberCollection();
        String collectionBlueId = DirectBlueIdCalculator.calculateBlueId(
                exactCollection);
        Node scope = collectionReferenceScope(collectionBlueId);
        List<String> providerDemands = new ArrayList<>();
        NodeProvider provider = providerWithResult(
                collectionBlueId,
                NodeProviderResult.found(
                        Collections.singletonList(exactCollection)),
                providerDemands);
        GasMeter meter = new GasMeter(GasSchedule.contracts10());

        // when
        try (BlueLanguage language = BlueLanguage.builder()
                .nodeProvider(provider)
                .build();
             LanguageProcessing.Scope processingScope =
                     language.processing().openScope()) {
            LanguageProcessingSnapshotManager manager =
                    new LanguageProcessingSnapshotManager(processingScope);
            new EmbeddedScopePlanner(
                    manager::materializeVerifiedExactReference).plan(
                            FrozenNode.fromResolvedNode(scope),
                            ROOT_SCOPE_PATH,
                            Collections.emptyList(),
                            Collections.singletonList(LESSONS_DECLARATION),
                            meter);
        }

        // then
        assertEquals(twoMemberCollectionTrace(), traceSignatures(meter));
        assertEquals(19L, meter.totalGas());
        assertEquals(
                Collections.singletonList(collectionBlueId),
                providerDemands);
    }

    @Test
    void shouldKeepInlineAndProviderBackedCollectionGasIdentical() {
        // given
        Node exactCollection = twoMemberCollection();
        String collectionBlueId = DirectBlueIdCalculator.calculateBlueId(
                exactCollection);
        GasMeter inlineMeter = new GasMeter(GasSchedule.contracts10());
        GasMeter providerMeter = new GasMeter(GasSchedule.contracts10());
        NodeProvider provider = providerWithResult(
                collectionBlueId,
                NodeProviderResult.found(
                        Collections.singletonList(exactCollection)),
                new ArrayList<>());

        // when
        new EmbeddedScopePlanner().plan(
                FrozenNode.fromResolvedNode(new Node().properties(
                        "lessons", exactCollection)),
                ROOT_SCOPE_PATH,
                Collections.emptyList(),
                Collections.singletonList(LESSONS_DECLARATION),
                inlineMeter);
        try (BlueLanguage language = BlueLanguage.builder()
                .nodeProvider(provider)
                .build();
             LanguageProcessing.Scope processingScope =
                     language.processing().openScope()) {
            LanguageProcessingSnapshotManager manager =
                    new LanguageProcessingSnapshotManager(processingScope);
            new EmbeddedScopePlanner(
                    manager::materializeVerifiedExactReference).plan(
                            FrozenNode.fromResolvedNode(
                                    collectionReferenceScope(
                                            collectionBlueId)),
                            ROOT_SCOPE_PATH,
                            Collections.emptyList(),
                            Collections.singletonList(
                                    LESSONS_DECLARATION),
                            providerMeter);
        }

        // then
        assertEquals(inlineMeter.totalGas(), providerMeter.totalGas());
        assertEquals(
                traceSignatures(inlineMeter),
                traceSignatures(providerMeter));
    }

    @Test
    void shouldChargeDeclarationsCollectionOpeningAndGeneratedPaths() {
        // given
        Node scope = new Node().properties(
                "payment", object(),
                "lessons", new Node().properties(
                        "b", object(),
                        "a", object()));
        GasMeter meter = new GasMeter(GasSchedule.contracts10());

        // when
        new EmbeddedScopePlanner().plan(
                FrozenNode.fromResolvedNode(scope),
                "/",
                Collections.singletonList("/payment"),
                Collections.singletonList("/lessons"),
                meter);

        // then
        assertEquals(4L, quantity(
                meter.trace(),
                GasScheduleConstants.ProcessorCounter
                        .EMBEDDED_PATH_ENTRY_READ));
        assertEquals(6L, quantity(
                meter.trace(),
                GasScheduleConstants.ProcessorCounter
                        .EMBEDDED_PATH_SEGMENT_VALIDATED));
        assertEquals(1L, quantity(
                meter.trace(),
                GasScheduleConstants.SemanticCounter.NODE_MANIFEST_OPENED));
        assertEquals(2L, quantity(
                meter.trace(),
                GasScheduleConstants.SemanticCounter.OBJECT_MEMBER_READ));
    }

    private static long quantity(
            List<GasTraceEntry> trace,
            String counter) {
        long result = 0L;
        for (GasTraceEntry entry : trace) {
            if (counter.equals(entry.counter())) {
                result += entry.quantity();
            }
        }
        return result;
    }

    private static Node object() {
        return new Node();
    }

    private static Node collectionWithOneMember() {
        return new Node().properties(
                "member",
                new Node().properties(
                        "state", new Node().value(1)));
    }

    private static Node twoMemberCollection() {
        return new Node().properties(
                "lesson-b", new Node().properties(
                        "state", new Node().value("b")),
                "lesson-a", new Node().properties(
                        "state", new Node().value("a")));
    }

    private static Node collectionReferenceScope(String collectionBlueId) {
        return new Node().properties(
                "lessons", new Node().blueId(collectionBlueId));
    }

    private static NodeProvider providerWithResult(
            String requestedBlueId,
            NodeProviderResult requestedResult,
            List<String> providerDemands) {
        return new NodeProvider() {
            @Override
            public List<Node> fetchByBlueId(String blueId) {
                NodeProviderResult result = fetchResultByBlueId(blueId);
                return result.outcome() == NodeProviderOutcome.FOUND
                        ? result.nodes()
                        : null;
            }

            @Override
            public NodeProviderResult fetchResultByBlueId(String blueId) {
                providerDemands.add(blueId);
                return requestedBlueId.equals(blueId)
                        ? requestedResult
                        : NodeProviderResult.notFound();
            }
        };
    }

    private static List<String> traceSignatures(GasMeter meter) {
        List<String> signatures = new ArrayList<>();
        for (GasTraceEntry entry : meter.trace()) {
            signatures.add(traceSignature(
                    entry.sequence(),
                    entry.namespace(),
                    entry.counter(),
                    entry.quantity(),
                    entry.weight(),
                    entry.subtotal(),
                    entry.scopePath(),
                    entry.contractKey(),
                    entry.logicalPath(),
                    entry.reason()));
        }
        return signatures;
    }

    private static List<String> twoMemberCollectionTrace() {
        String processor = GasScheduleConstants.Namespace.PROCESSOR;
        String semantic = GasScheduleConstants.Namespace.SEMANTIC;
        String embedded = ProcessorContractConstants.KEY_EMBEDDED;
        String route = GasScheduleConstants.ChargeReason.ROUTE;
        return Arrays.asList(
                unitTrace(0L, processor,
                        GasScheduleConstants.ProcessorCounter
                                .EMBEDDED_PATH_ENTRY_READ,
                        1L, ROOT_SCOPE_PATH, null,
                        "/root/lessons", route),
                unitTrace(1L, processor,
                        GasScheduleConstants.ProcessorCounter
                                .EMBEDDED_PATH_SEGMENT_VALIDATED,
                        1L, ROOT_SCOPE_PATH, null,
                        "/root/lessons", route),
                unitTrace(2L, semantic,
                        GasScheduleConstants.SemanticCounter
                                .NODE_MANIFEST_OPENED,
                        1L, ROOT_SCOPE_PATH, embedded,
                        "/root/lessons", route),
                unitTrace(3L, semantic,
                        GasScheduleConstants.SemanticCounter
                                .OBJECT_MEMBER_READ,
                        2L, ROOT_SCOPE_PATH, embedded,
                        "/root/lessons", route),
                unitTrace(4L, semantic,
                        GasScheduleConstants.SemanticCounter
                                .SORT_COMPARISON,
                        1L, ROOT_SCOPE_PATH, embedded,
                        LESSONS_DECLARATION, route),
                unitTrace(5L, semantic,
                        GasScheduleConstants.SemanticCounter
                                .SCALAR_COMPARISON,
                        1L, ROOT_SCOPE_PATH, embedded,
                        LESSONS_DECLARATION, route),
                unitTrace(6L, semantic,
                        GasScheduleConstants.SemanticCounter
                                .TEXT_BLOCK_EXAMINED,
                        1L, ROOT_SCOPE_PATH, embedded,
                        LESSONS_DECLARATION, route),
                unitTrace(7L, semantic,
                        GasScheduleConstants.SemanticCounter
                                .TEXT_BLOCK_EXAMINED,
                        1L, ROOT_SCOPE_PATH, embedded,
                        LESSONS_DECLARATION, route),
                unitTrace(8L, processor,
                        GasScheduleConstants.ProcessorCounter
                                .EMBEDDED_PATH_ENTRY_READ,
                        1L, ROOT_SCOPE_PATH, null,
                        "/root/lessons/lesson-a", route),
                unitTrace(9L, processor,
                        GasScheduleConstants.ProcessorCounter
                                .EMBEDDED_PATH_SEGMENT_VALIDATED,
                        2L, ROOT_SCOPE_PATH, null,
                        "/root/lessons/lesson-a", route),
                unitTrace(10L, processor,
                        GasScheduleConstants.ProcessorCounter
                                .EMBEDDED_PATH_ENTRY_READ,
                        1L, ROOT_SCOPE_PATH, null,
                        "/root/lessons/lesson-b", route),
                unitTrace(11L, processor,
                        GasScheduleConstants.ProcessorCounter
                                .EMBEDDED_PATH_SEGMENT_VALIDATED,
                        2L, ROOT_SCOPE_PATH, null,
                        "/root/lessons/lesson-b", route),
                unitTrace(12L, semantic,
                        GasScheduleConstants.SemanticCounter
                                .SORT_COMPARISON,
                        1L, ROOT_SCOPE_PATH, embedded,
                        ROOT_SCOPE_PATH, route),
                unitTrace(13L, semantic,
                        GasScheduleConstants.SemanticCounter
                                .SCALAR_COMPARISON,
                        1L, ROOT_SCOPE_PATH, embedded,
                        ROOT_SCOPE_PATH, route),
                unitTrace(14L, semantic,
                        GasScheduleConstants.SemanticCounter
                                .TEXT_BLOCK_EXAMINED,
                        1L, ROOT_SCOPE_PATH, embedded,
                        ROOT_SCOPE_PATH, route),
                unitTrace(15L, semantic,
                        GasScheduleConstants.SemanticCounter
                                .TEXT_BLOCK_EXAMINED,
                        1L, ROOT_SCOPE_PATH, embedded,
                        ROOT_SCOPE_PATH, route));
    }

    private static String unitTrace(
            long sequence,
            String namespace,
            String counter,
            long quantity,
            String scopePath,
            String contractKey,
            String logicalPath,
            String reason) {
        return traceSignature(
                sequence,
                namespace,
                counter,
                quantity,
                1L,
                quantity,
                scopePath,
                contractKey,
                logicalPath,
                reason);
    }

    private static String traceSignature(
            long sequence,
            String namespace,
            String counter,
            long quantity,
            long weight,
            long subtotal,
            String scopePath,
            String contractKey,
            String logicalPath,
            String reason) {
        return sequence
                + "|" + namespace
                + "|" + counter
                + "|" + quantity
                + "|" + weight
                + "|" + subtotal
                + "|" + scopePath
                + "|" + contractKey
                + "|" + logicalPath
                + "|" + reason;
    }

    private static EmbeddedConcretePath explicitConcrete(String path) {
        return new EmbeddedConcretePath(
                path,
                EmbeddedPathOrigin.EXPLICIT,
                path,
                null);
    }

    private static FrozenNode objectFrozen() {
        return FrozenNode.fromResolvedNode(object());
    }
}
