package blue.language.model;

import blue.language.utils.BlueIdCalculator;
import blue.language.utils.NodeToBlueIdInput;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static blue.language.model.wire.BlueLanguageConstants.TEXT_TYPE_BLUE_ID;

class NodeIdentityProviderTest {

    @Test
    void shouldPreserveDerivedBlueIdPathSemanticsThroughModelSpi() {
        // given
        Node node = new Node()
                .name("Identity subject")
                .properties("value", new Node().value("stable"));
        String expected = BlueIdCalculator.INSTANCE.calculate(
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
}
