package blue.language.matching;

import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.identity.CanonicalTypeIdentityEvidence;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrozenSchemaMatcherTest {

    private final FrozenSchemaMatcher matcher = new FrozenSchemaMatcher(
            CanonicalTypeIdentityLookup.incomplete());

    @Test
    void shouldCountUnicodeCodePointsForLengthConstraints() {
        // given
        FrozenNode candidate = FrozenNode.fromResolvedNode(
                new Node().value("\uD83D\uDE00"));
        Schema schema = new Schema().minLength(1).maxLength(1);

        // when
        boolean matched = matcher.matches(candidate, schema);

        // then
        assertTrue(matched);
    }

    @Test
    void shouldFailClosedForContradictoryNumericBounds() {
        // given
        FrozenNode candidate = FrozenNode.fromResolvedNode(
                new Node().value(new BigDecimal("5")));
        Schema schema = new Schema()
                .minimum(new BigDecimal("10"))
                .maximum(new BigDecimal("1"));

        // when
        boolean matched = matcher.matches(candidate, schema);

        // then
        assertFalse(matched);
    }

    @Test
    void shouldFailClosedWhenNumericKeywordTargetsWrongPayloadKind() {
        // given
        FrozenNode candidate = FrozenNode.fromResolvedNode(
                new Node().value("not-a-number"));
        Schema schema = new Schema().minimum(BigDecimal.ZERO);

        // when
        boolean matched = matcher.matches(candidate, schema);

        // then
        assertFalse(matched);
    }

    @Test
    void shouldMatchCompletedInlineAndReferencedEnumTypesWithResolverEvidence() {
        // given
        Node authoredType = new Node()
                .name("Enum value type")
                .properties("kind", new Node().value("marker"));
        String typeBlueId = DirectBlueIdCalculator.calculateBlueId(
                authoredType);
        Node completedType = authoredType.clone();
        CanonicalTypeIdentityLookup identities = lookup(
                completedType, typeBlueId);
        FrozenSchemaMatcher evidenceBoundMatcher =
                new FrozenSchemaMatcher(identities);
        FrozenNode candidate = FrozenNode.fromResolvedNode(
                new Node().value("same").type(completedType));
        Schema schema = new Schema().enumValues(Collections.singletonList(
                new Node()
                        .value("same")
                        .type(new Node().blueId(typeBlueId))));

        // when
        boolean matched = evidenceBoundMatcher.matches(candidate, schema);

        // then
        assertTrue(matched);
    }

    @Test
    void shouldFailClosedForCompletedInlineEnumTypeWithoutResolverEvidence() {
        // given
        Node completedType = new Node()
                .name("Unproven enum value type")
                .properties("kind", new Node().value("marker"));
        FrozenNode candidate = FrozenNode.fromResolvedNode(
                new Node().value("same").type(completedType));
        Schema schema = new Schema().enumValues(Collections.singletonList(
                new Node().value("same").type(completedType.clone())));

        // when
        boolean matched = matcher.matches(candidate, schema);

        // then
        assertFalse(matched);
    }

    @Test
    void shouldTreatInlineAndReferenceFormsAsSameUniqueItem() {
        // given
        Node itemType = new Node().name("Canonical item type");
        String itemTypeBlueId = DirectBlueIdCalculator.calculateBlueId(
                itemType);
        FrozenSchemaMatcher evidenceBoundMatcher =
                new FrozenSchemaMatcher(lookup(
                        itemType, itemTypeBlueId));
        FrozenNode candidate = FrozenNode.fromResolvedNode(
                new Node().items(
                        new Node()
                                .type(itemType.clone())
                                .value("same"),
                        new Node()
                                .type(new Node().blueId(itemTypeBlueId))
                                .value("same")));
        Schema schema = new Schema().uniqueItems(true);

        // when
        boolean matched = evidenceBoundMatcher.matches(candidate, schema);

        // then
        assertFalse(matched);
    }

    @Test
    void shouldPreservePureReferenceTypedEnumMatchingWithoutLookup() {
        // given
        String typeBlueId = DirectBlueIdCalculator.calculateBlueId(
                new Node().name("Referenced enum type"));
        FrozenNode candidate = FrozenNode.fromResolvedNode(
                new Node()
                        .value("same")
                        .type(new Node().blueId(typeBlueId)));
        Schema schema = new Schema().enumValues(Collections.singletonList(
                new Node()
                        .value("same")
                        .type(new Node().blueId(typeBlueId))));

        // when
        boolean matched = matcher.matches(candidate, schema);

        // then
        assertTrue(matched);
    }

    private static CanonicalTypeIdentityLookup lookup(
            Node completedType,
            String canonicalBlueId) {
        return new CanonicalTypeIdentityLookup() {
            @Override
            public boolean hasCompleteCoverage() {
                return true;
            }

            @Override
            public Optional<CanonicalTypeIdentityEvidence>
            findCanonicalTypeIdentityEvidence(Node candidate) {
                String blueId = requireCanonicalTypeBlueId(candidate);
                return Optional.of(candidate.isReferenceOnly()
                        ? CanonicalTypeIdentityEvidence.referenceSource(blueId)
                        : CanonicalTypeIdentityEvidence.identityOnly(blueId));
            }

            @Override
            public String requireCanonicalTypeBlueId(Node candidate) {
                if (candidate.isReferenceOnly()) {
                    return candidate.getBlueId();
                }
                if (!FrozenNode.fromResolvedNode(completedType)
                        .sameResolvedStructure(
                                FrozenNode.fromResolvedNode(candidate))) {
                    throw new IllegalStateException(
                            "Unexpected completed type");
                }
                return canonicalBlueId;
            }
        };
    }
}
