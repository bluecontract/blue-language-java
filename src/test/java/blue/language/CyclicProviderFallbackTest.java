package blue.language;

import blue.language.model.Node;
import blue.language.provider.BasicNodeProvider;
import blue.language.provider.CyclicAwareNodeProvider;
import blue.language.provider.CyclicSetProofResult;
import blue.language.provider.ProviderUnavailableException;
import blue.language.provider.SequentialNodeProvider;
import blue.language.provider.VerifyingNodeProvider;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static blue.language.processor.FailureCapture.captureFailure;
import static blue.language.utils.UncheckedObjectMapper.YAML_MAPPER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CyclicProviderFallbackTest {

    @Test
    void shouldFallThroughFromPlainCyclicMissToVerifiedCyclicProvider() {
        // given
        CyclicFixture fixture = new CyclicFixture();
        AtomicInteger missFetches = new AtomicInteger();
        CountingCyclicProvider fallback = new CountingCyclicProvider(fixture.provider);
        Blue blue = new Blue(new SequentialNodeProvider(
                new VerifyingNodeProvider(blueId -> {
                    missFetches.incrementAndGet();
                    return null;
                }),
                new VerifyingNodeProvider(fallback)));

        // when
        Node resolved = blue.resolve(typedNode(fixture.memberBlueId));

        // then
        assertEquals("cyclic", resolved.getAsText("/fixed"));
        assertEquals(1, missFetches.get());
        assertEquals(1, fallback.fetches.get());
        assertEquals(1, fallback.proofQueries.get());
    }

    @Test
    void shouldTreatEmptyResultAsNotFoundAndFallThroughToVerifiedCyclicProvider() {
        // given
        CyclicFixture fixture = new CyclicFixture();
        AtomicInteger emptyFetches = new AtomicInteger();
        CountingCyclicProvider fallback = new CountingCyclicProvider(fixture.provider);
        Blue blue = new Blue(new SequentialNodeProvider(
                new VerifyingNodeProvider(blueId -> {
                    emptyFetches.incrementAndGet();
                    return Collections.emptyList();
                }),
                new VerifyingNodeProvider(fallback)));

        // when
        Node resolved = blue.resolve(typedNode(fixture.memberBlueId));

        // then
        assertEquals("cyclic", resolved.getAsText("/fixed"));
        assertEquals(1, emptyFetches.get());
        assertEquals(1, fallback.fetches.get());
        assertEquals(1, fallback.proofQueries.get());
    }

    @Test
    void shouldStopBeforeFallbackForPlainCyclicContentWithoutProof() {
        // given
        CyclicFixture fixture = new CyclicFixture();
        AtomicInteger plainFetches = new AtomicInteger();
        CountingCyclicProvider fallback = new CountingCyclicProvider(fixture.provider);
        List<Node> memberContent = fixture.provider.fetchByBlueId(fixture.memberBlueId);
        Blue blue = new Blue(new SequentialNodeProvider(
                new VerifyingNodeProvider(blueId -> {
                    plainFetches.incrementAndGet();
                    return memberContent;
                }),
                new VerifyingNodeProvider(fallback)));

        // when
        Throwable failure = captureFailure(
                () -> blue.resolve(typedNode(fixture.memberBlueId)));

        // then
        assertInstanceOf(IllegalArgumentException.class, failure);
        assertTrue(messageChain(failure).contains("cyclic-set-aware verifier"));
        assertEquals(1, plainFetches.get());
        assertEquals(0, fallback.fetches.get());
        assertEquals(0, fallback.proofQueries.get());
    }

    @Test
    void shouldNotBypassFallbackProofRequirementAfterCyclicAwareMiss() {
        // given
        CyclicFixture fixture = new CyclicFixture();
        CountingCyclicMiss first = new CountingCyclicMiss();
        AtomicInteger plainFetches = new AtomicInteger();
        List<Node> memberContent = fixture.provider.fetchByBlueId(fixture.memberBlueId);
        Blue blue = new Blue(new SequentialNodeProvider(
                new VerifyingNodeProvider(first),
                new VerifyingNodeProvider(blueId -> {
                    plainFetches.incrementAndGet();
                    return memberContent;
                })));

        // when
        Throwable failure = captureFailure(
                () -> blue.resolve(typedNode(fixture.memberBlueId)));

        // then
        assertInstanceOf(IllegalArgumentException.class, failure);
        assertTrue(messageChain(failure).contains("cyclic-set-aware verifier"));
        assertEquals(1, first.fetches.get());
        assertEquals(0, first.proofQueries.get());
        assertEquals(1, plainFetches.get());
    }

    @Test
    void shouldStopBeforeFallbackWhenCyclicProofIsUnavailable() {
        // given
        CyclicFixture fixture = new CyclicFixture();
        List<Node> memberContent =
                fixture.provider.fetchByBlueId(fixture.memberBlueId);
        AtomicInteger fallbackFetches = new AtomicInteger();
        NodeProvider unavailableProof =
                new UnavailableProofProvider(memberContent);
        SequentialNodeProvider providers = new SequentialNodeProvider(
                new VerifyingNodeProvider(unavailableProof),
                blueId -> {
                    fallbackFetches.incrementAndGet();
                    return memberContent;
                });

        // when
        Throwable failure = captureFailure(
                () -> providers.fetchByBlueId(fixture.memberBlueId));

        // then
        assertInstanceOf(ProviderUnavailableException.class, failure);
        assertTrue(messageChain(failure).contains(
                "cyclic proof service offline"));
        assertEquals(0, fallbackFetches.get());
    }

    private static String messageChain(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        Throwable current = failure;
        while (current != null) {
            if (current.getMessage() != null) {
                messages.append(current.getMessage()).append('\n');
            }
            current = current.getCause();
        }
        return messages.toString();
    }

    private static Node typedNode(String blueId) {
        return new Node().type(new Node().blueId(blueId));
    }

    private static final class CountingCyclicProvider
            implements NodeProvider, CyclicAwareNodeProvider {
        private final BasicNodeProvider delegate;
        private final AtomicInteger fetches = new AtomicInteger();
        private final AtomicInteger proofQueries = new AtomicInteger();

        private CountingCyclicProvider(BasicNodeProvider delegate) {
            this.delegate = delegate;
        }

        @Override
        public List<Node> fetchByBlueId(String blueId) {
            fetches.incrementAndGet();
            return delegate.fetchByBlueId(blueId);
        }

        @Override
        public CyclicSetProofResult cyclicSetProofFor(String blueId) {
            proofQueries.incrementAndGet();
            return delegate.cyclicSetProofFor(blueId);
        }
    }

    private static final class CountingCyclicMiss
            implements NodeProvider, CyclicAwareNodeProvider {
        private final AtomicInteger fetches = new AtomicInteger();
        private final AtomicInteger proofQueries = new AtomicInteger();

        @Override
        public List<Node> fetchByBlueId(String blueId) {
            fetches.incrementAndGet();
            return null;
        }

        @Override
        public CyclicSetProofResult cyclicSetProofFor(String blueId) {
            proofQueries.incrementAndGet();
            return CyclicSetProofResult.notFound();
        }
    }

    private static final class UnavailableProofProvider
            implements NodeProvider, CyclicAwareNodeProvider {
        private final List<Node> content;

        private UnavailableProofProvider(List<Node> content) {
            this.content = content;
        }

        @Override
        public List<Node> fetchByBlueId(String blueId) {
            return content;
        }

        @Override
        public CyclicSetProofResult cyclicSetProofFor(String blueId) {
            return CyclicSetProofResult.unavailable(
                    "cyclic proof service offline");
        }
    }

    private static final class CyclicFixture {
        private final BasicNodeProvider provider = new BasicNodeProvider(YAML_MAPPER.readValue(
                "- name: Cyclic Event\n"
                        + "  fixed: cyclic\n"
                        + "  peer:\n"
                        + "    blueId: this#1\n"
                        + "- name: Cyclic Companion\n"
                        + "  fixed: companion\n"
                        + "  peer:\n"
                        + "    blueId: this#0\n",
                Node.class));
        private final String memberBlueId = provider.getBlueIdByName("Cyclic Event");
    }
}
