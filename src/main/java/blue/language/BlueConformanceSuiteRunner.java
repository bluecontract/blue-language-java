package blue.language;

import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.snapshot.FrozenNode;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.CircularBlueIdCalculator;
import blue.language.utils.Nodes;
import blue.language.utils.UncheckedObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class BlueConformanceSuiteRunner {

    private static final String FIXTURE_ROOT = "blue-language-1.0/fixtures/";
    private static final Set<String> OPERATIONS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "parseSource",
            "parseBlueIdInput",
            "calculateBlueId",
            "calculateCircularSetBlueIds",
            "preprocess",
            "resolve",
            "canonicalize",
            "calculateContentBlueId",
            "calculateSemanticBlueId",
            "expand",
            "collapse",
            "assertSameNodeBlueId"
    )));

    private BlueConformanceSuiteRunner() {
    }

    public static BlueConformanceReport run(Blue blue) {
        BlueConformanceReport metadata = blue.conformanceReport();
        List<String> passed = new ArrayList<>();
        List<BlueConformanceFailure> failures = new ArrayList<>();
        for (FixtureEntry fixture : fixtureEntries()) {
            try {
                runFixture(fixture);
                passed.add(fixture.id);
            } catch (RuntimeException | AssertionError e) {
                failures.add(failure(fixture, e));
            }
        }
        return new BlueConformanceReport(
                metadata.getSpecVersion(),
                metadata.getCoreRegistryBlueIds(),
                metadata.getFixturePackageIdentity(),
                metadata.getFixtureIds(),
                passed,
                Collections.emptyList(),
                metadata.getFixtureCategories(),
                failures);
    }

    public static Set<String> knownOperations() {
        return OPERATIONS;
    }

    public static void validateFixtureMetadataForTest(JsonNode spec) {
        validateFixtureMetadata(spec);
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
            BlueFixtureCategory.fromLabel(category);
            String path = requireNonNull(entry, "path").asText();
            entries.add(new FixtureEntry(id, category, path));
        }
        return entries;
    }

    private static void runFixture(FixtureEntry fixture) {
        JsonNode spec = readResource(FIXTURE_ROOT + fixture.path);
        validateFixtureMatchesManifest(fixture, spec);
        String operation = text(spec, "operation", "calculateBlueId");
        boolean expectError = spec.path("expectError").asBoolean(false);
        if (expectError) {
            try {
                runOperation(spec, operation);
            } catch (RuntimeException expected) {
                return;
            }
            throw new AssertionError("Fixture expected an error but operation succeeded: " + fixture.id);
        }

        Object actual = runOperation(spec, operation);
        if ("calculateBlueId".equals(operation)
                || "assertSameNodeBlueId".equals(operation)) {
            assertExpectedText(spec, "expectedNodeBlueId", (String) actual);
            if (!"assertSameNodeBlueId".equals(operation)) {
                assertEquivalents((String) actual, spec.get("alsoEquivalentTo"));
                assertDifferent((String) actual, spec.get("alsoDifferentFrom"));
            }
        } else if ("calculateCircularSetBlueIds".equals(operation)) {
            assertExpectedTextList(spec, "expectedBlueIds", (List<String>) actual);
        } else if ("calculateContentBlueId".equals(operation) || "calculateSemanticBlueId".equals(operation)) {
            assertExpectedText(spec, "expectedContentBlueId", (String) actual);
        } else if ("parseSource".equals(operation) || "parseBlueIdInput".equals(operation)) {
            assertExpectedNode(spec, "expectedParsed", (Node) actual);
        } else if ("preprocess".equals(operation)) {
            assertExpectedNode(spec, "expectedPreprocessed", (Node) actual);
        } else if ("canonicalize".equals(operation)) {
            assertExpectedNode(spec, "expectedCanonicalOverlay", (Node) actual);
            assertCanonicalOverlayIsValidBlueIdInput((Node) actual);
        } else if ("resolve".equals(operation)) {
            assertExpectedNode(spec, "expectedResolved", (Node) actual);
        } else if ("expand".equals(operation)) {
            assertExpectedNode(spec, "expectedExpanded", (Node) actual);
            assertExpectedNodeBlueIdIfPresent(spec, (Node) actual, requirePresent(spec, "source"));
        } else if ("collapse".equals(operation)) {
            assertExpectedNode(spec, "expectedCollapsed", (Node) actual);
            assertExpectedNodeBlueIdIfPresent(spec, (Node) actual, requirePresent(spec, "source"));
        }
    }

    private static Object runOperation(JsonNode spec, String operation) {
        Blue blue = new Blue(provider(spec.get("provider")));
        if ("parseSource".equals(operation)) {
            return blue.parseSourceYaml(UncheckedObjectMapper.YAML_MAPPER.writeValueAsString(requirePresent(spec, "source")));
        }
        if ("parseBlueIdInput".equals(operation)) {
            return blue.parseBlueIdInputYaml(UncheckedObjectMapper.YAML_MAPPER.writeValueAsString(requirePresent(spec, "input")));
        }
        if ("calculateBlueId".equals(operation)) {
            Node input = readNode(requirePresent(spec, "input"));
            String blueId = BlueIdCalculator.calculateBlueId(input);
            assertEquals(blueId, FrozenNode.fromNode(input).blueId());
            return blueId;
        }
        if ("calculateCircularSetBlueIds".equals(operation)) {
            Node documents = readNode(requirePresent(spec, "documents"));
            if (documents.getItems() == null) {
                throw new IllegalArgumentException("calculateCircularSetBlueIds fixtures require a documents list.");
            }
            return CircularBlueIdCalculator.calculateCircularSetBlueIds(documents.getItems());
        }
        if ("preprocess".equals(operation)) {
            return blue.preprocess(readNode(requirePresent(spec, "source")));
        }
        if ("resolve".equals(operation)) {
            return blue.resolve(readNode(requirePresent(spec, "source")));
        }
        if ("canonicalize".equals(operation)) {
            return blue.canonicalize(readNode(requirePresent(spec, "source")));
        }
        if ("calculateContentBlueId".equals(operation) || "calculateSemanticBlueId".equals(operation)) {
            return blue.calculateSemanticBlueId(readNode(requirePresent(spec, "source")));
        }
        if ("expand".equals(operation)) {
            return blue.expand(readNode(requirePresent(spec, "source")));
        }
        if ("collapse".equals(operation)) {
            return blue.collapse(readNode(requirePresent(spec, "source")));
        }
        if ("assertSameNodeBlueId".equals(operation)) {
            String left = BlueIdCalculator.calculateBlueId(readNode(requirePresent(spec, "left")));
            String right = BlueIdCalculator.calculateBlueId(readNode(requirePresent(spec, "right")));
            assertEquals(left, right);
            return left;
        }
        throw new IllegalArgumentException("Unsupported fixture operation: " + operation);
    }

    private static NodeProvider provider(JsonNode providerSpec) {
        if (providerSpec == null || providerSpec.isNull()) {
            return blueId -> null;
        }
        if (!providerSpec.isArray()) {
            throw new IllegalArgumentException("Fixture provider must be a list.");
        }
        Map<String, Node> nodesByBlueId = new LinkedHashMap<>();
        for (JsonNode entry : providerSpec) {
            String requestedBlueId = text(entry, "requestedBlueId", text(entry, "blueId", null));
            JsonNode nodeSpec = entry.has("returnedNode") ? entry.get("returnedNode") : entry.get("node");
            if (requestedBlueId == null || nodeSpec == null || nodeSpec.isNull()) {
                throw new IllegalArgumentException("Fixture provider entries require requestedBlueId and node/returnedNode.");
            }
            nodesByBlueId.put(requestedBlueId, readNode(nodeSpec));
        }
        return blueId -> {
            Node node = nodesByBlueId.get(blueId);
            return node == null ? null : Collections.singletonList(node.clone());
        };
    }

    private static void validateFixtureMatchesManifest(FixtureEntry fixture, JsonNode spec) {
        validateFixtureMetadata(spec);
        assertEquals(fixture.id, requireNonNull(spec, "id").asText());
        assertEquals(
                BlueFixtureCategory.fromLabel(fixture.category),
                BlueFixtureCategory.fromLabel(requireNonNull(spec, "category").asText()));
    }

    private static void validateFixtureMetadata(JsonNode spec) {
        requireNonNull(spec, "id");
        requireNonNull(spec, "category");
        requireNonNull(spec, "operation");
        if (spec.has("profile")) {
            throw new IllegalArgumentException("Fixtures must use category, not profile.");
        }
        BlueFixtureCategory.fromLabel(requireNonNull(spec, "category").asText());
        String operation = requireNonNull(spec, "operation").asText();
        if (!OPERATIONS.contains(operation)) {
            throw new IllegalArgumentException("Unsupported fixture operation: " + operation);
        }
        if (!spec.path("expectError").asBoolean(false)) {
            requireExpectedOutput(spec, operation);
        }
    }

    private static BlueConformanceFailure failure(FixtureEntry fixture, Throwable throwable) {
        String operation = null;
        try {
            operation = text(readResource(FIXTURE_ROOT + fixture.path), "operation", null);
        } catch (RuntimeException ignored) {
            // The fixture may be unreadable; keep the manifest-level failure details.
        }
        return new BlueConformanceFailure(
                fixture.id,
                BlueFixtureCategory.fromLabel(fixture.category),
                operation,
                throwable.getClass().getName(),
                throwable.getMessage());
    }

    private static void requireExpectedOutput(JsonNode spec, String operation) {
        if ("calculateBlueId".equals(operation)
                || "assertSameNodeBlueId".equals(operation)) {
            requireNonNull(spec, "expectedNodeBlueId");
            return;
        }
        if ("calculateCircularSetBlueIds".equals(operation)) {
            requireNonNull(spec, "expectedBlueIds");
            return;
        }
        if ("calculateContentBlueId".equals(operation) || "calculateSemanticBlueId".equals(operation)) {
            requireNonNull(spec, "expectedContentBlueId");
            return;
        }
        if ("parseSource".equals(operation) || "parseBlueIdInput".equals(operation)) {
            requireNonNull(spec, "expectedParsed");
            return;
        }
        if ("preprocess".equals(operation)) {
            requireNonNull(spec, "expectedPreprocessed");
            return;
        }
        if ("canonicalize".equals(operation)) {
            requireNonNull(spec, "expectedCanonicalOverlay");
            return;
        }
        if ("resolve".equals(operation)) {
            requireNonNull(spec, "expectedResolved");
            return;
        }
        if ("expand".equals(operation)) {
            requireNonNull(spec, "expectedExpanded");
            return;
        }
        if ("collapse".equals(operation)) {
            requireNonNull(spec, "expectedCollapsed");
            return;
        }
        throw new IllegalArgumentException("Unsupported fixture operation: " + operation);
    }

    private static void assertExpectedText(JsonNode spec, String field, String actual) {
        assertEquals(requireNonNull(spec, field).asText(), actual);
    }

    private static void assertExpectedTextList(JsonNode spec, String field, List<String> actual) {
        JsonNode expected = requireNonNull(spec, field);
        if (!expected.isArray()) {
            throw new AssertionError("Expected fixture field \"" + field + "\" to be a list.");
        }
        List<String> expectedValues = new ArrayList<>();
        for (JsonNode value : expected) {
            expectedValues.add(value.asText());
        }
        assertEquals(expectedValues, actual);
    }

    private static void assertExpectedNode(JsonNode spec, String field, Node actual) {
        JsonNode expected = UncheckedObjectMapper.YAML_MAPPER.readTree(
                UncheckedObjectMapper.YAML_MAPPER.writeValueAsString(readNode(requireNonNull(spec, field))));
        JsonNode actualTree = UncheckedObjectMapper.YAML_MAPPER.readTree(
                UncheckedObjectMapper.YAML_MAPPER.writeValueAsString(actual));
        assertEquals(expected, actualTree);
    }

    private static void assertExpectedNodeBlueIdIfPresent(JsonNode spec, Node actual, JsonNode sourceSpec) {
        JsonNode expected = spec.get("expectedNodeBlueId");
        if (expected == null || expected.isNull()) {
            return;
        }
        String expectedBlueId = expected.asText();
        assertEquals(expectedBlueId, BlueIdCalculator.calculateBlueId(actual));
        assertEquals(expectedBlueId, BlueIdCalculator.calculateBlueId(readNode(sourceSpec)));
    }

    private static void assertEquivalents(String actualBlueId, JsonNode equivalents) {
        if (equivalents == null || equivalents.isNull()) {
            return;
        }
        if (equivalents.isArray()) {
            for (JsonNode equivalent : equivalents) {
                assertEquals(actualBlueId, BlueIdCalculator.calculateBlueId(readNode(equivalent)));
            }
        } else {
            assertEquals(actualBlueId, BlueIdCalculator.calculateBlueId(readNode(equivalents)));
        }
    }

    private static void assertDifferent(String actualBlueId, JsonNode differentInputs) {
        if (differentInputs == null || differentInputs.isNull()) {
            return;
        }
        if (differentInputs.isArray()) {
            for (JsonNode different : differentInputs) {
                assertNotEquals(actualBlueId, BlueIdCalculator.calculateBlueId(readNode(different)));
            }
        } else {
            assertNotEquals(actualBlueId, BlueIdCalculator.calculateBlueId(readNode(differentInputs)));
        }
    }

    private static void assertCanonicalOverlayIsValidBlueIdInput(Node canonical) {
        BlueIdCalculator.calculateBlueId(canonical);
        assertNoCanonicalOverlayControls(canonical, "/", false);
    }

    private static void assertNoCanonicalOverlayControls(Node node, String path, boolean listElement) {
        if (node == null) {
            if (listElement) {
                throw new AssertionError("Canonical Overlay contains null list element at " + path);
            }
            return;
        }
        if (node.getBlue() != null) {
            throw new AssertionError("Canonical Overlay contains blue at " + path);
        }
        if (node.getPreviousBlueId() != null) {
            throw new AssertionError("Canonical Overlay contains $previous at " + path);
        }
        if (node.getPosition() != null) {
            throw new AssertionError("Canonical Overlay contains $pos at " + path);
        }
        if (node.getProperties() != null && node.getProperties().containsKey("$replace")) {
            throw new AssertionError("Canonical Overlay contains $replace at " + path);
        }
        if (listElement && Nodes.isEmptyNode(node) && !Nodes.isEmptyPlaceholder(node)) {
            throw new AssertionError("Canonical Overlay contains empty-object list element at " + path);
        }
        assertNoCanonicalOverlayControls(node.getType(), appendPath(path, "type"), false);
        assertNoCanonicalOverlayControls(node.getItemType(), appendPath(path, "itemType"), false);
        assertNoCanonicalOverlayControls(node.getKeyType(), appendPath(path, "keyType"), false);
        assertNoCanonicalOverlayControls(node.getValueType(), appendPath(path, "valueType"), false);
        assertNoCanonicalOverlayControls(node.getBlue(), appendPath(path, "blue"), false);
        assertNoCanonicalOverlayControls(node.getContracts(), appendPath(path, "contracts"), false);
        assertNoCanonicalOverlayControls(node.getSchema(), appendPath(path, "schema"));
        if (node.getItems() != null) {
            for (int i = 0; i < node.getItems().size(); i++) {
                assertNoCanonicalOverlayControls(node.getItems().get(i), appendPath(path, String.valueOf(i)), true);
            }
        }
        if (node.getProperties() != null) {
            node.getProperties().forEach((key, value) ->
                    assertNoCanonicalOverlayControls(value, appendPath(path, key), false));
        }
    }

    private static void assertNoCanonicalOverlayControls(Schema schema, String path) {
        if (schema == null) {
            return;
        }
        assertNoCanonicalOverlayControls(schema.getRequired(), appendPath(path, "required"), false);
        assertNoCanonicalOverlayControls(schema.getMinLength(), appendPath(path, "minLength"), false);
        assertNoCanonicalOverlayControls(schema.getMaxLength(), appendPath(path, "maxLength"), false);
        assertNoCanonicalOverlayControls(schema.getMinimum(), appendPath(path, "minimum"), false);
        assertNoCanonicalOverlayControls(schema.getMaximum(), appendPath(path, "maximum"), false);
        assertNoCanonicalOverlayControls(schema.getExclusiveMinimum(), appendPath(path, "exclusiveMinimum"), false);
        assertNoCanonicalOverlayControls(schema.getExclusiveMaximum(), appendPath(path, "exclusiveMaximum"), false);
        assertNoCanonicalOverlayControls(schema.getMultipleOf(), appendPath(path, "multipleOf"), false);
        assertNoCanonicalOverlayControls(schema.getMinItems(), appendPath(path, "minItems"), false);
        assertNoCanonicalOverlayControls(schema.getMaxItems(), appendPath(path, "maxItems"), false);
        assertNoCanonicalOverlayControls(schema.getUniqueItems(), appendPath(path, "uniqueItems"), false);
        assertNoCanonicalOverlayControls(schema.getMinFields(), appendPath(path, "minFields"), false);
        assertNoCanonicalOverlayControls(schema.getMaxFields(), appendPath(path, "maxFields"), false);
        if (schema.getEnum() != null) {
            for (int i = 0; i < schema.getEnum().size(); i++) {
                assertNoCanonicalOverlayControls(schema.getEnum().get(i), appendPath(path, "enum/" + i), false);
            }
        }
    }

    private static JsonNode readResource(String resource) {
        try (InputStream inputStream = BlueConformanceSuiteRunner.class.getClassLoader()
                .getResourceAsStream(resource)) {
            if (inputStream == null) {
                throw new IllegalArgumentException("Missing fixture resource: " + resource);
            }
            return UncheckedObjectMapper.YAML_MAPPER.readTree(inputStream);
        } catch (Exception e) {
            throw new IllegalArgumentException("Unable to read fixture resource: " + resource, e);
        }
    }

    private static Node readNode(JsonNode node) {
        return UncheckedObjectMapper.YAML_MAPPER.treeToValue(node, Node.class);
    }

    private static JsonNode requirePresent(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null) {
            throw new IllegalArgumentException("Fixture is missing required field: " + field);
        }
        return value;
    }

    private static JsonNode requireNonNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            throw new IllegalArgumentException("Fixture is missing required field: " + field);
        }
        return value;
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? fallback : value.asText();
    }

    private static void assertEquals(Object expected, Object actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError("Expected " + expected + " but was " + actual);
        }
    }

    private static void assertNotEquals(Object unexpected, Object actual) {
        if (unexpected == null ? actual == null : unexpected.equals(actual)) {
            throw new AssertionError("Did not expect " + actual);
        }
    }

    private static String appendPath(String path, String segment) {
        if (path == null || path.isEmpty() || "/".equals(path)) {
            return "/" + segment;
        }
        return path + "/" + segment;
    }

    private static final class FixtureEntry {
        private final String id;
        private final String category;
        private final String path;

        private FixtureEntry(String id, String category, String path) {
            this.id = id;
            this.category = category;
            this.path = path;
        }
    }
}
