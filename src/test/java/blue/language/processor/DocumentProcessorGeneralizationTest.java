package blue.language.processor;

import blue.language.Blue;
import blue.language.conformance.ConformancePlan;
import blue.language.conformance.ConformanceEngineTest;
import blue.language.model.Node;
import blue.language.model.Nodes;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.processor.registry.BlueRuntimeTypeRegistry;
import blue.language.preprocess.provider.BasicNodeProvider;
import blue.language.registry.BootstrapProvider;
import blue.language.provider.SequentialNodeProvider;
import blue.language.snapshot.FrozenNode;
import blue.language.merge.ResolvedSnapshot;
import blue.language.model.wire.BlueLanguageConstants;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static blue.language.processor.FailureCapture.captureFailure;
import static blue.language.codec.jackson.UncheckedObjectMapper.YAML_MAPPER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentProcessorGeneralizationTest {

    @Test
    void shouldVerifyPatchGeneralizesChangedNodeAndAncestorsBeforeCommit() {
        // given
        BasicNodeProvider nodeProvider = ConformanceEngineTest.priceProvider();
        Blue blue = ProcessorTestSupport.blue(nodeProvider);
        Node document = canonicalRoot(blue, YAML_MAPPER.readValue(
                "name: Shoes\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("European Product") + "\n" +
                "price:\n" +
                "  amount: 150\n" +
                "  currency: EUR", Node.class));
        DocumentProcessingRuntime runtime = runtime(blue, document);

        // when
        DocumentUpdateData update =
                runtime.applyPatch("/", JsonPatch.replace("/price/currency", new Node().value("USD")));

        // then
        assertEquals("/price/currency", update.path(),
                "singleton API keeps returning the authored update");
        assertEquals("USD", update.after().getValue());
        assertEquals(nodeProvider.getBlueIdByName("Price"),
                document.getAsNode("/price/type").getBlueId());
        assertEquals(nodeProvider.getBlueIdByName("Global Product"),
                document.getType().getBlueId());
    }

    @Test
    void shouldVerifyNonGeneralizablePatchRollsBackDocument() {
        // given
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs(
                "name: Fixed One\n" +
                "x: 1");
        Blue blue = ProcessorTestSupport.blue(nodeProvider);
        Node document = canonicalRoot(blue, YAML_MAPPER.readValue(
                "name: Instance\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Fixed One") + "\n" +
                "x: 1", Node.class));
        DocumentProcessingRuntime runtime = runtime(blue, document);

        // when
        Throwable failure = captureFailure(
                () -> runtime.applyPatch(
                        "/",
                        JsonPatch.replace(
                                "/x",
                                new Node().value(2))));

        // then
        assertTrue(failure instanceof IllegalArgumentException);
        assertEquals(nodeProvider.getBlueIdByName("Fixed One"),
                document.getType().getBlueId());
        assertEquals(1, document.getAsInteger("/x"));
    }

    @Test
    void shouldVerifyUntypedRootOrdinaryPatchesAreNotConformanceEnforced() {
        // given
        Blue blue = ProcessorTestSupport.blue();
        Node document = Nodes.emptyObject();
        DocumentProcessingRuntime runtime = runtime(blue, document);

        // when
        runtime.applyPatch("/", JsonPatch.add("/status", new Node().value("active")));

        // then
        assertEquals("active", document.getAsText("/status"));
    }

    @Test
    void shouldVerifyBatchPatchGeneralizesChangedNodeAndAncestorOnce() {
        // given
        BasicNodeProvider nodeProvider = ConformanceEngineTest.priceProvider();
        Blue blue = ProcessorTestSupport.blue(nodeProvider);
        Node document = canonicalRoot(blue, YAML_MAPPER.readValue(
                "name: Shoes\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("European Product") + "\n" +
                "price:\n" +
                "  amount: 150\n" +
                "  currency: EUR\n" +
                "stock: 5", Node.class));
        DocumentProcessingRuntime runtime = runtime(blue, document);

        // when
        List<DocumentUpdateData> updates = runtime.applyPatches("/", Arrays.asList(
                JsonPatch.replace("/price/currency", new Node().value("USD")),
                JsonPatch.replace("/stock", new Node().value(6))
        ));

        // then
        assertEquals(4, updates.size(),
                "two generated type writes and two authored writes are observable");
        assertEquals("/price/type", updates.get(0).path());
        assertEquals("/type", updates.get(1).path());
        assertEquals("/price/currency", updates.get(2).path());
        assertEquals("/stock", updates.get(3).path());
        assertEquals("USD", document.getAsText("/price/currency"));
        assertEquals(6, document.getAsInteger("/stock"));
        assertEquals(nodeProvider.getBlueIdByName("Price"),
                document.getAsNode("/price/type").getBlueId());
        assertEquals(nodeProvider.getBlueIdByName("Global Product"),
                document.getType().getBlueId());
    }

    @Test
    void shouldAttributeNestedAndRootTypeWritesToTheCausalMiddlePatch() {
        // given
        BasicNodeProvider nodeProvider = ConformanceEngineTest.priceProvider();
        Blue blue = ProcessorTestSupport.blue(nodeProvider);
        Node document = canonicalRoot(blue, YAML_MAPPER.readValue(
                "name: Shoes\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("European Product") + "\n" +
                "price:\n" +
                "  amount: 150\n" +
                "  currency: EUR\n" +
                "stock: 5", Node.class));

        // when
        List<DocumentUpdateData> updates = runtime(blue, document)
                .applyPatches("/", Arrays.asList(
                        JsonPatch.replace("/stock", new Node().value(6)),
                        JsonPatch.replace(
                                "/price/currency",
                                new Node().value("USD")),
                        JsonPatch.replace("/stock", new Node().value(7))));

        // then
        assertEquals(5, updates.size(), updatePaths(updates).toString());
        assertEquals("/stock", updates.get(0).path());
        assertEquals("/price/type", updates.get(1).path());
        assertEquals("/type", updates.get(2).path());
        assertEquals("/price/currency", updates.get(3).path());
        assertEquals("/stock", updates.get(4).path());
        assertEquals(7, document.getAsInteger("/stock"));
        assertEquals("USD", document.getAsText("/price/currency"));
    }

    @Test
    void shouldVerifyNonGeneralizableBatchRollsBackAllPatches() {
        // given
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs(
                "name: Fixed One\n" +
                "x: 1");
        Blue blue = ProcessorTestSupport.blue(nodeProvider);
        Node document = canonicalRoot(blue, YAML_MAPPER.readValue(
                "name: Instance\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Fixed One") + "\n" +
                "x: 1\n" +
                "y: old", Node.class));
        DocumentProcessingRuntime runtime = runtime(blue, document);

        // when
        Throwable failure = captureFailure(
                () -> runtime.applyPatches("/", Arrays.asList(
                JsonPatch.replace("/y", new Node().value("new")),
                JsonPatch.replace("/x", new Node().value(2))
        )));

        // then
        assertTrue(failure instanceof IllegalArgumentException);
        assertEquals(1, document.getAsInteger("/x"));
        assertEquals("old", document.getAsText("/y"));
        assertEquals(nodeProvider.getBlueIdByName("Fixed One"),
                document.getType().getBlueId());
    }

    @Test
    void shouldVerifyApplicationBatchCannotWriteProcessorManagedInitializedMarker() {
        // given
        Blue blue = ProcessorTestSupport.blue();
        Node document = new Node().contracts(
                new Node().properties(
                        "application",
                        new Node().properties(
                                "enabled",
                                new Node().value(true))));
        Node original = document.clone();
        DocumentProcessingRuntime runtime = runtime(blue, document);

        // when
        ProcessorFailureException failure = captureFailure(
                () -> runtime.applyPatches("/", Arrays.asList(
                        JsonPatch.add("/contracts/initialized",
                                new Node().type(new Node().blueId(
                                        RuntimeBlueIds.PROCESSING_INITIALIZED_MARKER))),
                        JsonPatch.add("/status", new Node().value("active"))
                )));

        // then
        assertEquals(ProcessorFailureException.class,
                failure.getClass());
        assertEquals(ProcessorErrorCategory.ProtectedProcessorStateMutation,
                failure.errorCategory());
        assertEquivalentDocuments(original, document,
                "protected processor state rejection must roll back the batch");
    }

    @Test
    void shouldVerifyBatchParentThenChildPatchGeneralizesAndPreservesChildValue() {
        // given
        BasicNodeProvider nodeProvider = ConformanceEngineTest.priceProvider();
        Blue blue = ProcessorTestSupport.blue(nodeProvider);
        Node document = canonicalRoot(blue, YAML_MAPPER.readValue(
                "name: Shoes\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("European Product") + "\n" +
                "price:\n" +
                "  amount: 150\n" +
                "  currency: EUR", Node.class));
        DocumentProcessingRuntime runtime = runtime(blue, document);

        // when
        runtime.applyPatches("/", Arrays.asList(
                JsonPatch.replace("/price", YAML_MAPPER.readValue(
                        "amount: 175\n" +
                        "currency: EUR", Node.class)),
                JsonPatch.replace("/price/currency", new Node().value("USD"))
        ));

        // then
        assertEquals(175, document.getAsInteger("/price/amount"));
        assertEquals("USD", document.getAsText("/price/currency"));
        assertEquals(nodeProvider.getBlueIdByName("Price"),
                document.getAsNode("/price/type").getBlueId());
        assertEquals(nodeProvider.getBlueIdByName("Global Product"),
                document.getType().getBlueId());
    }

    @Test
    void shouldVerifyBatchChildThenSiblingPatchGeneralizesOnceAndPreservesBothChanges() {
        // given
        BasicNodeProvider nodeProvider = ConformanceEngineTest.priceProvider();
        Blue blue = ProcessorTestSupport.blue(nodeProvider);
        Node document = canonicalRoot(blue, YAML_MAPPER.readValue(
                "name: Shoes\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("European Product") + "\n" +
                "price:\n" +
                "  amount: 150\n" +
                "  currency: EUR", Node.class));
        DocumentProcessingRuntime runtime = runtime(blue, document);

        // when
        runtime.applyPatches("/", Arrays.asList(
                JsonPatch.replace("/price/currency", new Node().value("USD")),
                JsonPatch.replace("/price/amount", new Node().value(200))
        ));

        // then
        assertEquals(200, document.getAsInteger("/price/amount"));
        assertEquals("USD", document.getAsText("/price/currency"));
        assertEquals(nodeProvider.getBlueIdByName("Price"),
                document.getAsNode("/price/type").getBlueId());
        assertEquals(nodeProvider.getBlueIdByName("Global Product"),
                document.getType().getBlueId());
    }

    @Test
    void shouldVerifyBatchSiblingPatchesRequiringAncestorGeneralizationPreserveBothChanges() {
        // given
        BasicNodeProvider nodeProvider = productWithAvailabilityProvider();
        Blue blue = ProcessorTestSupport.blue(nodeProvider);
        Node document = canonicalRoot(blue, YAML_MAPPER.readValue(
                "name: Shoes\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("European Listed Product") + "\n" +
                "price:\n" +
                "  amount: 150\n" +
                "  currency: EUR\n" +
                "availability:\n" +
                "  region: EU", Node.class));
        DocumentProcessingRuntime runtime = runtime(blue, document);

        // when
        runtime.applyPatches("/", Arrays.asList(
                JsonPatch.replace("/price/currency", new Node().value("USD")),
                JsonPatch.replace("/availability/region", new Node().value("US"))
        ));

        // then
        assertEquals("USD", document.getAsText("/price/currency"));
        assertEquals("US", document.getAsText("/availability/region"));
        assertEquals(nodeProvider.getBlueIdByName("Price"),
                document.getAsNode("/price/type").getBlueId());
        assertEquals(nodeProvider.getBlueIdByName("Availability"),
                runtime.snapshot().resolvedRoot()
                        .getAsNode("/availability/type").getBlueId());
        assertEquals(nodeProvider.getBlueIdByName(
                        "Global Listed Product"),
                document.getType().getBlueId());
    }

    @Test
    void shouldVerifyBatchDictionaryValueTypePatchesPreserveValuesAndDictionaryType() {
        // given
        BasicNodeProvider nodeProvider = orderBookProvider();
        Blue blue = ProcessorTestSupport.blue(nodeProvider);
        Node document = canonicalRoot(blue, YAML_MAPPER.readValue(
                "name: Book\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Open Order Book") + "\n" +
                "orders:\n" +
                "  order-a:\n" +
                "    status: open\n" +
                "  order-b:\n" +
                "    status: open", Node.class));
        DocumentProcessingRuntime runtime = runtime(blue, document);

        // when
        runtime.applyPatches("/", Arrays.asList(
                JsonPatch.replace("/orders/order-a/status", new Node().value("closed")),
                JsonPatch.replace("/orders/order-b/status", new Node().value("closed"))
        ));

        // then
        assertEquals("closed", document.getAsText("/orders/order-a/status"));
        assertEquals("closed", document.getAsText("/orders/order-b/status"));
        assertEquals(nodeProvider.getBlueIdByName("Order"),
                runtime.snapshot().resolvedRoot()
                        .getAsNode("/orders/valueType").getBlueId());
    }

    @Test
    void shouldVerifyBatchListItemTypePatchesMatchSequentialBehavior() {
        // given
        BasicNodeProvider nodeProvider = itemListProvider();
        Blue blue = ProcessorTestSupport.blue(nodeProvider);
        Node batchDocument = canonicalRoot(blue, YAML_MAPPER.readValue(
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

        // when
        DocumentProcessingRuntime batchRuntime = runtime(blue, batchDocument);
        batchRuntime.applyPatches("/", patches);
        DocumentProcessingRuntime sequential = runtime(blue, sequentialDocument);
        for (JsonPatch patch : patches) {
            sequential.applyPatch("/", patch);
        }

        // then
        assertEquals(sequentialDocument.getAsText("/entries/0/status"), batchDocument.getAsText("/entries/0/status"));
        assertEquals(sequentialDocument.getAsText("/entries/1/status"), batchDocument.getAsText("/entries/1/status"));
                assertEquals(sequential.snapshot().resolvedRoot()
                        .getAsNode("/entries/itemType")
                        .getBlueId(),
                batchRuntime.snapshot().resolvedRoot()
                        .getAsNode("/entries/itemType")
                        .getBlueId());
    }

    @Test
    void shouldVerifyBatchGeneralizesTypedChildUnderUntypedRoot() {
        // given
        BasicNodeProvider nodeProvider = ConformanceEngineTest.priceProvider();
        Blue blue = ProcessorTestSupport.blue(nodeProvider);
        Node batchDocument = canonicalRoot(blue, YAML_MAPPER.readValue(
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

        // when
        runtime(blue, batchDocument).applyPatches("/", patches);
        applySequential(sequentialDocument, blue, patches);

        // then
        assertEquivalentDocuments(sequentialDocument, batchDocument, "typed child under untyped root");
        assertEquals("USD", batchDocument.getAsText("/child/currency"));
        assertEquals(nodeProvider.getBlueIdByName("Price"),
                batchDocument.getAsNode(
                        "/child/type").getBlueId());
    }

    @Test
    void shouldVerifyBatchGeneralizesDictionaryValueTypeUnderUntypedRoot() {
        // given
        BasicNodeProvider nodeProvider = orderBookProvider();
        Blue blue = ProcessorTestSupport.blue(nodeProvider);
        Node batchDocument = canonicalRoot(blue, YAML_MAPPER.readValue(
                "name: Untyped Book\n" +
                "orders:\n" +
                "  type:\n" +
                "    blueId: " + BlueLanguageConstants.DICTIONARY_TYPE_BLUE_ID + "\n" +
                "  keyType:\n" +
                "    blueId: " + BlueLanguageConstants.TEXT_TYPE_BLUE_ID + "\n" +
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

        // when
        runtime(blue, batchDocument).applyPatches("/", patches);
        applySequential(sequentialDocument, blue, patches);

        // then
        assertEquivalentDocuments(sequentialDocument, batchDocument, "dictionary valueType under untyped root");
        assertEquals("closed", batchDocument.getAsText("/orders/order-a/status"));
        assertEquals(nodeProvider.getBlueIdByName("Order"),
                batchDocument.getAsNode(
                        "/orders/valueType").getBlueId());
    }

    @Test
    void shouldVerifyBatchGeneralizesListItemTypeUnderUntypedRoot() {
        // given
        BasicNodeProvider nodeProvider = itemListProvider();
        Blue blue = ProcessorTestSupport.blue(nodeProvider);
        Node batchDocument = canonicalRoot(blue, YAML_MAPPER.readValue(
                "name: Untyped List\n" +
                "entries:\n" +
                "  type:\n" +
                "    blueId: " + BlueLanguageConstants.LIST_TYPE_BLUE_ID + "\n" +
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

        // when
        runtime(blue, batchDocument).applyPatches("/", patches);
        applySequential(sequentialDocument, blue, patches);

        // then
        assertEquivalentDocuments(sequentialDocument, batchDocument, "list itemType under untyped root");
        assertEquals("closed", batchDocument.getAsText("/entries/0/status"));
        assertEquals(nodeProvider.getBlueIdByName("Item"),
                batchDocument.getAsNode(
                        "/entries/itemType").getBlueId());
    }

    @Test
    void shouldVerifyConformanceAffectedUpdateAfterReflectsCommittedResolvedValue() {
        // given
        BasicNodeProvider nodeProvider = ConformanceEngineTest.priceProvider();
        Blue blue = ProcessorTestSupport.blue(nodeProvider);
        Node document = canonicalRoot(blue, YAML_MAPPER.readValue(
                "name: Shoes\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("European Product") + "\n" +
                "price:\n" +
                "  amount: 150\n" +
                "  currency: EUR", Node.class));
        DocumentProcessingRuntime runtime = runtime(blue, document);

        // when
        List<DocumentUpdateData> updates = runtime.applyPatches("/", Arrays.asList(
                JsonPatch.replace("/price", YAML_MAPPER.readValue(
                        "amount: 150\n" +
                        "currency: USD", Node.class))
        ));

        // then
        assertEquals(3, updates.size());
        assertEquals("/price/type", updates.get(0).path());
        assertEquals("/type", updates.get(1).path());
        assertEquals("/price", updates.get(2).path());
        assertEquals("USD", updates.get(2).after().getAsText("/currency"));
        assertEquals(nodeProvider.getBlueIdByName("Price"),
                updates.get(2).after().getType().getBlueId());
        assertEquals(nodeProvider.getBlueIdByName("Price"),
                document.getAsNode("/price/type").getBlueId());
        assertEquals(nodeProvider.getBlueIdByName("Global Product"),
                document.getType().getBlueId());
    }

    @Test
    void shouldVerifyBatchMatchesSequentialRuntimeForUntypedPatchOrdering() {
        // given
        List<BatchComparisonCase> cases = Arrays.asList(
                new BatchComparisonCase(
                        new Node().properties(
                                "a", new Node().value("one"),
                                "b", new Node().value("two")),
                        null,
                        Arrays.asList(
                                JsonPatch.replace(
                                        "/a",
                                        new Node().value("three")),
                                JsonPatch.replace(
                                        "/b",
                                        new Node().value("four"))),
                        "multiple object replacements"),
                new BatchComparisonCase(
                        new Node().properties(
                                "status",
                                new Node().value("idle")),
                        null,
                        Arrays.asList(
                                JsonPatch.replace(
                                        "/status",
                                        new Node().value("first")),
                                JsonPatch.replace(
                                        "/status",
                                        new Node().value("second"))),
                        "duplicate paths"),
                new BatchComparisonCase(
                        Nodes.emptyObject(),
                        null,
                        Arrays.asList(
                                JsonPatch.add(
                                        "/temp",
                                        new Node().value("value")),
                                JsonPatch.remove("/temp")),
                        "add then remove same path"),
                new BatchComparisonCase(
                        new Node().properties(
                                "temp",
                                new Node().value("old")),
                        null,
                        Arrays.asList(
                                JsonPatch.remove("/temp"),
                                JsonPatch.add(
                                        "/temp",
                                        new Node().value("new"))),
                        "remove then add same path"),
                new BatchComparisonCase(
                        listDocument(),
                        null,
                        Arrays.asList(
                                JsonPatch.add(
                                        "/values/1",
                                        new Node().value(99)),
                                JsonPatch.replace(
                                        "/values/2",
                                        new Node().value(100)),
                                JsonPatch.remove("/values/0")),
                        "list add replace remove"));

        // when
        List<BatchComparison> comparisons =
                compareBatchCases(cases);

        // then
        for (BatchComparison comparison : comparisons) {
            assertBatchMatchesSequential(comparison);
        }
    }

    @Test
    void shouldVerifyBatchMatchesSequentialRuntimeForTypedContainerGeneralization()
            throws Exception {
        // given
        BasicNodeProvider priceProvider = ConformanceEngineTest.priceProvider();
        Blue priceBlue = ProcessorTestSupport.blue(priceProvider);
        BasicNodeProvider orderProvider = orderBookProvider();
        Blue orderBlue = ProcessorTestSupport.blue(orderProvider);
        BasicNodeProvider itemProvider = itemListProvider();
        Blue itemBlue = ProcessorTestSupport.blue(itemProvider);
        List<BatchComparisonCase> cases = Arrays.asList(
                new BatchComparisonCase(
                        canonicalRoot(
                                priceBlue,
                                YAML_MAPPER.readValue(
                                        "name: Untyped Container\n"
                                                + "child:\n"
                                                + "  type:\n"
                                                + "    blueId: "
                                                + priceProvider.getBlueIdByName("Price in EUR")
                                                + "\n"
                                                + "  amount: 100\n"
                                                + "  currency: EUR",
                                        Node.class)),
                        priceBlue,
                        Collections.singletonList(
                                JsonPatch.replace(
                                        "/child/currency",
                                        new Node().value("USD"))),
                        "typed child generalization"),
                new BatchComparisonCase(
                        canonicalRoot(
                                orderBlue,
                                YAML_MAPPER.readValue(
                                        "name: Untyped Book\n"
                                                + "orders:\n"
                                                + "  type:\n"
                                                + "    blueId: "
                                                + BlueLanguageConstants.DICTIONARY_TYPE_BLUE_ID
                                                + "\n"
                                                + "  keyType:\n"
                                                + "    blueId: "
                                                + BlueLanguageConstants.TEXT_TYPE_BLUE_ID
                                                + "\n"
                                                + "  valueType:\n"
                                                + "    blueId: "
                                                + orderProvider.getBlueIdByName("Open Order")
                                                + "\n"
                                                + "  order-a:\n"
                                                + "    type:\n"
                                                + "      blueId: "
                                                + orderProvider.getBlueIdByName("Open Order")
                                                + "\n"
                                                + "    status: open",
                                        Node.class)),
                        orderBlue,
                        Collections.singletonList(
                                JsonPatch.replace(
                                        "/orders/order-a/status",
                                        new Node().value("closed"))),
                        "dictionary valueType update"),
                new BatchComparisonCase(
                        canonicalRoot(
                                itemBlue,
                                YAML_MAPPER.readValue(
                                        "name: Untyped List\n"
                                                + "entries:\n"
                                                + "  type:\n"
                                                + "    blueId: "
                                                + BlueLanguageConstants.LIST_TYPE_BLUE_ID
                                                + "\n"
                                                + "  itemType:\n"
                                                + "    blueId: "
                                                + itemProvider.getBlueIdByName("Open Item")
                                                + "\n"
                                                + "  items:\n"
                                                + "    - type:\n"
                                                + "        blueId: "
                                                + itemProvider.getBlueIdByName("Open Item")
                                                + "\n"
                                                + "      status: open",
                                        Node.class)),
                        itemBlue,
                        Collections.singletonList(
                                JsonPatch.replace(
                                        "/entries/0/status",
                                        new Node().value("closed"))),
                        "list itemType update"));

        // when
        List<BatchComparison> comparisons =
                compareBatchCases(cases);

        // then
        for (BatchComparison comparison : comparisons) {
            assertBatchMatchesSequential(comparison);
        }
    }

    @Test
    void shouldKeepCurrentTypeWhenRemovingOptionalWorkflowContract() throws Exception {
        // given
        BasicNodeProvider nodeProvider = applicationContractEvolutionProvider();
        Blue blue = snapshotBlue(nodeProvider);
        Node document = YAML_MAPPER.readValue(
                "name: Optional workflow instance\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Workflow Optional Document") + "\n" +
                "note: retained\n" +
                "contracts:\n" +
                "  workflow:\n" +
                "    kind: optional\n" +
                "  generalization:\n" +
                "    type:\n" +
                "      blueId: " + RuntimeBlueIds.TYPE_GENERALIZATION_POLICY + "\n" +
                "    defaultMode: nearest-valid-ancestor", Node.class);

        // when
        List<DocumentUpdateData> updates = runtime(blue, document)
                .applyPatches("/", Collections.singletonList(
                        JsonPatch.remove("/contracts/workflow")));

        // then
        assertEquals(1, updates.size());
        assertEquals("/contracts/workflow", updates.get(0).path());
        assertEquals(nodeProvider.getBlueIdByName("Workflow Optional Document"),
                document.getType().getBlueId());
        assertEquals("retained", document.getAsText("/note"));
    }

    @Test
    void shouldEmitGeneratedTypeWriteBeforeRequiredWorkflowRemovalUpdate() throws Exception {
        // given
        BasicNodeProvider nodeProvider = applicationContractEvolutionProvider();
        Blue blue = snapshotBlue(nodeProvider);
        Node document = requiredWorkflowInstance(nodeProvider, true);

        // when
        List<DocumentUpdateData> updates = runtime(blue, document)
                .applyPatches("/", Collections.singletonList(
                        JsonPatch.remove("/contracts/workflow")));

        // then
        assertEquals(2, updates.size());
        assertEquals("/type", updates.get(0).path(),
                "the generated type write precedes delivery of the authored update");
        assertEquals(nodeProvider.getBlueIdByName("Workflow Optional Document"),
                updates.get(0).after().getBlueId());
        assertEquals("/contracts/workflow", updates.get(1).path());
        assertEquals(nodeProvider.getBlueIdByName("Workflow Optional Document"),
                document.getType().getBlueId());
        assertEquals("retained", document.getAsText("/note"),
                "generalization must not delete unrelated application content");
    }

    @Test
    void shouldAttributeRootTypeWriteToRequiredRemovalBetweenUnrelatedPatches()
            throws Exception {
        // given
        BasicNodeProvider nodeProvider = applicationContractEvolutionProvider();
        Blue blue = snapshotBlue(nodeProvider);
        Node document = requiredWorkflowInstance(nodeProvider, true);

        // when
        List<DocumentUpdateData> updates = runtime(blue, document)
                .applyPatches("/", Arrays.asList(
                        JsonPatch.replace(
                                "/note",
                                new Node().value("before removal")),
                        JsonPatch.remove("/contracts/workflow"),
                        JsonPatch.replace(
                                "/note",
                                new Node().value("after removal"))));

        // then
        assertEquals(4, updates.size());
        assertEquals("/note", updates.get(0).path());
        assertEquals("/type", updates.get(1).path(),
                "root type metadata belongs to the removal that requires it");
        assertEquals("/contracts/workflow", updates.get(2).path());
        assertEquals("/note", updates.get(3).path());
        assertEquals(nodeProvider.getBlueIdByName("Workflow Optional Document"),
                updates.get(1).after().getBlueId());
        assertEquals("after removal", document.getAsText("/note"));
    }

    @Test
    void shouldRejectRequiredWorkflowRemovalWhenGeneralizationPolicyIsAbsent() throws Exception {
        // given
        BasicNodeProvider nodeProvider = applicationContractEvolutionProvider();
        Blue blue = snapshotBlue(nodeProvider);
        Node document = requiredWorkflowInstance(nodeProvider, false);
        Node original = document.clone();

        // when
        ProcessorFailureException failure = captureFailure(
                () -> runtime(blue, document).applyPatch(
                        "/", JsonPatch.remove("/contracts/workflow")));

        // then
        assertEquals(ProcessorFailureException.class, failure.getClass());
        assertEquals(ProcessorErrorCategory.TypeGeneralizationFailure,
                failure.errorCategory());
        assertEquivalentDocuments(original, document,
                "an absent policy fails closed and rolls back atomically");
    }

    @Test
    void shouldNotUsePolicyWrittenBySameBatchToAuthorizeGeneralization() throws Exception {
        // given
        BasicNodeProvider nodeProvider = applicationContractEvolutionProvider();
        Blue blue = snapshotBlue(nodeProvider);
        Node document = requiredWorkflowInstance(nodeProvider, true);
        document.getContracts().getProperties().get("generalization")
                .getProperties().put("defaultMode", new Node().value("reject"));
        Node original = document.clone();

        // when
        ProcessorFailureException failure = captureFailure(
                () -> runtime(blue, document).applyPatches("/", Arrays.asList(
                        JsonPatch.replace("/contracts/generalization/defaultMode",
                                new Node().value("nearest-valid-ancestor")),
                        JsonPatch.remove("/contracts/workflow"))));

        // then
        assertEquals(ProcessorFailureException.class, failure.getClass());
        assertTrue(failure.errorCategory()
                        == ProcessorErrorCategory.ProtectedProcessorStateMutation
                        || failure.errorCategory()
                        == ProcessorErrorCategory.TypeGeneralizationFailure,
                "the frozen reject policy or protected-state guard must reject self-authorization");
        assertEquivalentDocuments(original, document,
                "policy mutation and dependent patch roll back together");
    }

    @Test
    void shouldNotSpecializeWhenAddingSubtypeCompatibleWorkflowContract() throws Exception {
        // given
        BasicNodeProvider nodeProvider = applicationContractEvolutionProvider();
        Blue blue = snapshotBlue(nodeProvider);
        Node document = YAML_MAPPER.readValue(
                "name: Optional workflow instance\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Workflow Optional Document") + "\n" +
                "note: retained\n" +
                "contracts:\n" +
                "  generalization:\n" +
                "    type:\n" +
                "      blueId: " + RuntimeBlueIds.TYPE_GENERALIZATION_POLICY + "\n" +
                "    defaultMode: nearest-valid-ancestor", Node.class);

        // when
        List<DocumentUpdateData> updates = runtime(blue, document).applyPatches(
                "/", Collections.singletonList(JsonPatch.add(
                        "/contracts/workflow",
                        new Node().properties("kind", new Node().value("required")))));

        // then
        assertEquals(1, updates.size());
        assertEquals("/contracts/workflow", updates.get(0).path());
        assertEquals(nodeProvider.getBlueIdByName("Workflow Optional Document"),
                document.getType().getBlueId(),
                "adding a subtype-compatible contract cannot specialize the node");
    }

    @Test
    void shouldGeneralizeWhenRemovedDirectWorkflowHasNoSemanticInheritedValue()
            throws Exception {
        // given
        BasicNodeProvider nodeProvider = applicationContractEvolutionProvider();
        Blue blue = snapshotBlue(nodeProvider);
        Node document = YAML_MAPPER.readValue(
                "name: Inherited workflow instance\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Workflow Inherited Document") + "\n" +
                "note: retained\n" +
                "contracts:\n" +
                "  workflow:\n" +
                "    kind: direct\n" +
                "  generalization:\n" +
                "    type:\n" +
                "      blueId: " + RuntimeBlueIds.TYPE_GENERALIZATION_POLICY + "\n" +
                "    defaultMode: nearest-valid-ancestor", Node.class);
        DocumentProcessingRuntime runtime = runtime(blue, document);
        String initialResolvedKind = runtime.snapshot().resolvedRoot()
                .getAsText("/contracts/workflow/kind");
        String initialCanonicalKind = runtime.snapshot().canonicalRoot()
                .getAsText("/contracts/workflow/kind");

        // when
        List<DocumentUpdateData> updates = runtime.applyPatches(
                "/", Collections.singletonList(
                        JsonPatch.remove("/contracts/workflow")));

        // then
        assertEquals("direct", initialResolvedKind);
        assertEquals("direct", initialCanonicalKind);
        assertEquals(Arrays.asList("/type", "/contracts/workflow"),
                updatePaths(updates));
        assertEquals(nodeProvider.getBlueIdByName("Workflow Optional Document"),
                document.getType().getBlueId());
        assertTrue(runtime.snapshot().canonicalRoot()
                .getContracts().getProperties().get("workflow") == null);
        assertTrue(runtime.snapshot().resolvedRoot()
                .getContracts().getProperties().get("workflow") == null);
    }

    @Test
    void shouldRejectOrdinaryValueGeneralizationWhenPolicyIsAbsent() {
        // given
        BasicNodeProvider nodeProvider = ConformanceEngineTest.priceProvider();
        Blue blue = ProcessorTestSupport.blue(nodeProvider);
        Node document = canonicalRoot(blue, YAML_MAPPER.readValue(
                "price:\n" +
                "  type:\n" +
                "    blueId: " + nodeProvider.getBlueIdByName("Price in EUR") + "\n" +
                "  amount: 150\n" +
                "  currency: EUR", Node.class));
        document.contracts(null);
        Node before = document.clone();

        // when
        ProcessorFailureException failure = captureFailure(() -> runtime(blue, document)
                .applyPatch("/", JsonPatch.replace("/price/currency", new Node().value("USD"))));

        // then
        assertNotNull(failure);
        assertEquals(ProcessorErrorCategory.TypeGeneralizationFailure, failure.errorCategory());
        assertEquivalentDocuments(before, document,
                "ordinary data changes need policy permission and roll back atomically");
    }

    @Test
    void shouldVerifyProductionGeneralizationPolicyRejectModeFailsWithoutScriptedRuntime() throws Exception {
        // given
        BasicNodeProvider nodeProvider = ConformanceEngineTest.priceProvider();
        Blue blue = ProcessorTestSupport.blue(nodeProvider);
        Node document = canonicalRoot(blue, YAML_MAPPER.readValue(
                "price:\n" +
                "  type:\n" +
                "    blueId: " + nodeProvider.getBlueIdByName("Price in EUR") + "\n" +
                "  amount: 150\n" +
                "  currency: EUR", Node.class));
        document.contracts(generalizationPolicy(new Node().items(Arrays.asList(
                new Node().properties("path", new Node().value("/price"),
                        "mode", new Node().value("reject"))))));

        // when
        ProcessorFailureException failure = captureFailure(
                () -> runtime(blue, document)
                        .applyPatch("/", JsonPatch.replace("/price/currency", new Node().value("USD"))));

        // then
        assertEquals(ProcessorFailureException.class,
                failure.getClass());
        assertEquals(ProcessorErrorCategory.TypeGeneralizationFailure,
                failure.errorCategory(),
                "Unexpected category for " + failure.getMessage());
        assertEquals("EUR", document.getAsText("/price/currency"));
        assertEquals(nodeProvider.getBlueIdByName("Price in EUR"), document.getAsNode("/price/type").getBlueId());
    }

    @Test
    void shouldVerifyProductionGeneralizationPolicyFloorAllowsEqualGeneratedType() throws Exception {
        // given
        BasicNodeProvider nodeProvider = ConformanceEngineTest.priceProvider();
        Blue blue = ProcessorTestSupport.blue(nodeProvider);
        Node document = canonicalRoot(blue, YAML_MAPPER.readValue(
                "price:\n" +
                "  type:\n" +
                "    blueId: " + nodeProvider.getBlueIdByName("Price in EUR") + "\n" +
                "  amount: 150\n" +
                "  currency: EUR", Node.class));
        document.contracts(generalizationPolicy(new Node().items(Arrays.asList(
                new Node().properties("path", new Node().value("/price"),
                        "mode", new Node().value("nearest-valid-ancestor"),
                        "mustRemainSubtypeOf", new Node().blueId(nodeProvider.getBlueIdByName("Price")))))));

        // when
        runtime(blue, document)
                .applyPatch("/", JsonPatch.replace("/price/currency", new Node().value("USD")));

        // then
        assertEquals("USD", document.getAsText("/price/currency"));
        assertEquals(nodeProvider.getBlueIdByName("Price"), document.getAsNode("/price/type").getBlueId());
    }

    @Test
    void shouldVerifyProductionGeneralizationPolicyFloorAllowsEqualGeneratedTypeWithSnapshotRuntime() throws Exception {
        // given
        BasicNodeProvider nodeProvider = ConformanceEngineTest.priceProvider();
        Blue blue = snapshotBlue(nodeProvider);
        Node document = canonicalRoot(blue, YAML_MAPPER.readValue(
                "price:\n" +
                "  type:\n" +
                "    blueId: " + nodeProvider.getBlueIdByName("Price in EUR") + "\n" +
                "  amount: 150\n" +
                "  currency: EUR", Node.class));
        document.contracts(generalizationPolicy(new Node().items(Arrays.asList(
                new Node().properties("path", new Node().value("/price"),
                        "mode", new Node().value("nearest-valid-ancestor"),
                        "mustRemainSubtypeOf", new Node().blueId(nodeProvider.getBlueIdByName("Price")))))));

        // when
        ResolvedSnapshot snapshot = blue.resolveToSnapshot(document);
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(snapshot,
                blue.conformanceEngine(),
                snapshotManager(blue));
        runtime.applyPatch("/", JsonPatch.replace("/price/currency", new Node().value("USD")));

        // then
        assertEquals("USD", runtime.document().getAsText("/price/currency"));
        assertEquals(nodeProvider.getBlueIdByName("Price"), runtime.document().getAsNode("/price/type").getBlueId());
        assertNotNull(runtime.snapshot());
    }

    @Test
    void shouldVerifyProductionGeneralizationPolicyFloorRejectsOvergeneralizationWithoutScriptedRuntime() throws Exception {
        // given
        BasicNodeProvider nodeProvider = payNoteProvider();
        Blue blue = snapshotBlue(nodeProvider);
        Node document = canonicalRoot(blue, YAML_MAPPER.readValue(
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("EUBankTransferPayNote") + "\n" +
                "paymentKind: bank-transfer\n" +
                "rail: SEPA\n" +
                "amount: 10", Node.class));
        document.contracts(generalizationPolicy(new Node().items(Arrays.asList(
                new Node().properties("path", new Node().value("/"),
                        "mode", new Node().value("nearest-valid-ancestor"),
                        "mustRemainSubtypeOf", new Node().blueId(nodeProvider.getBlueIdByName("BankTransferPayNote")))))));

        // when
        ProcessorFailureException failure = captureFailure(
                () -> runtime(blue, document)
                        .applyPatch("/", JsonPatch.replace("/paymentKind", new Node().value("card"))));

        // then
        assertEquals(ProcessorFailureException.class,
                failure.getClass());
        assertEquals(ProcessorErrorCategory.TypeGeneralizationFailure,
                failure.errorCategory(),
                "Unexpected category for " + failure.getMessage());
        assertEquals("bank-transfer", document.getAsText("/paymentKind"));
        assertEquals(nodeProvider.getBlueIdByName("EUBankTransferPayNote"), document.getType().getBlueId());
    }

    @Test
    void shouldVerifyProductionGeneralizationPolicyUsesScopeLocalMarker() throws Exception {
        // given
        BasicNodeProvider nodeProvider = ConformanceEngineTest.priceProvider();
        Blue blue = snapshotBlue(nodeProvider);
        Node document = canonicalRoot(blue, YAML_MAPPER.readValue(
                "child:\n" +
                "  contracts:\n" +
                "    generalization:\n" +
                "      type:\n" +
                "        blueId: " + RuntimeBlueIds.TYPE_GENERALIZATION_POLICY + "\n" +
                "      rules:\n" +
                "        - path: /price\n" +
                "          mode: reject\n" +
                "  price:\n" +
                "    type:\n" +
                "      blueId: " + nodeProvider.getBlueIdByName("Price in EUR") + "\n" +
                "    amount: 150\n" +
                "    currency: EUR", Node.class));

        // when
        ProcessorFailureException failure = captureFailure(
                () -> runtime(blue, document)
                        .applyPatch("/child", JsonPatch.replace("/child/price/currency", new Node().value("USD"))));

        // then
        assertEquals(ProcessorFailureException.class,
                failure.getClass());
        assertEquals(ProcessorErrorCategory.TypeGeneralizationFailure,
                failure.errorCategory(),
                "Unexpected category for " + failure.getMessage());
        assertEquals("EUR", document.getAsText("/child/price/currency"));
        assertEquals(nodeProvider.getBlueIdByName("Price in EUR"),
                document.getAsNode("/child/price/type").getBlueId());
    }

    @Test
    void shouldVerifyProductionGeneralizationPolicyRulePathIsScopeRelative() throws Exception {
        // given
        BasicNodeProvider nodeProvider = ConformanceEngineTest.priceProvider();
        Blue blue = snapshotBlue(nodeProvider);
        Node document = canonicalRoot(blue, YAML_MAPPER.readValue(
                "child:\n" +
                "  contracts:\n" +
                "    generalization:\n" +
                "      type:\n" +
                "        blueId: " + RuntimeBlueIds.TYPE_GENERALIZATION_POLICY + "\n" +
                "      rules:\n" +
                "        - path: /price\n" +
                "          mode: nearest-valid-ancestor\n" +
                "          mustRemainSubtypeOf:\n" +
                "            blueId: " + nodeProvider.getBlueIdByName("Price") + "\n" +
                "  price:\n" +
                "    type:\n" +
                "      blueId: " + nodeProvider.getBlueIdByName("Price in EUR") + "\n" +
                "    amount: 150\n" +
                "    currency: EUR", Node.class));

        // when
        runtime(blue, document)
                .applyPatch("/child", JsonPatch.replace("/child/price/currency", new Node().value("USD")));

        // then
        assertEquals("USD", document.getAsText("/child/price/currency"));
        assertEquals(nodeProvider.getBlueIdByName("Price"), document.getAsNode("/child/price/type").getBlueId());
    }

    @Test
    void shouldVerifyRootGeneralizationPolicyDoesNotAccidentallyOverrideChildPolicyUnlessSpecified() throws Exception {
        // given
        BasicNodeProvider nodeProvider = ConformanceEngineTest.priceProvider();
        Blue blue = snapshotBlue(nodeProvider);
        Node document = canonicalRoot(blue, YAML_MAPPER.readValue(
                "contracts:\n" +
                "  generalization:\n" +
                "    type:\n" +
                "      blueId: " + RuntimeBlueIds.TYPE_GENERALIZATION_POLICY + "\n" +
                "    defaultMode: nearest-valid-ancestor\n" +
                "    rules:\n" +
                "      - path: /price\n" +
                "        mode: reject\n" +
                "child:\n" +
                "  price:\n" +
                "    type:\n" +
                "      blueId: " + nodeProvider.getBlueIdByName("Price in EUR") + "\n" +
                "    amount: 150\n" +
                "    currency: EUR", Node.class));

        // when
        runtime(blue, document)
                .applyPatch("/child", JsonPatch.replace("/child/price/currency", new Node().value("USD")));

        // then
        assertEquals("USD", document.getAsText("/child/price/currency"));
        assertEquals(nodeProvider.getBlueIdByName("Price"), document.getAsNode("/child/price/type").getBlueId());
    }

    @Test
    void shouldVerifyEmbeddedChildPatchCannotGeneralizeParentWithoutScriptedRuntime() throws Exception {
        // given
        BasicNodeProvider nodeProvider = ConformanceEngineTest.priceProvider();
        Blue blue = ProcessorTestSupport.blue(nodeProvider);
        Node document = YAML_MAPPER.readValue(
                "contracts:\n" +
                "  embedded:\n" +
                "    paths:\n" +
                "      - /child\n" +
                "child:\n" +
                "  price:\n" +
                "    type:\n" +
                "      blueId: " + nodeProvider.getBlueIdByName("Price in EUR") + "\n" +
                "    amount: 150\n" +
                "    currency: EUR", Node.class);

        // when
        ProcessorFailureException failure = captureFailure(
                () -> new DocumentProcessingRuntime(document,
                        blue.conformanceEngine(),
                        parentGeneralizationOverride(),
                        snapshotManager(blue),
                        null)
                        .applyPatch("/child", JsonPatch.replace("/child/price/currency", new Node().value("USD"))));

        // then
        assertEquals(ProcessorFailureException.class,
                failure.getClass());
        assertEquals(ProcessorErrorCategory.PatchBoundaryViolation,
                failure.errorCategory());
        assertEquals("EUR", document.getAsText("/child/price/currency"));
        assertEquals(nodeProvider.getBlueIdByName("Price in EUR"),
                document.getAsNode("/child/price/type").getBlueId());
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

    private BasicNodeProvider applicationContractEvolutionProvider() {
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs(
                "name: Workflow Optional Document\n" +
                "note:\n" +
                "  type: Text");
        nodeProvider.addSingleDocs(
                "name: Workflow Required Document\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Workflow Optional Document") + "\n" +
                "contracts:\n" +
                "  workflow:\n" +
                "    schema:\n" +
                "      required: true");
        nodeProvider.addSingleDocs(
                "name: Workflow Inherited Document\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Workflow Optional Document") + "\n" +
                "contracts:\n" +
                "  workflow:\n" +
                "    schema:\n" +
                "      required: true");
        return nodeProvider;
    }

    private Node requiredWorkflowInstance(
            BasicNodeProvider nodeProvider,
            boolean includeGeneralizationPolicy) throws Exception {
        String policy = includeGeneralizationPolicy
                ? "  generalization:\n" +
                  "    type:\n" +
                  "      blueId: " + RuntimeBlueIds.TYPE_GENERALIZATION_POLICY + "\n" +
                  "    defaultMode: nearest-valid-ancestor\n"
                : "";
        return YAML_MAPPER.readValue(
                "name: Required workflow instance\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Workflow Required Document") + "\n" +
                "note: retained\n" +
                "contracts:\n" +
                "  workflow:\n" +
                "    kind: required\n" +
                policy,
                Node.class);
    }

    private Node generalizationPolicy(Node rules) {
        return new Node().properties("generalization",
                new Node()
                        .type(new Node().blueId(RuntimeBlueIds.TYPE_GENERALIZATION_POLICY))
                        .properties(
                                "defaultMode",
                                new Node().value("nearest-valid-ancestor"),
                                "rules",
                                rules));
    }

    private Node canonicalRoot(Blue blue, Node source) {
        /*
         * The runtime owns resolution. Passing a minimized or merged synthetic
         * root here would lose selected fixed values and would violate the
         * PROCESS boundary's exact-document rule. Legacy widening fixtures
         * explicitly opt in to nearest-valid-ancestor now that missing policy
         * fails closed.
         */
        Node contracts = source.getContracts();
        if (contracts == null) {
            contracts = new Node();
            source.contracts(contracts);
        }
        if (contracts.getProperties() == null
                || !contracts.getProperties().containsKey("generalization")) {
            contracts.properties("generalization",
                    new Node()
                            .type(new Node().blueId(
                                    RuntimeBlueIds.TYPE_GENERALIZATION_POLICY))
                            .properties("defaultMode",
                                    new Node().value(
                                            "nearest-valid-ancestor")));
        }
        return source;
    }

    private DocumentProcessingRuntime runtime(Blue blue, Node document) {
        if (blue == null) {
            return new DocumentProcessingRuntime(document);
        }
        return new DocumentProcessingRuntime(document,
                blue.conformanceEngine(),
                snapshotManager(blue));
    }

    private Blue snapshotBlue(BasicNodeProvider nodeProvider) {
        return new Blue(new SequentialNodeProvider(
                BootstrapProvider.INSTANCE,
                BlueRuntimeTypeRegistry.getDefault().asProcessorSnapshotProvider(),
                nodeProvider));
    }

    private ProcessingSnapshotManager snapshotManager(Blue blue) {
        return new ProcessingSnapshotManager() {
            @Override
            public ResolvedSnapshot fromDocument(Node document) {
                return blue.resolveToSnapshot(document);
            }

            @Override
            public ResolvedSnapshot applyPatch(ResolvedSnapshot snapshot, JsonPatch patch) {
                return blue.applyCanonicalPatch(snapshot, patch);
            }

            @Override
            public ResolvedSnapshot cacheSnapshot(ResolvedSnapshot snapshot) {
                blue.cacheResolvedSnapshot(snapshot);
                return snapshot;
            }
        };
    }

    private BasicNodeProvider payNoteProvider() {
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs(
                "name: PayNote\n" +
                "paymentKind:\n" +
                "  type: Text\n" +
                "rail:\n" +
                "  type: Text\n" +
                "amount:\n" +
                "  type: Integer");
        nodeProvider.addSingleDocs(
                "name: BankTransferPayNote\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("PayNote") + "\n" +
                "paymentKind: bank-transfer");
        nodeProvider.addSingleDocs(
                "name: EUBankTransferPayNote\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("BankTransferPayNote") + "\n" +
                "rail: SEPA");
        return nodeProvider;
    }

    private ConformancePlannerOverride parentGeneralizationOverride() {
        return new ConformancePlannerOverride() {
            @Override
            public boolean applies() {
                return true;
            }

            @Override
            public ConformancePlan plan(FrozenNode canonicalRoot,
                                        FrozenNode resolvedRoot,
                                        List<ConformanceChangedPath> changedPaths) {
                return ConformancePlan.generalized(canonicalRoot,
                        resolvedRoot,
                        Collections.emptyList(),
                        Collections.singletonList("/type"),
                        canonicalRoot != null);
            }
        };
    }

    private List<BatchComparison> compareBatchCases(
            List<BatchComparisonCase> cases) {
        List<BatchComparison> comparisons =
                new ArrayList<>(cases.size());
        for (BatchComparisonCase comparisonCase : cases) {
            comparisons.add(compareBatchToSequential(
                    comparisonCase));
        }
        return comparisons;
    }

    private BatchComparison compareBatchToSequential(
            BatchComparisonCase comparisonCase) {
        Node batchDocument =
                comparisonCase.initial.clone();
        Node sequentialDocument =
                comparisonCase.initial.clone();
        List<DocumentUpdateData> batchUpdates =
                runtime(comparisonCase.blue, batchDocument)
                        .applyPatches(
                                "/",
                                comparisonCase.patches);
        List<DocumentUpdateData> sequentialUpdates =
                applySequential(
                        sequentialDocument,
                        comparisonCase.blue,
                        comparisonCase.patches);
        return new BatchComparison(
                comparisonCase.label,
                batchDocument,
                sequentialDocument,
                batchUpdates,
                sequentialUpdates);
    }

    private void assertBatchMatchesSequential(
            BatchComparison comparison) {
        assertEquivalentDocuments(
                comparison.sequentialDocument,
                comparison.batchDocument,
                comparison.label);
        assertEquals(
                updatePaths(comparison.sequentialUpdates),
                updatePaths(comparison.batchUpdates),
                comparison.label + " update paths");
    }

    private List<DocumentUpdateData> applySequential(Node document,
                                                                               Blue blue,
                                                                               List<JsonPatch> patches) {
        DocumentProcessingRuntime sequential = runtime(blue, document);
        List<DocumentUpdateData> updates = new ArrayList<>();
        for (JsonPatch patch : patches) {
            updates.addAll(sequential.applyPatches(
                    "/", Collections.singletonList(patch)));
        }
        return updates;
    }

    private void assertEquivalentDocuments(Node expected, Node actual, String label) {
        assertEquals(runtimeDocumentBlueId(expected),
                runtimeDocumentBlueId(actual),
                label);
    }

    private String runtimeDocumentBlueId(Node node) {
        return FrozenNode.fromResolvedNode(node).blueId();
    }

    private List<String> updatePaths(List<DocumentUpdateData> updates) {
        List<String> paths = new ArrayList<>();
        for (DocumentUpdateData update : updates) {
            assertNotNull(update);
            paths.add(update.path());
        }
        return paths;
    }

    private static final class BatchComparisonCase {
        private final Node initial;
        private final Blue blue;
        private final List<JsonPatch> patches;
        private final String label;

        private BatchComparisonCase(
                Node initial,
                Blue blue,
                List<JsonPatch> patches,
                String label) {
            this.initial = initial;
            this.blue = blue;
            this.patches = patches;
            this.label = label;
        }
    }

    private static final class BatchComparison {
        private final String label;
        private final Node batchDocument;
        private final Node sequentialDocument;
        private final List<DocumentUpdateData>
                batchUpdates;
        private final List<DocumentUpdateData>
                sequentialUpdates;

        private BatchComparison(
                String label,
                Node batchDocument,
                Node sequentialDocument,
                List<DocumentUpdateData>
                        batchUpdates,
                List<DocumentUpdateData>
                        sequentialUpdates) {
            this.label = label;
            this.batchDocument = batchDocument;
            this.sequentialDocument = sequentialDocument;
            this.batchUpdates = batchUpdates;
            this.sequentialUpdates = sequentialUpdates;
        }
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
