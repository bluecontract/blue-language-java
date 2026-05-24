package blue.language;

import blue.language.model.Node;
import blue.language.provider.BasicNodeProvider;
import blue.language.utils.BlueIdCalculator;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Collections;

import static blue.language.utils.Properties.INTEGER_TYPE_BLUE_ID;
import static blue.language.utils.Properties.TEXT_TYPE_BLUE_ID;
import static blue.language.utils.UncheckedObjectMapper.YAML_MAPPER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SemanticCanonicalizationTest {

    @Test
    void sourceTypeIntegerValueOneSemanticBlueIdWorks() {
        Blue blue = new Blue();
        Node source = YAML_MAPPER.readValue("type: Integer\nvalue: 1", Node.class);

        assertEquals(
                blue.calculateSemanticBlueId(YAML_MAPPER.readValue(
                        "type:\n" +
                        "  blueId: " + INTEGER_TYPE_BLUE_ID + "\n" +
                        "value: 1", Node.class)),
                blue.calculateSemanticBlueId(source));
    }

    @Test
    void sourceTypeIntegerCanonicalizesToIntegerBlueId() {
        Node canonical = new Blue().canonicalize(YAML_MAPPER.readValue("type: Integer\nvalue: 1", Node.class));

        assertEquals(INTEGER_TYPE_BLUE_ID, canonical.getType().getBlueId());
        assertEquals(BigInteger.ONE, canonical.getValue());
    }

    @Test
    void directBlueIdTypeIntegerRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> BlueIdCalculator.calculateBlueId(YAML_MAPPER.readValue("type: Integer\nvalue: 1", Node.class)));
    }

    @Test
    void directBlueIdCanonicalIntegerBlueIdAccepted() {
        Node canonical = YAML_MAPPER.readValue(
                "type:\n" +
                "  blueId: " + INTEGER_TYPE_BLUE_ID + "\n" +
                "value: 1", Node.class);

        assertEquals(BlueIdCalculator.calculateBlueId(canonical), new Blue().calculateBlueId(canonical));
    }

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

    @Test
    void calculateSemanticBlueIdResolvesTypes() {
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs(
                "name: Product Type\n" +
                "inherited: value");
        String productTypeBlueId = nodeProvider.getBlueIdByName("Product Type");

        Blue blue = new Blue(nodeProvider);
        Node source = YAML_MAPPER.readValue(
                "type:\n" +
                "  blueId: " + productTypeBlueId + "\n" +
                "inherited: value\n" +
                "own: value", Node.class);

        Node canonical = blue.canonicalize(source);

        assertFalse(canonical.getProperties().containsKey("inherited"));
        assertEquals(BlueIdCalculator.calculateBlueId(canonical), blue.calculateSemanticBlueId(source));
    }

    @Test
    void calculateSemanticBlueIdPreprocessesRootBlue() {
        Blue blue = new Blue();
        Node aliased = YAML_MAPPER.readValue(
                "blue:\n" +
                "  imports:\n" +
                "    Person:\n" +
                "      blueId: " + TEXT_TYPE_BLUE_ID + "\n" +
                "type: Person\n" +
                "value: hello", Node.class);
        Node direct = YAML_MAPPER.readValue(
                "type:\n" +
                "  blueId: " + TEXT_TYPE_BLUE_ID + "\n" +
                "value: hello", Node.class);

        Node canonical = blue.canonicalize(aliased);

        assertNull(canonical.getBlue());
        assertEquals(blue.calculateSemanticBlueId(direct), blue.calculateSemanticBlueId(aliased));
    }

    @Test
    void calculateSemanticBlueIdRejectsInvalidProviderContent() {
        String requestedBlueId = BlueIdCalculator.calculateBlueId(new Node().value("expected"));
        Blue blue = new Blue(blueId -> Collections.singletonList(new Node().value("actual")));
        Node source = new Node().type(new Node().blueId(requestedBlueId)).value("x");

        assertThrows(IllegalArgumentException.class, () -> blue.calculateSemanticBlueId(source));
    }

    @Test
    void calculateSemanticBlueIdRejectsUnresolvableProviderReferences() {
        String missingBlueId = BlueIdCalculator.calculateBlueId(new Node().value("missing"));
        Blue blue = new Blue(blueId -> null);
        Node source = new Node().type(new Node().blueId(missingBlueId)).value("x");

        assertThrows(IllegalArgumentException.class, () -> blue.calculateSemanticBlueId(source));
    }

    @Test
    void calculateSemanticBlueIdCanonicalOverlayContainsNoPreviousOrPos() {
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs(
                "name: Append Type\n" +
                "type: List\n" +
                "mergePolicy: append-only\n" +
                "items:\n" +
                "  - value: A");
        String typeBlueId = nodeProvider.getBlueIdByName("Append Type");
        String previousBlueId = BlueIdCalculator.calculateBlueId(YAML_MAPPER.readValue(
                "items:\n" +
                "  - value: A", Node.class).getItems());
        Blue blue = new Blue(nodeProvider);
        Node source = YAML_MAPPER.readValue(
                "type:\n" +
                "  blueId: " + typeBlueId + "\n" +
                "items:\n" +
                "  - $previous:\n" +
                "      blueId: " + previousBlueId + "\n" +
                "  - value: B", Node.class);

        Node canonical = blue.canonicalize(source);

        assertNoPreviousOrPos(canonical);
        assertEquals(BlueIdCalculator.calculateBlueId(canonical), blue.calculateSemanticBlueId(source));
    }

    @Test
    void contractsCanonicalizesAsReservedField() {
        Blue blue = new Blue();
        Node source = YAML_MAPPER.readValue(
                "value: x\n" +
                "contracts:\n" +
                "  audit:\n" +
                "    enabled: true", Node.class);

        Node canonical = blue.canonicalize(source);

        assertEquals("x", canonical.getValue());
        assertEquals(Boolean.TRUE, canonical.get("/contracts/audit/enabled/value"));
        assertFalse(canonical.getProperties() != null && canonical.getProperties().containsKey("contracts"));
        assertEquals(BlueIdCalculator.calculateBlueId(canonical), blue.calculateSemanticBlueId(source));
    }

    private void assertNoPreviousOrPos(Node node) {
        if (node == null) {
            return;
        }
        assertNull(node.getPreviousBlueId());
        assertNull(node.getPosition());
        assertNull(node.getBlue());
        assertNoPreviousOrPos(node.getType());
        assertNoPreviousOrPos(node.getItemType());
        assertNoPreviousOrPos(node.getKeyType());
        assertNoPreviousOrPos(node.getValueType());
        assertNoPreviousOrPos(node.getContracts());
        if (node.getItems() != null) {
            node.getItems().forEach(this::assertNoPreviousOrPos);
        }
        if (node.getProperties() != null) {
            node.getProperties().values().forEach(this::assertNoPreviousOrPos);
        }
    }
}
