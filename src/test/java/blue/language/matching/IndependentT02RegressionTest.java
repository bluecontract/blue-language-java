package blue.language.matching;

import blue.language.api.BlueOperationLimits;
import blue.language.api.BlueOperationOutcome;
import blue.language.api.BlueOperationResult;
import blue.language.api.NodeProviderOutcome;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.provider.NodeProvider;
import blue.language.provider.NodeProviderResult;
import blue.language.runtime.BlueLanguage;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static blue.language.model.wire.BlueLanguageConstants.TEXT_TYPE_BLUE_ID;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndependentT02RegressionTest {

    @Test
    void shouldPreserveUnavailableTargetAsIncomplete() {
        // given
        NodeProviderResult evidence = NodeProviderResult.unavailable("target store offline");

        // when
        TargetObservation observation = observeTarget(evidence);

        // then
        assertTargetFailure(observation, BlueOperationOutcome.INCOMPLETE, NodeProviderOutcome.UNAVAILABLE);
    }

    @Test
    void shouldPreserveMissingTargetAsIncomplete() {
        // given
        NodeProviderResult evidence = NodeProviderResult.notFound();

        // when
        TargetObservation observation = observeTarget(evidence);

        // then
        assertTargetFailure(observation, BlueOperationOutcome.INCOMPLETE, NodeProviderOutcome.NOT_FOUND);
    }

    @Test
    void shouldPreserveInvalidTargetEvidenceAsInvalid() {
        // given
        NodeProviderResult evidence = NodeProviderResult.invalidEvidence("invalid target proof");

        // when
        TargetObservation observation = observeTarget(evidence);

        // then
        assertTargetFailure(observation, BlueOperationOutcome.INVALID, NodeProviderOutcome.INVALID_EVIDENCE);
    }

    @Test
    void shouldRejectMismatchedTargetContentAsInvalid() {
        // given
        NodeProviderResult evidence = NodeProviderResult.found(Collections.singletonList(
                new Node().value("different content")));

        // when
        TargetObservation observation = observeTarget(evidence);

        // then
        assertTargetFailure(observation, BlueOperationOutcome.INVALID, NodeProviderOutcome.INVALID_EVIDENCE);
    }

    @Test
    void shouldPreventTargetReadWithZeroBudget() {
        // given
        RecordingProvider provider = new RecordingProvider();
        String targetId = provider.add(textDefinition());
        try (BlueLanguage language = BlueLanguage.builder().nodeProvider(provider).build()) {
            // when
            BlueOperationResult<Boolean> result = language.matching().matchesLimited(
                    new Node().value("ok"), pattern(targetId), budget(0));

            // then
            assertAll(
                    () -> assertEquals(BlueOperationOutcome.INCOMPLETE, result.outcome()),
                    () -> assertEquals(Collections.singleton(targetId), result.outstandingBlueIds()),
                    () -> assertFalse(result.value().isPresent()),
                    () -> assertFalse(result.providerOutcome().isPresent()),
                    () -> assertTrue(provider.requests.isEmpty()));
        }
    }

    @Test
    void shouldEstablishFoundTargetWithExactBudget() {
        // given
        RecordingProvider provider = new RecordingProvider();
        String targetId = provider.add(textDefinition());
        try (BlueLanguage language = BlueLanguage.builder().nodeProvider(provider).build()) {
            // when
            BlueOperationResult<Boolean> result = language.matching().matchesLimited(
                    new Node().value("ok"), pattern(targetId), budget(1));

            // then
            assertTrue(result.requireEstablished());
            assertEquals(Collections.singletonList(targetId), provider.requests);
        }
    }

    @Test
    void shouldKeepGenuineSchemaMismatchEstablishedFalse() {
        // given
        RecordingProvider provider = new RecordingProvider();
        String targetId = provider.add(textDefinition());
        try (BlueLanguage language = BlueLanguage.builder().nodeProvider(provider).build()) {
            // when
            BlueOperationResult<Boolean> result = language.matching().matchesLimited(
                    new Node().value("x"), pattern(targetId), budget(1));

            // then
            assertFalse(result.requireEstablished());
            assertTrue(result.outstandingBlueIds().isEmpty());
            assertEquals(Collections.singletonList(targetId), provider.requests);
        }
    }

    @Test
    void shouldKeepOrdinaryBooleanMatchingFailureBehavior() {
        // given
        RecordingProvider provider = new RecordingProvider();
        String targetId = provider.add(textDefinition());
        provider.results.put(targetId, NodeProviderResult.unavailable("target store offline"));
        try (BlueLanguage language = BlueLanguage.builder().nodeProvider(provider).build()) {
            // when
            boolean matched = language.matching().matches(new Node().value("ok"), pattern(targetId));

            // then
            assertFalse(matched);
        }
    }

    @Test
    void shouldShareOneReferenceBudgetBetweenCandidateAndTarget() {
        // given
        RecordingProvider provider = new RecordingProvider();
        String candidateTypeId = provider.add(textDefinition().name("T02 candidate type"));
        String targetId = provider.add(textDefinition());
        Node candidate = pattern(candidateTypeId).value("ok");
        try (BlueLanguage language = BlueLanguage.builder().nodeProvider(provider).build()) {
            // when
            BlueOperationResult<Boolean> shortResult = language.matching().matchesLimited(
                    candidate, pattern(targetId), budget(1));
            List<String> shortRequests = new ArrayList<>(provider.requests);
            provider.requests.clear();
            BlueOperationResult<Boolean> exactResult = language.matching().matchesLimited(
                    candidate, pattern(targetId), budget(2));

            // then
            assertEquals(BlueOperationOutcome.INCOMPLETE, shortResult.outcome());
            assertEquals(Collections.singleton(targetId), shortResult.outstandingBlueIds());
            assertEquals(Collections.singletonList(candidateTypeId), shortRequests);
            assertTrue(exactResult.requireEstablished());
            assertEquals(Arrays.asList(candidateTypeId, targetId), provider.requests);
        }
    }

    @Test
    void shouldChargeCompletedCandidateEvidenceOnlyOnce() {
        // given
        RecordingProvider provider = new RecordingProvider();
        String targetId = provider.add(textDefinition());
        try (BlueLanguage language = BlueLanguage.builder().nodeProvider(provider).build()) {
            // when
            BlueOperationResult<Boolean> result = language.matching().matchesLimited(
                    pattern(targetId).value("ok"), pattern(targetId), budget(1));

            // then
            assertTrue(result.requireEstablished());
            assertEquals(Collections.singletonList(targetId), provider.requests);
        }
    }

    @Test
    void shouldBoundTargetAncestryWithZeroOneShortAndExactBudgets() {
        // given
        RecordingProvider provider = new RecordingProvider();
        String parentId = provider.add(textDefinition());
        String childId = provider.add(pattern(parentId).name("T02 child")
                .schema(new Schema().maxLength(4)));
        List<BlueOperationResult<Boolean>> results = new ArrayList<>();
        List<List<String>> requests = new ArrayList<>();
        try (BlueLanguage language = BlueLanguage.builder().nodeProvider(provider).build()) {
            // when
            for (int maximum = 0; maximum <= 2; maximum++) {
                provider.requests.clear();
                results.add(language.matching().matchesLimited(
                        new Node().value("ok"), pattern(childId), budget(maximum)));
                requests.add(new ArrayList<>(provider.requests));
            }

            // then
            for (int maximum = 0; maximum < 2; maximum++) {
                assertEquals(BlueOperationOutcome.INCOMPLETE, results.get(maximum).outcome());
                assertEquals(Collections.singleton(maximum == 0 ? childId : parentId),
                        results.get(maximum).outstandingBlueIds());
                assertEquals(maximum, requests.get(maximum).size());
            }
            assertTrue(results.get(2).requireEstablished());
            assertEquals(Arrays.asList(childId, parentId), requests.get(2));
        }
    }

    @Test
    void shouldPreserveUnavailableTargetAncestorIdentityAndOutcome() {
        // given
        RecordingProvider provider = new RecordingProvider();
        String parentId = provider.add(textDefinition());
        String childId = provider.add(pattern(parentId).name("T02 child"));
        provider.results.put(parentId, NodeProviderResult.unavailable("ancestor offline"));
        try (BlueLanguage language = BlueLanguage.builder().nodeProvider(provider).build()) {
            // when
            BlueOperationResult<Boolean> result = language.matching().matchesLimited(
                    new Node().value("ok"), pattern(childId), budget(2));

            // then
            assertEquals(BlueOperationOutcome.INCOMPLETE, result.outcome());
            assertEquals(NodeProviderOutcome.UNAVAILABLE, result.providerOutcome().orElse(null));
            assertEquals(Collections.singleton(parentId), result.outstandingBlueIds());
            assertEquals(Arrays.asList(childId, parentId), provider.requests);
        }
    }

    @Test
    void shouldMatchInlineAndReferencedTargetsEqually() {
        // given
        RecordingProvider provider = new RecordingProvider();
        Node definition = textDefinition();
        String targetId = provider.add(definition);
        List<BlueOperationResult<Boolean>> inline = new ArrayList<>();
        List<BlueOperationResult<Boolean>> referenced = new ArrayList<>();
        try (BlueLanguage language = BlueLanguage.builder().nodeProvider(provider).build()) {
            // when
            for (String value : Arrays.asList("ok", "x")) {
                inline.add(language.matching().matchesLimited(
                        new Node().value(value), new Node().type(definition.clone()), budget(0)));
                referenced.add(language.matching().matchesLimited(
                        new Node().value(value), pattern(targetId), budget(1)));
            }

            // then
            for (int index = 0; index < 2; index++) {
                assertEquals(index == 0, inline.get(index).requireEstablished());
                assertEquals(inline.get(index).requireEstablished(), referenced.get(index).requireEstablished());
            }
        }
    }

    @Test
    void shouldMatchInlineAndReferencedCandidatesEqually() {
        // given
        RecordingProvider provider = new RecordingProvider();
        Node candidate = new Node().value("ok");
        String candidateId = provider.add(candidate);
        String targetId = provider.add(textDefinition());
        try (BlueLanguage language = BlueLanguage.builder().nodeProvider(provider).build()) {
            // when
            BlueOperationResult<Boolean> inline = language.matching().matchesLimited(
                    candidate, pattern(targetId), budget(1));
            provider.requests.clear();
            BlueOperationResult<Boolean> referenced = language.matching().matchesLimited(
                    new Node().blueId(candidateId), pattern(targetId), budget(2));

            // then
            assertTrue(inline.requireEstablished());
            assertTrue(referenced.requireEstablished());
            assertEquals(Arrays.asList(candidateId, targetId), provider.requests);
        }
    }

    @Test
    void shouldEnforceCurrentReferenceBudgetWithWarmSnapshots() {
        // given
        RecordingProvider provider = new RecordingProvider();
        String targetId = provider.add(textDefinition());
        try (BlueLanguage language = BlueLanguage.builder().nodeProvider(provider).build()) {
            // when
            BlueOperationResult<Boolean> cold = language.matching().matchesLimited(
                    new Node().value("ok"), pattern(targetId), budget(1));
            language.snapshots().resolve(pattern(targetId).value("ok"));
            provider.requests.clear();
            BlueOperationResult<Boolean> exhausted = language.matching().matchesLimited(
                    new Node().value("ok"), pattern(targetId), budget(0));
            List<String> exhaustedRequests = new ArrayList<>(provider.requests);
            BlueOperationResult<Boolean> warm = language.matching().matchesLimited(
                    new Node().value("ok"), pattern(targetId), budget(1));

            // then
            assertTrue(cold.requireEstablished());
            assertEquals(BlueOperationOutcome.INCOMPLETE, exhausted.outcome());
            assertEquals(Collections.singleton(targetId), exhausted.outstandingBlueIds());
            assertTrue(exhaustedRequests.isEmpty());
            assertTrue(warm.requireEstablished());
            assertEquals(Collections.singletonList(targetId), provider.requests);
        }
    }

    @Test
    void shouldRetryUnavailableEvidenceWithoutMutatingInputs() {
        // given
        RecordingProvider provider = new RecordingProvider();
        String targetId = provider.add(textDefinition());
        NodeProviderResult found = provider.results.get(targetId);
        FrozenNode frozenCandidate = FrozenNode.fromSourceNode(new Node().value("ok"));
        FrozenNode frozenTarget = FrozenNode.fromSourceNode(pattern(targetId));
        Node candidate = frozenCandidate.toNode();
        Node target = frozenTarget.toNode();
        provider.results.put(targetId, NodeProviderResult.unavailable("target offline"));
        try (BlueLanguage language = BlueLanguage.builder().nodeProvider(provider).build()) {
            // when
            BlueOperationResult<Boolean> unavailable = language.matching().matchesLimited(
                    candidate, target, budget(1));
            provider.results.put(targetId, found);
            BlueOperationResult<Boolean> retried = language.matching().matchesLimited(candidate, target, budget(1));

            // then
            assertEquals(BlueOperationOutcome.INCOMPLETE, unavailable.outcome());
            assertTrue(retried.requireEstablished());
            assertEquals(Arrays.asList(targetId, targetId), provider.requests);
            assertTrue(frozenCandidate.sameResolvedStructure(FrozenNode.fromSourceNode(candidate)));
            assertTrue(frozenTarget.sameResolvedStructure(FrozenNode.fromSourceNode(target)));
            assertEquals("ok", frozenCandidate.getValue());
        }
    }

    @Test
    void shouldReadDemandedNestedTargetWithoutReadingUnrelatedCandidateSibling() {
        // given
        RecordingProvider provider = new RecordingProvider();
        String targetId = provider.add(textDefinition());
        String unrelatedId = provider.add(new Node().value("unrelated"));
        provider.results.put(unrelatedId, NodeProviderResult.unavailable("not demanded"));
        Node candidate = new Node().properties(
                "wanted", new Node().value("ok"), "unrelated", new Node().blueId(unrelatedId));
        Node target = new Node().properties("wanted", pattern(targetId));
        try (BlueLanguage language = BlueLanguage.builder().nodeProvider(provider).build()) {
            // when
            BlueOperationResult<Boolean> result = language.matching().matchesLimited(candidate, target,
                    BlueOperationLimits.demandedPath("/wanted").withMaxReferenceExpansions(1));

            // then
            assertTrue(result.requireEstablished());
            assertEquals(Collections.singletonList(targetId), provider.requests);
        }
    }

    @Test
    void shouldShortCircuitMismatchBeforeReadingLaterTargetBranch() {
        // given
        RecordingProvider provider = new RecordingProvider();
        String targetId = provider.add(textDefinition());
        provider.results.put(targetId, NodeProviderResult.unavailable("not demanded"));
        Node candidate = new Node().properties(
                "status", new Node().value("stop"), "later", new Node().value("ok"));
        Node target = new Node().properties(
                "status", new Node().value("go"), "later", pattern(targetId));
        try (BlueLanguage language = BlueLanguage.builder().nodeProvider(provider).build()) {
            // when
            BlueOperationResult<Boolean> result = language.matching().matchesLimited(candidate, target, budget(0));

            // then
            assertFalse(result.requireEstablished());
            assertTrue(provider.requests.isEmpty());
        }
    }

    @Test
    void shouldKeepInvalidTargetSchemaInvalid() {
        // given
        RecordingProvider provider = new RecordingProvider();
        String targetId = provider.add(textDefinition().schema(new Schema().minLength(-1)));
        try (BlueLanguage language = BlueLanguage.builder().nodeProvider(provider).build()) {
            // when
            BlueOperationResult<Boolean> result = language.matching().matchesLimited(
                    new Node().value("ok"), pattern(targetId), budget(1));

            // then
            assertEquals(BlueOperationOutcome.INVALID, result.outcome());
            assertFalse(result.value().isPresent());
            assertEquals(Collections.singletonList(targetId), provider.requests);
        }
    }

    @Test
    void shouldKeepInvalidCandidateInvalid() {
        // given
        Node candidate = new Node().value("x").type(textDefinition());
        Node target = new Node().type(new Node().blueId(TEXT_TYPE_BLUE_ID));
        try (BlueLanguage language = BlueLanguage.builder().build()) {
            // when
            BlueOperationResult<Boolean> result = language.matching().matchesLimited(candidate, target, budget(0));

            // then
            assertEquals(BlueOperationOutcome.INVALID, result.outcome());
        }
    }

    @Test
    void shouldMatchExactReferenceIdentityWithoutReadingTargetContent() {
        // given
        String identity = DirectBlueIdCalculator.calculateBlueId(new Node().value("same"));
        RecordingProvider provider = new RecordingProvider();
        try (BlueLanguage language = BlueLanguage.builder().nodeProvider(provider).build()) {
            // when
            BlueOperationResult<Boolean> result = language.matching().matchesLimited(
                    new Node().blueId(identity), new Node().blueId(identity), budget(0));

            // then
            assertTrue(result.requireEstablished());
            assertTrue(provider.requests.isEmpty());
        }
    }

    @Test
    void shouldRetainLimitedResolutionOutcomesForNullPattern() {
        // given
        Node candidate = new Node().value("ok");
        try (BlueLanguage language = BlueLanguage.builder().build()) {
            // when
            BlueOperationResult<Boolean> established = language.matching().matchesLimited(candidate, null, budget(0));
            BlueOperationResult<Boolean> absent = language.matching().matchesLimited(
                    candidate, null, BlueOperationLimits.demandedPath("/missing"));

            // then
            assertTrue(established.requireEstablished());
            assertEquals(BlueOperationOutcome.ABSENT, absent.outcome());
        }
    }

    @Test
    void shouldReturnAbsentBeforeDemandingTargetEvidence() {
        // given
        RecordingProvider provider = new RecordingProvider();
        String targetId = provider.add(textDefinition());
        provider.results.put(targetId, NodeProviderResult.unavailable("target offline"));
        try (BlueLanguage language = BlueLanguage.builder().nodeProvider(provider).build()) {
            // when
            BlueOperationResult<Boolean> result = language.matching().matchesLimited(
                    new Node().value("ok"), pattern(targetId),
                    BlueOperationLimits.demandedPath("/missing").withMaxReferenceExpansions(1));

            // then
            assertEquals(BlueOperationOutcome.ABSENT, result.outcome());
            assertFalse(result.value().isPresent());
            assertTrue(provider.requests.isEmpty());
        }
    }

    private static TargetObservation observeTarget(NodeProviderResult evidence) {
        RecordingProvider provider = new RecordingProvider();
        String targetId = provider.add(textDefinition());
        provider.results.put(targetId, evidence);
        try (BlueLanguage language = BlueLanguage.builder().nodeProvider(provider).build()) {
            BlueOperationResult<Boolean> result = language.matching().matchesLimited(
                    new Node().value("ok"), pattern(targetId), budget(1));
            return new TargetObservation(targetId, result, new ArrayList<>(provider.requests));
        }
    }

    private static void assertTargetFailure(TargetObservation observation,
                                            BlueOperationOutcome outcome,
                                            NodeProviderOutcome providerOutcome) {
        BlueOperationResult<Boolean> result = observation.result;
        assertAll(
                () -> assertEquals(outcome, result.outcome()),
                () -> assertEquals(providerOutcome, result.providerOutcome().orElse(null)),
                () -> assertFalse(result.value().isPresent()),
                () -> assertEquals(Collections.singletonList(observation.targetId), observation.requests),
                () -> assertEquals(outcome == BlueOperationOutcome.INCOMPLETE
                                ? Collections.singleton(observation.targetId) : Collections.emptySet(),
                        result.outstandingBlueIds()));
    }

    private static final class TargetObservation {
        private final String targetId;
        private final BlueOperationResult<Boolean> result;
        private final List<String> requests;

        private TargetObservation(String targetId, BlueOperationResult<Boolean> result, List<String> requests) {
            this.targetId = targetId;
            this.result = result;
            this.requests = requests;
        }
    }

    private static Node textDefinition() {
        return new Node().name("T02 text target")
                .type(new Node().blueId(TEXT_TYPE_BLUE_ID))
                .schema(new Schema().minLength(2));
    }

    private static Node pattern(String targetId) {
        return new Node().type(new Node().blueId(targetId));
    }

    private static BlueOperationLimits budget(int maximum) {
        return BlueOperationLimits.UNLIMITED.withMaxReferenceExpansions(maximum);
    }

    private static final class RecordingProvider implements NodeProvider {
        private final Map<String, NodeProviderResult> results = new LinkedHashMap<>();
        private final List<String> requests = new ArrayList<>();

        private String add(Node node) {
            String blueId = DirectBlueIdCalculator.calculateBlueId(node);
            results.put(blueId, NodeProviderResult.found(Collections.singletonList(node)));
            return blueId;
        }

        @Override
        public List<Node> fetchByBlueId(String blueId) {
            throw new AssertionError("Matching must retain typed provider outcomes.");
        }

        @Override
        public NodeProviderResult fetchResultByBlueId(String blueId) {
            requests.add(blueId);
            return results.getOrDefault(blueId, NodeProviderResult.notFound());
        }
    }
}
