package blue.language.runtime;

import blue.language.api.BlueOperationOutcome;
import blue.language.api.BlueOperationResult;
import blue.language.api.NodeProviderOutcome;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.merge.ResolvedSnapshot;
import blue.language.model.Node;
import blue.language.provider.CyclicAwareNodeProvider;
import blue.language.provider.CyclicSetProofResult;
import blue.language.provider.NodeProvider;
import blue.language.provider.NodeProviderResult;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LanguageProcessingTest {

    @Test
    void shouldReusePublishedProcessingSnapshotWithoutChangingSemantics() {
        // given
        CountingObserver observer = new CountingObserver();
        Node document = new Node().value("published");

        // when
        try (BlueLanguage language = BlueLanguage.builder().build();
             LanguageProcessing.Scope scope =
                     language.processing().openScope(observer)) {
            ResolvedSnapshot first = scope.resolve(document);
            ResolvedSnapshot second = scope.resolve(document.clone());

            // then
            assertSame(first, second);
            assertEquals(1, observer.hits.get());
            assertEquals(1, observer.misses.get());
            assertEquals(2, observer.lookupCount.get());
            assertTrue(observer.totalLookupNanos.get() >= 0L);
        }
    }

    @Test
    void shouldReturnTypedExactProviderOutcomes() {
        // given
        Node exactContent = new Node().value("exact");
        String exactBlueId = DirectBlueIdCalculator.calculateBlueId(
                exactContent);
        String absentBlueId = DirectBlueIdCalculator.calculateBlueId(
                new Node().value("absent"));
        String unavailableBlueId = DirectBlueIdCalculator.calculateBlueId(
                new Node().value("unavailable"));
        String invalidBlueId = DirectBlueIdCalculator.calculateBlueId(
                new Node().value("expected-but-invalid"));
        NodeProvider provider = providerWithOutcomes(
                exactBlueId,
                exactContent,
                unavailableBlueId,
                invalidBlueId);

        // when
        try (BlueLanguage language = BlueLanguage.builder()
                .nodeProvider(provider)
                .build();
             LanguageProcessing.Scope scope =
                     language.processing().openScope()) {
            BlueOperationResult<FrozenNode> found =
                    scope.materializeVerifiedExactReference(
                            reference(exactBlueId));
            BlueOperationResult<FrozenNode> absent =
                    scope.materializeVerifiedExactReference(
                            reference(absentBlueId));
            BlueOperationResult<FrozenNode> unavailable =
                    scope.materializeVerifiedExactReference(
                            reference(unavailableBlueId));
            BlueOperationResult<FrozenNode> invalid =
                    scope.materializeVerifiedExactReference(
                            reference(invalidBlueId));

            // then
            assertEquals(BlueOperationOutcome.ESTABLISHED,
                    found.outcome());
            assertEquals(exactBlueId,
                    found.requireEstablished().blueId());
            assertEquals(BlueOperationOutcome.ABSENT,
                    absent.outcome());
            assertEquals(BlueOperationOutcome.INCOMPLETE,
                    unavailable.outcome());
            assertEquals(NodeProviderOutcome.UNAVAILABLE,
                    unavailable.providerOutcome().orElse(null));
            assertTrue(unavailable.outstandingBlueIds().contains(
                    unavailableBlueId));
            assertEquals(BlueOperationOutcome.INVALID,
                    invalid.outcome());
            assertEquals(NodeProviderOutcome.INVALID_EVIDENCE,
                    invalid.providerOutcome().orElse(null));
        }
    }

    @Test
    void shouldPreserveTypedCyclicProofUnavailability() {
        // given
        Node memberContent = new Node().value("member");
        String masterBlueId = DirectBlueIdCalculator.calculateBlueId(
                memberContent);
        String memberBlueId = masterBlueId + "#0";
        NodeProvider provider = new UnavailableCyclicProofProvider(
                memberBlueId, memberContent);

        // when
        BlueOperationResult<FrozenNode> result;
        try (BlueLanguage language = BlueLanguage.builder()
                .nodeProvider(provider)
                .build();
             LanguageProcessing.Scope scope =
                     language.processing().openScope()) {
            result = scope.materializeVerifiedExactReference(
                    reference(memberBlueId));
        }

        // then
        assertEquals(BlueOperationOutcome.INCOMPLETE,
                result.outcome());
        assertEquals(NodeProviderOutcome.UNAVAILABLE,
                result.providerOutcome().orElse(null));
        assertTrue(result.outstandingBlueIds().contains(memberBlueId));
    }

    @Test
    void shouldReleaseSequenceLocalEvidenceOnClose() {
        // given
        Node exactContent = new Node().value("sequence-local");
        String blueId = DirectBlueIdCalculator.calculateBlueId(
                exactContent);
        AtomicInteger fetches = new AtomicInteger();
        NodeProvider provider = blueIdRequest -> {
            if (!blueId.equals(blueIdRequest)) {
                return null;
            }
            fetches.incrementAndGet();
            return Collections.singletonList(exactContent.clone());
        };

        // when
        try (BlueLanguage language = BlueLanguage.builder()
                .nodeProvider(provider)
                .build();
             LanguageProcessing.Scope root =
                     language.processing().openScope()) {
            LanguageProcessing.Scope sequence =
                    root.transientSequence();
            sequence.materializeVerifiedExactReference(
                    reference(blueId));
            sequence.materializeVerifiedExactReference(
                    reference(blueId));
            sequence.close();

            // then
            assertEquals(1, fetches.get());
            assertFalse(sequence.isTransientStateCurrent());
            assertThrows(IllegalStateException.class,
                    () -> sequence.materializeVerifiedExactReference(
                            reference(blueId)));

            root.materializeVerifiedExactReference(reference(blueId));
            assertEquals(2, fetches.get());
        }
    }

    private static FrozenNode reference(String blueId) {
        return FrozenNode.fromNode(new Node().blueId(blueId));
    }

    private static NodeProvider providerWithOutcomes(
            String exactBlueId,
            Node exactContent,
            String unavailableBlueId,
            String invalidBlueId) {
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
                if (exactBlueId.equals(blueId)) {
                    return NodeProviderResult.found(
                            Collections.singletonList(exactContent));
                }
                if (unavailableBlueId.equals(blueId)) {
                    return NodeProviderResult.unavailable(
                            "provider offline");
                }
                if (invalidBlueId.equals(blueId)) {
                    return NodeProviderResult.found(
                            Collections.singletonList(
                                    new Node().value("wrong")));
                }
                return NodeProviderResult.notFound();
            }
        };
    }

    private static final class CountingObserver
            implements LanguageProcessing.Observer {
        private final AtomicInteger hits = new AtomicInteger();
        private final AtomicInteger misses = new AtomicInteger();
        private final AtomicInteger lookupCount = new AtomicInteger();
        private final AtomicLong totalLookupNanos = new AtomicLong();

        @Override
        public void snapshotCacheHit() {
            hits.incrementAndGet();
        }

        @Override
        public void snapshotCacheMiss() {
            misses.incrementAndGet();
        }

        @Override
        public void snapshotCacheLookupNanos(long nanos) {
            lookupCount.incrementAndGet();
            totalLookupNanos.addAndGet(nanos);
        }
    }

    private static final class UnavailableCyclicProofProvider
            implements NodeProvider, CyclicAwareNodeProvider {
        private final String memberBlueId;
        private final Node memberContent;

        private UnavailableCyclicProofProvider(
                String memberBlueId,
                Node memberContent) {
            this.memberBlueId = memberBlueId;
            this.memberContent = memberContent;
        }

        @Override
        public List<Node> fetchByBlueId(String blueId) {
            return memberBlueId.equals(blueId)
                    ? Collections.singletonList(memberContent.clone())
                    : null;
        }

        @Override
        public CyclicSetProofResult cyclicSetProofFor(String blueId) {
            return CyclicSetProofResult.unavailable(
                    "cyclic proof store offline");
        }
    }
}
