package blue.language.processor;

import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.identity.CanonicalTypeIdentityEvidence;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.processor.model.MarkerContract;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class HandlerMatchContextCanonicalTypeIdentityEvidenceTest {

    @Test
    void shouldUseResolverIssuedIdsForCompletedSubtypeOperands() {
        // given
        Node candidateType = new Node().name("Completed candidate");
        Node expectedType = new Node().name("Completed expected");
        String candidateBlueId = DirectBlueIdCalculator.calculateBlueId(
                new Node().name("Canonical candidate"));
        String expectedBlueId = DirectBlueIdCalculator.calculateBlueId(
                new Node().name("Canonical expected"));
        RecordingMatcherSession matcher = new RecordingMatcherSession(
                candidateBlueId, expectedBlueId);
        HandlerMatchContext context = context(
                candidateType,
                lookup(
                        candidateType, candidateBlueId,
                        expectedType, expectedBlueId),
                matcher);

        // when
        boolean matched = context.eventDeclaredTypeIsSameOrDescendantOf(
                expectedType);

        // then
        assertTrue(matched);
        assertEquals(candidateBlueId, matcher.candidateBlueId);
        assertEquals(expectedBlueId, matcher.expectedBlueId);
    }

    @Test
    void shouldFailClosedWithoutCompletedTypeEvidence() {
        // given
        Node candidateType = new Node().name("Unproved candidate");
        HandlerMatchContext context = context(
                candidateType,
                CanonicalTypeIdentityLookup.incomplete(),
                new RecordingMatcherSession("unused", "unused"));

        // when
        Executable match = () -> context
                .eventDeclaredTypeIsSameOrDescendantOf(
                        new Node().blueId(
                                DirectBlueIdCalculator.calculateBlueId(
                                        new Node().name("Expected"))));

        // then
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                match);

        assertTrue(failure.getMessage().contains(
                "canonical type identity evidence"));
    }

    private static HandlerMatchContext context(
            Node candidateType,
            CanonicalTypeIdentityLookup identities,
            ExternalChannelFunctionEvaluation.MatcherSession matcher) {
        Node event = new Node().type(candidateType.clone());
        return new HandlerMatchContext(
                "/",
                "handler",
                "channel",
                event,
                event,
                null,
                Collections.<String, MarkerContract>emptyMap(),
                new ContractMatchingService(),
                identities,
                null,
                matcher);
    }

    private static CanonicalTypeIdentityLookup lookup(
            Node first,
            String firstBlueId,
            Node second,
            String secondBlueId) {
        Map<FrozenNode.ResolvedStructuralKey, String> identities =
                new LinkedHashMap<>();
        identities.put(
                FrozenNode.fromResolvedNode(first).resolvedStructuralKey(),
                firstBlueId);
        identities.put(
                FrozenNode.fromResolvedNode(second).resolvedStructuralKey(),
                secondBlueId);
        return new CanonicalTypeIdentityLookup() {
            @Override
            public boolean hasCompleteCoverage() {
                return true;
            }

            @Override
            public Optional<String> findCanonicalTypeBlueId(Node type) {
                if (type.isReferenceOnly()) {
                    return Optional.of(type.getBlueId());
                }
                return Optional.ofNullable(identities.get(
                        FrozenNode.fromResolvedNode(type)
                                .resolvedStructuralKey()));
            }

            @Override
            public Optional<CanonicalTypeIdentityEvidence>
            findCanonicalTypeIdentityEvidence(Node type) {
                return findCanonicalTypeBlueId(type).map(blueId ->
                        type.isReferenceOnly()
                                ? CanonicalTypeIdentityEvidence
                                .referenceSource(blueId)
                                : CanonicalTypeIdentityEvidence
                                .identityOnly(blueId));
            }

            @Override
            public String requireCanonicalTypeBlueId(Node type) {
                return findCanonicalTypeBlueId(type)
                        .orElseThrow(() -> new IllegalStateException(
                                "Missing test identity evidence"));
            }
        };
    }

    private static final class RecordingMatcherSession
            implements ExternalChannelFunctionEvaluation.MatcherSession {
        private final String acceptedCandidate;
        private final String acceptedExpected;
        private String candidateBlueId;
        private String expectedBlueId;

        private RecordingMatcherSession(
                String acceptedCandidate,
                String acceptedExpected) {
            this.acceptedCandidate = acceptedCandidate;
            this.acceptedExpected = acceptedExpected;
        }

        @Override
        public void requireActive() {
        }

        @Override
        public boolean matches(FrozenNode candidate, FrozenNode pattern) {
            return false;
        }

        @Override
        public boolean isAssignableToType(
                String candidateTypeBlueId,
                String baseTypeBlueId) {
            this.candidateBlueId = candidateTypeBlueId;
            this.expectedBlueId = baseTypeBlueId;
            return acceptedCandidate.equals(candidateTypeBlueId)
                    && acceptedExpected.equals(baseTypeBlueId);
        }

        @Override
        public FrozenNode materializeExactReference(FrozenNode reference) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() {
        }
    }
}
