package blue.language.processor;

import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.identity.CanonicalTypeIdentityEvidence;
import blue.language.matching.FrozenTypeMatcher;
import blue.language.merge.TypeEvidenceResolution;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Optional;
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
                DirectBlueIdCalculator.calculateBlueId(
                        exactText);
        Node event =
                new Node().properties(
                        "request",
                        new Node().blueId(
                                exactTextBlueId));
        String eventBlueId =
                DirectBlueIdCalculator.calculateBlueId(event);
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
                        eventBlueId,
                        Collections.emptyMap(),
                        new ContractMatchingService(),
                        CanonicalTypeIdentityLookup.incomplete(),
                        null,
                        matcherSession);

        // when
        boolean exactMatch =
                context.matchesEventPattern(
                        exactPattern);
        boolean rewrittenMatch =
                context.matchesEventPattern(
                        rewrittenPattern);
        boolean exactIdentityMatch =
                context.matchesEventPattern(
                        new Node().blueId(eventBlueId));

        // then
        assertTrue(exactMatch);
        assertFalse(rewrittenMatch);
        assertTrue(exactIdentityMatch);
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
                                    this::materializeReferenceEvidence);
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

        private TypeEvidenceResolution materializeReferenceEvidence(
                FrozenNode reference) {
            FrozenNode materialized = materializeExactReference(reference);
            return new TypeEvidenceResolution(
                    materialized,
                    new CanonicalTypeIdentityLookup() {
                        @Override
                        public boolean hasCompleteCoverage() {
                            return false;
                        }

                        @Override
                        public Optional<CanonicalTypeIdentityEvidence>
                        findCanonicalTypeIdentityEvidence(
                                Node completedType) {
                            return Optional.of(CanonicalTypeIdentityEvidence
                                    .referenceSource(
                                            requireCanonicalTypeBlueId(
                                                    completedType)));
                        }

                        @Override
                        public String requireCanonicalTypeBlueId(
                                Node completedType) {
                            if (!materialized.resolvedStructuralKey().equals(
                                    FrozenNode.fromResolvedNode(completedType)
                                            .resolvedStructuralKey())) {
                                throw new IllegalStateException(
                                        "Unexpected exact materialization");
                            }
                            return reference.getReferenceBlueId();
                        }
                    });
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
