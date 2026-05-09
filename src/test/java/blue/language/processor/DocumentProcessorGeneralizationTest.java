package blue.language.processor;

import blue.language.Blue;
import blue.language.conformance.ConformanceEngineTest;
import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import blue.language.provider.BasicNodeProvider;
import org.junit.jupiter.api.Test;

import static blue.language.utils.UncheckedObjectMapper.YAML_MAPPER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DocumentProcessorGeneralizationTest {

    @Test
    void patchGeneralizesChangedNodeAndAncestorsBeforeCommit() {
        BasicNodeProvider nodeProvider = ConformanceEngineTest.priceProvider();
        Blue blue = new Blue(nodeProvider);
        Node document = blue.resolve(YAML_MAPPER.readValue(
                "name: Shoes\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("European Product") + "\n" +
                "price:\n" +
                "  amount: 150\n" +
                "  currency: EUR", Node.class));
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document, blue.conformanceEngine());

        DocumentProcessingRuntime.DocumentUpdateData update =
                runtime.applyPatch("/", JsonPatch.replace("/price/currency", new Node().value("USD")));

        assertEquals("USD", update.after().getValue());
        assertEquals("Price", document.getAsNode("/price/type").getName());
        assertEquals("Global Product", document.getType().getName());
    }

    @Test
    void nonGeneralizablePatchRollsBackDocument() {
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs(
                "name: Fixed One\n" +
                "x: 1");
        Blue blue = new Blue(nodeProvider);
        Node document = blue.resolve(YAML_MAPPER.readValue(
                "name: Instance\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Fixed One") + "\n" +
                "x: 1", Node.class));
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document, blue.conformanceEngine());

        assertThrows(IllegalArgumentException.class,
                () -> runtime.applyPatch("/", JsonPatch.replace("/x", new Node().value(2))));

        assertEquals("Fixed One", document.getType().getName());
        assertEquals(1, document.getAsInteger("/x"));
    }

    @Test
    void untypedRootPatchesAreNotConformanceEnforced() {
        Blue blue = new Blue();
        Node document = new Node();
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document, blue.conformanceEngine());

        runtime.applyPatch("/", JsonPatch.add("/contracts/initialized",
                new Node().type(new Node().blueId("InitializationMarker"))));

        assertNotNull(document.getAsNode("/contracts/initialized"));
        assertEquals("InitializationMarker", document.getAsNode("/contracts/initialized/type").getBlueId());
    }
}
