package blue.language;

import blue.language.model.Node;
import blue.language.provider.BasicNodeProvider;
import blue.language.provider.CyclicAwareNodeProvider;
import blue.language.provider.SequentialNodeProvider;
import blue.language.provider.VerifyingNodeProvider;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static blue.language.utils.UncheckedObjectMapper.YAML_MAPPER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CyclicProviderFallbackTest {

    @Test
    void plainCyclicMissFallsThroughToVerifiedCyclicProvider() {
        CyclicFixture fixture = new CyclicFixture();
        AtomicInteger missFetches = new AtomicInteger();
        CountingCyclicProvider fallback = new CountingCyclicProvider(fixture.provider);
        Blue blue = new Blue(new SequentialNodeProvider(
                new VerifyingNodeProvider(blueId -> {
                    missFetches.incrementAndGet();
                    return null;
                }),
                new VerifyingNodeProvider(fallback)));

        Node resolved = blue.resolve(typedNode(fixture.memberBlueId));

        assertEquals("cyclic", resolved.getAsText("/fixed"));
        assertEquals(1, missFetches.get());
        assertEquals(1, fallback.fetches.get());
        assertEquals(1, fallback.proofQueries.get());
    }

    @Test
    void emptyResultIsNotFoundAndFallsThroughToVerifiedCyclicProvider() {
        CyclicFixture fixture = new CyclicFixture();
        AtomicInteger emptyFetches = new AtomicInteger();
        CountingCyclicProvider fallback = new CountingCyclicProvider(fixture.provider);
        Blue blue = new Blue(new SequentialNodeProvider(
                new VerifyingNodeProvider(blueId -> {
                    emptyFetches.incrementAndGet();
                    return Collections.emptyList();
                }),
                new VerifyingNodeProvider(fallback)));

        Node resolved = blue.resolve(typedNode(fixture.memberBlueId));

        assertEquals("cyclic", resolved.getAsText("/fixed"));
        assertEquals(1, emptyFetches.get());
        assertEquals(1, fallback.fetches.get());
        assertEquals(1, fallback.proofQueries.get());
    }

    @Test
    void plainCyclicContentWithoutProofStopsBeforeFallback() {
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

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> blue.resolve(typedNode(fixture.memberBlueId)));

        assertTrue(messageChain(failure).contains("cyclic-set-aware verifier"));
        assertEquals(1, plainFetches.get());
        assertEquals(0, fallback.fetches.get());
        assertEquals(0, fallback.proofQueries.get());
    }

    @Test
    void cyclicAwareMissDoesNotBypassFallbackProofRequirement() {
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

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> blue.resolve(typedNode(fixture.memberBlueId)));

        assertTrue(messageChain(failure).contains("cyclic-set-aware verifier"));
        assertEquals(1, first.fetches.get());
        assertEquals(0, first.proofQueries.get());
        assertEquals(1, plainFetches.get());
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
        public boolean hasVerifiedContentForBlueId(String blueId) {
            proofQueries.incrementAndGet();
            return delegate.hasVerifiedContentForBlueId(blueId);
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
        public boolean hasVerifiedContentForBlueId(String blueId) {
            proofQueries.incrementAndGet();
            return true;
        }
    }

    private static final class CyclicFixture {
        private final BasicNodeProvider provider = new BasicNodeProvider(YAML_MAPPER.readValue(
                "- name: Cyclic Event\n"
                        + "  fixed: cyclic\n"
                        + "- name: Cyclic Companion\n"
                        + "  fixed: companion\n",
                Node.class));
        private final String memberBlueId = provider.getBlueIdByName("Cyclic Event");
    }
}
