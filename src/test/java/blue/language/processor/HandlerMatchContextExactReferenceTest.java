package blue.language.processor;

import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.FrozenTypeMatcher;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class HandlerMatchContextExactReferenceTest {

    @Test
    void shouldMatchExactReferencedWhitespaceAndUnicodeWithoutRewriting() {
        // given
        Node exactText =
                new Node().value(
                        "  café\u00a0\u2126  ");
        String exactTextBlueId =
                BlueIdCalculator.calculateBlueId(
                        exactText);
        Node event =
                new Node().properties(
                        "request",
                        new Node().blueId(
                                exactTextBlueId));
        Node exactPattern =
                new Node().properties(
                        "request",
                        exactText.clone());
        Node rewrittenPattern =
                new Node().properties(
                        "request",
                        new Node().value(
                                "café \u03a9"));
        AtomicInteger materializations =
                new AtomicInteger();
        ExactMatcherSession matcherSession =
                new ExactMatcherSession(
                        exactTextBlueId,
                        FrozenNode.fromResolvedNode(
                                exactText),
                        materializations);
        HandlerMatchContext context =
                new HandlerMatchContext(
                        "/",
                        "handler",
                        "events",
                        event,
                        event,
                        Collections.emptyMap(),
                        new ContractMatchingService(),
                        null,
                        matcherSession);

        // when
        boolean exactMatch =
                context.matchesEventPattern(
                        exactPattern);
        boolean rewrittenMatch =
                context.matchesEventPattern(
                        rewrittenPattern);

        // then
        assertTrue(exactMatch);
        assertFalse(rewrittenMatch);
        assertTrue(materializations.get() >= 1);
        matcherSession.close();
    }

    private static final class ExactMatcherSession
            implements ExternalChannelFunctionEvaluation
            .MatcherSession {
        private final String expectedBlueId;
        private final FrozenNode exactContent;
        private final AtomicInteger materializations;
        private FrozenTypeMatcher matcher;

        private ExactMatcherSession(
                String expectedBlueId,
                FrozenNode exactContent,
                AtomicInteger materializations) {
            this.expectedBlueId =
                    expectedBlueId;
            this.exactContent =
                    exactContent;
            this.materializations =
                    materializations;
            this.matcher =
                    FrozenTypeMatcher
                            .withVerifiedReferenceMaterializer(
                                    this::materializeExactReference);
        }

        @Override
        public void requireActive() {
            if (matcher == null) {
                throw new IllegalStateException(
                        "matcher is closed");
            }
        }

        @Override
        public boolean matches(
                FrozenNode candidate,
                FrozenNode pattern) {
            requireActive();
            return matcher.matchesType(
                    candidate,
                    pattern);
        }

        @Override
        public boolean isAssignableToType(
                String candidateTypeBlueId,
                String baseTypeBlueId) {
            requireActive();
            return candidateTypeBlueId.equals(
                    baseTypeBlueId);
        }

        @Override
        public FrozenNode materializeExactReference(
                FrozenNode reference) {
            requireActive();
            if (!expectedBlueId.equals(
                    reference.getReferenceBlueId())) {
                throw new IllegalArgumentException(
                        "unexpected exact reference");
            }
            materializations.incrementAndGet();
            return exactContent;
        }

        @Override
        public void close() {
            if (matcher != null) {
                matcher.clearCaches();
                matcher = null;
            }
        }
    }
}
