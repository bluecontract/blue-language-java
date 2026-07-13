package blue.language.provider;

import blue.language.BlueLanguageErrorCategory;
import blue.language.BlueLanguageErrorClassifier;
import blue.language.NodeProvider;
import blue.language.model.Node;
import blue.language.utils.BlueIdCalculator;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static blue.language.utils.UncheckedObjectMapper.YAML_MAPPER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VerifyingNodeProviderResultSemanticsTest {

    @Test
    void malformedCyclicMemberFailsBeforeDelegateLookup() {
        CyclicFixture fixture = new CyclicFixture();
        AtomicInteger fetches = new AtomicInteger();
        VerifyingNodeProvider provider = new VerifyingNodeProvider(blueId -> {
            fetches.incrementAndGet();
            return null;
        });

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> provider.fetchByBlueId(fixture.baseBlueId + "#01"));

        assertEquals(BlueLanguageErrorCategory.InvalidBlueId,
                BlueLanguageErrorClassifier.classify(failure));
        assertEquals(0, fetches.get());
    }

    @Test
    void nonCyclicAwareCyclicMissReturnsNull() {
        CyclicFixture fixture = new CyclicFixture();
        RecordingProvider delegate = new RecordingProvider(null);
        VerifyingNodeProvider provider = new VerifyingNodeProvider(delegate);

        assertNull(provider.fetchByBlueId(fixture.memberBlueId));
        assertEquals(1, delegate.fetches.get());
    }

    @Test
    void nonCyclicAwareCyclicEmptyResultRemainsEmpty() {
        CyclicFixture fixture = new CyclicFixture();
        List<Node> empty = Collections.emptyList();
        RecordingProvider delegate = new RecordingProvider(empty);
        VerifyingNodeProvider provider = new VerifyingNodeProvider(delegate);

        assertSame(empty, provider.fetchByBlueId(fixture.memberBlueId));
        assertEquals(1, delegate.fetches.get());
    }

    @Test
    void nonCyclicAwareCyclicContentStillFailsVerification() {
        CyclicFixture fixture = new CyclicFixture();
        List<Node> content = fixture.provider.fetchByBlueId(fixture.memberBlueId);
        RecordingProvider delegate = new RecordingProvider(content);
        VerifyingNodeProvider provider = new VerifyingNodeProvider(delegate);

        assertThrows(UnsupportedOperationException.class,
                () -> provider.fetchByBlueId(fixture.memberBlueId));
        assertEquals(1, delegate.fetches.get());
    }

    @Test
    void cyclicAwareMissDoesNotRequireProof() {
        CyclicFixture fixture = new CyclicFixture();
        RecordingCyclicProvider delegate = new RecordingCyclicProvider(null, true);
        VerifyingNodeProvider provider = new VerifyingNodeProvider(delegate);

        assertNull(provider.fetchByBlueId(fixture.memberBlueId));
        assertEquals(1, delegate.fetches.get());
        assertEquals(0, delegate.proofQueries.get());
    }

    @Test
    void cyclicAwareEmptyDoesNotRequireProof() {
        CyclicFixture fixture = new CyclicFixture();
        List<Node> empty = Collections.emptyList();
        RecordingCyclicProvider delegate = new RecordingCyclicProvider(empty, true);
        VerifyingNodeProvider provider = new VerifyingNodeProvider(delegate);

        assertSame(empty, provider.fetchByBlueId(fixture.memberBlueId));
        assertEquals(1, delegate.fetches.get());
        assertEquals(0, delegate.proofQueries.get());
    }

    @Test
    void cyclicAwareVerifiedContentReturnsUnchanged() {
        CyclicFixture fixture = new CyclicFixture();
        List<Node> content = fixture.provider.fetchByBlueId(fixture.memberBlueId);
        RecordingCyclicProvider delegate = new RecordingCyclicProvider(content, true);
        VerifyingNodeProvider provider = new VerifyingNodeProvider(delegate);

        assertSame(content, provider.fetchByBlueId(fixture.memberBlueId));
        assertEquals(1, delegate.fetches.get());
        assertEquals(1, delegate.proofQueries.get());
    }

    @Test
    void cyclicAwareUnverifiedContentStillFails() {
        CyclicFixture fixture = new CyclicFixture();
        List<Node> content = fixture.provider.fetchByBlueId(fixture.memberBlueId);
        RecordingCyclicProvider delegate = new RecordingCyclicProvider(content, false);
        VerifyingNodeProvider provider = new VerifyingNodeProvider(delegate);

        assertThrows(UnsupportedOperationException.class,
                () -> provider.fetchByBlueId(fixture.memberBlueId));
        assertEquals(1, delegate.fetches.get());
        assertEquals(1, delegate.proofQueries.get());
    }

    @Test
    void plainProviderBehaviorIsUnchanged() {
        String requestedBlueId = BlueIdCalculator.calculateBlueId(new Node().value("expected"));

        RecordingProvider missing = new RecordingProvider(null);
        assertNull(new VerifyingNodeProvider(missing).fetchByBlueId(requestedBlueId));
        assertEquals(1, missing.fetches.get());

        List<Node> empty = Collections.emptyList();
        RecordingProvider terminalEmpty = new RecordingProvider(empty);
        assertSame(empty, new VerifyingNodeProvider(terminalEmpty).fetchByBlueId(requestedBlueId));
        assertEquals(1, terminalEmpty.fetches.get());

        List<Node> exact = Collections.singletonList(new Node().value("expected"));
        RecordingProvider matching = new RecordingProvider(exact);
        assertSame(exact, new VerifyingNodeProvider(matching).fetchByBlueId(requestedBlueId));
        assertEquals(1, matching.fetches.get());

        RecordingProvider mismatch = new RecordingProvider(
                Collections.singletonList(new Node().value("actual")));
        assertThrows(IllegalArgumentException.class,
                () -> new VerifyingNodeProvider(mismatch).fetchByBlueId(requestedBlueId));
        assertEquals(1, mismatch.fetches.get());
    }

    @Test
    void delegateFailureRemainsTerminal() {
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

        RuntimeException actual = assertThrows(RuntimeException.class,
                () -> providers.fetchByBlueId(requestedBlueId));

        assertSame(delegateFailure, actual);
        assertEquals(1, fetches.get());
        assertEquals(0, fallbackFetches.get());
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
        private final boolean verified;
        private final AtomicInteger proofQueries = new AtomicInteger();

        private RecordingCyclicProvider(List<Node> result, boolean verified) {
            super(result);
            this.verified = verified;
        }

        @Override
        public boolean hasVerifiedContentForBlueId(String blueId) {
            proofQueries.incrementAndGet();
            return verified;
        }
    }

    private static final class CyclicFixture {
        private final BasicNodeProvider provider = new BasicNodeProvider(YAML_MAPPER.readValue(
                "- name: Cyclic A\n"
                        + "  next:\n"
                        + "    type:\n"
                        + "      blueId: this#1\n"
                        + "- name: Cyclic B\n"
                        + "  next:\n"
                        + "    type:\n"
                        + "      blueId: this#0\n",
                Node.class));
        private final String memberBlueId = provider.getBlueIdByName("Cyclic A");
        private final String baseBlueId = memberBlueId.substring(0, memberBlueId.indexOf('#'));
    }
}
