package blue.language.conformance;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.provider.BasicNodeProvider;
import org.junit.jupiter.api.Test;

import static blue.language.utils.UncheckedObjectMapper.YAML_MAPPER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ConformanceEngineTest {

    @Test
    void detectsFixedValueViolationAndGeneralizesToNearestConformingType() {
        BasicNodeProvider nodeProvider = priceProvider();
        Blue blue = new Blue(nodeProvider);
        Node document = blue.resolve(YAML_MAPPER.readValue(
                "name: Shoes\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("European Product") + "\n" +
                "price:\n" +
                "  amount: 150\n" +
                "  currency: EUR", Node.class));

        document.getProperties().get("price").getProperties().get("currency").value("USD");

        ConformanceEngine engine = blue.conformanceEngine();
        assertFalse(engine.conforms(document));

        boolean generalized = engine.generalizeChangedPath(document, "/price/currency");

        assertTrue(generalized);
        assertTrue(engine.conforms(document));
        assertEquals("Price", document.getAsNode("/price/type").getName());
        assertEquals("Global Product", document.getType().getName());
    }

    @Test
    void leavesAlreadyConformantDocumentUnchanged() {
        BasicNodeProvider nodeProvider = priceProvider();
        Blue blue = new Blue(nodeProvider);
        Node document = blue.resolve(YAML_MAPPER.readValue(
                "name: Shoes\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("European Product") + "\n" +
                "price:\n" +
                "  amount: 150\n" +
                "  currency: EUR", Node.class));

        boolean generalized = blue.conformanceEngine().generalizeChangedPath(document, "/price/amount");

        assertFalse(generalized);
        assertEquals("Price in EUR", document.getAsNode("/price/type").getName());
        assertEquals("European Product", document.getType().getName());
    }

    @Test
    void generalizesRootWhenRootFixedValueIsViolated() {
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs(
                "name: Product\n" +
                "status:\n" +
                "  type: Text");
        nodeProvider.addSingleDocs(
                "name: Draft Product\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Product") + "\n" +
                "status: draft");
        Blue blue = new Blue(nodeProvider);
        Node document = blue.resolve(YAML_MAPPER.readValue(
                "name: Release\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Draft Product") + "\n" +
                "status: draft", Node.class));

        document.getProperties().get("status").value("published");

        blue.conformanceEngine().generalizeChangedPath(document, "/status");

        assertTrue(blue.conformanceEngine().conforms(document));
        assertEquals("Product", document.getType().getName());
        assertEquals("published", document.getAsText("/status"));
    }

    @Test
    void generalizesRootWhenSchemaConstraintIsViolated() {
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs(
                "name: Any Score\n" +
                "type: Integer");
        nodeProvider.addSingleDocs(
                "name: Positive Score\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Any Score") + "\n" +
                "schema:\n" +
                "  minimum: 0");
        Blue blue = new Blue(nodeProvider);
        Node document = blue.resolve(YAML_MAPPER.readValue(
                "name: Score\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Positive Score") + "\n" +
                "value: 5", Node.class));

        document.value(-1);

        blue.conformanceEngine().generalizeChangedPath(document, "/value");

        assertTrue(blue.conformanceEngine().conforms(document));
        assertEquals("Any Score", document.getType().getName());
        assertEquals(-1, document.getAsInteger("/"));
    }

    public static BasicNodeProvider priceProvider() {
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs(
                "name: Price\n" +
                "amount:\n" +
                "  type: Integer\n" +
                "currency:\n" +
                "  type: Text");
        nodeProvider.addSingleDocs(
                "name: Price in EUR\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Price") + "\n" +
                "currency: EUR");
        nodeProvider.addSingleDocs(
                "name: Global Product\n" +
                "price:\n" +
                "  type:\n" +
                "    blueId: " + nodeProvider.getBlueIdByName("Price"));
        nodeProvider.addSingleDocs(
                "name: European Product\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Global Product") + "\n" +
                "price:\n" +
                "  type:\n" +
                "    blueId: " + nodeProvider.getBlueIdByName("Price in EUR"));
        return nodeProvider;
    }
}
