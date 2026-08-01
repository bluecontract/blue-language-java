package blue.language.provider;

import blue.language.api.BlueLanguageErrorCategory;
import blue.language.api.BlueLanguageErrorClassifier;
import blue.language.provider.NodeProvider;
import blue.language.model.Node;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.identity.CircularSetIdentityCalculator;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static blue.language.processor.FailureCapture.captureFailure;
import static blue.language.utils.UncheckedObjectMapper.YAML_MAPPER;
import static blue.language.utils.UncheckedObjectMapper.JSON_MAPPER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerifyingNodeProviderResultSemanticsTest {

    @Test
    void shouldFailMalformedCyclicMemberBeforeDelegateLookup() {
        // given
        CyclicFixture fixture = new CyclicFixture();
        AtomicInteger fetches = new AtomicInteger();
        VerifyingNodeProvider provider = new VerifyingNodeProvider(blueId -> {
            fetches.incrementAndGet();
            return null;
        });

        // when
        IllegalArgumentException failure = captureFailure(
                () -> provider.fetchByBlueId(fixture.baseBlueId + "#01"));
        int fetchCount = fetches.get();

        // then
        assertTrue(failure instanceof IllegalArgumentException);
        assertEquals(BlueLanguageErrorCategory.InvalidBlueId,
                BlueLanguageErrorClassifier.classify(failure));
        assertEquals(0, fetchCount);
    }

    @Test
    void shouldReturnNullForNonCyclicAwareCyclicMiss() {
        // given
        CyclicFixture fixture = new CyclicFixture();
        RecordingProvider delegate = new RecordingProvider(null);
        VerifyingNodeProvider provider = new VerifyingNodeProvider(delegate);

        // when
        List<Node> result = provider.fetchByBlueId(fixture.memberBlueId);
        int fetchCount = delegate.fetches.get();

        // then
        assertNull(result);
        assertEquals(1, fetchCount);
    }

    @Test
    void shouldTreatNonCyclicAwareEmptyCyclicResultAsCanonicalNotFound() {
        // given
        CyclicFixture fixture = new CyclicFixture();
        List<Node> empty = Collections.emptyList();
        RecordingProvider delegate = new RecordingProvider(empty);
        VerifyingNodeProvider provider = new VerifyingNodeProvider(delegate);

        // when
        List<Node> result = provider.fetchByBlueId(fixture.memberBlueId);
        int fetchCount = delegate.fetches.get();

        // then
        assertNull(result);
        assertEquals(1, fetchCount);
    }

    @Test
    void shouldFailVerificationForNonCyclicAwareCyclicContent() {
        // given
        CyclicFixture fixture = new CyclicFixture();
        List<Node> content = fixture.provider.fetchByBlueId(fixture.memberBlueId);
        RecordingProvider delegate = new RecordingProvider(content);
        VerifyingNodeProvider provider = new VerifyingNodeProvider(delegate);

        // when
        NodeProviderOutcome outcome =
                provider.fetchResultByBlueId(fixture.memberBlueId).outcome();
        IllegalArgumentException failure = captureFailure(
                () -> provider.fetchByBlueId(fixture.memberBlueId));
        int fetchCount = delegate.fetches.get();

        // then
        assertEquals(NodeProviderOutcome.INVALID_EVIDENCE, outcome);
        assertTrue(failure instanceof IllegalArgumentException);
        assertEquals(2, fetchCount);
    }

    @Test
    void shouldNotRequireProofForCyclicAwareMiss() {
        // given
        CyclicFixture fixture = new CyclicFixture();
        RecordingCyclicProvider delegate =
                new RecordingCyclicProvider(
                        null,
                        CyclicSetProofResult.found(fixture.proof));
        VerifyingNodeProvider provider = new VerifyingNodeProvider(delegate);

        // when
        List<Node> result = provider.fetchByBlueId(fixture.memberBlueId);
        int fetchCount = delegate.fetches.get();
        int proofQueryCount = delegate.proofQueries.get();

        // then
        assertNull(result);
        assertEquals(1, fetchCount);
        assertEquals(0, proofQueryCount);
    }

    @Test
    void shouldTreatCyclicAwareEmptyAsCanonicalNotFoundWithoutRequiringProof() {
        // given
        CyclicFixture fixture = new CyclicFixture();
        List<Node> empty = Collections.emptyList();
        RecordingCyclicProvider delegate =
                new RecordingCyclicProvider(
                        empty,
                        CyclicSetProofResult.found(fixture.proof));
        VerifyingNodeProvider provider = new VerifyingNodeProvider(delegate);

        // when
        List<Node> result = provider.fetchByBlueId(fixture.memberBlueId);
        int fetchCount = delegate.fetches.get();
        int proofQueryCount = delegate.proofQueries.get();

        // then
        assertNull(result);
        assertEquals(1, fetchCount);
        assertEquals(0, proofQueryCount);
    }

    @Test
    void shouldReturnCyclicAwareVerifiedContentUnchanged() {
        // given
        CyclicFixture fixture = new CyclicFixture();
        List<Node> content = fixture.provider.fetchByBlueId(fixture.memberBlueId);
        RecordingCyclicProvider delegate =
                new RecordingCyclicProvider(
                        content,
                        CyclicSetProofResult.found(fixture.proof));
        VerifyingNodeProvider provider = new VerifyingNodeProvider(delegate);

        // when
        List<Node> actual = provider.fetchByBlueId(fixture.memberBlueId);
        int fetchCount = delegate.fetches.get();
        int proofQueryCount = delegate.proofQueries.get();

        // then
        assertNotSame(content, actual);
        assertEquals(content.size(), actual.size());
        assertEquals(fixture.expectedMemberBlueId, fixture.memberBlueId);
        assertEquals(JSON_MAPPER.valueToTree(content),
                JSON_MAPPER.valueToTree(actual));
        assertEquals(1, fetchCount);
        assertEquals(1, proofQueryCount);
    }

    @Test
    void shouldRejectCyclicContentWhenProofIsNotFound() {
        // given
        CyclicFixture fixture = new CyclicFixture();
        List<Node> content = fixture.provider.fetchByBlueId(fixture.memberBlueId);
        RecordingCyclicProvider delegate =
                new RecordingCyclicProvider(
                        content,
                        CyclicSetProofResult.notFound());
        VerifyingNodeProvider provider = new VerifyingNodeProvider(delegate);

        // when
        NodeProviderOutcome outcome =
                provider.fetchResultByBlueId(fixture.memberBlueId).outcome();
        IllegalArgumentException failure = captureFailure(
                () -> provider.fetchByBlueId(fixture.memberBlueId));
        int fetchCount = delegate.fetches.get();
        int proofQueryCount = delegate.proofQueries.get();

        // then
        assertEquals(NodeProviderOutcome.INVALID_EVIDENCE, outcome);
        assertTrue(failure instanceof IllegalArgumentException);
        assertEquals(2, fetchCount);
        assertEquals(2, proofQueryCount);
    }

    @Test
    void shouldPreserveCyclicProofUnavailability() {
        // given
        CyclicFixture fixture = new CyclicFixture();
        List<Node> content =
                fixture.provider.fetchByBlueId(fixture.memberBlueId);
        RecordingCyclicProvider delegate =
                new RecordingCyclicProvider(
                        content,
                        CyclicSetProofResult.unavailable(
                                "proof store offline"));
        VerifyingNodeProvider provider =
                new VerifyingNodeProvider(delegate);

        // when
        NodeProviderResult result =
                provider.fetchResultByBlueId(fixture.memberBlueId);
        RuntimeException legacyFailure = captureFailure(
                () -> provider.fetchByBlueId(fixture.memberBlueId));

        // then
        assertEquals(NodeProviderOutcome.UNAVAILABLE, result.outcome());
        assertEquals("proof store offline",
                result.diagnostic().orElse(null));
        assertTrue(legacyFailure instanceof ProviderUnavailableException);
        assertEquals(2, delegate.fetches.get());
        assertEquals(2, delegate.proofQueries.get());
    }

    @Test
    void shouldPreserveTypedInvalidCyclicProofEvidence() {
        // given
        CyclicFixture fixture = new CyclicFixture();
        List<Node> content =
                fixture.provider.fetchByBlueId(fixture.memberBlueId);
        RecordingCyclicProvider delegate =
                new RecordingCyclicProvider(
                        content,
                        CyclicSetProofResult.invalidEvidence(
                                "proof signature mismatch"));

        // when
        NodeProviderResult result =
                new VerifyingNodeProvider(delegate)
                        .fetchResultByBlueId(fixture.memberBlueId);

        // then
        assertEquals(
                NodeProviderOutcome.INVALID_EVIDENCE,
                result.outcome());
        assertEquals("proof signature mismatch",
                result.diagnostic().orElse(null));
    }

    @Test
    void shouldBypassProofLookupWhenCyclicContentIsUnavailable() {
        // given
        CyclicFixture fixture = new CyclicFixture();
        AtomicInteger proofQueries = new AtomicInteger();
        NodeProvider delegate = new NodeProvider() {
            @Override
            public List<Node> fetchByBlueId(String blueId) {
                throw new AssertionError(
                        "Typed result path should be used.");
            }

            @Override
            public NodeProviderResult fetchResultByBlueId(String blueId) {
                return NodeProviderResult.unavailable(
                        "content store offline");
            }
        };
        CyclicAwareNodeProvider cyclicEvidence =
                new CyclicAwareNodeProvider() {
                    @Override
                    public CyclicSetProofResult cyclicSetProofFor(
                            String blueId) {
                        proofQueries.incrementAndGet();
                        return CyclicSetProofResult.found(fixture.proof);
                    }
                };
        NodeProvider combined = new UnavailableCyclicProvider(
                delegate, cyclicEvidence);

        // when
        NodeProviderResult result =
                new VerifyingNodeProvider(combined)
                        .fetchResultByBlueId(fixture.memberBlueId);

        // then
        assertEquals(NodeProviderOutcome.UNAVAILABLE, result.outcome());
        assertEquals(0, proofQueries.get());
    }

    @Test
    void shouldDefensivelyCopyCyclicProofAndReturnedContent() {
        // given
        CyclicFixture fixture = new CyclicFixture();
        List<Node> returned = fixture.provider.fetchByBlueId(
                fixture.memberBlueId);
        RecordingCyclicProvider delegate =
                new RecordingCyclicProvider(
                        returned,
                        CyclicSetProofResult.found(fixture.proof));
        VerifyingNodeProvider provider = new VerifyingNodeProvider(delegate);

        List<Node> exposedProof = fixture.proof.declaredPlaceholderSet();
        exposedProof.get(0).name("mutated proof copy");
        List<Node> first = provider.fetchByBlueId(fixture.memberBlueId);
        first.get(0).name("mutated returned copy");

        // when
        List<Node> actual = provider.fetchByBlueId(fixture.memberBlueId);

        // then
        assertEquals("Cyclic A", actual.get(0).getName());
        assertEquals("Cyclic A",
                fixture.proof.declaredPlaceholderSet().get(0).getName());
    }

    @Test
    void shouldAllowCyclicMemberToOmitMatchingRootIdentity() {
        // given
        CyclicFixture fixture = new CyclicFixture();
        Node withoutRootIdentity = fixture.provider
                .fetchByBlueId(fixture.memberBlueId).get(0)
                .clone().blueId(null);
        RecordingCyclicProvider delegate = new RecordingCyclicProvider(
                Collections.singletonList(withoutRootIdentity),
                CyclicSetProofResult.found(fixture.proof));

        // when
        List<Node> actual = new VerifyingNodeProvider(delegate)
                .fetchByBlueId(fixture.memberBlueId);

        // then
        assertNull(actual.get(0).getBlueId());
    }

    @Test
    void shouldRejectMismatchedCyclicMemberRootIdentity() {
        // given
        CyclicFixture fixture = new CyclicFixture();
        Node wrongIdentity = fixture.provider
                .fetchByBlueId(fixture.memberBlueId).get(0)
                .clone().blueId(fixture.baseBlueId + "#1");
        RecordingCyclicProvider delegate = new RecordingCyclicProvider(
                Collections.singletonList(wrongIdentity),
                CyclicSetProofResult.found(fixture.proof));

        // when
        NodeProviderResult result = new VerifyingNodeProvider(delegate)
                .fetchResultByBlueId(fixture.memberBlueId);

        // then
        assertEquals(NodeProviderOutcome.INVALID_EVIDENCE, result.outcome());
    }

    @Test
    void shouldKeepPlainProviderMissingBehaviorUnchanged() {
        // given
        String requestedBlueId = DirectBlueIdCalculator.calculateBlueId(new Node().value("expected"));
        RecordingProvider missing = new RecordingProvider(null);

        // when
        List<Node> result =
                new VerifyingNodeProvider(missing).fetchByBlueId(requestedBlueId);
        int fetchCount = missing.fetches.get();

        // then
        assertNull(result);
        assertEquals(1, fetchCount);
    }

    @Test
    void shouldKeepPlainProviderEmptyBehaviorUnchanged() {
        // given
        String requestedBlueId = DirectBlueIdCalculator.calculateBlueId(new Node().value("expected"));
        List<Node> empty = Collections.emptyList();
        RecordingProvider terminalEmpty = new RecordingProvider(empty);

        // when
        List<Node> result =
                new VerifyingNodeProvider(terminalEmpty).fetchByBlueId(requestedBlueId);
        int fetchCount = terminalEmpty.fetches.get();

        // then
        assertNull(result);
        assertEquals(1, fetchCount);
    }

    @Test
    void shouldKeepPlainProviderMatchingBehaviorUnchanged() {
        // given
        String requestedBlueId = DirectBlueIdCalculator.calculateBlueId(new Node().value("expected"));
        List<Node> exact = Collections.singletonList(new Node().value("expected"));
        RecordingProvider matching = new RecordingProvider(exact);

        // when
        List<Node> actual = new VerifyingNodeProvider(matching)
                .fetchByBlueId(requestedBlueId);
        int fetchCount = matching.fetches.get();

        // then
        assertNotSame(exact, actual);
        assertEquals(DirectBlueIdCalculator.calculateBlueId(exact),
                DirectBlueIdCalculator.calculateBlueId(actual));
        assertEquals(1, fetchCount);
    }

    @Test
    void shouldKeepPlainProviderMismatchBehaviorUnchanged() {
        // given
        String requestedBlueId = DirectBlueIdCalculator.calculateBlueId(new Node().value("expected"));
        RecordingProvider mismatch = new RecordingProvider(
                Collections.singletonList(new Node().value("actual")));

        // when
        IllegalArgumentException failure = captureFailure(
                () -> new VerifyingNodeProvider(mismatch).fetchByBlueId(requestedBlueId));
        int fetchCount = mismatch.fetches.get();

        // then
        assertTrue(failure instanceof IllegalArgumentException);
        assertEquals(1, fetchCount);
    }

    @Test
    void shouldKeepDelegateFailureTerminal() {
        // given
        String requestedBlueId = new CyclicFixture().memberBlueId;
        RuntimeException delegateFailure = new IllegalStateException("delegate failure");
        AtomicInteger fetches = new AtomicInteger();
        AtomicInteger fallbackFetches = new AtomicInteger();
        NodeProvider failing = blueId -> {
            fetches.incrementAndGet();
            throw delegateFailure;
        };
        SequentialNodeProvider providers = new SequentialNodeProvider(
                new VerifyingNodeProvider(failing),
                blueId -> {
                    fallbackFetches.incrementAndGet();
                    return Collections.singletonList(new Node().value("requested"));
                });

        // when
        RuntimeException actual = captureFailure(
                () -> providers.fetchByBlueId(requestedBlueId));
        int fetchCount = fetches.get();
        int fallbackFetchCount = fallbackFetches.get();

        // then
        assertTrue(actual instanceof RuntimeException);
        assertSame(delegateFailure, actual);
        assertEquals(1, fetchCount);
        assertEquals(0, fallbackFetchCount);
    }

    private static class RecordingProvider implements NodeProvider {
        private final List<Node> result;
        final AtomicInteger fetches = new AtomicInteger();

        private RecordingProvider(List<Node> result) {
            this.result = result;
        }

        @Override
        public List<Node> fetchByBlueId(String blueId) {
            fetches.incrementAndGet();
            return result;
        }
    }

    private static final class RecordingCyclicProvider extends RecordingProvider
            implements CyclicAwareNodeProvider {
        private final CyclicSetProofResult proofResult;
        private final AtomicInteger proofQueries = new AtomicInteger();

        private RecordingCyclicProvider(
                List<Node> result,
                CyclicSetProofResult proofResult) {
            super(result);
            this.proofResult = proofResult;
        }

        @Override
        public CyclicSetProofResult cyclicSetProofFor(String blueId) {
            proofQueries.incrementAndGet();
            return proofResult;
        }
    }

    private static final class UnavailableCyclicProvider
            implements NodeProvider, CyclicAwareNodeProvider {
        private final NodeProvider content;
        private final CyclicAwareNodeProvider evidence;

        private UnavailableCyclicProvider(
                NodeProvider content,
                CyclicAwareNodeProvider evidence) {
            this.content = content;
            this.evidence = evidence;
        }

        @Override
        public List<Node> fetchByBlueId(String blueId) {
            return content.fetchByBlueId(blueId);
        }

        @Override
        public NodeProviderResult fetchResultByBlueId(String blueId) {
            return content.fetchResultByBlueId(blueId);
        }

        @Override
        public CyclicSetProofResult cyclicSetProofFor(String blueId) {
            return evidence.cyclicSetProofFor(blueId);
        }
    }

    private static final class CyclicFixture {
        private final Node documents = YAML_MAPPER.readValue(
                "- name: Cyclic A\n"
                        + "  next:\n"
                        + "    type:\n"
                        + "      blueId: this#1\n"
                        + "- name: Cyclic B\n"
                        + "  next:\n"
                        + "    type:\n"
                        + "      blueId: this#0\n",
                Node.class);
        private final String expectedMemberBlueId =
                CircularSetIdentityCalculator.calculateCircularSetBlueIds(
                        documents.getItems()).get(0);
        private final BasicNodeProvider provider =
                new BasicNodeProvider(documents);
        private final String memberBlueId = provider.getBlueIdByName("Cyclic A");
        private final CyclicSetProof proof = provider
                .cyclicSetProofFor(memberBlueId)
                .proof()
                .orElseThrow(AssertionError::new);
        private final String baseBlueId = memberBlueId.substring(0, memberBlueId.indexOf('#'));
    }
}
