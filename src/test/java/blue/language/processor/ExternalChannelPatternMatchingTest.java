package blue.language.processor;

import blue.language.Blue;
import blue.language.provider.NodeProvider;
import blue.language.conformance.ConformanceEngine;
import blue.language.merge.IncrementalValueResolutionRequest;
import blue.language.model.Node;
import blue.language.processor.model.ChannelContract;
import blue.language.processor.model.JsonPatch;
import blue.language.snapshot.FrozenNode;
import blue.language.snapshot.ResolvedSnapshot;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.FrozenTypeMatcher;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static blue.language.processor.DocumentProcessingResultTestSupport
        .diagnosticMessage;
import static blue.language.processor.FailureCapture.captureFailure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ExternalChannelPatternMatchingTest {

    private static final Node LEAF_TYPE =
            new Node().name("Pattern Matching Leaf Channel");
    private static final String LEAF_TYPE_BLUE_ID =
            BlueIdCalculator.calculateBlueId(LEAF_TYPE);
    private static final Node AGGREGATE_TYPE =
            new Node().name("Pattern Matching Aggregate Channel");
    private static final String AGGREGATE_TYPE_BLUE_ID =
            BlueIdCalculator.calculateBlueId(
                    AGGREGATE_TYPE);
    private static final ExternalOrderKey EVENT_ORDER =
            ExternalOrderKey.of(
                    Collections.<Object>singletonList(
                            "pattern-event"));

    @Test
    void shouldMatchInlineCandidateWithoutExactMaterialization() {
        // given
        boolean referenceCandidate = false;

        // when
        CandidateMatchObservation observation =
                observeCandidateMatch(
                        referenceCandidate,
                        false);

        // then
        assertTrue(observation.first.accepts());
        assertEquals(0, observation.exactCallsAfterFirst);
        assertEquals(0, observation.providerFetchesAfterFirst);
        assertEquals(
                observation.patternIdentity,
                observation.patternIdentityAfterEvaluation);
        assertEquals(
                observation.eventIdentity,
                observation.eventIdentityAfterEvaluation);
        assertEquals("retained", observation.retainedDetail);
    }

    @Test
    void shouldMatchPureReferenceCandidateWithPassLocalCaches() {
        // given
        boolean referenceCandidate = true;

        // when
        CandidateMatchObservation observation =
                observeCandidateMatch(
                        referenceCandidate,
                        true);

        // then
        assertTrue(observation.first.accepts());
        assertTrue(observation.repeated.accepts());
        /*
         * The aggregate reevaluates its selected member from ACCEPTS,
         * PAYLOAD, and CHECKPOINT_SUBJECT, while the leaf itself asks the
         * matcher twice. One exact materialization per deterministic pass
         * proves that all nested calls share that pass's matcher. Two calls
         * per evaluation prove that passes do not share matcher caches.
         */
        assertEquals(2, observation.exactCallsAfterFirst);
        assertEquals(1, observation.providerFetchesAfterFirst);
        assertEquals(4, observation.exactCallsAfterRepeat);
        assertEquals(
                1,
                observation.providerFetchesAfterRepeat,
                "verified canonical materialization should reuse the "
                        + "snapshot manager's cache");
        assertEquals(
                observation.first.dependencies(),
                observation.repeated.dependencies());
        assertTrue(observation.reference.isReferenceOnly());
        assertEquals(
                observation.candidateBlueId,
                observation.reference.getBlueId());
        assertEquals(
                observation.patternIdentity,
                observation.patternIdentityAfterEvaluation);
        assertEquals(
                observation.eventIdentity,
                observation.eventIdentityAfterEvaluation);
        assertEquals("retained", observation.retainedDetail);
    }

    @Test
    void shouldPreserveEvaluationSemanticsAcrossInlineAndReferenceCandidates() {
        // given
        boolean inlineCandidate = false;
        boolean referenceCandidate = true;

        // when
        CandidateMatchObservation inline =
                observeCandidateMatch(
                        inlineCandidate,
                        false);
        CandidateMatchObservation reference =
                observeCandidateMatch(
                        referenceCandidate,
                        false);

        // then
        assertEquals(
                inline.first.checkpointDomainBlueId(),
                reference.first.checkpointDomainBlueId());
        assertEquals(
                inline.first.dependencies(),
                reference.first.dependencies());
    }

    @Test
    void shouldResolveNestedCandidateReferenceDuringPatternMatching() {
        // given
        Node nested = new Node()
                .properties(
                        "kind",
                        new Node().value("coordination"))
                .properties(
                        "detail",
                        new Node().value("nested-retained"));
        String nestedBlueId =
                BlueIdCalculator.calculateBlueId(nested);
        Map<String, Node> supplied =
                Collections.singletonMap(
                        nestedBlueId,
                        nested);
        NodeProvider provider = provider(supplied);
        Node nestedPattern = new Node().properties(
                "nested",
                new Node().properties(
                        "kind",
                        new Node().value(
                                "coordination")));
        Node nestedCandidate = new Node()
                .properties(
                        "nested",
                        reference(nestedBlueId))
                .properties(
                        "outerDetail",
                        new Node().value("retained"));

        // when
        ExternalChannelFunctionEvaluation result;
        int exactCalls;
        try (Blue blue = runtime(
                provider,
                new PatternLeafProcessor(false),
                new PatternAggregateProcessor())) {
            DocumentProcessor processor =
                    blue.getDocumentProcessor();
            CountingSnapshotManager manager =
                    new CountingSnapshotManager(
                            processor.snapshotManager());
            result = evaluate(
                    processor,
                    manager,
                    bundle(
                            processor,
                            root(leaf(
                                    "leaf",
                                    nestedPattern))),
                    "leaf",
                    event(nestedCandidate));
            exactCalls =
                    manager.exactCalls(nestedBlueId);
        }

        // then
        assertTrue(result.accepts());
        assertEquals(2, exactCalls);
    }

    @Test
    void shouldResolveExactCanonicalTypeLineageDuringPatternMatching() {
        // given
        Node baseType =
                new Node().name("Pattern Base");
        String baseBlueId =
                BlueIdCalculator.calculateBlueId(baseType);
        Node parentType =
                new Node()
                        .name("Pattern Parent")
                        .type(reference(baseBlueId));
        String parentBlueId =
                BlueIdCalculator.calculateBlueId(
                        parentType);
        Node childType =
                new Node()
                        .name("Pattern Child")
                        .type(reference(parentBlueId));
        String childBlueId =
                BlueIdCalculator.calculateBlueId(childType);
        Map<String, Node> supplied =
                new LinkedHashMap<>();
        supplied.put(baseBlueId, baseType);
        supplied.put(parentBlueId, parentType);
        supplied.put(childBlueId, childType);
        NodeProvider provider = provider(supplied);
        Node lineagePattern =
                new Node().type(
                        reference(baseBlueId));
        Node lineageCandidate =
                new Node()
                        .type(reference(childBlueId))
                        .properties(
                                "extended",
                                new Node().value(true));

        // when
        ExternalChannelFunctionEvaluation result;
        int childCalls;
        int parentCalls;
        int baseCalls;
        try (Blue blue = runtime(
                provider,
                new PatternLeafProcessor(false),
                new PatternAggregateProcessor())) {
            DocumentProcessor processor =
                    blue.getDocumentProcessor();
            CountingSnapshotManager lineageManager =
                    new CountingSnapshotManager(
                            processor.snapshotManager());
            result = evaluate(
                            processor,
                            lineageManager,
                            bundle(
                                    processor,
                                    root(leaf(
                                            "leaf",
                                            lineagePattern))),
                            "leaf",
                            event(lineageCandidate));
            childCalls =
                    lineageManager.exactCalls(childBlueId);
            parentCalls =
                    lineageManager.exactCalls(parentBlueId);
            baseCalls =
                    lineageManager.exactCalls(baseBlueId);
        }

        // then
        assertTrue(
                result.accepts(),
                "an exact canonical child definition should follow its "
                        + "exact parent reference");
        assertEquals(2, childCalls);
        assertEquals(2, parentCalls);
        assertEquals(
                0,
                baseCalls,
                "the exact parent reference identity is sufficient once "
                        + "the intermediate definition is materialized");
        assertTrue(lineageCandidate.getType()
                .isReferenceOnly());
        assertEquals(
                childBlueId,
                lineageCandidate.getType()
                        .getBlueId());
    }

    @Test
    void shouldPropagateMissingReferenceMaterialization() {
        // given
        Node candidate = extendedCandidate();
        String candidateBlueId =
                BlueIdCalculator.calculateBlueId(candidate);

        // when
        RuntimeException failure =
                captureReferenceFailure(
                        candidateBlueId,
                        blueId -> null,
                        null);

        // then
        assertTrue(failure.getMessage().contains(
                candidateBlueId));
    }

    @Test
    void shouldPropagateMismatchedProviderMaterialization() {
        // given
        Node candidate = extendedCandidate();
        String candidateBlueId =
                BlueIdCalculator.calculateBlueId(candidate);
        Node wrong = new Node().value("wrong-content");
        NodeProvider provider = blueId ->
                candidateBlueId.equals(blueId)
                        ? Collections.singletonList(wrong.clone())
                        : null;

        // when
        RuntimeException failure =
                captureReferenceFailure(
                        candidateBlueId,
                        provider,
                        null);

        // then
        assertTrue(failure.getMessage().contains(
                candidateBlueId));
    }

    @Test
    void shouldPropagateVerifiedMaterializerFailure() {
        // given
        String candidateBlueId =
                BlueIdCalculator.calculateBlueId(
                        extendedCandidate());
        IllegalStateException sentinel =
                new IllegalStateException(
                        "verified manager unavailable");

        // when
        RuntimeException failure =
                captureReferenceFailure(
                        candidateBlueId,
                        null,
                        reference -> {
                            throw sentinel;
                        });

        // then
        assertSame(sentinel, failure);
    }

    @Test
    void shouldRejectVerifiedMaterializerWithoutContent() {
        // given
        String candidateBlueId =
                BlueIdCalculator.calculateBlueId(
                        extendedCandidate());

        // when
        RuntimeException failure =
                captureReferenceFailure(
                        candidateBlueId,
                        null,
                        reference -> null);

        // then
        assertEquals(IllegalArgumentException.class,
                failure.getClass());
        assertTrue(failure.getMessage().contains(
                "returned no content"));
    }

    @Test
    void shouldRejectVerifiedMaterializerRetainingPureReference() {
        // given
        String candidateBlueId =
                BlueIdCalculator.calculateBlueId(
                        extendedCandidate());

        // when
        RuntimeException failure =
                captureReferenceFailure(
                        candidateBlueId,
                        null,
                        reference -> reference);

        // then
        assertEquals(IllegalArgumentException.class,
                failure.getClass());
        assertTrue(failure.getMessage().contains(
                "retained a pure reference"));
    }

    @Test
    void shouldRejectVerifiedMaterializerWithMismatchedContent() {
        // given
        String candidateBlueId =
                BlueIdCalculator.calculateBlueId(
                        extendedCandidate());
        Node wrong = new Node().value("wrong-content");

        // when
        RuntimeException failure =
                captureReferenceFailure(
                        candidateBlueId,
                        null,
                        reference -> FrozenNode.fromNode(wrong));

        // then
        assertEquals(IllegalArgumentException.class,
                failure.getClass());
        assertTrue(failure.getMessage().contains(
                "mismatched content"));
    }

    @Test
    void shouldVerifyHeaderPatternMatchingFailsBeforeAnyMaterialization() {
        // given
        PatternLeafProcessor processorFunctions =
                new PatternLeafProcessor(true);
        IllegalStateException failure;
        int exactCalls;
        try (Blue blue = runtime(
                null,
                processorFunctions,
                new PatternAggregateProcessor())) {
            DocumentProcessor processor =
                    blue.getDocumentProcessor();
            Node document = root(
                    leaf("leaf", kindPattern()));
            ContractBundle bundle = bundle(
                    processor, document);
            EffectiveContractSnapshot snapshot =
                    bundle.effectiveContractSnapshot(
                            "leaf");
            CountingSnapshotManager manager =
                    new CountingSnapshotManager(
                            processor.snapshotManager());
            ExternalChannelFunctionEvaluation.MatcherSession
                    matcher =
                    ExternalChannelFunctionEvaluation
                            .verifiedMatcherSessions(
                                    manager)
                            .open();

            // when
            failure = captureFailure(
                            () -> new ExternalChannelFunctionResolver(
                                    processor.registry(),
                                    processor.contractConverter(),
                                    matcher,
                                    bundle)
                                    .header(snapshot));
            matcher.close();
            exactCalls = manager.totalExactCalls();
        }

        // then
        assertEquals(IllegalStateException.class,
                failure.getClass());
        assertTrue(failure.getMessage().contains(
                "available only during event evaluation"));
        assertEquals(0, exactCalls);
    }

    @Test
    void shouldVerifyEventEvaluationRecomputesHeadersWithoutMatcherAccess() {
        // given
        Node headerCandidate = extendedCandidate();
        String headerCandidateBlueId =
                BlueIdCalculator.calculateBlueId(
                        headerCandidate);
        AtomicInteger providerFetches =
                new AtomicInteger();
        NodeProvider provider = blueId -> {
            if (!headerCandidateBlueId.equals(blueId)) {
                return null;
            }
            providerFetches.incrementAndGet();
            return Collections.singletonList(
                    headerCandidate.clone());
        };
        PatternLeafProcessor functions =
                new PatternLeafProcessor(
                        false,
                        reference(headerCandidateBlueId),
                        "peer");

        try (Blue blue = runtime(
                provider,
                functions,
                new PatternAggregateProcessor())) {
            DocumentProcessor processor =
                    blue.getDocumentProcessor();
            ContractBundle bundle = bundle(
                    processor,
                    root(leaf(
                            "leaf",
                            kindPattern()),
                            leaf(
                                    "peer",
                                    kindPattern())));
            CountingSnapshotManager manager =
                    new CountingSnapshotManager(
                            processor.snapshotManager());

            // when
            ExternalChannelFunctionEvaluation evaluation =
                    evaluate(
                            processor,
                            manager,
                            bundle,
                            "leaf",
                            event(extendedCandidate()));
            int exactCalls =
                    manager.exactCalls(
                            headerCandidateBlueId);
            int fetched = providerFetches.get();

            // then
            assertTrue(evaluation.accepts());
            assertEquals(
                    4,
                    functions.headerMatchFailures(),
                    "each deterministic pass must reject matcher access "
                            + "during both header derivations");
            assertEquals(
                    4,
                    functions.headerMemberEvaluationFailures(),
                    "header contexts must reject indirect event matching "
                            + "through member evaluation");
            assertEquals(0, exactCalls);
            assertEquals(0, fetched);
        }
    }

    @Test
    void shouldVerifyDispatchOverrideDetectionUsesExactErasedSignatures() {
        // given
        ExternalChannelSubscriptionFunctions<
                PatternLeafChannel> unrelatedOverloads =
                new ExternalChannelSubscriptionFunctions<
                        PatternLeafChannel>() {
                    public boolean preselects(
                            String left,
                            String right) {
                        return false;
                    }

                    public boolean accepts(
                            String first,
                            String second,
                            String third) {
                        return false;
                    }
                };
        ExternalChannelSubscriptionFunctions<
                PatternLeafChannel> exactOverrides =
                new ExternalChannelSubscriptionFunctions<
                        PatternLeafChannel>() {
                    @Override
                    public boolean preselects(
                            PatternLeafChannel contract,
                            Node exactEvent,
                            ExternalChannelFunctionContext context) {
                        return true;
                    }

                    @Override
                    public boolean accepts(
                            PatternLeafChannel contract,
                            Node exactEvent) {
                        return true;
                    }
                };

        // when
        boolean unrelatedPreselects =
                ExternalChannelFunctionResolver
                        .overridesExact(
                                unrelatedOverloads,
                                "preselects",
                                ChannelContract.class,
                                Node.class);
        boolean unrelatedAccepts =
                ExternalChannelFunctionResolver
                        .overridesExact(
                                unrelatedOverloads,
                                "accepts",
                                ChannelContract.class,
                                Node.class,
                                ExternalChannelFunctionContext.class);
        boolean exactPreselects =
                ExternalChannelFunctionResolver
                        .overridesExact(
                                exactOverrides,
                                "preselects",
                                ChannelContract.class,
                                Node.class,
                                ExternalChannelFunctionContext.class);
        boolean exactAccepts =
                ExternalChannelFunctionResolver
                        .overridesExact(
                                exactOverrides,
                                "accepts",
                                ChannelContract.class,
                                Node.class);

        // then
        assertFalse(unrelatedPreselects);
        assertFalse(unrelatedAccepts);
        assertTrue(
                exactPreselects);
        assertTrue(exactAccepts);
    }

    @Test
    void shouldVerifyRetainedEventContextCannotMatchAfterItsPassCloses() {
        // given
        PatternLeafProcessor functions =
                new PatternLeafProcessor(false);
        boolean accepted;
        IllegalStateException closed;
        IllegalStateException nullPattern;
        IllegalStateException nullCandidate;
        IllegalStateException retainedMember;
        try (Blue blue = runtime(
                null,
                functions,
                new PatternAggregateProcessor())) {
            DocumentProcessor processor =
                    blue.getDocumentProcessor();
            Node pattern = kindPattern();
            ContractBundle bundle = bundle(
                    processor,
                    root(
                            leaf("leaf", pattern),
                            leaf("peer", pattern)));

            // when
            accepted = evaluate(
                            processor,
                            processor.snapshotManager(),
                            bundle,
                            "leaf",
                            event(extendedCandidate()))
                            .accepts();
            closed = captureFailure(
                            () -> functions
                                    .lastContext()
                                    .matchesPattern(
                                            extendedCandidate(),
                                            pattern));
            nullPattern = captureFailure(
                    () -> functions
                            .lastContext()
                                    .matchesPattern(
                                            extendedCandidate(),
                                            null));
            nullCandidate = captureFailure(
                    () -> functions
                            .lastContext()
                                    .matchesPattern(
                                            null,
                                            kindPattern()));
            retainedMember = captureFailure(
                            () -> functions
                                    .lastContext()
                                    .member("peer")
                                    .evaluate(
                                            event(
                                                    extendedCandidate())));
        }

        // then
        assertTrue(accepted);
        assertEquals(IllegalStateException.class,
                closed.getClass());
        assertTrue(closed.getMessage().contains(
                "no longer active"));
        assertEquals(IllegalStateException.class,
                nullPattern.getClass());
        assertEquals(IllegalStateException.class,
                nullCandidate.getClass());
        assertEquals(IllegalStateException.class,
                retainedMember.getClass());
        assertTrue(retainedMember.getMessage().contains(
                "no longer active"));
    }

    @Test
    void shouldVerifyClosedMatcherSessionSeversVerifiedManagerCapture()
            throws Exception {
        // given
        ExternalChannelFunctionEvaluation.MatcherSession
                session =
                ExternalChannelFunctionEvaluation
                        .verifiedMatcherSessions(
                                materializer(reference -> null))
                        .open();

        // when
        boolean matched = session.matches(
                        FrozenNode.fromResolvedNode(
                                extendedCandidate()),
                        FrozenNode.fromResolvedNode(
                                kindPattern()));

        session.close();

        Field matcherField =
                session.getClass()
                        .getDeclaredField("matcher");
        matcherField.setAccessible(true);

        // then
        assertTrue(matched);
        assertEquals(
                FrozenTypeMatcher.class,
                matcherField.getType());
        assertNull(matcherField.get(session));
        for (Field field
                : session.getClass()
                .getDeclaredFields()) {
            assertFalse(
                    ProcessingSnapshotManager.class
                            .isAssignableFrom(
                                    field.getType()),
                    "closed session must not retain a manager field");
            assertFalse(
                    field.getName().startsWith("this$"),
                    "matcher session must remain a static wrapper");
        }
    }

    @Test
    void shouldVerifyAbsentManagerAllowsInlineMatchingButRejectsReferenceDemand() {
        // given
        Node candidate = extendedCandidate();
        String candidateBlueId =
                BlueIdCalculator.calculateBlueId(candidate);
        try (Blue blue = runtime(
                null,
                new PatternLeafProcessor(false),
                new PatternAggregateProcessor())) {
            DocumentProcessor processor =
                    blue.getDocumentProcessor();
            ContractBundle bundle = bundle(
                    processor,
                    root(leaf("leaf", kindPattern())));

            // when
            ExternalChannelFunctionEvaluation inlineEvaluation =
                    evaluate(
                            processor,
                            null,
                            bundle,
                            "leaf",
                            event(candidate));
            Throwable unavailable =
                    captureFailure(
                            () -> evaluate(
                                    processor,
                                    null,
                                    bundle,
                                    "leaf",
                                    event(reference(
                                            candidateBlueId))));

            // then
            assertTrue(inlineEvaluation.accepts());
            assertTrue(unavailable instanceof IllegalStateException);
            assertTrue(unavailable.getMessage().contains(
                    "requires a verified ProcessingSnapshotManager"));
        }
    }

    @Test
    void shouldVerifyRootVerifierAndChannelRunnerUseCapturedSnapshotManager() {
        // given
        Node candidate = extendedCandidate();
        String candidateBlueId =
                BlueIdCalculator.calculateBlueId(candidate);
        AtomicInteger providerFetches =
                new AtomicInteger();
        NodeProvider provider = blueId -> {
            if (!candidateBlueId.equals(blueId)) {
                return null;
            }
            providerFetches.incrementAndGet();
            return Collections.singletonList(
                    candidate.clone());
        };
        PatternLeafProcessor functions =
                new PatternLeafProcessor(false);
        AtomicReference<ExternalDeliveryPlan> plan =
                new AtomicReference<>();

        try (Blue language = runtime(
                provider,
                new PatternLeafProcessor(false),
                new PatternAggregateProcessor())) {
            CountingSnapshotManager manager =
                    new CountingSnapshotManager(
                            language.getDocumentProcessor()
                                    .snapshotManager());
            DocumentProcessor owner =
                    DocumentProcessor.builder()
                            .registerContractProcessor(
                                    LEAF_TYPE_BLUE_ID,
                                    LEAF_TYPE,
                                    functions)
                            .withMatchingService(
                                    new ContractMatchingService(
                                            language))
                            .withSnapshotManager(manager)
                            .withExternalDeliveryPlanDeriver(
                                    (root, event) ->
                                            plan.get())
                            .build();
            Node channel =
                    leaf("leaf", kindPattern());
            Node document = root(channel);
            Node event =
                    event(reference(candidateBlueId));
            ResolvedSnapshot captured =
                    manager.fromDocumentTransient(
                            document);
            ContractBundle bundle =
                    owner.contractLoader().load(
                            captured, "/");
            EffectiveContractSnapshot snapshot =
                    bundle.effectiveContractSnapshot(
                            "leaf");
            String domain =
                    CheckpointDomain.derive(
                            snapshot.effectiveTypeBlueId(),
                            snapshot
                                    .sourceContributionNodeBlueIds(),
                            ExternalChannelDependencySnapshot.none(),
                            "pattern-v1");
            ExternalDeliverySnapshot.Builder deliveryBuilder =
                    ExternalDeliverySnapshot.builder(
                                    "/", "leaf")
                            .order(snapshot.order())
                            .effectiveTypeBlueId(
                                    snapshot
                                            .effectiveTypeBlueId())
                            .subscriptionKey("topic")
                            .checkpointDomainBlueId(domain)
                            .checkpointSubjectBlueId(
                                    BlueIdCalculator
                                            .calculateBlueId(
                                                    event));
            for (String contribution
                    : snapshot
                    .sourceContributionNodeBlueIds()) {
                deliveryBuilder.sourceContribution(
                        contribution);
            }
            ExternalDeliverySnapshot delivery =
                    deliveryBuilder.build();
            SubscriptionDelta.Entry interval =
                    new SubscriptionDelta.Entry(
                            "/",
                            "leaf",
                            snapshot.effectiveTypeBlueId(),
                            snapshot
                                    .sourceContributionNodeBlueIds(),
                            snapshot.order(),
                            Collections.singletonList("topic"),
                            domain,
                            ExternalChannelDependencySnapshot.none(),
                            0L,
                            null,
                            null);
            plan.set(
                    ExternalDeliveryPlan.builder()
                            .revisions(0L, 0L)
                            .eventOrderKey(EVENT_ORDER)
                            .delivery(delivery)
                            .activeSubscriptionInterval(
                                    interval)
                            .exactRuntimeState()
                            .build());

            // when
            DocumentProcessingResult result =
                    owner.processDocument(
                            document, event);

            // then
            assertEquals(
                    ProcessorStatus.SUCCESS,
                    result.status(),
                    diagnosticMessage(result));
            assertEquals(
                    4,
                    functions.acceptInvocations(),
                    "two root-verifier passes and two runtime passes should "
                            + "reach the same registered function");
            assertEquals(
                    4,
                    manager.exactCalls(candidateBlueId),
                    "root verification and runtime classification must each "
                            + "open two manager-backed matcher sessions");
            assertEquals(
                    1,
                    providerFetches.get(),
                    "all sessions remain inside one verified manager cache "
                            + "generation");
        }
    }

    private static CandidateMatchObservation observeCandidateMatch(
            boolean referenceCandidate,
            boolean repeat) {
        Node extended = extendedCandidate();
        String candidateBlueId =
                BlueIdCalculator.calculateBlueId(extended);
        AtomicInteger providerFetches =
                new AtomicInteger();
        NodeProvider provider = blueId -> {
            if (!candidateBlueId.equals(blueId)) {
                return null;
            }
            providerFetches.incrementAndGet();
            return Collections.singletonList(
                    extended.clone());
        };
        try (Blue blue = runtime(
                provider,
                new PatternLeafProcessor(false),
                new PatternAggregateProcessor())) {
            DocumentProcessor processor =
                    blue.getDocumentProcessor();
            Node pattern = kindPattern();
            ContractBundle bundle = bundle(
                    processor,
                    root(
                            aggregate("outer", "leaf"),
                            leaf("leaf", pattern)));
            CountingSnapshotManager manager =
                    new CountingSnapshotManager(
                            processor.snapshotManager());
            Node reference = referenceCandidate
                    ? reference(candidateBlueId)
                    : null;
            Node candidate = referenceCandidate
                    ? reference
                    : extended.clone();
            Node candidateEvent = event(candidate);
            String patternIdentity =
                    BlueIdCalculator.calculateBlueId(pattern);
            String eventIdentity =
                    BlueIdCalculator.calculateBlueId(
                            candidateEvent);
            ExternalChannelFunctionEvaluation first =
                    evaluate(
                            processor,
                            manager,
                            bundle,
                            "outer",
                            candidateEvent);
            int exactCallsAfterFirst =
                    manager.exactCalls(candidateBlueId);
            int providerFetchesAfterFirst =
                    providerFetches.get();
            ExternalChannelFunctionEvaluation repeated =
                    repeat
                            ? evaluate(
                                    processor,
                                    manager,
                                    bundle,
                                    "outer",
                                    candidateEvent)
                            : null;
            return new CandidateMatchObservation(
                    candidateBlueId,
                    reference,
                    first,
                    repeated,
                    exactCallsAfterFirst,
                    manager.exactCalls(candidateBlueId),
                    providerFetchesAfterFirst,
                    providerFetches.get(),
                    patternIdentity,
                    BlueIdCalculator.calculateBlueId(pattern),
                    eventIdentity,
                    BlueIdCalculator.calculateBlueId(
                            candidateEvent),
                    extended.getAsText("/detail"));
        }
    }

    private static RuntimeException captureReferenceFailure(
            String candidateBlueId,
            NodeProvider provider,
            Function<FrozenNode, FrozenNode> exactMaterializer) {
        try (Blue blue = runtime(
                provider,
                new PatternLeafProcessor(false),
                new PatternAggregateProcessor())) {
            DocumentProcessor processor =
                    blue.getDocumentProcessor();
            ContractBundle bundle = bundle(
                    processor,
                    root(leaf("leaf", kindPattern())));
            ProcessingSnapshotManager manager =
                    exactMaterializer != null
                            ? materializer(exactMaterializer)
                            : processor.snapshotManager();
            Node referenceEvent =
                    event(reference(candidateBlueId));
            return captureFailure(
                    () -> evaluate(
                            processor,
                            manager,
                            bundle,
                            "leaf",
                            referenceEvent));
        }
    }

    private static NodeProvider provider(
            Map<String, Node> supplied) {
        return blueId -> {
            Node node = supplied.get(blueId);
            return node != null
                    ? Collections.singletonList(node.clone())
                    : null;
        };
    }

    private static ExternalChannelFunctionEvaluation evaluate(
            DocumentProcessor processor,
            ProcessingSnapshotManager manager,
            ContractBundle bundle,
            String key,
            Node event) {
        return ExternalChannelFunctionEvaluation.evaluate(
                processor.registry(),
                processor.contractConverter(),
                ExternalChannelFunctionEvaluation
                        .verifiedMatcherSessions(manager),
                bundle,
                bundle.effectiveContractSnapshot(key),
                event);
    }

    private static final class CandidateMatchObservation {
        private final String candidateBlueId;
        private final Node reference;
        private final ExternalChannelFunctionEvaluation first;
        private final ExternalChannelFunctionEvaluation repeated;
        private final int exactCallsAfterFirst;
        private final int exactCallsAfterRepeat;
        private final int providerFetchesAfterFirst;
        private final int providerFetchesAfterRepeat;
        private final String patternIdentity;
        private final String patternIdentityAfterEvaluation;
        private final String eventIdentity;
        private final String eventIdentityAfterEvaluation;
        private final String retainedDetail;

        private CandidateMatchObservation(
                String candidateBlueId,
                Node reference,
                ExternalChannelFunctionEvaluation first,
                ExternalChannelFunctionEvaluation repeated,
                int exactCallsAfterFirst,
                int exactCallsAfterRepeat,
                int providerFetchesAfterFirst,
                int providerFetchesAfterRepeat,
                String patternIdentity,
                String patternIdentityAfterEvaluation,
                String eventIdentity,
                String eventIdentityAfterEvaluation,
                String retainedDetail) {
            this.candidateBlueId = candidateBlueId;
            this.reference = reference;
            this.first = first;
            this.repeated = repeated;
            this.exactCallsAfterFirst = exactCallsAfterFirst;
            this.exactCallsAfterRepeat = exactCallsAfterRepeat;
            this.providerFetchesAfterFirst =
                    providerFetchesAfterFirst;
            this.providerFetchesAfterRepeat =
                    providerFetchesAfterRepeat;
            this.patternIdentity = patternIdentity;
            this.patternIdentityAfterEvaluation =
                    patternIdentityAfterEvaluation;
            this.eventIdentity = eventIdentity;
            this.eventIdentityAfterEvaluation =
                    eventIdentityAfterEvaluation;
            this.retainedDetail = retainedDetail;
        }
    }

    private static ContractBundle bundle(
            DocumentProcessor processor,
            Node document) {
        ResolvedSnapshot snapshot =
                processor.snapshotManager()
                        .fromDocumentTransient(
                                document);
        return processor.contractLoader()
                .load(snapshot, "/");
    }

    private static Blue runtime(
            NodeProvider provider,
            PatternLeafProcessor leaf,
            PatternAggregateProcessor aggregate) {
        Blue blue = provider != null
                ? ProcessorTestSupport.blue(provider)
                : ProcessorTestSupport.blue();
        blue.registerExternalContractType(
                LEAF_TYPE_BLUE_ID,
                LEAF_TYPE,
                leaf);
        blue.registerExternalContractType(
                AGGREGATE_TYPE_BLUE_ID,
                AGGREGATE_TYPE,
                aggregate);
        return blue;
    }

    private static Node root(Node... contracts) {
        Node contractMap = new Node();
        for (Node supplied : contracts) {
            Node contract = supplied.clone();
            String key = contract.getName();
            contract.name(null);
            contractMap.properties(key, contract);
        }
        return new Node().contracts(
                contractMap);
    }

    private static Node leaf(
            String key,
            Node pattern) {
        return new Node()
                .name(key)
                .type(reference(
                        LEAF_TYPE_BLUE_ID))
                .properties(
                        "pattern",
                        pattern.clone());
    }

    private static Node aggregate(
            String key,
            String memberKey) {
        return new Node()
                .name(key)
                .type(reference(
                        AGGREGATE_TYPE_BLUE_ID))
                .properties(
                        "memberKey",
                        new Node().value(memberKey));
    }

    private static Node event(Node candidate) {
        return new Node()
                .properties(
                        "subscriptionKey",
                        new Node().value("topic"))
                .properties(
                        "candidate",
                        candidate);
    }

    private static Node kindPattern() {
        return new Node().properties(
                "kind",
                new Node().value(
                        "coordination"));
    }

    private static Node extendedCandidate() {
        return new Node()
                .properties(
                        "kind",
                        new Node().value(
                                "coordination"))
                .properties(
                        "detail",
                        new Node().value(
                                "retained"));
    }

    private static Node reference(String blueId) {
        return new Node().blueId(blueId);
    }

    private static ProcessingSnapshotManager materializer(
            Function<FrozenNode, FrozenNode> materializer) {
        return new ProcessingSnapshotManager() {
            @Override
            public ResolvedSnapshot fromDocument(
                    Node document) {
                throw new UnsupportedOperationException();
            }

            @Override
            public FrozenNode materializeVerifiedExactReference(
                    FrozenNode reference) {
                return materializer.apply(reference);
            }

            @Override
            public ResolvedSnapshot applyPatch(
                    ResolvedSnapshot snapshot,
                    JsonPatch patch) {
                throw new UnsupportedOperationException();
            }
        };
    }

    public static final class PatternLeafChannel
            extends ChannelContract {
        private Node pattern;

        public Node getPattern() {
            return pattern;
        }

        public void setPattern(Node pattern) {
            this.pattern = pattern;
        }
    }

    public static final class PatternAggregateChannel
            extends ChannelContract {
        private String memberKey;

        public String getMemberKey() {
            return memberKey;
        }

        public void setMemberKey(
                String memberKey) {
            this.memberKey = memberKey;
        }
    }

    private static final class PatternLeafProcessor
            implements ChannelProcessor<PatternLeafChannel> {
        private final boolean matchDuringHeader;
        private final Node caughtHeaderCandidate;
        private final String caughtHeaderMemberKey;
        private final AtomicInteger headerMatchFailures =
                new AtomicInteger();
        private final AtomicInteger
                headerMemberEvaluationFailures =
                new AtomicInteger();
        private final AtomicInteger acceptInvocations =
                new AtomicInteger();
        private volatile ExternalChannelFunctionContext
                lastContext;
        private final ExternalChannelSubscriptionFunctions<
                PatternLeafChannel> functions =
                new ExternalChannelSubscriptionFunctions<
                        PatternLeafChannel>() {
                    @Override
                    public List<String> channelKeys(
                            PatternLeafChannel contract,
                            ExternalChannelFunctionContext context) {
                        if (matchDuringHeader) {
                            context.matchesPattern(
                                    new Node().value(
                                            "header"),
                                    contract.getPattern());
                        }
                        if (caughtHeaderCandidate != null
                                && (caughtHeaderMemberKey == null
                                || !caughtHeaderMemberKey.equals(
                                contract.getKey()))) {
                            boolean rejected = false;
                            try {
                                context.matchesPattern(
                                        caughtHeaderCandidate,
                                        contract.getPattern());
                            } catch (IllegalStateException expected) {
                                if (!expected.getMessage().contains(
                                        "available only during event "
                                                + "evaluation")) {
                                    throw expected;
                                }
                                rejected = true;
                                headerMatchFailures
                                        .incrementAndGet();
                            }
                            if (!rejected) {
                                throw new IllegalStateException(
                                        "header matcher unexpectedly "
                                                + "available");
                            }
                        }
                        if (caughtHeaderMemberKey != null
                                && !caughtHeaderMemberKey.equals(
                                contract.getKey())) {
                            boolean rejected = false;
                            try {
                                context.member(
                                        caughtHeaderMemberKey)
                                        .evaluate(
                                                event(
                                                        extendedCandidate()));
                            } catch (IllegalStateException expected) {
                                if (!expected.getMessage().contains(
                                        "available only during event "
                                                + "evaluation")) {
                                    throw expected;
                                }
                                rejected = true;
                                headerMemberEvaluationFailures
                                        .incrementAndGet();
                            }
                            if (!rejected) {
                                throw new IllegalStateException(
                                        "header member evaluation "
                                                + "unexpectedly available");
                            }
                        }
                        return Collections.singletonList(
                                "topic");
                    }

                    @Override
                    public boolean accepts(
                            PatternLeafChannel contract,
                            Node exactEvent,
                            ExternalChannelFunctionContext context) {
                        acceptInvocations.incrementAndGet();
                        lastContext = context;
                        Node candidate =
                                exactEvent.getProperties() != null
                                        ? exactEvent
                                        .getProperties()
                                        .get("candidate")
                                        : null;
                        boolean first =
                                context.matchesPattern(
                                        candidate,
                                        contract.getPattern());
                        boolean second =
                                context.matchesPattern(
                                        candidate,
                                        contract.getPattern());
                        if (first != second) {
                            throw new IllegalStateException(
                                    "matcher changed within one evaluation");
                        }
                        return first;
                    }

                    @Override
                    public String checkpointDomainDiscriminator(
                            PatternLeafChannel contract) {
                        return "pattern-v1";
                    }
                };

        private PatternLeafProcessor(
                boolean matchDuringHeader) {
            this(matchDuringHeader, null, null);
        }

        private PatternLeafProcessor(
                boolean matchDuringHeader,
                Node caughtHeaderCandidate) {
            this(
                    matchDuringHeader,
                    caughtHeaderCandidate,
                    null);
        }

        private PatternLeafProcessor(
                boolean matchDuringHeader,
                Node caughtHeaderCandidate,
                String caughtHeaderMemberKey) {
            this.matchDuringHeader =
                    matchDuringHeader;
            this.caughtHeaderCandidate =
                    caughtHeaderCandidate != null
                            ? caughtHeaderCandidate.clone()
                            : null;
            this.caughtHeaderMemberKey =
                    caughtHeaderMemberKey;
        }

        int headerMatchFailures() {
            return headerMatchFailures.get();
        }

        int headerMemberEvaluationFailures() {
            return headerMemberEvaluationFailures.get();
        }

        int acceptInvocations() {
            return acceptInvocations.get();
        }

        ExternalChannelFunctionContext lastContext() {
            return lastContext;
        }

        @Override
        public Class<PatternLeafChannel> contractType() {
            return PatternLeafChannel.class;
        }

        @Override
        public ExternalChannelSubscriptionFunctions<
                PatternLeafChannel>
        externalSubscriptionFunctions() {
            return functions;
        }
    }

    private static final class PatternAggregateProcessor
            implements ChannelProcessor<
            PatternAggregateChannel> {
        private final ExternalChannelSubscriptionFunctions<
                PatternAggregateChannel> functions =
                new ExternalChannelSubscriptionFunctions<
                        PatternAggregateChannel>() {
                    @Override
                    public List<String> channelKeys(
                            PatternAggregateChannel contract,
                            ExternalChannelFunctionContext context) {
                        return context.member(
                                contract.getMemberKey())
                                .channelKeys();
                    }

                    @Override
                    public boolean accepts(
                            PatternAggregateChannel contract,
                            Node exactEvent,
                            ExternalChannelFunctionContext context) {
                        return selected(
                                contract,
                                exactEvent,
                                context)
                                .accepts();
                    }

                    @Override
                    public Node payload(
                            PatternAggregateChannel contract,
                            Node exactEvent,
                            ExternalChannelFunctionContext context) {
                        return selected(
                                contract,
                                exactEvent,
                                context)
                                .payload();
                    }

                    @Override
                    public Node checkpointSubject(
                            PatternAggregateChannel contract,
                            Node exactEvent,
                            Node exactPayload,
                            ExternalChannelFunctionContext context) {
                        return selected(
                                contract,
                                exactEvent,
                                context)
                                .checkpointSubject();
                    }

                    @Override
                    public String checkpointDomainDiscriminator(
                            PatternAggregateChannel contract) {
                        return "aggregate-pattern-v1";
                    }

                    private ExternalChannelMemberEvaluation selected(
                            PatternAggregateChannel contract,
                            Node exactEvent,
                            ExternalChannelFunctionContext context) {
                        return context.member(
                                contract.getMemberKey())
                                .evaluate(exactEvent);
                    }
                };

        @Override
        public Class<PatternAggregateChannel> contractType() {
            return PatternAggregateChannel.class;
        }

        @Override
        public ExternalChannelSubscriptionFunctions<
                PatternAggregateChannel>
        externalSubscriptionFunctions() {
            return functions;
        }
    }

    private static final class CountingSnapshotManager
            implements ProcessingSnapshotManager {
        private final ProcessingSnapshotManager delegate;
        private final Map<String, AtomicInteger> exactCalls;

        private CountingSnapshotManager(
                ProcessingSnapshotManager delegate) {
            this(
                    delegate,
                    new LinkedHashMap<String, AtomicInteger>());
        }

        private CountingSnapshotManager(
                ProcessingSnapshotManager delegate,
                Map<String, AtomicInteger> exactCalls) {
            this.delegate = delegate;
            this.exactCalls = exactCalls;
        }

        int exactCalls(String blueId) {
            AtomicInteger count =
                    exactCalls.get(blueId);
            return count != null ? count.get() : 0;
        }

        int totalExactCalls() {
            int total = 0;
            for (AtomicInteger count
                    : exactCalls.values()) {
                total += count.get();
            }
            return total;
        }

        @Override
        public ResolvedSnapshot fromDocument(
                Node document) {
            return delegate.fromDocument(document);
        }

        @Override
        public ResolvedSnapshot fromDocumentTransient(
                Node document) {
            return delegate.fromDocumentTransient(
                    document);
        }

        @Override
        public ResolvedSnapshot fromDocumentPreservingPaths(
                Node document,
                Collection<String> preservedPaths) {
            return delegate.fromDocumentPreservingPaths(
                    document,
                    preservedPaths);
        }

        @Override
        public ResolvedSnapshot
        fromDocumentTransientPreservingPaths(
                Node document,
                Collection<String> preservedPaths) {
            return delegate
                    .fromDocumentTransientPreservingPaths(
                            document,
                            preservedPaths);
        }

        @Override
        public String calculateScopeContentBlueId(
                String scopePath,
                FrozenNode selectedScope,
                ResolvedSnapshot capturedDocumentSnapshot) {
            return delegate.calculateScopeContentBlueId(
                    scopePath,
                    selectedScope,
                    capturedDocumentSnapshot);
        }

        @Override
        public FrozenNode materializeVerifiedReference(
                FrozenNode reference) {
            return delegate
                    .materializeVerifiedReference(
                            reference);
        }

        @Override
        public FrozenNode materializeVerifiedExactReference(
                FrozenNode reference) {
            String blueId =
                    reference.getReferenceBlueId();
            AtomicInteger count =
                    exactCalls.get(blueId);
            if (count == null) {
                count = new AtomicInteger();
                exactCalls.put(blueId, count);
            }
            count.incrementAndGet();
            return delegate
                    .materializeVerifiedExactReference(
                            reference);
        }

        @Override
        public ProcessingSnapshotManager transientSequence() {
            return new CountingSnapshotManager(
                    delegate.transientSequence(),
                    exactCalls);
        }

        @Override
        public ProcessingSnapshotManager forkTransientSequence() {
            return new CountingSnapshotManager(
                    delegate.forkTransientSequence(),
                    exactCalls);
        }

        @Override
        public void retainTransientState(
                FrozenNode canonicalRoot,
                FrozenNode resolvedRoot) {
            delegate.retainTransientState(
                    canonicalRoot,
                    resolvedRoot);
        }

        @Override
        public void releaseTransientState() {
            delegate.releaseTransientState();
        }

        @Override
        public boolean isTransientStateCurrent() {
            return delegate.isTransientStateCurrent();
        }

        @Override
        public boolean supportsIncrementalValueResolution() {
            return delegate
                    .supportsIncrementalValueResolution();
        }

        @Override
        public boolean supportsIncrementalValueResolution(
                IncrementalValueResolutionRequest request) {
            return delegate
                    .supportsIncrementalValueResolution(
                            request);
        }

        @Override
        public ConformanceEngine transientConformanceEngine(
                ConformanceEngine conformanceEngine) {
            return delegate.transientConformanceEngine(
                    conformanceEngine);
        }

        @Override
        public ResolvedSnapshot applyPatch(
                ResolvedSnapshot snapshot,
                JsonPatch patch) {
            return delegate.applyPatch(
                    snapshot, patch);
        }

        @Override
        public ResolvedSnapshot cacheSnapshot(
                ResolvedSnapshot snapshot) {
            return delegate.cacheSnapshot(snapshot);
        }
    }
}
