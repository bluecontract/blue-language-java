package blue.language.processor;

import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.identity.CanonicalTypeIdentityEvidence;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CanonicalTypeIdentityEvidenceUnionTest {

    @Test
    void shouldEstablishCompleteGraphEvidenceFromPartialConstituents() {
        // given
        Node firstType = new Node().name("First completed type");
        Node secondType = new Node().name("Second completed type");
        Node resolvedGraph = new Node()
                .properties("first", new Node().type(firstType))
                .properties("second", new Node().type(secondType));
        String firstBlueId = DirectBlueIdCalculator.calculateBlueId(
                new Node().name("First authored type"));
        String secondBlueId = DirectBlueIdCalculator.calculateBlueId(
                new Node().name("Second authored type"));

        CanonicalTypeIdentityLookup firstEvidence = partialLookup(
                firstType, firstBlueId);
        CanonicalTypeIdentityLookup secondEvidence = partialLookup(
                secondType, secondBlueId);

        // when
        CanonicalTypeIdentityLookup union = CanonicalTypeIdentityEvidenceUnion
                .establishForResolvedGraph(
                        resolvedGraph,
                        Arrays.asList(firstEvidence, secondEvidence));

        // then
        assertTrue(union.hasCompleteCoverage());
        union.requireCompleteCoverage();
        assertEquals(firstBlueId,
                union.requireCanonicalTypeBlueId(firstType));
        assertEquals(secondBlueId,
                union.requireCanonicalTypeBlueId(secondType));
    }

    @Test
    void shouldRejectRecombinedGraphWithUncoveredMaterializedType() {
        // given
        Node coveredType = new Node().name("Covered completed type");
        Node uncoveredType = new Node().name("Uncovered completed type");
        Node resolvedGraph = new Node()
                .properties("covered", new Node().type(coveredType))
                .properties("uncovered", new Node().type(uncoveredType));
        CanonicalTypeIdentityLookup partialEvidence = partialLookup(
                coveredType,
                DirectBlueIdCalculator.calculateBlueId(
                        new Node().name("Covered authored type")));

        // when
        Executable union = () -> CanonicalTypeIdentityEvidenceUnion
                .establishForResolvedGraph(
                        resolvedGraph,
                        Arrays.asList(partialEvidence));

        // then
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                union);
        assertTrue(failure.getMessage().contains(
                "No resolver-issued canonical type identity evidence"));
    }

    @Test
    void shouldRejectConflictingEvidenceForOneCompletedType() {
        // given
        Node completedType = new Node()
                .name("Completed executable-body type");
        Node handler = new Node().type(completedType);
        CanonicalTypeIdentityLookup first = completeLookup(
                DirectBlueIdCalculator.calculateBlueId(
                        new Node().name("First source type")));
        CanonicalTypeIdentityLookup second = completeLookup(
                DirectBlueIdCalculator.calculateBlueId(
                        new Node().name("Second source type")));

        // when
        Executable union = () -> CanonicalTypeIdentityEvidenceUnion
                .establishForResolvedGraph(
                        handler,
                        Arrays.asList(first, second));

        // then
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                union);

        assertTrue(failure.getMessage().contains(
                "Conflicting canonical type identities"));
    }

    private static CanonicalTypeIdentityLookup completeLookup(
            String blueId) {
        return new CanonicalTypeIdentityLookup() {
            @Override
            public boolean hasCompleteCoverage() {
                return true;
            }

            @Override
            public Optional<String> findCanonicalTypeBlueId(
                    Node completedType) {
                return Optional.of(blueId);
            }

            @Override
            public Optional<CanonicalTypeIdentityEvidence>
            findCanonicalTypeIdentityEvidence(Node completedType) {
                return Optional.of(CanonicalTypeIdentityEvidence
                        .identityOnly(blueId));
            }

            @Override
            public String requireCanonicalTypeBlueId(
                    Node completedType) {
                return blueId;
            }
        };
    }

    private static CanonicalTypeIdentityLookup partialLookup(
            Node completedType,
            String blueId) {
        Map<Node, String> identities = new IdentityHashMap<>();
        identities.put(completedType, blueId);
        return new CanonicalTypeIdentityLookup() {
            @Override
            public boolean hasCompleteCoverage() {
                return false;
            }

            @Override
            public Optional<String> findCanonicalTypeBlueId(Node candidate) {
                return Optional.ofNullable(identities.get(candidate));
            }

            @Override
            public Optional<CanonicalTypeIdentityEvidence>
            findCanonicalTypeIdentityEvidence(Node candidate) {
                return findCanonicalTypeBlueId(candidate)
                        .map(CanonicalTypeIdentityEvidence::identityOnly);
            }

            @Override
            public String requireCanonicalTypeBlueId(Node candidate) {
                return findCanonicalTypeBlueId(candidate)
                        .orElseThrow(() -> new IllegalStateException(
                                "No resolver-issued canonical type identity "
                                        + "evidence covers the recombined graph"));
            }
        };
    }
}
