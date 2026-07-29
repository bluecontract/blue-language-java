package blue.language.provider;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.utils.BlueIdCalculator;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static blue.language.processor.FailureCapture.captureFailure;
import static blue.language.utils.UncheckedObjectMapper.YAML_MAPPER;
import static org.junit.jupiter.api.Assertions.*;

class ProviderCanonicalIngestionTest {

    @Test
    void shouldRejectInvalidConstraintsKey() {
        // given
        String invalidConstraintsDoc = "name: Invalid Constraints\n" +
                "constraints:\n" +
                "  minLength: 2";
        BasicNodeProvider provider = new BasicNodeProvider();

        // when
        Throwable deserializationFailure = captureFailure(
                () -> YAML_MAPPER.readValue(
                        invalidConstraintsDoc,
                        blue.language.model.Node.class));
        Throwable ingestionFailure = captureFailure(
                () -> provider.addSingleDocs(invalidConstraintsDoc));

        // then
        assertInstanceOf(RuntimeException.class, deserializationFailure);
        assertInstanceOf(RuntimeException.class, ingestionFailure);
    }

    @Test
    void shouldFailTypeResolutionWhenProviderContentHasWrongBlueId() {
        // given
        String requestedBlueId = BlueIdCalculator.calculateBlueId(new Node().value("expected"));
        Blue blue = new Blue(blueId -> Collections.singletonList(new Node().value("actual")));
        Node typedNode = new Node().type(new Node().blueId(requestedBlueId));

        // when
        Throwable failure = captureFailure(() -> blue.resolve(typedNode));

        // then
        assertInstanceOf(IllegalArgumentException.class, failure);
    }

    @Test
    void shouldFailDeterministicallyWhenProviderContentIsMissing() {
        // given
        String requestedBlueId = BlueIdCalculator.calculateBlueId(new Node().value("missing"));
        Blue blue = new Blue(blueId -> Collections.emptyList());
        Node typedNode = new Node()
                .type(new Node().blueId(requestedBlueId));

        // when
        Throwable error = captureFailure(
                () -> blue.resolve(typedNode));

        // then
        assertInstanceOf(RuntimeException.class, error);
        assertNotNull(error.getMessage());
    }

    @Test
    void shouldRejectInvalidBlueIdBeforeProviderFetch() {
        // given
        AtomicBoolean fetched = new AtomicBoolean(false);
        VerifyingNodeProvider provider = new VerifyingNodeProvider(blueId -> {
            fetched.set(true);
            return Collections.singletonList(new Node().value("x"));
        });

        // when
        Throwable failure = captureFailure(
                () -> provider.fetchByBlueId("not-a-real-blueid"));

        // then
        assertInstanceOf(IllegalArgumentException.class, failure);
        assertFalse(fetched.get());
    }

    @Test
    void shouldNotSkipVerificationWhenProviderContentReferencesRequestedBlueId() {
        // given
        String requestedBlueId = BlueIdCalculator.calculateBlueId(new Node().value("expected"));
        VerifyingNodeProvider provider = new VerifyingNodeProvider(blueId -> Collections.singletonList(
                new Node().properties(
                        "self", new Node().blueId(requestedBlueId),
                        "actual", new Node().value("actual"))));

        // when
        Throwable failure = captureFailure(
                () -> provider.fetchByBlueId(requestedBlueId));

        // then
        assertInstanceOf(IllegalArgumentException.class, failure);
    }

    @Test
    void shouldNotUseCyclicRewriteFallbackForPlainProviderId() {
        // given
        String requestedBlueId = BlueIdCalculator.calculateBlueIdAllowingCyclicPlaceholders(
                new Node().properties("self", new Node().blueId(NodeContentHandler.ZERO_BLUE_ID)));
        VerifyingNodeProvider provider = new VerifyingNodeProvider(blueId -> Collections.singletonList(
                new Node().properties("self", new Node().blueId(requestedBlueId))));

        // when
        Throwable failure = captureFailure(
                () -> provider.fetchByBlueId(requestedBlueId));

        // then
        assertInstanceOf(IllegalArgumentException.class, failure);
    }

    @Test
    void shouldNotBypassPlainVerificationForCyclicAwareDelegate() {
        // given
        String requestedBlueId = BlueIdCalculator.calculateBlueId(new Node().value("expected"));
        VerifyingNodeProvider provider =
                new VerifyingNodeProvider(
                        new CyclicAwareWrongContentProvider());

        // when
        Throwable failure = captureFailure(
                () -> provider.fetchByBlueId(requestedBlueId));

        // then
        assertInstanceOf(IllegalArgumentException.class, failure);
    }

    @Test
    void shouldRejectWrongCyclicMemberDespiteValidCompleteSetProof() {
        // given
        BasicNodeProvider canonical = cyclicProvider();
        String requestedBlueId = canonical.getBlueIdByName("A");
        CyclicSetProof proof = canonical
                .cyclicSetProofFor(requestedBlueId)
                .proof()
                .orElseThrow(AssertionError::new);
        Node wrongMember = canonical.fetchByBlueId(
                canonical.getBlueIdByName("B")).get(0);
        VerifyingNodeProvider provider = new VerifyingNodeProvider(
                new LyingCyclicProvider(
                        Collections.singletonList(wrongMember), proof));

        // when
        NodeProviderResult result =
                provider.fetchResultByBlueId(requestedBlueId);
        Throwable failure = captureFailure(
                () -> provider.fetchByBlueId(requestedBlueId));

        // then
        assertEquals(NodeProviderOutcome.INVALID_EVIDENCE, result.outcome());
        assertInstanceOf(IllegalArgumentException.class, failure);
    }

    @Test
    void shouldNotProduceCyclicProofForOrdinaryMultiDocumentContent() {
        // given
        BasicNodeProvider provider = new BasicNodeProvider(
                YAML_MAPPER.readValue(
                        "- name: Ordinary A\n"
                                + "  value: a\n"
                                + "- name: Ordinary B\n"
                                + "  value: b\n",
                        Node.class));
        String memberBlueId =
                provider.getBlueIdByName("Ordinary A");
        VerifyingNodeProvider verifyingProvider =
                new VerifyingNodeProvider(provider);

        // when
        CyclicSetProofResult proofResult =
                provider.cyclicSetProofFor(memberBlueId);
        NodeProviderOutcome outcome = verifyingProvider
                .fetchResultByBlueId(memberBlueId)
                .outcome();

        // then
        assertEquals(NodeProviderOutcome.NOT_FOUND, proofResult.outcome());
        assertEquals(NodeProviderOutcome.INVALID_EVIDENCE, outcome);
    }

    @Test
    void shouldRequireCyclicAwareVerificationForCyclicMemberFetch() {
        // given
        String baseBlueId = BlueIdCalculator.calculateBlueId(new Node().value("base"));
        String memberBlueId = baseBlueId + "#0";
        VerifyingNodeProvider provider = new VerifyingNodeProvider(blueId -> {
            if (memberBlueId.equals(blueId)) {
                return Collections.singletonList(new Node().value("member"));
            }
            return null;
        });

        // when
        Throwable failure = captureFailure(
                () -> provider.fetchByBlueId(memberBlueId));

        // then
        assertInstanceOf(IllegalArgumentException.class, failure);
    }

    @Test
    void shouldNotUsePartialBaseSetVerificationForCyclicMember() {
        // given
        BasicNodeProvider baseProvider = cyclicProvider();
        String aBlueId = baseProvider.getBlueIdByName("A");
        String baseBlueId = aBlueId.substring(0, aBlueId.indexOf('#'));
        String memberBlueId = baseBlueId + "#0";
        List<Node> baseNodes = baseProvider.fetchByBlueId(baseBlueId);

        VerifyingNodeProvider provider = new VerifyingNodeProvider(blueId -> {
            if (baseBlueId.equals(blueId)) {
                return baseNodes;
            }
            if (memberBlueId.equals(blueId)) {
                return Collections.singletonList(baseNodes.get(1));
            }
            return null;
        });

        // when
        Throwable failure = captureFailure(
                () -> provider.fetchByBlueId(memberBlueId));

        // then
        assertInstanceOf(IllegalArgumentException.class, failure);
    }

    private static BasicNodeProvider cyclicProvider() {
        return new BasicNodeProvider(YAML_MAPPER.readValue(
                "- name: A\n"
                        + "  next:\n"
                        + "    type:\n"
                        + "      blueId: this#1\n"
                        + "- name: B\n"
                        + "  next:\n"
                        + "    type:\n"
                        + "      blueId: this#0",
                Node.class));
    }

    private static final class CyclicAwareWrongContentProvider
            implements blue.language.NodeProvider, CyclicAwareNodeProvider {

        @Override
        public List<Node> fetchByBlueId(String blueId) {
            return Collections.singletonList(new Node().value("actual"));
        }

        @Override
        public CyclicSetProofResult cyclicSetProofFor(String blueId) {
            return CyclicSetProofResult.notFound();
        }
    }

    private static final class LyingCyclicProvider
            implements blue.language.NodeProvider, CyclicAwareNodeProvider {
        private final List<Node> returned;
        private final CyclicSetProof proof;

        private LyingCyclicProvider(
                List<Node> returned,
                CyclicSetProof proof) {
            this.returned = returned;
            this.proof = proof;
        }

        @Override
        public List<Node> fetchByBlueId(String blueId) {
            return returned;
        }

        @Override
        public CyclicSetProofResult cyclicSetProofFor(String blueId) {
            return CyclicSetProofResult.found(proof);
        }
    }
}
