package blue.language;

import blue.language.model.Node;
import blue.language.provider.BasicNodeProvider;
import blue.language.utils.BlueIdCalculator;
import org.junit.jupiter.api.Test;

import static blue.language.utils.UncheckedObjectMapper.YAML_MAPPER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SemanticCanonicalizationTest {

    @Test
    void canonicalizeRemovesRedundantInheritedOverridesBeforeHashing() {
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs(
                "name: Product Type\n" +
                "x: 1\n" +
                "label: inherited");
        String productTypeBlueId = nodeProvider.getBlueIdByName("Product Type");

        Blue blue = new Blue(nodeProvider);
        Node noisy = YAML_MAPPER.readValue(
                "name: Product Instance\n" +
                "type:\n" +
                "  blueId: " + productTypeBlueId + "\n" +
                "x: 1\n" +
                "label: inherited\n" +
                "y: 2", Node.class);
        Node minimal = YAML_MAPPER.readValue(
                "name: Product Instance\n" +
                "type:\n" +
                "  blueId: " + productTypeBlueId + "\n" +
                "y: 2", Node.class);

        Node canonical = blue.canonicalize(noisy);

        assertEquals(productTypeBlueId, canonical.getType().getBlueId());
        assertFalse(canonical.getProperties().containsKey("x"));
        assertFalse(canonical.getProperties().containsKey("label"));
        assertEquals(blue.calculateSemanticBlueId(minimal), blue.calculateSemanticBlueId(noisy));
        assertEquals(BlueIdCalculator.calculateBlueId(canonical), blue.calculateSemanticBlueId(noisy));
    }
}
