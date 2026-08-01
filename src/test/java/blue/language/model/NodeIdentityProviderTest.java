package blue.language.model;

import blue.language.model.wire.BlueLanguageConstants;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.identity.NodeToBlueIdInput;
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
                .properties("value", new Node().value("stable"));
        String expected = DirectBlueIdCalculator.INSTANCE.directBlueIdFromCanonicalInput(
                NodeToBlueIdInput.getWithResolvedBlueIdMetadata(node));

        // when
        String actual = node.getAsText("/blueId");

        // then
        assertEquals(expected, actual);
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
