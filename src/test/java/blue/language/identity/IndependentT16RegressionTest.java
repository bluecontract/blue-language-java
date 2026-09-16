package blue.language.identity;

import blue.language.api.NodeProviderOutcome;
import blue.language.model.Node;
import blue.language.model.NodeWireForm;
import blue.language.provider.CachingNodeProvider;
import blue.language.provider.CyclicAwareNodeProvider;
import blue.language.provider.CyclicSetProof;
import blue.language.provider.CyclicSetProofResult;
import blue.language.provider.NodeProvider;
import blue.language.provider.NodeProviderResult;
import blue.language.provider.VerifyingNodeProvider;
import blue.language.registry.NodeProviderWrapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exact cyclic evidence must survive insertion of the content cache. */
class IndependentT16RegressionTest {

    @Test
    void shouldVerifyEveryMemberDirectly() {
        // given
        Fixture fixture = new Fixture();

        // when
        List<NodeProviderResult> results = fetchMembers(
                fixture, new VerifyingNodeProvider(fixture.provider));

        // then
        assertMembers(fixture, results);
    }

    @Test
    void shouldVerifyTheSameMembersThroughCache() {
        // given
        Fixture fixture = new Fixture();

        // when
        List<NodeProviderResult> direct = fetchMembers(
                fixture, new VerifyingNodeProvider(fixture.provider));
        List<NodeProviderResult> cached = fetchMembers(fixture,
                new VerifyingNodeProvider(
                        new CachingNodeProvider(fixture.provider, 1000000)));

        // then
        assertMembers(fixture, direct);
        assertMembers(fixture, cached);
    }

    @ParameterizedTest
    @EnumSource(Composition.class)
    void shouldPreserveEveryMemberAndExactCacheKeys(Composition composition) {
        // given
        Fixture fixture = new Fixture();
        NodeProvider provider = composition.wrap(fixture.provider);

        // when
        List<NodeProviderResult> first = fetchMembers(fixture, provider);
        List<NodeProviderResult> repeated = fetchMembers(fixture, provider);

        // then
        assertMembers(fixture, first);
        assertMembers(fixture, repeated);
        assertEquals(composition == Composition.DIRECT ? 4 : 2,
                fixture.provider.fetches);
        assertTrue(fixture.provider.proofRequests.containsAll(fixture.memberBlueIds));
        assertTrue(fixture.memberBlueIds.containsAll(fixture.provider.proofRequests));
    }

    @ParameterizedTest
    @MethodSource("proofFailures")
    void shouldRetryMissingUnavailableAndInvalidProof(
            Composition composition, CyclicSetProofResult failure) {
        // given
        Fixture fixture = new Fixture();
        String blueId = fixture.memberBlueIds.get(0);
        fixture.provider.proofResult = failure;
        NodeProvider provider = composition.wrap(fixture.provider);

        // when
        NodeProviderResult rejected = provider.fetchResultByBlueId(blueId);
        fixture.provider.proofResult = CyclicSetProofResult.found(fixture.proof);
        NodeProviderResult repaired = provider.fetchResultByBlueId(blueId);

        // then
        assertEquals(failure.outcome() == NodeProviderOutcome.NOT_FOUND
                        ? NodeProviderOutcome.INVALID_EVIDENCE : failure.outcome(),
                rejected.outcome());
        if (failure.diagnostic().isPresent()) {
            assertEquals(failure.diagnostic(), rejected.diagnostic());
        }
        assertEquals(NodeProviderOutcome.FOUND, repaired.outcome(),
                repaired.diagnostic().orElse("repair failed"));
        assertEquals(Arrays.asList(blueId, blueId), fixture.provider.proofRequests);
    }

    @ParameterizedTest
    @EnumSource(Composition.class)
    void shouldRejectWrongMemberIndexAndMaster(Composition composition) {
        // given
        Fixture fixture = new Fixture();
        Node actual = fixture.provider.content.get(fixture.memberBlueIds.get(0));
        List<String> invalidRequests = Arrays.asList(
                fixture.finalization.masterBlueId() + "#2",
                DirectBlueIdCalculator.calculateBlueId(new Node().value("other")) + "#0");
        for (String requested : invalidRequests) {
            fixture.provider.content.put(requested, actual);
        }
        NodeProvider provider = composition.wrap(fixture.provider);

        // when
        List<NodeProviderResult> results = invalidRequests.stream()
                .map(provider::fetchResultByBlueId).collect(Collectors.toList());

        // then
        for (NodeProviderResult result : results) {
            assertEquals(NodeProviderOutcome.INVALID_EVIDENCE,
                    result.outcome());
        }
        assertEquals(invalidRequests, fixture.provider.proofRequests);
    }

    @ParameterizedTest
    @EnumSource(Composition.class)
    void shouldRejectFoundButInvalidProofAndAcceptRepair(Composition composition) {
        // given
        Fixture fixture = new Fixture();
        List<Node> invalidSet = fixture.proof.declaredPlaceholderSet();
        invalidSet.get(0).getProperties().get("next").blueId("this#99");
        fixture.provider.proofResult = CyclicSetProofResult.found(
                CyclicSetProof.fromDeclaredPlaceholderSet(invalidSet));
        NodeProvider provider = composition.wrap(fixture.provider);
        String blueId = fixture.memberBlueIds.get(0);

        // when
        NodeProviderResult rejected = provider.fetchResultByBlueId(blueId);
        fixture.provider.proofResult = CyclicSetProofResult.found(fixture.proof);
        NodeProviderResult repaired = provider.fetchResultByBlueId(blueId);

        // then
        assertEquals(NodeProviderOutcome.INVALID_EVIDENCE, rejected.outcome());
        assertEquals(NodeProviderOutcome.FOUND, repaired.outcome());
    }

    @ParameterizedTest
    @EnumSource(Composition.class)
    void shouldRejectTamperedMemberWithValidProof(Composition composition) {
        // given
        Fixture fixture = new Fixture();
        String blueId = fixture.memberBlueIds.get(0);
        fixture.provider.content.get(blueId).name("tampered");

        // when
        NodeProviderResult result = composition.wrap(fixture.provider)
                .fetchResultByBlueId(blueId);

        // then
        assertEquals(NodeProviderOutcome.INVALID_EVIDENCE, result.outcome());
        assertTrue(result.diagnostic().orElse("").contains("does not match"));
    }

    @Test
    void shouldForwardTypedProofResultsWithoutRetainingMissesOrFailures() {
        // given
        Fixture fixture = new Fixture();
        CyclicAwareNodeProvider cache = cyclicCache(fixture.provider);
        String blueId = fixture.memberBlueIds.get(0);
        List<CyclicSetProofResult> expected = Arrays.asList(
                CyclicSetProofResult.notFound(),
                CyclicSetProofResult.unavailable("offline"),
                CyclicSetProofResult.invalidEvidence("invalid"),
                CyclicSetProofResult.found(fixture.proof));
        List<CyclicSetProofResult> actual = new ArrayList<>();

        // when
        for (CyclicSetProofResult result : expected) {
            fixture.provider.proofResult = result;
            actual.add(cache.cyclicSetProofFor(blueId));
        }

        // then
        for (int index = 0; index < expected.size(); index++) {
            assertSame(expected.get(index), actual.get(index));
        }
        assertEquals(Collections.nCopies(4, blueId), fixture.provider.proofRequests);
    }

    @Test
    void shouldPreserveImmutableProofAndCachedContentSnapshots() {
        // given
        Fixture fixture = new Fixture();
        NodeProvider cache = new CachingNodeProvider(fixture.provider, 1000000);
        CyclicAwareNodeProvider cyclic = requireCyclic(cache);
        String blueId = fixture.memberBlueIds.get(0);
        NodeProviderResult retained = cache.fetchResultByBlueId(blueId);
        Object expected = NodeWireForm.get(retained.nodes().get(0));
        CyclicSetProofResult retainedProof = cyclic.cyclicSetProofFor(blueId);

        // when
        fixture.declared.get(0).name("changed proof input");
        retainedProof.proof().get().declaredPlaceholderSet().get(0).name("changed proof copy");
        retained.nodes().get(0).name("changed result copy");
        fixture.provider.content.get(blueId).name("changed provider node");
        fixture.provider.proofResult = CyclicSetProofResult.unavailable("offline now");

        CyclicSetProofResult unavailable = cyclic.cyclicSetProofFor(blueId);
        fixture.provider.proofResult = retainedProof;
        NodeProviderResult verified = new VerifyingNodeProvider(cache).fetchResultByBlueId(blueId);

        // then
        assertEquals(NodeProviderOutcome.FOUND, retainedProof.outcome());
        assertEquals(NodeProviderOutcome.UNAVAILABLE, unavailable.outcome());
        assertEquals(NodeProviderOutcome.FOUND, verified.outcome());
        assertEquals(expected, NodeWireForm.get(verified.nodes().get(0)));
        assertEquals(expected, NodeWireForm.get(retained.nodes().get(0)));
        assertEquals(1, fixture.provider.fetches);
    }

    @Test
    void shouldDelegateCompatibilityProbeWithoutTreatingCacheHitAsTrust() {
        // given
        Fixture fixture = new Fixture();
        NodeProvider cache = new CachingNodeProvider(fixture.provider, 1000000);
        CyclicAwareNodeProvider cyclic = requireCyclic(cache);
        String blueId = fixture.memberBlueIds.get(0);

        // when
        boolean present = cyclic.hasVerifiedContentForBlueId(blueId);
        boolean absent = cyclic.hasVerifiedContentForBlueId(
                fixture.finalization.masterBlueId() + "#2");
        cache.fetchResultByBlueId(blueId);
        fixture.provider.content.clear();
        boolean cached = cyclic.hasVerifiedContentForBlueId(blueId);

        // then
        assertTrue(present);
        assertFalse(absent);
        assertFalse(cached);
    }

    @Test
    void shouldFailClosedWithoutDelegateCapabilityOrTypedProof() {
        // given
        Fixture fixture = new Fixture();
        String blueId = fixture.memberBlueIds.get(0);
        NodeProvider plain = fixture.provider::fetchByBlueId;
        NodeProvider cache = new CachingNodeProvider(plain, 1000000);

        // when
        boolean verified = requireCyclic(cache).hasVerifiedContentForBlueId(blueId);
        CyclicSetProofResult missing = requireCyclic(cache).cyclicSetProofFor(blueId);
        NodeProviderResult withoutCapability = new VerifyingNodeProvider(cache)
                .fetchResultByBlueId(blueId);
        fixture.provider.proofResult = null;
        NodeProviderResult nullProof =
                new VerifyingNodeProvider(new CachingNodeProvider(fixture.provider, 1000000))
                        .fetchResultByBlueId(blueId);

        // then
        assertFalse(verified);
        assertEquals(NodeProviderOutcome.NOT_FOUND, missing.outcome());
        assertEquals(NodeProviderOutcome.INVALID_EVIDENCE, withoutCapability.outcome());
        assertEquals(NodeProviderOutcome.INVALID_EVIDENCE, nullProof.outcome());
    }

    @ParameterizedTest
    @MethodSource("contentFailures")
    void shouldPreserveTypedContentFailuresAndRetry(
            Composition composition, NodeProviderResult failure) {
        // given
        Fixture fixture = new Fixture();
        String blueId = fixture.memberBlueIds.get(0);
        fixture.provider.contentResult = failure;
        NodeProvider provider = composition.wrap(fixture.provider);

        // when
        NodeProviderResult actual = provider.fetchResultByBlueId(blueId);
        int proofsBeforeRepair = fixture.provider.proofRequests.size();
        fixture.provider.contentResult = null;
        NodeProviderResult retried = provider.fetchResultByBlueId(blueId);

        // then
        assertEquals(failure.outcome(), actual.outcome());
        assertEquals(failure.diagnostic(), actual.diagnostic());
        assertEquals(0, proofsBeforeRepair);
        if (failure.outcome() == NodeProviderOutcome.NOT_FOUND
                && composition != Composition.DIRECT) {
            assertEquals(NodeProviderOutcome.NOT_FOUND, retried.outcome());
            assertEquals(1, fixture.provider.fetches);
        } else {
            assertEquals(NodeProviderOutcome.FOUND, retried.outcome());
            assertEquals(2, fixture.provider.fetches);
        }
    }

    @ParameterizedTest
    @EnumSource(Composition.class)
    void shouldPreserveAcyclicVerification(Composition composition) {
        // given
        Fixture fixture = new Fixture();
        Node plain = new Node().value("acyclic");
        String blueId = DirectBlueIdCalculator.calculateBlueId(plain);
        fixture.provider.content.put(blueId, plain);
        NodeProvider provider = composition.wrap(fixture.provider);

        // when
        NodeProviderResult first = provider.fetchResultByBlueId(blueId);
        NodeProviderResult repeated = provider.fetchResultByBlueId(blueId);

        // then
        assertEquals(NodeProviderOutcome.FOUND, first.outcome());
        assertEquals(NodeProviderOutcome.FOUND, repeated.outcome());
        assertTrue(fixture.provider.proofRequests.isEmpty());
    }

    private static Stream<Arguments> proofFailures() {
        return Arrays.stream(Composition.values()).flatMap(composition ->
                Stream.of(CyclicSetProofResult.notFound(),
                        CyclicSetProofResult.unavailable("proof store offline"),
                        CyclicSetProofResult.invalidEvidence("proof corrupt"))
                        .map(failure -> Arguments.of(composition, failure)));
    }

    private static Stream<Arguments> contentFailures() {
        return Arrays.stream(Composition.values()).flatMap(composition ->
                Stream.of(NodeProviderResult.notFound(),
                        NodeProviderResult.unavailable("content offline"),
                        NodeProviderResult.invalidEvidence("content invalid"))
                        .map(failure -> Arguments.of(composition, failure)));
    }

    private static CyclicAwareNodeProvider cyclicCache(NodeProvider delegate) {
        return requireCyclic(new CachingNodeProvider(delegate, 1000000));
    }

    private static CyclicAwareNodeProvider requireCyclic(NodeProvider provider) {
        assertTrue(provider instanceof CyclicAwareNodeProvider,
                "Content cache must preserve the cyclic evidence capability");
        return (CyclicAwareNodeProvider) provider;
    }

    private enum Composition {
        DIRECT, CACHE_INSIDE_VERIFIER, CACHE_OUTSIDE_VERIFIER, LANGUAGE_WRAPPED_CACHE;

        private NodeProvider wrap(NodeProvider provider) {
            switch (this) {
                case DIRECT:
                    return new VerifyingNodeProvider(provider);
                case CACHE_INSIDE_VERIFIER:
                    return new VerifyingNodeProvider(new CachingNodeProvider(provider, 1000000));
                case CACHE_OUTSIDE_VERIFIER:
                    return new CachingNodeProvider(new VerifyingNodeProvider(provider), 1000000);
                case LANGUAGE_WRAPPED_CACHE:
                    return NodeProviderWrapper.wrap(new CachingNodeProvider(provider, 1000000));
                default:
                    throw new AssertionError(this);
            }
        }
    }

    private static List<NodeProviderResult> fetchMembers(
            Fixture fixture, NodeProvider provider) {
        return fixture.memberBlueIds.stream().map(provider::fetchResultByBlueId)
                .collect(Collectors.toList());
    }

    private static void assertMembers(
            Fixture fixture, List<NodeProviderResult> results) {
        for (int index = 0; index < fixture.memberBlueIds.size(); index++) {
            String blueId = fixture.memberBlueIds.get(index);
            NodeProviderResult actual = results.get(index);
            assertEquals(NodeProviderOutcome.FOUND, actual.outcome(),
                    actual.diagnostic().orElse(blueId));
            assertEquals(NodeWireForm.get(fixture.provider.content.get(blueId)),
                    NodeWireForm.get(actual.nodes().get(0)));
        }
    }

    private static final class Fixture {
        private final CyclicSetFinalization finalization =
                CircularSetIdentityCalculator.calculateCircularSetFinalization(
                        Arrays.asList(
                                new Node().name("A").properties(
                                        "next", new Node().blueId("this#1")),
                                new Node().name("B").properties(
                                        "next", new Node().blueId("this#0"))));
        private final List<Node> declared = finalization.canonicalMemberBodies();
        private final List<String> memberBlueIds = CircularSetIdentityCalculator
                .calculateCircularSetBlueIds(declared);
        private final CyclicSetProof proof =
                CyclicSetProof.fromDeclaredPlaceholderSet(declared);
        private final ExactProvider provider = new ExactProvider();

        private Fixture() {
            for (int index = 0; index < declared.size(); index++) {
                assertEquals(finalization.masterBlueId() + "#" + index,
                        memberBlueIds.get(index));
                Node member = declared.get(index).clone();
                Node next = member.getProperties().get("next");
                int target = Integer.parseInt(next.getBlueId().substring(5));
                next.blueId(memberBlueIds.get(target));
                provider.content.put(memberBlueIds.get(index), member);
            }
            provider.proofResult = CyclicSetProofResult.found(proof);
        }
    }

    private static final class ExactProvider
            implements NodeProvider, CyclicAwareNodeProvider {
        private final Map<String, Node> content = new LinkedHashMap<>();
        private final List<String> proofRequests = new ArrayList<>();
        private int fetches;
        private NodeProviderResult contentResult;
        private CyclicSetProofResult proofResult;

        @Override
        public List<Node> fetchByBlueId(String blueId) {
            return fetchResultByBlueId(blueId).nodes();
        }

        @Override
        public NodeProviderResult fetchResultByBlueId(String blueId) {
            fetches++;
            if (contentResult != null) {
                return contentResult;
            }
            Node member = content.get(blueId);
            return member == null ? NodeProviderResult.notFound()
                    : NodeProviderResult.found(Collections.singletonList(member));
        }

        @Override
        public boolean hasVerifiedContentForBlueId(String blueId) {
            return content.containsKey(blueId);
        }

        @Override
        public CyclicSetProofResult cyclicSetProofFor(String blueId) {
            proofRequests.add(blueId);
            return proofResult;
        }
    }
}
