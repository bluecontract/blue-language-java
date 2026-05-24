package blue.language.processor;

import blue.language.Blue;
import blue.language.conformance.ConformanceEngine;
import blue.language.conformance.ConformanceEngineTest;
import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import blue.language.provider.BasicNodeProvider;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.NodeToBlueIdInput;
import blue.language.utils.Properties;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static blue.language.utils.UncheckedObjectMapper.YAML_MAPPER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void batchPatchGeneralizesChangedNodeAndAncestorOnce() {
        BasicNodeProvider nodeProvider = ConformanceEngineTest.priceProvider();
        Blue blue = new Blue(nodeProvider);
        Node document = blue.resolve(YAML_MAPPER.readValue(
                "name: Shoes\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("European Product") + "\n" +
                "price:\n" +
                "  amount: 150\n" +
                "  currency: EUR\n" +
                "stock: 5", Node.class));
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document, blue.conformanceEngine());

        List<DocumentProcessingRuntime.DocumentUpdateData> updates = runtime.applyPatches("/", Arrays.asList(
                JsonPatch.replace("/price/currency", new Node().value("USD")),
                JsonPatch.replace("/stock", new Node().value(6))
        ));

        assertEquals(2, updates.size());
        assertEquals("USD", document.getAsText("/price/currency"));
        assertEquals(6, document.getAsInteger("/stock"));
        assertEquals("Price", document.getAsNode("/price/type").getName());
        assertEquals("Global Product", document.getType().getName());
    }

    @Test
    void nonGeneralizableBatchRollsBackAllPatches() {
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs(
                "name: Fixed One\n" +
                "x: 1");
        Blue blue = new Blue(nodeProvider);
        Node document = blue.resolve(YAML_MAPPER.readValue(
                "name: Instance\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Fixed One") + "\n" +
                "x: 1\n" +
                "y: old", Node.class));
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document, blue.conformanceEngine());

        assertThrows(IllegalArgumentException.class, () -> runtime.applyPatches("/", Arrays.asList(
                JsonPatch.replace("/y", new Node().value("new")),
                JsonPatch.replace("/x", new Node().value(2))
        )));

        assertEquals(1, document.getAsInteger("/x"));
        assertEquals("old", document.getAsText("/y"));
        assertEquals("Fixed One", document.getType().getName());
    }

    @Test
    void processorManagedInitializedMarkerBypassWorksInBatch() {
        Blue blue = new Blue();
        Node document = new Node();
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document, blue.conformanceEngine());

        runtime.applyPatches("/", Arrays.asList(
                JsonPatch.add("/contracts/initialized",
                        new Node().type(new Node().blueId("InitializationMarker"))),
                JsonPatch.add("/status", new Node().value("active"))
        ));

        assertNotNull(document.getAsNode("/contracts/initialized"));
        assertEquals("active", document.getAsText("/status"));
    }

    @Test
    void batchParentThenChildPatchGeneralizesAndPreservesChildValue() {
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

        runtime.applyPatches("/", Arrays.asList(
                JsonPatch.replace("/price", YAML_MAPPER.readValue(
                        "amount: 175\n" +
                        "currency: EUR", Node.class)),
                JsonPatch.replace("/price/currency", new Node().value("USD"))
        ));

        assertEquals(175, document.getAsInteger("/price/amount"));
        assertEquals("USD", document.getAsText("/price/currency"));
        assertEquals("Price", document.getAsNode("/price/type").getName());
        assertEquals("Global Product", document.getType().getName());
    }

    @Test
    void batchChildThenSiblingPatchGeneralizesOnceAndPreservesBothChanges() {
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

        runtime.applyPatches("/", Arrays.asList(
                JsonPatch.replace("/price/currency", new Node().value("USD")),
                JsonPatch.replace("/price/amount", new Node().value(200))
        ));

        assertEquals(200, document.getAsInteger("/price/amount"));
        assertEquals("USD", document.getAsText("/price/currency"));
        assertEquals("Price", document.getAsNode("/price/type").getName());
        assertEquals("Global Product", document.getType().getName());
    }

    @Test
    void batchSiblingPatchesRequiringAncestorGeneralizationPreserveBothChanges() {
        BasicNodeProvider nodeProvider = productWithAvailabilityProvider();
        Blue blue = new Blue(nodeProvider);
        Node document = blue.resolve(YAML_MAPPER.readValue(
                "name: Shoes\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("European Listed Product") + "\n" +
                "price:\n" +
                "  amount: 150\n" +
                "  currency: EUR\n" +
                "availability:\n" +
                "  region: EU", Node.class));
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document, blue.conformanceEngine());

        runtime.applyPatches("/", Arrays.asList(
                JsonPatch.replace("/price/currency", new Node().value("USD")),
                JsonPatch.replace("/availability/region", new Node().value("US"))
        ));

        assertEquals("USD", document.getAsText("/price/currency"));
        assertEquals("US", document.getAsText("/availability/region"));
        assertEquals("Price", document.getAsNode("/price/type").getName());
        assertEquals("Availability", document.getAsNode("/availability/type").getName());
        assertEquals("Global Listed Product", document.getType().getName());
    }

    @Test
    void batchDictionaryValueTypePatchesPreserveValuesAndDictionaryType() {
        BasicNodeProvider nodeProvider = orderBookProvider();
        Blue blue = new Blue(nodeProvider);
        Node document = blue.resolve(YAML_MAPPER.readValue(
                "name: Book\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Open Order Book") + "\n" +
                "orders:\n" +
                "  order-a:\n" +
                "    status: open\n" +
                "  order-b:\n" +
                "    status: open", Node.class));
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(document, blue.conformanceEngine());

        runtime.applyPatches("/", Arrays.asList(
                JsonPatch.replace("/orders/order-a/status", new Node().value("closed")),
                JsonPatch.replace("/orders/order-b/status", new Node().value("closed"))
        ));

        assertEquals("closed", document.getAsText("/orders/order-a/status"));
        assertEquals("closed", document.getAsText("/orders/order-b/status"));
        assertEquals("Order", document.getAsNode("/orders/valueType").getName());
    }

    @Test
    void batchListItemTypePatchesMatchSequentialBehavior() {
        BasicNodeProvider nodeProvider = itemListProvider();
        Blue blue = new Blue(nodeProvider);
        Node batchDocument = blue.resolve(YAML_MAPPER.readValue(
                "name: Batch List\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Open Item List") + "\n" +
                "entries:\n" +
                "  - status: open\n" +
                "  - status: open", Node.class));
        Node sequentialDocument = batchDocument.clone();
        List<JsonPatch> patches = Arrays.asList(
                JsonPatch.replace("/entries/0/status", new Node().value("closed")),
                JsonPatch.add("/entries/-", YAML_MAPPER.readValue("status: closed", Node.class)),
                JsonPatch.remove("/entries/1")
        );

        new DocumentProcessingRuntime(batchDocument, blue.conformanceEngine()).applyPatches("/", patches);
        DocumentProcessingRuntime sequential = new DocumentProcessingRuntime(sequentialDocument, blue.conformanceEngine());
        for (JsonPatch patch : patches) {
            sequential.applyPatch("/", patch);
        }

        assertEquals(sequentialDocument.getAsText("/entries/0/status"), batchDocument.getAsText("/entries/0/status"));
        assertEquals(sequentialDocument.getAsText("/entries/1/status"), batchDocument.getAsText("/entries/1/status"));
        assertEquals(sequentialDocument.getAsNode("/entries/itemType").getName(),
                batchDocument.getAsNode("/entries/itemType").getName());
    }

    @Test
    void batchGeneralizesTypedChildUnderUntypedRoot() {
        BasicNodeProvider nodeProvider = ConformanceEngineTest.priceProvider();
        Blue blue = new Blue(nodeProvider);
        Node batchDocument = blue.resolve(YAML_MAPPER.readValue(
                "name: Untyped Container\n" +
                "child:\n" +
                "  type:\n" +
                "    blueId: " + nodeProvider.getBlueIdByName("Price in EUR") + "\n" +
                "  amount: 100\n" +
                "  currency: EUR", Node.class));
        Node sequentialDocument = batchDocument.clone();
        List<JsonPatch> patches = Arrays.asList(
                JsonPatch.replace("/child/currency", new Node().value("USD"))
        );

        new DocumentProcessingRuntime(batchDocument, blue.conformanceEngine()).applyPatches("/", patches);
        applySequential(sequentialDocument, blue.conformanceEngine(), patches);

        assertEquivalentDocuments(sequentialDocument, batchDocument, "typed child under untyped root");
        assertEquals("USD", batchDocument.getAsText("/child/currency"));
        assertEquals("Price", batchDocument.getAsNode("/child/type").getName());
    }

    @Test
    void batchGeneralizesDictionaryValueTypeUnderUntypedRoot() {
        BasicNodeProvider nodeProvider = orderBookProvider();
        Blue blue = new Blue(nodeProvider);
        Node batchDocument = blue.resolve(YAML_MAPPER.readValue(
                "name: Untyped Book\n" +
                "orders:\n" +
                "  type:\n" +
                "    blueId: " + Properties.DICTIONARY_TYPE_BLUE_ID + "\n" +
                "  keyType:\n" +
                "    blueId: " + Properties.TEXT_TYPE_BLUE_ID + "\n" +
                "  valueType:\n" +
                "    blueId: " + nodeProvider.getBlueIdByName("Open Order") + "\n" +
                "  order-a:\n" +
                "    type:\n" +
                "      blueId: " + nodeProvider.getBlueIdByName("Open Order") + "\n" +
                "    status: open\n" +
                "  order-b:\n" +
                "    type:\n" +
                "      blueId: " + nodeProvider.getBlueIdByName("Open Order") + "\n" +
                "    status: open", Node.class));
        Node sequentialDocument = batchDocument.clone();
        List<JsonPatch> patches = Arrays.asList(
                JsonPatch.replace("/orders/order-a/status", new Node().value("closed"))
        );

        new DocumentProcessingRuntime(batchDocument, blue.conformanceEngine()).applyPatches("/", patches);
        applySequential(sequentialDocument, blue.conformanceEngine(), patches);

        assertEquivalentDocuments(sequentialDocument, batchDocument, "dictionary valueType under untyped root");
        assertEquals("closed", batchDocument.getAsText("/orders/order-a/status"));
        assertEquals("Order", batchDocument.getAsNode("/orders/valueType").getName());
    }

    @Test
    void batchGeneralizesListItemTypeUnderUntypedRoot() {
        BasicNodeProvider nodeProvider = itemListProvider();
        Blue blue = new Blue(nodeProvider);
        Node batchDocument = blue.resolve(YAML_MAPPER.readValue(
                "name: Untyped List\n" +
                "entries:\n" +
                "  type:\n" +
                "    blueId: " + Properties.LIST_TYPE_BLUE_ID + "\n" +
                "  itemType:\n" +
                "    blueId: " + nodeProvider.getBlueIdByName("Open Item") + "\n" +
                "  items:\n" +
                "    - type:\n" +
                "        blueId: " + nodeProvider.getBlueIdByName("Open Item") + "\n" +
                "      status: open\n" +
                "    - type:\n" +
                "        blueId: " + nodeProvider.getBlueIdByName("Open Item") + "\n" +
                "      status: open", Node.class));
        Node sequentialDocument = batchDocument.clone();
        List<JsonPatch> patches = Arrays.asList(
                JsonPatch.replace("/entries/0/status", new Node().value("closed"))
        );

        new DocumentProcessingRuntime(batchDocument, blue.conformanceEngine()).applyPatches("/", patches);
        applySequential(sequentialDocument, blue.conformanceEngine(), patches);

        assertEquivalentDocuments(sequentialDocument, batchDocument, "list itemType under untyped root");
        assertEquals("closed", batchDocument.getAsText("/entries/0/status"));
        assertEquals("Item", batchDocument.getAsNode("/entries/itemType").getName());
    }

    @Test
    void conformanceAffectedUpdateAfterReflectsCommittedResolvedValue() {
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

        List<DocumentProcessingRuntime.DocumentUpdateData> updates = runtime.applyPatches("/", Arrays.asList(
                JsonPatch.replace("/price", YAML_MAPPER.readValue(
                        "amount: 150\n" +
                        "currency: USD", Node.class))
        ));

        assertEquals(1, updates.size());
        assertEquals("USD", updates.get(0).after().getAsText("/currency"));
        assertEquals("Price", updates.get(0).after().getType().getName());
        assertEquals("Price", document.getAsNode("/price/type").getName());
        assertEquals("Global Product", document.getType().getName());
    }

    @Test
    void batchAndSequentialRuntimeProduceEquivalentDocumentsAcrossPatchLists() {
        assertBatchMatchesSequential(new Node().properties("a", new Node().value("one"),
                        "b", new Node().value("two")),
                null,
                Arrays.asList(
                        JsonPatch.replace("/a", new Node().value("three")),
                        JsonPatch.replace("/b", new Node().value("four"))),
                "multiple object replacements");

        assertBatchMatchesSequential(new Node().properties("status", new Node().value("idle")),
                null,
                Arrays.asList(
                        JsonPatch.replace("/status", new Node().value("first")),
                        JsonPatch.replace("/status", new Node().value("second"))),
                "duplicate paths");

        assertBatchMatchesSequential(new Node(),
                null,
                Arrays.asList(
                        JsonPatch.add("/temp", new Node().value("value")),
                        JsonPatch.remove("/temp")),
                "add then remove same path");

        assertBatchMatchesSequential(new Node().properties("temp", new Node().value("old")),
                null,
                Arrays.asList(
                        JsonPatch.remove("/temp"),
                        JsonPatch.add("/temp", new Node().value("new"))),
                "remove then add same path");

        assertBatchMatchesSequential(listDocument(),
                null,
                Arrays.asList(
                        JsonPatch.add("/values/1", new Node().value(99)),
                        JsonPatch.replace("/values/2", new Node().value(100)),
                        JsonPatch.remove("/values/0")),
                "list add replace remove");

        BasicNodeProvider priceProvider = ConformanceEngineTest.priceProvider();
        Blue priceBlue = new Blue(priceProvider);
        assertBatchMatchesSequential(priceBlue.resolve(YAML_MAPPER.readValue(
                        "name: Untyped Container\n" +
                        "child:\n" +
                        "  type:\n" +
                        "    blueId: " + priceProvider.getBlueIdByName("Price in EUR") + "\n" +
                        "  amount: 100\n" +
                        "  currency: EUR", Node.class)),
                priceBlue.conformanceEngine(),
                Arrays.asList(JsonPatch.replace("/child/currency", new Node().value("USD"))),
                "typed child generalization");

        BasicNodeProvider orderProvider = orderBookProvider();
        Blue orderBlue = new Blue(orderProvider);
        assertBatchMatchesSequential(orderBlue.resolve(YAML_MAPPER.readValue(
                        "name: Untyped Book\n" +
                        "orders:\n" +
                        "  type:\n" +
                        "    blueId: " + Properties.DICTIONARY_TYPE_BLUE_ID + "\n" +
                        "  keyType:\n" +
                        "    blueId: " + Properties.TEXT_TYPE_BLUE_ID + "\n" +
                        "  valueType:\n" +
                        "    blueId: " + orderProvider.getBlueIdByName("Open Order") + "\n" +
                        "  order-a:\n" +
                        "    type:\n" +
                        "      blueId: " + orderProvider.getBlueIdByName("Open Order") + "\n" +
                        "    status: open", Node.class)),
                orderBlue.conformanceEngine(),
                Arrays.asList(JsonPatch.replace("/orders/order-a/status", new Node().value("closed"))),
                "dictionary valueType update");

        BasicNodeProvider itemProvider = itemListProvider();
        Blue itemBlue = new Blue(itemProvider);
        assertBatchMatchesSequential(itemBlue.resolve(YAML_MAPPER.readValue(
                        "name: Untyped List\n" +
                        "entries:\n" +
                        "  type:\n" +
                        "    blueId: " + Properties.LIST_TYPE_BLUE_ID + "\n" +
                        "  itemType:\n" +
                        "    blueId: " + itemProvider.getBlueIdByName("Open Item") + "\n" +
                        "  items:\n" +
                        "    - type:\n" +
                        "        blueId: " + itemProvider.getBlueIdByName("Open Item") + "\n" +
                        "      status: open", Node.class)),
                itemBlue.conformanceEngine(),
                Arrays.asList(JsonPatch.replace("/entries/0/status", new Node().value("closed"))),
                "list itemType update");
    }

    private BasicNodeProvider productWithAvailabilityProvider() {
        BasicNodeProvider nodeProvider = ConformanceEngineTest.priceProvider();
        nodeProvider.addSingleDocs(
                "name: Availability\n" +
                "region:\n" +
                "  type: Text");
        nodeProvider.addSingleDocs(
                "name: EU Availability\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Availability") + "\n" +
                "region: EU");
        nodeProvider.addSingleDocs(
                "name: Global Listed Product\n" +
                "price:\n" +
                "  type:\n" +
                "    blueId: " + nodeProvider.getBlueIdByName("Price") + "\n" +
                "availability:\n" +
                "  type:\n" +
                "    blueId: " + nodeProvider.getBlueIdByName("Availability"));
        nodeProvider.addSingleDocs(
                "name: European Listed Product\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Global Listed Product") + "\n" +
                "price:\n" +
                "  type:\n" +
                "    blueId: " + nodeProvider.getBlueIdByName("Price in EUR") + "\n" +
                "availability:\n" +
                "  type:\n" +
                "    blueId: " + nodeProvider.getBlueIdByName("EU Availability"));
        return nodeProvider;
    }

    private void assertBatchMatchesSequential(Node initial,
                                              ConformanceEngine conformanceEngine,
                                              List<JsonPatch> patches,
                                              String label) {
        Node batchDocument = initial.clone();
        Node sequentialDocument = initial.clone();
        List<DocumentProcessingRuntime.DocumentUpdateData> batchUpdates =
                new DocumentProcessingRuntime(batchDocument, conformanceEngine).applyPatches("/", patches);
        List<DocumentProcessingRuntime.DocumentUpdateData> sequentialUpdates =
                applySequential(sequentialDocument, conformanceEngine, patches);

        assertEquivalentDocuments(sequentialDocument, batchDocument, label);
        assertEquals(updatePaths(sequentialUpdates), updatePaths(batchUpdates), label + " update paths");
    }

    private List<DocumentProcessingRuntime.DocumentUpdateData> applySequential(Node document,
                                                                               ConformanceEngine conformanceEngine,
                                                                               List<JsonPatch> patches) {
        DocumentProcessingRuntime sequential = new DocumentProcessingRuntime(document, conformanceEngine);
        List<DocumentProcessingRuntime.DocumentUpdateData> updates = new ArrayList<>();
        for (JsonPatch patch : patches) {
            updates.add(sequential.applyPatch("/", patch));
        }
        return updates;
    }

    private void assertEquivalentDocuments(Node expected, Node actual, String label) {
        assertEquals(runtimeDocumentBlueId(expected),
                runtimeDocumentBlueId(actual),
                label);
    }

    private String runtimeDocumentBlueId(Node node) {
        return BlueIdCalculator.INSTANCE.calculate(NodeToBlueIdInput.getWithResolvedBlueIdMetadata(node));
    }

    private List<String> updatePaths(List<DocumentProcessingRuntime.DocumentUpdateData> updates) {
        List<String> paths = new ArrayList<>();
        for (DocumentProcessingRuntime.DocumentUpdateData update : updates) {
            assertNotNull(update);
            paths.add(update.path());
        }
        return paths;
    }

    private Node listDocument() {
        return new Node().properties("values", new Node().items(Arrays.asList(
                new Node().value(1),
                new Node().value(2),
                new Node().value(3))));
    }

    private BasicNodeProvider orderBookProvider() {
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs(
                "name: Order\n" +
                "status:\n" +
                "  type: Text");
        nodeProvider.addSingleDocs(
                "name: Open Order\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Order") + "\n" +
                "status: open");
        nodeProvider.addSingleDocs(
                "name: Open Order Book\n" +
                "orders:\n" +
                "  type: Dictionary\n" +
                "  keyType: Text\n" +
                "  valueType:\n" +
                "    blueId: " + nodeProvider.getBlueIdByName("Order"));
        return nodeProvider;
    }

    private BasicNodeProvider itemListProvider() {
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs(
                "name: Item\n" +
                "status:\n" +
                "  type: Text");
        nodeProvider.addSingleDocs(
                "name: Open Item\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Item") + "\n" +
                "status: open");
        nodeProvider.addSingleDocs(
                "name: Open Item List\n" +
                "entries:\n" +
                "  type: List\n" +
                "  itemType:\n" +
                "    blueId: " + nodeProvider.getBlueIdByName("Item"));
        return nodeProvider;
    }
}
