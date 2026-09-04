package blue.language.model;

import blue.language.model.wire.BlueLanguageConstants;

import blue.language.identity.DirectBlueIdCalculator;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static blue.language.model.wire.BlueLanguageConstants.TEXT_TYPE_BLUE_ID;

class NodeIdentityProviderTest {

    @Test
    void shouldPreserveDerivedBlueIdPathSemanticsThroughModelSpi() {
        // given
        Node node = new Node()
                .name("Identity subject")
                .properties("field", new Node().value("stable"));
        String expected = DirectBlueIdCalculator.calculateBlueId(node);

        // when
        String actual = node.getAsText("/blueId");

        // then
        assertEquals(expected, actual);
    }

    @Test
    void shouldDeriveCanonicalIdentityFromExpandedTypeMetadata() {
        // given
        Node exactType = new Node()
                .name("Expanded identity type")
                .properties("inherited", new Node().value("fixed"));
        String typeBlueId = DirectBlueIdCalculator.calculateBlueId(exactType);
        Node expanded = new Node()
                .type(exactType.clone().blueId(typeBlueId))
                .properties("own", new Node().value("kept"));
        Node canonical = new Node()
                .type(new Node().blueId(typeBlueId))
                .properties("own", new Node().value("kept"));

        // when
        String actual = expanded.getAsText("/blueId");

        // then
        assertEquals(DirectBlueIdCalculator.calculateBlueId(canonical), actual);
    }

    @Test
    void shouldCanonicalizeNestedResolvedTypePositionsThroughModelSpi() {
        // given
        Node baseType = new Node().name("Resolved base type");
        Node itemType = new Node().name("Resolved item type");
        Node keyType = new Node().name("Resolved key type");
        Node valueType = new Node().name("Resolved value type");
        String baseTypeBlueId = DirectBlueIdCalculator.calculateBlueId(baseType);
        String itemTypeBlueId = DirectBlueIdCalculator.calculateBlueId(itemType);
        String keyTypeBlueId = DirectBlueIdCalculator.calculateBlueId(keyType);
        String valueTypeBlueId = DirectBlueIdCalculator.calculateBlueId(valueType);

        Node canonicalOuterType = new Node()
                .name("Resolved composite type")
                .type(new Node().blueId(baseTypeBlueId))
                .itemType(new Node().blueId(itemTypeBlueId))
                .keyType(new Node().blueId(keyTypeBlueId))
                .valueType(new Node().blueId(valueTypeBlueId));
        String outerTypeBlueId = DirectBlueIdCalculator.calculateBlueId(
                canonicalOuterType);
        Node expandedOuterType = new Node()
                .name("Resolved composite type")
                .type(baseType.clone().blueId(baseTypeBlueId))
                .itemType(itemType.clone().blueId(itemTypeBlueId))
                .keyType(keyType.clone().blueId(keyTypeBlueId))
                .valueType(valueType.clone().blueId(valueTypeBlueId))
                .blueId(outerTypeBlueId);
        Node expanded = new Node()
                .type(expandedOuterType)
                .properties("own", new Node().value("kept"));
        Node canonical = new Node()
                .type(new Node().blueId(outerTypeBlueId))
                .properties("own", new Node().value("kept"));

        // when
        String actual = expanded.getAsText("/blueId");

        // then
        assertEquals(DirectBlueIdCalculator.calculateBlueId(canonical), actual);
    }

    @Test
    void shouldReturnExplicitReferenceBlueIdThroughSameSpi() {
        // given
        Node reference = new Node().blueId(TEXT_TYPE_BLUE_ID);

        // when
        String actual = reference.getAsText("/blueId");

        // then
        assertEquals(reference.getBlueId(), actual);
    }

    @Test
    void shouldPreserveOrderedListIdentityThroughModelSpi() {
        // given
        java.util.List<Node> nodes = Arrays.asList(
                new Node().value("first"),
                new Node().value("second"));
        String expected = DirectBlueIdCalculator.calculateBlueId(nodes);

        // when
        String actual = NodeIdentities.calculate(nodes);

        // then
        assertEquals(expected, actual);
    }

    @Test
    void shouldPreserveExpandedElementIdentityThroughListSpi() {
        // given
        Node exactType = new Node()
                .name("Expanded list element type")
                .properties("inherited", new Node().value("fixed"));
        String typeBlueId = DirectBlueIdCalculator.calculateBlueId(exactType);
        java.util.List<Node> expanded = Arrays.asList(
                new Node()
                        .type(exactType.clone().blueId(typeBlueId))
                        .properties("own", new Node().value("kept")));
        java.util.List<Node> canonical = Arrays.asList(
                new Node()
                        .type(new Node().blueId(typeBlueId))
                        .properties("own", new Node().value("kept")));

        // when
        String actual = NodeIdentities.calculate(expanded);

        // then
        assertEquals(DirectBlueIdCalculator.calculateBlueId(canonical), actual);
    }

    @Test
    void shouldKeepSingleNodeProvidersSourceCompatibleForListIdentity() {
        // given
        java.util.List<Node> nodes = Arrays.asList(
                new Node().value("first"),
                new Node().value("second"));
        NodeIdentityProvider singleNodeProvider =
                DirectBlueIdCalculator::calculateBlueId;
        String expected = DirectBlueIdCalculator.calculateBlueId(nodes);

        // when
        String actual = singleNodeProvider.calculate(nodes);

        // then
        assertEquals(expected, actual);
    }
}
