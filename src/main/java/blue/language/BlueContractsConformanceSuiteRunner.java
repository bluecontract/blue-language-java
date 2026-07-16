package blue.language;

import blue.language.conformance.ConformancePlan;
import blue.language.model.Node;
import blue.language.processor.ConformanceChangedPath;
import blue.language.processor.ConformancePlannerOverride;
import blue.language.processor.ContractMatchingService;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.ProcessingDocumentValidator;
import blue.language.processor.ProcessingSnapshotManager;
import blue.language.processor.ProcessorFatalException;
import blue.language.processor.conformance.MockExternalChannelProcessor;
import blue.language.processor.conformance.MockHandlerProcessor;
import blue.language.processor.conformance.MockTypeBlueIds;
import blue.language.processor.conformance.ScriptedContractsRuntime;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.registry.BlueRuntimeTypeRegistry;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.processor.registry.RuntimeTypeKey;
import blue.language.processor.util.PointerUtils;
import blue.language.snapshot.FrozenNode;
import blue.language.snapshot.ResolvedSnapshot;
import blue.language.utils.NodePathAccessor;
import blue.language.utils.NodePathEditor;
import blue.language.utils.NodeProviderWrapper;
import blue.language.utils.NodeToMapListOrValue;
import blue.language.utils.UncheckedObjectMapper;
import blue.language.utils.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class BlueContractsConformanceSuiteRunner {

    private static final String FIXTURE_ROOT = "blue-contracts-1.0/fixtures/";
    private static final Set<String> SUPPORTED_EXPECTED_FIELDS = new LinkedHashSet<>(Arrays.asList(
            "expectedAbsentDocumentPathValues",
            "expectedAbsentDocumentPaths",
            "expectedBlueId",
            "expectedCapabilityFailure",
            "expectedCheckpointLastEvents",
            "expectedDescendantOrEqual",
            "expectedDocument",
            "expectedDocumentPathExists",
            "expectedDocumentPathValues",
            "expectedDocumentPaths",
            "expectedDocumentUpdateOrder",
            "expectedDocumentUpdates",
            "expectedEffectApplicationOrder",
            "expectedEmbeddedDeliveryOrder",
            "expectedErrorCategories",
            "expectedErrorCategory",
            "expectedExactGas",
            "expectedFailureReasonContains",
            "expectedGasByteView",
            "expectedInitializationContentBlueIdInput",
            "expectedNoDocumentMutation",
            "expectedOriginalBlueId",
            "expectedPointerReads",
            "expectedPointerWrites",
            "expectedProcessorEventTypes",
            "expectedRootEventCount",
            "expectedRootEventPathValues",
            "expectedRootEventSuffix",
            "expectedRootEventTypes",
            "expectedRootEvents",
            "expectedRuntimeBlueIds",
            "expectedRuntimeInsertionNormalizedValues",
            "expectedStatus",
            "expectedStoredObjectKeys",
            "expectedTerminationFallback",
            "expectedTotalGas",
            "expectedTotalGasMin",
            "expectedTriggeredDeliveryOrder",
            "expectedTriggeredFifoAfterDocumentUpdates",
            "expectedValid"));
    private static final Set<String> SUPPORTED_PROCESSOR_CAPABILITIES = new LinkedHashSet<>(Arrays.asList(
            "blue-contracts-fixture-scripted-runtime-v1",
            "blue-contracts-fixture-type-graph-v1"));

    private BlueContractsConformanceSuiteRunner() {
    }

    public static BlueContractsConformanceReport run(Blue blue) {
        BlueContractsConformanceReport metadata = blue.contractsConformanceReport();
        List<String> passed = new ArrayList<>();
        List<BlueContractsConformanceFailure> failures = new ArrayList<>();
        for (FixtureEntry fixture : fixtureEntries()) {
            try {
                runFixture(fixture);
                passed.add(fixture.id);
            } catch (RuntimeException | AssertionError e) {
                failures.add(failure(fixture, e));
            }
        }
        return new BlueContractsConformanceReport(
                metadata.getSpecVersion(),
                metadata.getFixturePackageIdentity(),
                metadata.getFixtureIds(),
                passed,
                Collections.emptyList(),
                metadata.getFixtureCategories(),
                failures);
    }

    public static void validateFixtureMetadataForTest(JsonNode spec) {
        validateFixtureMetadata(spec);
    }

    public static void runFixtureSpecForTest(JsonNode spec) {
        validateFixtureMetadata(spec);
        String operation = requireNonNull(spec, "operation").asText();
        if ("registryRuntimeTypeBlueIds".equals(operation)) {
            runRegistryFixture(spec);
        } else if ("changingRegistryDescriptionChangesBlueId".equals(operation)) {
            runChangingRegistryDescriptionFixture(spec);
        } else if ("runtimeRegistryPreprocessingEnvironmentReproducible".equals(operation)) {
            runRuntimeRegistryPreprocessingEnvironmentFixture(spec);
        } else if ("registryNodeHashesToPublishedBlueId".equals(operation)) {
            runRegistryNodeHashesFixture(spec);
        } else if ("registryFieldUsesTextBlueIdString".equals(operation)) {
            runRegistryFieldUsesTextBlueIdStringFixture(spec);
        } else if ("processDocument".equals(operation)) {
            runProcessFixture(spec);
        } else if ("pointerDescendant".equals(operation)) {
            runPointerFixture(spec);
        } else if ("pointerValidation".equals(operation)) {
            runPointerValidationFixture(spec);
        } else {
            throw new IllegalArgumentException("Unsupported Blue Contracts fixture operation: " + operation);
        }
    }

    private static List<FixtureEntry> fixtureEntries() {
        JsonNode manifest = readResource(FIXTURE_ROOT + "manifest.yaml");
        JsonNode fixtures = requireNonNull(manifest, "fixtures");
        if (!fixtures.isArray()) {
            throw new IllegalArgumentException("Fixture manifest field \"fixtures\" must be a list.");
        }
        List<FixtureEntry> entries = new ArrayList<>();
        for (JsonNode entry : fixtures) {
            String id = requireNonNull(entry, "id").asText();
            String category = requireNonNull(entry, "category").asText();
            BlueContractsFixtureCategory.fromLabel(category);
            String path = requireNonNull(entry, "path").asText();
            entries.add(new FixtureEntry(id, category, path));
        }
        return entries;
    }

    private static void runFixture(FixtureEntry fixture) {
        JsonNode spec = readResource(FIXTURE_ROOT + fixture.path);
        validateFixtureMatchesManifest(fixture, spec);
        String operation = text(spec, "operation", null);
        if ("registryRuntimeTypeBlueIds".equals(operation)) {
            runRegistryFixture(spec);
        } else if ("changingRegistryDescriptionChangesBlueId".equals(operation)) {
            runChangingRegistryDescriptionFixture(spec);
        } else if ("runtimeRegistryPreprocessingEnvironmentReproducible".equals(operation)) {
            runRuntimeRegistryPreprocessingEnvironmentFixture(spec);
        } else if ("registryNodeHashesToPublishedBlueId".equals(operation)) {
            runRegistryNodeHashesFixture(spec);
        } else if ("registryFieldUsesTextBlueIdString".equals(operation)) {
            runRegistryFieldUsesTextBlueIdStringFixture(spec);
        } else if ("processDocument".equals(operation)) {
            runProcessFixture(spec);
        } else if ("pointerDescendant".equals(operation)) {
            runPointerFixture(spec);
        } else if ("pointerValidation".equals(operation)) {
            runPointerValidationFixture(spec);
        } else {
            throw new IllegalArgumentException("Unsupported Blue Contracts fixture operation: " + operation);
        }
    }

    private static void runRegistryFixture(JsonNode spec) {
        BlueRuntimeTypeRegistry registry = BlueRuntimeTypeRegistry.getDefault();
        JsonNode expected = requireNonNull(spec, "expectedRuntimeBlueIds");
        for (Iterator<Map.Entry<String, JsonNode>> it = expected.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = it.next();
            RuntimeTypeKey key = RuntimeTypeKey.valueOf(entry.getKey());
            assertEquals(entry.getValue().asText(), registry.blueId(key));
            assertTrue(registry.isProcessorManagedTypeBlueId(entry.getValue().asText()),
                    "Runtime type BlueId must be processor-managed: " + entry.getKey());
        }
    }

    private static void runChangingRegistryDescriptionFixture(JsonNode spec) {
        BlueRuntimeTypeRegistry registry = BlueRuntimeTypeRegistry.getDefault();
        RuntimeTypeKey key = runtimeTypeKey(requireNonNull(spec, "registryKey").asText());
        assertEquals(requireNonNull(spec, "expectedOriginalBlueId").asText(), registry.blueId(key));
        Node node = readRegistryNode(requireNonNull(spec, "registryPath").asText());
        String originalCalculated = blueId(node);
        JsonNode mutation = requireNonNull(spec, "mutation");
        String field = requireNonNull(mutation, "field").asText();
        if (!"description".equals(field)) {
            throw new IllegalArgumentException("Unsupported registry mutation field: " + field);
        }
        node.description((node.getDescription() != null ? node.getDescription() : "")
                + requireNonNull(mutation, "append").asText());
        String mutated = blueId(node);
        if (spec.path("expectBlueIdChanged").asBoolean(false)) {
            assertTrue(!originalCalculated.equals(mutated),
                    "Expected registry mutation to change BlueId for " + key);
        }
    }

    private static void runRuntimeRegistryPreprocessingEnvironmentFixture(JsonNode spec) {
        BlueRuntimeTypeRegistry registry = BlueRuntimeTypeRegistry.getDefault();
        JsonNode environment = requireNonNull(spec, "preprocessingEnvironment");
        assertEquals("blue-language-1.0", requireNonNull(environment, "coreRegistry").asText());
        assertEquals("blue-contracts-1.0", requireNonNull(environment, "runtimeRegistry").asText());
        assertEquals(RuntimeTypeKey.values().length, registry.blueIds().size());
        for (RuntimeTypeKey key : RuntimeTypeKey.values()) {
            assertEquals(RuntimeBlueIds.blueId(key), registry.blueId(key));
        }
    }

    private static void runRegistryNodeHashesFixture(JsonNode spec) {
        BlueRuntimeTypeRegistry registry = BlueRuntimeTypeRegistry.getDefault();
        RuntimeTypeKey key = runtimeTypeKey(requireNonNull(spec, "registryKey").asText());
        assertEquals(requireNonNull(spec, "expectedBlueId").asText(), registry.blueId(key));
        readRegistryNode(requireNonNull(spec, "registryPath").asText());
    }

    private static void runRegistryFieldUsesTextBlueIdStringFixture(JsonNode spec) {
        JsonNode fields = requireNonNull(spec, "fields");
        if (!fields.isArray()) {
            throw new IllegalArgumentException("registryFieldUsesTextBlueIdString fields must be a list");
        }
        for (JsonNode fieldSpec : fields) {
            runtimeTypeKey(requireNonNull(fieldSpec, "registryKey").asText());
            Node node = readRegistryNode(requireNonNull(fieldSpec, "registryPath").asText());
            Node field = nodeAt(node, requireNonNull(fieldSpec, "fieldPath").asText());
            if (field == null) {
                throw new AssertionError("Missing registry field " + fieldSpec.get("fieldPath").asText());
            }
            String expectedType = requireNonNull(fieldSpec, "expectedType").asText();
            Node type = field.getType();
            String actualType = type == null ? null
                    : type.getValue() != null ? type.getValue().toString()
                    : type.getBlueId();
            assertEquals(expectedType, actualType);
            String phrase = requireNonNull(fieldSpec, "expectedDescriptionContains").asText();
            String description = field.getDescription();
            assertTrue(description != null && description.contains(phrase),
                    "Expected registry field description to contain " + phrase);
        }
    }

    private static void runPointerFixture(JsonNode spec) {
        String path = requireNonNull(spec, "path").asText();
        String ancestor = requireNonNull(spec, "ancestor").asText();
        boolean expected = requireNonNull(spec, "expectedDescendantOrEqual").asBoolean();
        assertEquals(expected, PointerUtils.descendantOrEqual(path, ancestor));
    }

    private static void runPointerValidationFixture(JsonNode spec) {
        String pointer = requireNonNull(spec, "pointer").asText();
        boolean expected = requireNonNull(spec, "expectedValid").asBoolean();
        try {
            PointerUtils.assertValidRuntimePointer(pointer);
            assertTrue(expected, "Expected pointer to be invalid: " + pointer);
        } catch (RuntimeException ex) {
            if (expected) {
                throw ex;
            }
            assertFailureReasonContains(spec, ex.getMessage());
        }
    }

    private static void runProcessFixture(JsonNode spec) {
        JsonNode initialDocument = requireNonNull(spec, "initialDocument");
        DocumentProcessingResult rawPreValidationFailure = ProcessingDocumentValidator.validateRaw(initialDocument, null);
        if (rawPreValidationFailure != null) {
            assertProcessResult(spec, rawPreValidationFailure.document().clone(), rawPreValidationFailure, null);
            return;
        }
        Node document = ProcessingDocumentValidator.readProcessingDocument(initialDocument);
        DocumentProcessingResult preValidationFailure = ProcessingDocumentValidator.validateRaw(initialDocument, document);
        if (preValidationFailure != null) {
            assertProcessResult(spec, document.clone(), preValidationFailure, null);
            return;
        }
        ScriptedFixtureTypes scriptedTypes = discoverScriptedRuntimeTypes(spec, document);
        ScriptedContractsRuntime scriptedRuntime = new ScriptedContractsRuntime(spec.get("mockRuntime"), spec.get("typeGraph"));
        MockExternalChannelProcessor channelProcessor = new MockExternalChannelProcessor(scriptedRuntime);
        MockHandlerProcessor handlerProcessor = new MockHandlerProcessor(scriptedRuntime);
        Blue fixtureBlue = !scriptedTypes.externalTypeNodesByBlueId.isEmpty()
                ? new Blue(mockTypeProvider(scriptedTypes))
                : null;
        DocumentProcessor.Builder processorBuilder = DocumentProcessor.builder()
                .withMatchingService(new ContractMatchingService(new Blue()))
                .registerContractProcessor(channelProcessor)
                .registerContractProcessor(handlerProcessor);
        if (fixtureBlue != null) {
            processorBuilder.withConformanceEngine(fixtureBlue.conformanceEngine());
        }
        if (scriptedRuntime.hasFixtureTypeGraph()) {
            processorBuilder.withSnapshotManager(fixtureSnapshotManager(fixtureBlue));
            processorBuilder.withConformancePlannerOverride(
                    fixtureGeneralizationPlanner(scriptedRuntime, scriptedTypes, document));
        }
        for (String channelTypeBlueId : scriptedTypes.channelTypeBlueIds) {
            processorBuilder.registerContractProcessor(channelTypeBlueId, channelProcessor);
        }
        for (String handlerTypeBlueId : scriptedTypes.handlerTypeBlueIds) {
            processorBuilder.registerContractProcessor(handlerTypeBlueId, handlerProcessor);
        }
        DocumentProcessor processor = processorBuilder.build();
        Node originalDocument = document.clone();
        Node event = spec.has("event") ? readNode(spec.get("event")) : new Node().value("event");
        try (ScriptedContractsRuntime.Activation ignored = scriptedRuntime.activate()) {
            DocumentProcessingResult result = processor.processDocument(document, event);
            assertProcessResult(spec, originalDocument, result, scriptedRuntime);
        } catch (ProcessorFatalException ex) {
            DocumentProcessingResult result = ex.partialResult();
            if (result == null) {
                throw ex;
            }
            assertProcessResult(spec, originalDocument, result, scriptedRuntime);
        }
    }

    private static ProcessingSnapshotManager fixtureSnapshotManager(Blue fixtureBlue) {
        if (fixtureBlue == null) {
            throw new IllegalArgumentException("Fixture type graph requires a fixture Blue instance");
        }
        return new ProcessingSnapshotManager() {
            @Override
            public ResolvedSnapshot fromDocument(Node document) {
                return fixtureBlue.resolveToSnapshot(document);
            }

            @Override
            public ResolvedSnapshot applyPatch(ResolvedSnapshot snapshot, JsonPatch patch) {
                return fixtureBlue.applyCanonicalPatch(snapshot, patch);
            }

            @Override
            public ResolvedSnapshot cacheSnapshot(ResolvedSnapshot snapshot) {
                fixtureBlue.cacheResolvedSnapshot(snapshot);
                return snapshot;
            }
        };
    }

    private static ConformancePlannerOverride fixtureGeneralizationPlanner(
            ScriptedContractsRuntime scriptedRuntime,
            ScriptedFixtureTypes scriptedTypes,
            Node selectedRoot) {
        ConformancePlannerOverride delegate = scriptedRuntime.conformancePlannerOverride();
        Node initialSelectedRoot = selectedRoot.clone();
        return new ConformancePlannerOverride() {
            @Override
            public boolean applies() {
                return delegate.applies();
            }

            @Override
            public ConformancePlan plan(FrozenNode canonicalRoot,
                                        FrozenNode resolvedRoot,
                                        List<ConformanceChangedPath> changedPaths) {
                Node plannerRoot = resolvedRoot.toNode();
                restoreFixtureTypeReferences(plannerRoot,
                        canonicalRoot != null ? canonicalRoot.toNode() : null,
                        initialSelectedRoot,
                        scriptedTypes);
                return delegate.plan(canonicalRoot,
                        FrozenNode.fromResolvedNode(plannerRoot),
                        changedPaths);
            }
        };
    }

    /*
     * The scripted fixture planner names graph types by their declared BlueId,
     * while the real fixture resolver expands those type references. Keep all
     * effective fields from the resolved view and restore only type-reference
     * metadata from the canonical/selected fixture views for that planner.
     */
    private static void restoreFixtureTypeReferences(Node resolved,
                                                     Node canonical,
                                                     Node selected,
                                                     ScriptedFixtureTypes scriptedTypes) {
        if (resolved == null) {
            return;
        }
        resolved.type(fixturePlannerType(resolved.getType(),
                canonical != null ? canonical.getType() : null,
                selected != null ? selected.getType() : null,
                scriptedTypes));
        resolved.itemType(fixturePlannerType(resolved.getItemType(),
                canonical != null ? canonical.getItemType() : null,
                selected != null ? selected.getItemType() : null,
                scriptedTypes));
        resolved.keyType(fixturePlannerType(resolved.getKeyType(),
                canonical != null ? canonical.getKeyType() : null,
                selected != null ? selected.getKeyType() : null,
                scriptedTypes));
        resolved.valueType(fixturePlannerType(resolved.getValueType(),
                canonical != null ? canonical.getValueType() : null,
                selected != null ? selected.getValueType() : null,
                scriptedTypes));
        restoreFixtureTypeReferences(resolved.getContracts(),
                canonical != null ? canonical.getContracts() : null,
                selected != null ? selected.getContracts() : null,
                scriptedTypes);
        restoreFixtureTypeReferences(resolved.getBlue(),
                canonical != null ? canonical.getBlue() : null,
                selected != null ? selected.getBlue() : null,
                scriptedTypes);
        if (resolved.getProperties() != null) {
            for (Map.Entry<String, Node> entry : resolved.getProperties().entrySet()) {
                Node canonicalChild = canonical != null && canonical.getProperties() != null
                        ? canonical.getProperties().get(entry.getKey())
                        : null;
                Node selectedChild = selected != null && selected.getProperties() != null
                        ? selected.getProperties().get(entry.getKey())
                        : null;
                restoreFixtureTypeReferences(entry.getValue(), canonicalChild, selectedChild, scriptedTypes);
            }
        }
        if (resolved.getItems() != null) {
            for (int i = 0; i < resolved.getItems().size(); i++) {
                Node canonicalItem = canonical != null
                        && canonical.getItems() != null
                        && i < canonical.getItems().size()
                        ? canonical.getItems().get(i)
                        : null;
                Node selectedItem = selected != null
                        && selected.getItems() != null
                        && i < selected.getItems().size()
                        ? selected.getItems().get(i)
                        : null;
                restoreFixtureTypeReferences(resolved.getItems().get(i), canonicalItem, selectedItem, scriptedTypes);
            }
        }
    }

    private static Node fixturePlannerType(Node resolvedType,
                                           Node canonicalType,
                                           Node selectedType,
                                           ScriptedFixtureTypes scriptedTypes) {
        if (resolvedType == null) {
            return null;
        }
        if (canonicalType != null && canonicalType.getBlueId() != null) {
            return canonicalType.clone();
        }
        if (selectedType != null && selectedType.getBlueId() != null) {
            return selectedType.clone();
        }
        String fixtureTypeBlueId = scriptedTypes.externalTypeBlueId(resolvedType.getName());
        if (fixtureTypeBlueId != null) {
            return new Node().blueId(fixtureTypeBlueId);
        }
        restoreFixtureTypeReferences(resolvedType, canonicalType, selectedType, scriptedTypes);
        return resolvedType;
    }

    private static Node withoutProcessorManagedMutationMarkers(Node document) {
        Node copy = document != null ? document.clone() : new Node();
        stripProcessorManagedMarkers(copy);
        return copy;
    }

    private static void stripProcessorManagedMarkers(Node node) {
        if (node == null) {
            return;
        }
        if (node.getContracts() != null && node.getContracts().getProperties() != null) {
            node.getContracts().getProperties().remove("initialized");
            node.getContracts().getProperties().remove("checkpoint");
            node.getContracts().getProperties().remove("terminated");
            for (Node contract : node.getContracts().getProperties().values()) {
                stripProcessorManagedMarkers(contract);
            }
        }
        if (node.getProperties() != null) {
            for (Node child : node.getProperties().values()) {
                stripProcessorManagedMarkers(child);
            }
        }
        if (node.getItems() != null) {
            for (Node child : node.getItems()) {
                stripProcessorManagedMarkers(child);
            }
        }
    }

    private static void assertProcessResult(JsonNode spec,
                                            Node originalDocument,
                                            DocumentProcessingResult result,
                                            ScriptedContractsRuntime scriptedRuntime) {
        assertStatusAndError(spec, result);
        if (spec.has("expectedCapabilityFailure")) {
            assertEquals(spec.get("expectedCapabilityFailure").asBoolean(), result.capabilityFailure());
        }
        assertFailureReasonContains(spec, result.failureReason(), result.document());
        if (spec.path("expectedNoDocumentMutation").asBoolean(false)) {
            assertNodeEquals(withoutProcessorManagedMutationMarkers(originalDocument),
                    withoutProcessorManagedMutationMarkers(result.document()),
                    "Document mutation");
        }
        if (spec.has("expectedDocument")) {
            assertNodeEquals(readNode(spec.get("expectedDocument")), result.document(), "Document");
        }
        if (spec.has("expectedExactGas")) {
            assertEquals(spec.get("expectedExactGas").asLong(), result.totalGas());
        }
        if (spec.has("expectedTotalGas")) {
            assertEquals(spec.get("expectedTotalGas").asLong(), result.totalGas());
        }
        if (spec.has("expectedTotalGasMin")) {
            long min = spec.get("expectedTotalGasMin").asLong();
            assertTrue(result.totalGas() >= min, "Expected total gas >= " + min + " but was " + result.totalGas());
        }
        assertRootEvents(spec, result.triggeredEvents());
        assertDocumentPaths(spec, result.document());
        assertCheckpointLastEvents(spec, result.document());
        assertStoredObjectKeys(spec, result.document());
        assertPointerReadsAndWrites(spec, result.document());
        assertInitializationContentBlueIdInput(spec, originalDocument, result.document());
        assertRuntimeInsertionNormalizedValues(spec, result);
        assertGasByteView(spec, result);
        assertProcessorEventTypes(spec, result);
        assertTerminationFallback(spec, result);
        assertTraceExpectations(spec, scriptedRuntime);
    }

    private static void assertRootEvents(JsonNode spec, List<Node> rootEvents) {
        if (spec.has("expectedRootEventCount")) {
            assertEquals(spec.get("expectedRootEventCount").asInt(), rootEvents.size());
        }
        if (spec.has("expectedRootEvents")) {
            JsonNode expectedEvents = spec.get("expectedRootEvents");
            if (!expectedEvents.isArray()) {
                throw new AssertionError("expectedRootEvents must be a list");
            }
            assertEquals(expectedEvents.size(), rootEvents.size(), "Root event count");
            for (int i = 0; i < expectedEvents.size(); i++) {
                assertNodeEquals(readNode(expectedEvents.get(i)), rootEvents.get(i), "Root event " + i);
            }
        }
        if (spec.has("expectedRootEventSuffix")) {
            JsonNode expectedEvents = spec.get("expectedRootEventSuffix");
            if (!expectedEvents.isArray()) {
                throw new AssertionError("expectedRootEventSuffix must be a list");
            }
            if (rootEvents.size() < expectedEvents.size()) {
                throw new AssertionError("Expected at least " + expectedEvents.size()
                        + " root event(s) for suffix comparison but found " + rootEvents.size());
            }
            int offset = rootEvents.size() - expectedEvents.size();
            for (int i = 0; i < expectedEvents.size(); i++) {
                assertNodeEquals(readNode(expectedEvents.get(i)), rootEvents.get(offset + i),
                        "Root event suffix " + i);
            }
        }
        if (spec.has("expectedRootEventPathValues")) {
            JsonNode assertions = spec.get("expectedRootEventPathValues");
            if (!assertions.isArray()) {
                throw new AssertionError("expectedRootEventPathValues must be a list");
            }
            for (JsonNode assertion : assertions) {
                int index = requireNonNull(assertion, "index").asInt();
                String path = requireNonNull(assertion, "path").asText();
                if (index < 0 || index >= rootEvents.size()) {
                    throw new AssertionError("Expected root event index " + index + " but only "
                            + rootEvents.size() + " event(s) exist");
                }
                Node actual = nodeAt(rootEvents.get(index), path);
                Node expected = readNode(requireNonNull(assertion, "value"));
                assertNodeEquals(expected, actual, "Root event " + index + " path " + path);
            }
        }
        if (!spec.has("expectedRootEventTypes")) {
            return;
        }
        JsonNode expectedTypes = spec.get("expectedRootEventTypes");
        assertEquals(expectedTypes.size(), rootEvents.size());
        for (int i = 0; i < expectedTypes.size(); i++) {
            Node type = rootEvents.get(i).getType();
            String actual = type != null ? type.getBlueId() : null;
            assertEquals(expectedTypes.get(i).asText(), actual);
        }
    }

    private static List<Node> withoutLeadingProcessorEvents(List<Node> events) {
        int index = 0;
        while (index < events.size() && isProcessorEvent(events.get(index))) {
            index++;
        }
        return index == 0 ? events : events.subList(index, events.size());
    }

    private static boolean isProcessorEvent(Node event) {
        Node type = event != null ? event.getType() : null;
        String blueId = type != null ? type.getBlueId() : null;
        return RuntimeBlueIds.DOCUMENT_PROCESSING_INITIATED.equals(blueId)
                || RuntimeBlueIds.DOCUMENT_PROCESSING_TERMINATED.equals(blueId)
                || RuntimeBlueIds.DOCUMENT_PROCESSING_FATAL_ERROR.equals(blueId)
                || RuntimeBlueIds.DOCUMENT_UPDATE.equals(blueId);
    }

    private static void assertDocumentPaths(JsonNode spec, Node document) {
        JsonNode exists = spec.get("expectedDocumentPathExists");
        if (exists != null && exists.isArray()) {
            for (JsonNode path : exists) {
                Node actual = nodeAt(document, path.asText());
                if (actual == null) {
                    throw new AssertionError("Expected document path to exist: " + path.asText());
                }
            }
        }
        JsonNode absent = spec.get("expectedAbsentDocumentPaths");
        if (absent != null && absent.isArray()) {
            for (JsonNode path : absent) {
                Node actual = nodeAt(document, path.asText());
                if (actual != null) {
                    throw new AssertionError("Expected document path to be absent: " + path.asText()
                            + " but found " + nodeDebug(actual));
                }
            }
        }
        JsonNode paths = spec.get("expectedDocumentPaths");
        if (paths != null && !paths.isNull()) {
            for (Iterator<Map.Entry<String, JsonNode>> it = paths.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> entry = it.next();
                Node actual = nodeAt(document, entry.getKey());
                if (actual == null) {
                    throw new AssertionError("Expected document path " + entry.getKey()
                            + " in " + nodeDebug(document));
                }
                Node expected = readNode(entry.getValue());
                assertNodeEquals(expected, actual, "Document path " + entry.getKey()
                        + " in " + nodeDebug(document));
            }
        }

        JsonNode pathValues = spec.get("expectedDocumentPathValues");
        if (pathValues != null && pathValues.isArray()) {
            for (JsonNode assertion : pathValues) {
                String path = requireNonNull(assertion, "path").asText();
                Node actual = nodeAt(document, path);
                if (actual == null) {
                    throw new AssertionError("Expected document path " + path
                            + " in " + nodeDebug(document));
                }
                Node expected = readNode(requireNonNull(assertion, "value"));
                assertNodeEquals(expected, actual, "Document path " + path
                        + " in " + nodeDebug(document));
            }
        }

        JsonNode absentPathValues = spec.get("expectedAbsentDocumentPathValues");
        if (absentPathValues != null && absentPathValues.isArray()) {
            for (JsonNode assertion : absentPathValues) {
                String path = requireNonNull(assertion, "path").asText();
                Node actual = nodeAt(document, path);
                if (actual == null) {
                    continue;
                }
                Node forbidden = readNode(requireNonNull(assertion, "value"));
                Object actualObject = NodeToMapListOrValue.get(actual);
                Object forbiddenObject = NodeToMapListOrValue.get(forbidden);
                if (forbiddenObject.equals(actualObject)) {
                    throw new AssertionError("Expected document path " + path
                            + " not to equal " + nodeDebug(forbidden));
                }
            }
        }
    }

    private static void assertCheckpointLastEvents(JsonNode spec, Node document) {
        JsonNode expected = spec.get("expectedCheckpointLastEvents");
        if (expected == null || expected.isNull()) {
            return;
        }
        for (Iterator<Map.Entry<String, JsonNode>> it = expected.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = it.next();
            String pointer = "/contracts/checkpoint/lastEvents/" + PointerUtils.escapeSegment(entry.getKey());
            Node actual = nodeAt(document, pointer);
            if (actual == null) {
                throw new AssertionError("Expected checkpoint lastEvent for channel " + entry.getKey());
            }
            assertNodeEquals(readExpectedNode(entry.getValue()), actual, "Checkpoint lastEvent " + entry.getKey());
        }
    }

    private static void assertStatusAndError(JsonNode spec, DocumentProcessingResult result) {
        if (spec.has("expectedStatus")) {
            assertEquals(spec.get("expectedStatus").asText(), actualStatus(result), "Processing status");
        }
        if (spec.has("expectedErrorCategory")) {
            assertEquals(spec.get("expectedErrorCategory").asText(), actualErrorCategory(result), "Error category");
        }
        if (spec.has("expectedErrorCategories")) {
            JsonNode categories = spec.get("expectedErrorCategories");
            String actual = actualErrorCategory(result);
            boolean matched = false;
            for (JsonNode category : categories) {
                if (category.asText().equals(actual)) {
                    matched = true;
                    break;
                }
            }
            assertTrue(matched, "Expected error category " + actual + " to be one of " + categories);
        }
    }

    private static String actualStatus(DocumentProcessingResult result) {
        if (result.status() == null) {
            throw new AssertionError("Processor result did not provide a typed status");
        }
        return result.status().wireValue();
    }

    private static String actualErrorCategory(DocumentProcessingResult result) {
        return result.errorCategory() != null
                ? result.errorCategory().name()
                : null;
    }

    private static void assertStoredObjectKeys(JsonNode spec, Node document) {
        JsonNode expected = spec.get("expectedStoredObjectKeys");
        if (expected == null || expected.isNull()) {
            return;
        }
        for (Iterator<Map.Entry<String, JsonNode>> it = expected.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = it.next();
            Node object = nodeAt(document, entry.getKey());
            assertTrue(object != null && object.getProperties() != null,
                    "Expected object at " + entry.getKey());
            for (JsonNode key : entry.getValue()) {
                assertTrue(object.getProperties().containsKey(key.asText()),
                        "Expected raw object key " + key.asText() + " at " + entry.getKey());
            }
        }
    }

    private static void assertPointerReadsAndWrites(JsonNode spec, Node document) {
        assertPointerListAddressesExistingNodes("expectedPointerReads", spec, document);
        assertPointerListAddressesExistingNodes("expectedPointerWrites", spec, document);
    }

    private static void assertPointerListAddressesExistingNodes(String field, JsonNode spec, Node document) {
        JsonNode pointers = spec.get(field);
        if (pointers == null || !pointers.isArray()) {
            return;
        }
        for (JsonNode pointer : pointers) {
            Node actual = nodeAt(document, pointer.asText());
            assertTrue(actual != null, field + " pointer did not address an existing node: " + pointer.asText());
        }
    }

    private static void assertInitializationContentBlueIdInput(JsonNode spec, Node originalDocument, Node document) {
        if (!spec.has("expectedInitializationContentBlueIdInput")) {
            return;
        }
        JsonNode assertion = spec.get("expectedInitializationContentBlueIdInput");
        String excludesPath = text(assertion, "excludesPath", null);
        if (excludesPath != null) {
            assertTrue(nodeAt(originalDocument, excludesPath) == null,
                    "Initialization Content BlueId input unexpectedly included " + excludesPath);
        }
        Node documentId = nodeAt(document, "/contracts/initialized/documentId");
        assertTrue(documentId != null && documentId.getValue() != null,
                "Initialized marker documentId is missing");
        assertEquals(blueId(originalDocument), String.valueOf(documentId.getValue()),
                "Initialization documentId");
    }

    private static void assertRuntimeInsertionNormalizedValues(JsonNode spec, DocumentProcessingResult result) {
        JsonNode assertions = spec.get("expectedRuntimeInsertionNormalizedValues");
        if (assertions == null || !assertions.isArray()) {
            return;
        }
        for (JsonNode assertion : assertions) {
            Node actual;
            if (assertion.has("path")) {
                actual = nodeAt(result.document(), assertion.get("path").asText());
            } else if (assertion.has("eventIndexFromEnd")) {
                List<Node> events = result.triggeredEvents();
                int indexFromEnd = assertion.get("eventIndexFromEnd").asInt();
                int actualIndex = events.size() - 1 - indexFromEnd;
                actual = actualIndex >= 0 && actualIndex < events.size()
                        ? events.get(actualIndex)
                        : null;
            } else if (assertion.has("nonProcessorEventIndex")) {
                List<Node> events = withoutLeadingProcessorEvents(result.triggeredEvents());
                int index = assertion.get("nonProcessorEventIndex").asInt();
                actual = index >= 0 && index < events.size()
                        ? events.get(index)
                        : null;
            } else {
                List<Node> events = result.triggeredEvents();
                int index = requireNonNull(assertion, "eventIndex").asInt();
                actual = index >= 0 && index < events.size()
                        ? events.get(index)
                        : null;
            }
            assertNodeEquals(readNode(requireNonNull(assertion, "selectedDocumentForm")),
                    selectedDocumentForm(actual),
                    "Runtime insertion normalized value");
        }
    }

    private static Node selectedDocumentForm(Node node) {
        if (node == null) {
            return null;
        }
        Node copy = node.clone();
        if (copy.getType() == null && copy.getValue() instanceof String) {
            copy.type(new Node().blueId("GX7CFUmSDrE2MzptunLCCdZwnuwwrenRQqEnHL4x3uoC"));
        }
        return copy;
    }

    private static void assertGasByteView(JsonNode spec, DocumentProcessingResult result) {
        JsonNode expected = spec.get("expectedGasByteView");
        if (expected == null || expected.isNull()) {
            return;
        }
        assertEquals("selected-document-form-after-runtime-insertion-normalization",
                requireNonNull(expected, "representation").asText(),
                "Gas byte view representation");
        if (expected.has("patchValuePath")) {
            assertTrue(nodeAt(result.document(), expected.get("patchValuePath").asText()) != null,
                    "Gas byte view patch path missing");
        }
        if (expected.has("emittedEventIndex")) {
            int index = expected.get("emittedEventIndex").asInt();
            assertTrue(index >= 0 && index < result.triggeredEvents().size(),
                    "Gas byte view emitted event index missing");
        }
    }

    private static void assertProcessorEventTypes(JsonNode spec, DocumentProcessingResult result) {
        JsonNode expected = spec.get("expectedProcessorEventTypes");
        if (expected == null || expected.isNull()) {
            return;
        }
        assertProcessorEventType(expected, "DocumentUpdate", RuntimeBlueIds.DOCUMENT_UPDATE);
        assertProcessorEventType(expected, "DocumentProcessingInitiated", RuntimeBlueIds.DOCUMENT_PROCESSING_INITIATED);
        assertProcessorEventType(expected, "DocumentProcessingTerminated", RuntimeBlueIds.DOCUMENT_PROCESSING_TERMINATED);
        assertProcessorEventType(expected, "DocumentProcessingFatalError", RuntimeBlueIds.DOCUMENT_PROCESSING_FATAL_ERROR);
        assertTrue(nodeAt(result.document(), "/contracts/initialized/type/blueId") != null
                        || !result.triggeredEvents().isEmpty(),
                "Expected processor-created event evidence in result");
    }

    private static void assertProcessorEventType(JsonNode expected, String name, String blueId) {
        JsonNode node = expected.get(name);
        if (node != null && node.has("blueId")) {
            assertEquals(blueId, node.get("blueId").asText(), name + " BlueId");
        }
    }

    private static void assertTerminationFallback(JsonNode spec, DocumentProcessingResult result) {
        JsonNode expected = spec.get("expectedTerminationFallback");
        if (expected == null || expected.isNull()) {
            return;
        }
        String targetPath = requireNonNull(expected, "targetPath").asText();
        assertTrue(nodeAt(result.document(), targetPath) != null,
                "Termination fallback target path missing: " + targetPath);
        assertTrue(nodeAt(result.document(), "/contracts/terminated/cause") != null,
                "Termination fallback did not produce a terminated marker");
    }

    private static void assertTraceExpectations(JsonNode spec, ScriptedContractsRuntime runtime) {
        if (spec.has("expectedDocumentUpdateOrder")) {
            assertEquals(textArray(spec.get("expectedDocumentUpdateOrder")),
                    requireTrace(runtime).documentUpdateOrder(),
                    "Document Update order");
        }
        if (spec.has("expectedDocumentUpdates")) {
            assertDocumentUpdateTrace(spec.get("expectedDocumentUpdates"), requireTrace(runtime));
        }
        if (spec.has("expectedEmbeddedDeliveryOrder")) {
            assertEmbeddedDeliveryOrder(spec.get("expectedEmbeddedDeliveryOrder"), requireTrace(runtime));
        }
        if (spec.has("expectedTriggeredDeliveryOrder")) {
            assertDeliveryOrder(spec.get("expectedTriggeredDeliveryOrder"),
                    requireTrace(runtime).triggeredDeliveryOrder(),
                    "Triggered delivery order");
        }
        if (spec.has("expectedEffectApplicationOrder")) {
            assertEquals(textArray(spec.get("expectedEffectApplicationOrder")),
                    requireTrace(runtime).effectApplicationOrder(),
                    "Effect application order");
        }
        if (spec.path("expectedTriggeredFifoAfterDocumentUpdates").asBoolean(false)) {
            assertTrue(!requireTrace(runtime).documentUpdateOrder().isEmpty(),
                    "Expected Document Updates before Triggered FIFO");
        }
    }

    private static ScriptedContractsRuntime requireTrace(ScriptedContractsRuntime runtime) {
        if (runtime == null) {
            throw new AssertionError("Fixture expected execution trace, but no scripted runtime trace was collected");
        }
        return runtime;
    }

    private static List<String> textArray(JsonNode array) {
        List<String> values = new ArrayList<>();
        if (array != null && array.isArray()) {
            for (JsonNode item : array) {
                values.add(item.asText());
            }
        }
        return values;
    }

    private static void assertDocumentUpdateTrace(JsonNode expected, ScriptedContractsRuntime runtime) {
        List<ScriptedContractsRuntime.DocumentUpdateTrace> actual = runtime.documentUpdates();
        assertEquals(expected.size(), actual.size(), "Document Update trace count");
        for (int i = 0; i < expected.size(); i++) {
            JsonNode assertion = expected.get(i);
            ScriptedContractsRuntime.DocumentUpdateTrace trace = actual.get(i);
            assertEquals(requireNonNull(assertion, "path").asText(), trace.path(), "Document Update path");
            if (assertion.has("before")) {
                JsonNode before = assertion.get("before");
                if (before.isNull()) {
                    assertEquals(null, trace.before(), "Document Update before");
                } else {
                    assertNodeEquals(readNode(before), trace.before(), "Document Update before");
                }
            }
            if (assertion.has("after")) {
                JsonNode after = assertion.get("after");
                if (after.isNull()) {
                    assertEquals(null, trace.after(), "Document Update after");
                } else {
                    assertNodeEquals(readNode(after), trace.after(), "Document Update after");
                }
            }
        }
    }

    private static void assertEmbeddedDeliveryOrder(JsonNode expected, ScriptedContractsRuntime runtime) {
        if (expected.size() == 0 || expected.get(0).isTextual()) {
            assertEquals(textArray(expected), runtime.embeddedScopeOrder(), "Embedded scope delivery order");
            return;
        }
        assertDeliveryOrder(expected, runtime.embeddedDeliveryOrder(), "Embedded bridge delivery order");
    }

    private static void assertDeliveryOrder(JsonNode expected,
                                            List<ScriptedContractsRuntime.DeliveryTrace> actual,
                                            String message) {
        assertEquals(expected.size(), actual.size(), message + " count");
        for (int i = 0; i < expected.size(); i++) {
            JsonNode item = expected.get(i);
            ScriptedContractsRuntime.DeliveryTrace trace = actual.get(i);
            String event = item.has("event") ? item.get("event").asText()
                    : item.has("emission") ? item.get("emission").asText()
                    : item.asText();
            assertEquals(event, trace.event(), message + " event " + i);
            if (item.has("channels")) {
                assertEquals(textArray(item.get("channels")), trace.channels(), message + " channels " + i);
            }
        }
    }

    private static void validateFixtureMatchesManifest(FixtureEntry fixture, JsonNode spec) {
        validateFixtureMetadata(spec);
        assertEquals(fixture.id, requireNonNull(spec, "id").asText());
        assertEquals(
                BlueContractsFixtureCategory.fromLabel(fixture.category),
                BlueContractsFixtureCategory.fromLabel(requireNonNull(spec, "category").asText()));
    }

    private static void validateFixtureMetadata(JsonNode spec) {
        requireNonNull(spec, "id");
        requireNonNull(spec, "category");
        requireNonNull(spec, "operation");
        validateExpectedFields(spec);
        validateProcessorCapabilities(spec);
        BlueContractsFixtureCategory.fromLabel(requireNonNull(spec, "category").asText());
        String operation = requireNonNull(spec, "operation").asText();
        if ("registryRuntimeTypeBlueIds".equals(operation)) {
            requireNonNull(spec, "expectedRuntimeBlueIds");
        } else if ("changingRegistryDescriptionChangesBlueId".equals(operation)) {
            requireNonNull(spec, "registryKey");
            requireNonNull(spec, "registryPath");
            requireNonNull(spec, "expectedOriginalBlueId");
            requireNonNull(spec, "mutation");
        } else if ("runtimeRegistryPreprocessingEnvironmentReproducible".equals(operation)) {
            requireNonNull(spec, "preprocessingEnvironment");
        } else if ("registryNodeHashesToPublishedBlueId".equals(operation)) {
            requireNonNull(spec, "registryKey");
            requireNonNull(spec, "registryPath");
            requireNonNull(spec, "expectedBlueId");
        } else if ("registryFieldUsesTextBlueIdString".equals(operation)) {
            requireNonNull(spec, "fields");
        } else if ("processDocument".equals(operation)) {
            requireNonNull(spec, "initialDocument");
            if (!hasMeaningfulProcessAssertion(spec)) {
                throw new IllegalArgumentException("processDocument fixtures must assert outputs");
            }
        } else if ("pointerDescendant".equals(operation)) {
            requireNonNull(spec, "path");
            requireNonNull(spec, "ancestor");
            requireNonNull(spec, "expectedDescendantOrEqual");
        } else if ("pointerValidation".equals(operation)) {
            requireNonNull(spec, "pointer");
            requireNonNull(spec, "expectedValid");
        } else {
            throw new IllegalArgumentException("Unsupported Blue Contracts fixture operation: " + operation);
        }
    }

    private static boolean hasMeaningfulProcessAssertion(JsonNode spec) {
        return hasMeaningfulCapabilityFailureAssertion(spec)
                || spec.has("expectedTotalGas")
                || spec.has("expectedExactGas")
                || spec.has("expectedTotalGasMin")
                || spec.has("expectedDocument")
                || spec.has("expectedDocumentPaths")
                || spec.has("expectedDocumentPathValues")
                || spec.has("expectedDocumentPathExists")
                || spec.has("expectedAbsentDocumentPaths")
                || spec.has("expectedAbsentDocumentPathValues")
                || spec.has("expectedRootEventCount")
                || spec.has("expectedRootEvents")
                || spec.has("expectedRootEventTypes")
                || spec.has("expectedRootEventPathValues")
                || spec.has("expectedStatus")
                || spec.has("expectedErrorCategory")
                || spec.has("expectedErrorCategories")
                || spec.has("expectedFailureReasonContains")
                || spec.has("expectedNoDocumentMutation")
                || spec.has("expectedCheckpointLastEvents")
                || spec.has("expectedDocumentUpdateOrder")
                || spec.has("expectedDocumentUpdates")
                || spec.has("expectedEmbeddedDeliveryOrder")
                || spec.has("expectedEffectApplicationOrder")
                || spec.has("expectedTriggeredDeliveryOrder")
                || spec.has("expectedTriggeredFifoAfterDocumentUpdates")
                || spec.has("expectedRuntimeInsertionNormalizedValues")
                || spec.has("expectedGasByteView")
                || spec.has("expectedProcessorEventTypes")
                || spec.has("expectedInitializationContentBlueIdInput")
                || spec.has("expectedPointerReads")
                || spec.has("expectedPointerWrites")
                || spec.has("expectedStoredObjectKeys")
                || spec.has("expectedTerminationFallback");
    }

    private static void validateExpectedFields(JsonNode spec) {
        for (Iterator<String> it = spec.fieldNames(); it.hasNext(); ) {
            String field = it.next();
            if (field.startsWith("expected") && !SUPPORTED_EXPECTED_FIELDS.contains(field)) {
                throw new IllegalArgumentException("Unsupported expected fixture field: " + field);
            }
        }
    }

    private static void validateProcessorCapabilities(JsonNode spec) {
        JsonNode capabilities = spec.get("processorCapabilities");
        if (capabilities == null || capabilities.isNull()) {
            return;
        }
        if (!capabilities.isArray()) {
            throw new IllegalArgumentException("processorCapabilities must be a list");
        }
        for (JsonNode capability : capabilities) {
            if (!SUPPORTED_PROCESSOR_CAPABILITIES.contains(capability.asText())) {
                throw new IllegalArgumentException("Unsupported processor capability: " + capability.asText());
            }
        }
    }

    private static boolean hasMeaningfulCapabilityFailureAssertion(JsonNode spec) {
        JsonNode expected = spec.get("expectedCapabilityFailure");
        if (expected == null || !expected.asBoolean(false)) {
            return false;
        }
        return spec.path("expectedNoDocumentMutation").asBoolean(false)
                || isZero(spec.get("expectedTotalGas"))
                || isZero(spec.get("expectedExactGas"))
                || isZero(spec.get("expectedRootEventCount"))
                || isEmptyArray(spec.get("expectedRootEvents"))
                || spec.has("expectedFailureReasonContains");
    }

    private static boolean isZero(JsonNode node) {
        return node != null && node.isNumber() && node.asLong() == 0L;
    }

    private static boolean isEmptyArray(JsonNode node) {
        return node != null && node.isArray() && node.size() == 0;
    }

    private static BlueContractsConformanceFailure failure(FixtureEntry fixture, Throwable throwable) {
        String operation = null;
        try {
            operation = text(readResource(FIXTURE_ROOT + fixture.path), "operation", null);
        } catch (RuntimeException ignored) {
        }
        return new BlueContractsConformanceFailure(
                fixture.id,
                BlueContractsFixtureCategory.fromLabel(fixture.category),
                operation,
                throwable.getClass().getName(),
                throwable.getMessage());
    }

    private static JsonNode readResource(String resource) {
        try (InputStream inputStream = BlueContractsConformanceSuiteRunner.class.getClassLoader()
                .getResourceAsStream(resource)) {
            if (inputStream == null) {
                throw new IllegalArgumentException("Missing Blue Contracts fixture resource: " + resource);
            }
            return UncheckedObjectMapper.YAML_MAPPER.readTree(inputStream);
        } catch (Exception e) {
            throw new IllegalArgumentException("Unable to read Blue Contracts fixture resource: " + resource, e);
        }
    }

    private static Node readNode(JsonNode node) {
        try {
            return UncheckedObjectMapper.JSON_MAPPER.convertValue(node, Node.class);
        } catch (IllegalArgumentException ex) {
            JsonNode value = node != null && node.isObject() ? node.get("value") : null;
            if (value != null && (value.isObject() || value.isArray())) {
                return readNode(value);
            }
            JsonNode unwrapped = unwrapObjectValueWrappers(node);
            if (unwrapped != node) {
                return UncheckedObjectMapper.JSON_MAPPER.convertValue(unwrapped, Node.class);
            }
            throw ex;
        }
    }

    private static JsonNode unwrapObjectValueWrappers(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isObject()) {
            JsonNode value = node.get("value");
            if (node.size() == 1 && value != null && (value.isObject() || value.isArray())) {
                return unwrapObjectValueWrappers(value);
            }
            ObjectNode copy = UncheckedObjectMapper.JSON_MAPPER.createObjectNode();
            for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> entry = it.next();
                copy.set(entry.getKey(), unwrapObjectValueWrappers(entry.getValue()));
            }
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = UncheckedObjectMapper.JSON_MAPPER.createArrayNode();
            for (JsonNode item : node) {
                copy.add(unwrapObjectValueWrappers(item));
            }
            return copy;
        }
        return node;
    }

    private static Node readExpectedNode(JsonNode node) {
        return readNode(node);
    }

    private static Node readRegistryNode(String registryPath) {
        String normalized = registryPath.startsWith("/") ? registryPath.substring(1) : registryPath;
        try (InputStream inputStream = BlueContractsConformanceSuiteRunner.class.getClassLoader()
                .getResourceAsStream(normalized)) {
            if (inputStream == null) {
                throw new IllegalArgumentException("Missing runtime registry resource: " + normalized);
            }
            return UncheckedObjectMapper.YAML_MAPPER.readValue(inputStream, Node.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Unable to read runtime registry resource: " + normalized, e);
        }
    }

    private static String blueId(Node node) {
        return blue.language.utils.BlueIdCalculator.calculateUncheckedBlueId(node);
    }

    private static RuntimeTypeKey runtimeTypeKey(String key) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < key.length(); i++) {
            char ch = key.charAt(i);
            if (Character.isUpperCase(ch) && i > 0) {
                result.append('_');
            }
            result.append(Character.toUpperCase(ch));
        }
        return RuntimeTypeKey.valueOf(result.toString());
    }

    private static ScriptedFixtureTypes discoverScriptedRuntimeTypes(JsonNode spec, Node document) {
        ScriptedFixtureTypes types = new ScriptedFixtureTypes();
        types.addChannel(MockTypeBlueIds.MOCK_EXTERNAL_CHANNEL);
        types.addChannel(MockTypeBlueIds.LEGACY_MOCK_EXTERNAL_CHANNEL);
        types.addHandler(MockTypeBlueIds.MOCK_HANDLER);
        types.addHandler(MockTypeBlueIds.LEGACY_MOCK_HANDLER);
        JsonNode mockRuntime = spec.get("mockRuntime");
        JsonNode typeGraph = spec.get("typeGraph");
        if (typeGraph != null && typeGraph.isObject()) {
            Map<String, String> blueIdsByName = new LinkedHashMap<>();
            for (Iterator<Map.Entry<String, JsonNode>> it = typeGraph.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> entry = it.next();
                JsonNode blueId = entry.getValue().get("blueId");
                if (blueId != null && !blueId.isNull()) {
                    blueIdsByName.put(entry.getKey(), blueId.asText());
                }
            }
            for (Iterator<Map.Entry<String, JsonNode>> it = typeGraph.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> entry = it.next();
                String blueId = blueIdsByName.get(entry.getKey());
                if (blueId != null) {
                    types.addExternalType(blueId, fixtureTypeNode(entry.getKey(), blueId, entry.getValue(), blueIdsByName));
                }
            }
        }
        if (mockRuntime == null || mockRuntime.isNull()) {
            return types;
        }
        JsonNode channels = mockRuntime.get("channels");
        if (channels != null && channels.isArray()) {
            for (JsonNode channel : channels) {
                String contractPath = requireNonNull(channel, "contract").asText();
                Node contract = NodePathEditor.getOrNull(document, contractPath);
                String typeBlueId = typeBlueId(contract);
                if (typeBlueId != null) {
                    types.addChannel(typeBlueId);
                }
            }
        }
        JsonNode handlers = mockRuntime.get("handlers");
        if (handlers != null && handlers.isArray()) {
            for (JsonNode handler : handlers) {
                String contractPath = requireNonNull(handler, "contract").asText();
                Node contract = NodePathEditor.getOrNull(document, contractPath);
                String typeBlueId = typeBlueId(contract);
                if (typeBlueId != null) {
                    types.addHandler(typeBlueId);
                }
            }
        }
        return types;
    }

    private static String typeBlueId(Node contract) {
        return contract != null && contract.getType() != null ? contract.getType().getBlueId() : null;
    }

    private static NodeProvider mockTypeProvider(ScriptedFixtureTypes fixtureTypes) {
        /*
         * Fixture-only provider for mock external channel/handler contracts.
         * Production processor-managed runtime types are resolved through
         * BlueRuntimeTypeRegistry; this provider is installed only by the
         * conformance runner for fixture-declared mock type BlueIds.
         */
        NodeProvider provider = blueId -> {
            Node externalType = fixtureTypes.externalTypeNodesByBlueId.get(blueId);
            if (externalType != null) {
                return Collections.singletonList(externalType.clone());
            }
            if (fixtureTypes.channelTypeBlueIds.contains(blueId)) {
                return Collections.singletonList(mockTypeNode("MockExternalChannel", blueId));
            }
            if (fixtureTypes.handlerTypeBlueIds.contains(blueId)) {
                return Collections.singletonList(mockTypeNode("MockHandler", blueId));
            }
            return null;
        };
        return NodeProviderWrapper.unverified(provider);
    }

    private static Node mockTypeNode(String name, String blueId) {
        return new Node().blueId(blueId).name(name);
    }

    private static Node fixtureTypeNode(String name, String blueId, JsonNode spec, Map<String, String> blueIdsByName) {
        Node node = new Node().blueId(blueId).name(name);
        JsonNode parent = spec.get("parent");
        if (parent != null && !parent.isNull()) {
            String parentBlueId = blueIdsByName.get(parent.asText());
            if (parentBlueId == null) {
                throw new IllegalArgumentException("Unknown fixture type parent: " + parent.asText());
            }
            node.type(new Node().blueId(parentBlueId));
        }
        JsonNode fixedValues = spec.get("fixedValues");
        if (fixedValues != null && fixedValues.isObject()) {
            for (Iterator<Map.Entry<String, JsonNode>> it = fixedValues.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> entry = it.next();
                NodePathEditor.put(node, entry.getKey(), readNode(entry.getValue()));
            }
        }
        JsonNode fields = spec.get("fields");
        if (fields != null && fields.isObject()) {
            for (Iterator<Map.Entry<String, JsonNode>> it = fields.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> entry = it.next();
                JsonNode fieldType = entry.getValue().get("type");
                if (fieldType == null || fieldType.isNull()) {
                    continue;
                }
                String fieldTypeBlueId = blueIdsByName.get(fieldType.asText());
                if (fieldTypeBlueId == null) {
                    throw new IllegalArgumentException("Unknown fixture field type: " + fieldType.asText());
                }
                NodePathEditor.put(node, entry.getKey(), new Node().type(new Node().blueId(fieldTypeBlueId)));
            }
        }
        return node;
    }

    private static final class ScriptedFixtureTypes {
        final Set<String> channelTypeBlueIds = new LinkedHashSet<>();
        final Set<String> handlerTypeBlueIds = new LinkedHashSet<>();
        final Set<String> allTypeBlueIds = new LinkedHashSet<>();
        final Map<String, Node> externalTypeNodesByBlueId = new LinkedHashMap<>();
        final Map<String, String> externalTypeBlueIdsByName = new LinkedHashMap<>();

        void addChannel(String blueId) {
            channelTypeBlueIds.add(blueId);
            allTypeBlueIds.add(blueId);
        }

        void addHandler(String blueId) {
            handlerTypeBlueIds.add(blueId);
            allTypeBlueIds.add(blueId);
        }

        void addExternalType(String blueId, Node node) {
            externalTypeNodesByBlueId.put(blueId, node);
            if (node.getName() != null) {
                externalTypeBlueIdsByName.put(node.getName(), blueId);
            }
            allTypeBlueIds.add(blueId);
        }

        String externalTypeBlueId(String name) {
            return name != null ? externalTypeBlueIdsByName.get(name) : null;
        }
    }

    private static JsonNode requireNonNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            throw new IllegalArgumentException("Fixture field \"" + field + "\" is required.");
        }
        return value;
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? fallback : value.asText();
    }

    private static void assertNodeEquals(Node expected, Node actual, String message) {
        if (expected == null || actual == null) {
            assertEquals(expected, actual, message);
            return;
        }
        Object expectedObject = NodeToMapListOrValue.get(expected);
        Object actualObject = NodeToMapListOrValue.get(actual);
        assertEquals(expectedObject, actualObject, message);
    }

    private static void assertFailureReasonContains(JsonNode spec, String actualReason) {
        assertFailureReasonContains(spec, actualReason, null);
    }

    private static void assertFailureReasonContains(JsonNode spec, String actualReason, Node document) {
        if (!spec.has("expectedFailureReasonContains")) {
            return;
        }
        String expected = spec.get("expectedFailureReasonContains").asText();
        if (actualReason != null && actualReason.contains(expected)) {
            return;
        }
        if (document != null && nodeDebug(document).contains(expected)) {
            return;
        }
        throw new AssertionError("Expected failure reason to contain <" + expected
                + "> but was <" + actualReason + ">");
    }

    private static Node nodeAt(Node document, String pointer) {
        try {
            if (pointer == null || !pointer.startsWith("/")) {
                throw new IllegalArgumentException("Invalid path: " + pointer);
            }
            if ("/".equals(pointer)) {
                return document;
            }
            Node current = document;
            for (String segment : JsonPointer.split(pointer)) {
                if (current == null) {
                    return null;
                }
                if (current.getProperties() != null && current.getProperties().containsKey(segment)) {
                    current = current.getProperties().get(segment);
                } else if (JsonPointer.isArrayIndexSegment(segment) && current.getItems() != null) {
                    int index = Integer.parseInt(segment);
                    current = index >= 0 && index < current.getItems().size()
                            ? current.getItems().get(index)
                            : null;
                } else if ("type".equals(segment)) {
                    current = current.getType();
                } else if ("itemType".equals(segment)) {
                    current = current.getItemType();
                } else if ("keyType".equals(segment)) {
                    current = current.getKeyType();
                } else if ("valueType".equals(segment)) {
                    current = current.getValueType();
                } else if ("value".equals(segment)) {
                    current = current.getRawValue() != null ? new Node().value(current.getRawValue()) : null;
                } else if ("blueId".equals(segment)) {
                    current = new Node().value(blueId(current));
                } else if ("contracts".equals(segment)) {
                    current = current.getContracts();
                } else {
                    return null;
                }
            }
            return current;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static String nodeDebug(Node node) {
        try {
            return UncheckedObjectMapper.JSON_MAPPER.writeValueAsString(NodeToMapListOrValue.get(node));
        } catch (Exception ex) {
            return String.valueOf(node);
        }
    }

    private static void assertEquals(Object expected, Object actual) {
        assertEquals(expected, actual, null);
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError((message != null ? message + ": " : "")
                    + "expected <" + expected + "> but was <" + actual + ">");
        }
    }

    private static void assertTrue(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }

    private static final class FixtureEntry {
        private final String id;
        private final String category;
        private final String path;

        FixtureEntry(String id, String category, String path) {
            this.id = id;
            this.category = category;
            this.path = path;
        }
    }
}
