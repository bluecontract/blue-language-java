package blue.language.snapshot;

import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.utils.BlueIdCalculator;
import blue.language.Blue;
import blue.language.utils.NodeToBlueIdInput;
import blue.language.utils.Nodes;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.Array;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static blue.language.processor.FailureCapture.captureFailure;
import static blue.language.utils.UncheckedObjectMapper.YAML_MAPPER;
import static blue.language.utils.Properties.DOUBLE_TYPE_BLUE_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrozenNodeTest {

    @Test
    void shouldMatchMutableBlueIdCalculatorForObjectsScalarsAndPureReferences() {
        // given
        String referenceBlueId = BlueIdCalculator.calculateBlueId(new Node().value("reference"));
        Node node = YAML_MAPPER.readValue(
                "name: Product\n" +
                "count: 1\n" +
                "nested:\n" +
                "  label: abc\n" +
                "ref:\n" +
                "  blueId: " + referenceBlueId, Node.class);

        // when
        FrozenNode frozen = FrozenNode.fromNode(node);

        // then
        assertEquals(BlueIdCalculator.calculateBlueId(node), frozen.blueId());
        assertEquals(referenceBlueId, FrozenNode.fromNode(new Node().blueId(referenceBlueId)).blueId());
    }

    @Test
    void shouldMatchBlueIdCalculatorForEveryFrozenNodeFixture() throws Exception {
        // given
        JsonNode manifest = readFixtureResource("manifest.yaml");
        // when
        for (JsonNode entry : behaviorFixtureEntries(manifest)) {
            JsonNode fixture = readFixtureResource(entry.get("path").asText());
            if (expectsError(fixture)
                    || !"calculateBlueId".equals(fixture.path("operation").asText())) {
                continue;
            }
            Node input = YAML_MAPPER.treeToValue(fixture.get("input"), Node.class);

            // then
            assertEquals(
                    BlueIdCalculator.calculateBlueId(input),
                    FrozenNode.fromNode(input).blueId(),
                    "Frozen BlueId mismatch for fixture " + fixture.get("id").asText());
        }
    }

    @Test
    void shouldMatchMutableBlueIdInputForCanonicalFrozenNodeShapes() {
        // given
        String previousBlueId = BlueIdCalculator.calculateBlueId(new Node().items());
        String referenceBlueId = BlueIdCalculator.calculateBlueId(new Node().value("reference"));
        Node withSchema = new Node()
                .schema(new blue.language.model.Schema().minimum(new Node().type(new Node().blueId(
                        blue.language.utils.Properties.INTEGER_TYPE_BLUE_ID)).value("9007199254740992")));

        // when
        for (Node node : Arrays.asList(
                new Node().value("text"),
                new Node().items(new Node().value("A"), Nodes.emptyPlaceholder(), new Node().value("B")),
                new Node().items(new Node().previousBlueId(previousBlueId), new Node().value("A")),
                new Node().blueId(referenceBlueId),
                new Node().value("abc").contracts(new Node().properties("audit", new Node().value(true))),
                withSchema)) {
            // then
            assertEquals(
                    NodeToBlueIdInput.get(node),
                    FrozenNodeToBlueIdInput.get(FrozenNode.fromNode(node)),
                    "Frozen canonical input mismatch for " + node);
        }
    }

    @Test
    void shouldHashFrozenBlueIdInputLikeMutableInputForEveryValidFixture() throws Exception {
        // given
        JsonNode manifest = readFixtureResource("manifest.yaml");
        // when
        for (JsonNode entry : behaviorFixtureEntries(manifest)) {
            JsonNode fixture = readFixtureResource(entry.get("path").asText());
            if (!"BlueId".equals(fixture.path("category").asText())
                    || expectsError(fixture)
                    || !"calculateBlueId".equals(fixture.path("operation").asText())) {
                continue;
            }
            Node input = YAML_MAPPER.treeToValue(fixture.get("input"), Node.class);

            // then
            assertEquals(
                    BlueIdCalculator.INSTANCE.calculate(NodeToBlueIdInput.get(input)),
                    BlueIdCalculator.INSTANCE.calculate(FrozenNodeToBlueIdInput.get(FrozenNode.fromNode(input))),
                    "Frozen canonical input mismatch for fixture " + fixture.get("id").asText());
        }
    }

    @Test
    void shouldRejectEveryInvalidBlueIdFixtureThatParsesAsNode() throws Exception {
        // given
        JsonNode manifest = readFixtureResource("manifest.yaml");
        // when
        for (JsonNode entry : behaviorFixtureEntries(manifest)) {
            JsonNode fixture = readFixtureResource(entry.get("path").asText());
            if (!"BlueId".equals(fixture.path("category").asText())
                    || !expectsError(fixture)
                    || !"calculateBlueId".equals(fixture.path("operation").asText())
                    || !fixture.has("input")) {
                continue;
            }
            Node input;
            try {
                input = YAML_MAPPER.treeToValue(fixture.get("input"), Node.class);
            } catch (RuntimeException parserRejected) {
                continue;
            }

            // then
            assertThrows(
                    RuntimeException.class,
                    () -> BlueIdCalculator.calculateBlueId(input),
                    "Mutable calculator accepted invalid fixture " + fixture.get("id").asText());
            assertThrows(
                    RuntimeException.class,
                    () -> FrozenNode.fromNode(input).blueId(),
                    "Frozen calculator accepted invalid fixture " + fixture.get("id").asText());
        }
    }

    @Test
    void shouldCacheRepeatedFrozenBlueId() {
        // given
        Node node = new Node()
                .properties("a", new Node().value("b"));

        // when
        FrozenNode frozen = FrozenNode.fromNode(node);
        String firstBlueId = frozen.blueId();
        String secondBlueId = frozen.blueId();

        // then
        assertSame(firstBlueId, secondBlueId);
    }

    @Test
    void shouldNotRecomputeRepeatedFrozenBlueId() {
        // given
        FrozenNode frozen = FrozenNode.fromNode(new Node()
                .properties("a", new Node().value("b"))
                .properties("nested", new Node().properties("c", new Node().value("d"))));

        // when
        String first = frozen.blueId();
        boolean everyRepeatedIdentityIsCached = true;
        for (int i = 0; i < 10; i++) {
            everyRepeatedIdentityIsCached &=
                    first == frozen.blueId();
        }

        // then
        assertTrue(everyRepeatedIdentityIsCached);
    }

    @Test
    void shouldMemoizeRepeatedResolvedStructuralKey() {
        // given
        Node resolved = new Node()
                .properties("a", new Node().value("b"))
                .properties("nested",
                        new Node().properties(
                                "c",
                                new Node().value("d")));

        // when
        FrozenNode frozen = FrozenNode.fromResolvedNode(resolved);
        FrozenNode.ResolvedStructuralKey firstKey =
                frozen.resolvedStructuralKey();
        FrozenNode.ResolvedStructuralKey secondKey =
                frozen.resolvedStructuralKey();

        // then
        assertSame(firstKey, secondKey);
    }

    @Test
    void shouldPublishLazyResolvedIdentityAndStructuralKeySafelyAcrossThreads() throws Exception {
        // given
        FrozenNode frozen = FrozenNode.fromResolvedNode(new Node()
                .properties("a", new Node().value("b"))
                .properties("nested", new Node().properties("c", new Node().value("d"))));
        ExecutorService pool = Executors.newFixedThreadPool(8);

        // when
        boolean allIdentitiesSame = true;
        boolean allKeysSame = true;
        try {
            List<Future<String>> identities = new ArrayList<>();
            List<Future<FrozenNode.ResolvedStructuralKey>> keys = new ArrayList<>();
            for (int index = 0; index < 64; index++) {
                identities.add(pool.submit(frozen::blueId));
                keys.add(pool.submit(frozen::resolvedStructuralKey));
            }
            String expectedIdentity = identities.get(0).get();
            FrozenNode.ResolvedStructuralKey expectedKey = keys.get(0).get();
            for (Future<String> identity : identities) {
                allIdentitiesSame &=
                        expectedIdentity == identity.get();
            }
            for (Future<FrozenNode.ResolvedStructuralKey> key : keys) {
                allKeysSame &= expectedKey == key.get();
            }
        } finally {
            pool.shutdownNow();
        }

        // then
        assertTrue(allIdentitiesSame);
        assertTrue(allKeysSame);
    }

    @Test
    void shouldDropEmptyObjectPropertiesInStrictCanonicalModeLikeMutableCalculator() {
        // given
        Node node = YAML_MAPPER.readValue(
                "a: 1\n" +
                "empty: {}\n" +
                "nested:\n" +
                "  empty: {}\n" +
                "  label: ok", Node.class);

        // when
        FrozenNode frozen = FrozenNode.fromNode(node);
        String mutableBlueId =
                BlueIdCalculator.calculateBlueId(node);
        FrozenNode emptyProperty = frozen.property("empty");
        FrozenNode nestedEmptyProperty =
                frozen.property("nested").property("empty");
        String materializedBlueId =
                BlueIdCalculator.calculateBlueId(frozen.toNode());
        String frozenBlueId = frozen.blueId();

        // then
        assertEquals(mutableBlueId, frozenBlueId);
        assertNull(emptyProperty);
        assertNull(nestedEmptyProperty);
        assertEquals(materializedBlueId, frozenBlueId);
    }

    @Test
    void shouldMatchMutableBlueIdCalculatorForEmptySingletonAndNestedLists() {
        // given
        Node empty = YAML_MAPPER.readValue("items: []", Node.class);
        Node singleton = YAML_MAPPER.readValue("items:\n  - one", Node.class);
        Node nested = YAML_MAPPER.readValue("items:\n  - items:\n      - one\n  - two", Node.class);

        // when
        String mutableEmptyBlueId =
                BlueIdCalculator.calculateBlueId(empty);
        String frozenEmptyBlueId =
                FrozenNode.fromNode(empty).blueId();
        String mutableSingletonBlueId =
                BlueIdCalculator.calculateBlueId(singleton);
        String frozenSingletonBlueId =
                FrozenNode.fromNode(singleton).blueId();
        String mutableNestedBlueId =
                BlueIdCalculator.calculateBlueId(nested);
        String frozenNestedBlueId =
                FrozenNode.fromNode(nested).blueId();

        // then
        assertEquals(mutableEmptyBlueId, frozenEmptyBlueId);
        assertEquals(mutableSingletonBlueId, frozenSingletonBlueId);
        assertEquals(mutableNestedBlueId, frozenNestedBlueId);
    }

    @Test
    void shouldRejectDirectEmptyObjectInsideList() {
        // given
        Node withEmptyObject = YAML_MAPPER.readValue(
                "items:\n" +
                "  - {}", Node.class);

        // when
        Throwable failure = captureFailure(
                () -> FrozenNode.fromNode(withEmptyObject));

        // then
        assertTrue(failure instanceof IllegalArgumentException);
    }

    @Test
    void shouldNormalizeSourceEmptyObjectInsideListBeforeFreezing() {
        // given
        Blue blue = new Blue();
        String source = "items:\n  - {}";

        // when
        Node normalized = blue.yamlToNode(source);
        String mutableBlueId =
                BlueIdCalculator.calculateBlueId(normalized);
        String frozenBlueId =
                FrozenNode.fromNode(normalized).blueId();

        // then
        assertEquals(mutableBlueId, frozenBlueId);
    }

    @Test
    void shouldRejectPositionedListsInDirectFrozenBlueIdInput() {
        // given
        String previousBlueId = BlueIdCalculator.calculateBlueId(new Node().items());
        Node positioned = YAML_MAPPER.readValue(
                "items:\n" +
                "  - $pos: 0\n" +
                "    value: A", Node.class);
        Node previous = YAML_MAPPER.readValue(
                "items:\n" +
                "  - $previous:\n" +
                "      blueId: " + previousBlueId + "\n" +
                "  - $empty: true\n" +
                "  - value: A", Node.class);

        // when
        Throwable positionedFailure = captureFailure(
                () -> FrozenNode.fromNode(positioned));
        String mutablePreviousBlueId =
                BlueIdCalculator.calculateBlueId(previous);
        String frozenPreviousBlueId =
                FrozenNode.fromNode(previous).blueId();

        // then
        assertTrue(positionedFailure
                instanceof IllegalArgumentException);
        assertEquals(mutablePreviousBlueId, frozenPreviousBlueId);
    }

    @Test
    void shouldRejectPositionControlsInDirectFrozenBlueId() {
        // given
        String previousBlueId = BlueIdCalculator.calculateBlueId(new Node().items());
        Node node = YAML_MAPPER.readValue(
                "items:\n" +
                "  - $previous:\n" +
                "      blueId: " + previousBlueId + "\n" +
                "  - value: D", Node.class);
        Node positioned = YAML_MAPPER.readValue(
                "items:\n" +
                "  - $pos: 0\n" +
                "    value: A", Node.class);

        // when
        String mutableBlueId =
                BlueIdCalculator.calculateBlueId(node);
        String frozenBlueId = FrozenNode.fromNode(node).blueId();
        Throwable positionedFailure = captureFailure(
                () -> FrozenNode.fromNode(positioned));

        // then
        assertEquals(mutableBlueId, frozenBlueId);
        assertTrue(positionedFailure
                instanceof IllegalArgumentException);
    }

    @Test
    void shouldRejectRootPreviousOnlyNodeInStrictFrozenMode() {
        // given
        String previousBlueId = BlueIdCalculator.calculateBlueId(new Node().items());
        Node previousOnly =
                new Node().previousBlueId(previousBlueId);

        // when
        Throwable failure = captureFailure(
                () -> FrozenNode.fromNode(previousOnly));

        // then
        assertTrue(failure instanceof IllegalArgumentException);
    }

    @Test
    void shouldAllowPreviousOnlyNodeSolelyAsFirstListElementInStrictFrozenMode() {
        // given
        String previousBlueId = BlueIdCalculator.calculateBlueId(new Node().items());
        Node anchored = YAML_MAPPER.readValue(
                "items:\n" +
                "  - $previous:\n" +
                "      blueId: " + previousBlueId + "\n" +
                "  - value: A", Node.class);

        // when
        String mutableBlueId =
                BlueIdCalculator.calculateBlueId(anchored);
        String frozenBlueId =
                FrozenNode.fromNode(anchored).blueId();

        // then
        assertEquals(mutableBlueId, frozenBlueId);
    }

    @Test
    void shouldMatchMutableBlueIdCalculatorForTypedDoubleCanonicalization() {
        // given
        Node node = YAML_MAPPER.readValue(
                "type:\n" +
                "  blueId: " + DOUBLE_TYPE_BLUE_ID + "\n" +
                "value: 0.33333333333333333333333333333333333333", Node.class);

        // when
        String mutableBlueId =
                BlueIdCalculator.calculateBlueId(node);
        String frozenBlueId = FrozenNode.fromNode(node).blueId();

        // then
        assertEquals(mutableBlueId, frozenBlueId);
    }

    @Test
    void shouldAllowContractsAlongsideScalarAndListPayloadsInStrictCanonicalMode() {
        // given
        Node scalar = YAML_MAPPER.readValue(
                "value: abc\n" +
                "contracts:\n" +
                "  audit:\n" +
                "    value: enabled", Node.class);
        Node list = YAML_MAPPER.readValue(
                "items:\n" +
                "  - abc\n" +
                "contracts:\n" +
                "  audit:\n" +
                "    value: enabled", Node.class);
        Node invalidObject = new Node()
                .value("abc")
                .properties(
                        "contracts",
                        new Node().properties(
                                "audit",
                                new Node().value("enabled")),
                        "child",
                        new Node().value("not allowed"));

        // when
        String mutableScalarBlueId =
                BlueIdCalculator.calculateBlueId(scalar);
        String frozenScalarBlueId =
                FrozenNode.fromNode(scalar).blueId();
        String mutableListBlueId =
                BlueIdCalculator.calculateBlueId(list);
        String frozenListBlueId =
                FrozenNode.fromNode(list).blueId();
        Throwable invalidObjectFailure = captureFailure(
                () -> FrozenNode.fromNode(invalidObject));

        // then
        assertEquals(mutableScalarBlueId, frozenScalarBlueId);
        assertEquals(mutableListBlueId, frozenListBlueId);
        assertTrue(invalidObjectFailure
                instanceof IllegalArgumentException);
    }

    @Test
    void shouldRejectInvalidReferenceBlueIdsInStrictCanonicalMode() {
        // given
        Node invalidReference = new Node().blueId("invalid");
        Node invalidPreviousReference =
                new Node().previousBlueId("invalid");

        // when
        Throwable referenceFailure = captureFailure(
                () -> FrozenNode.fromNode(invalidReference));
        Throwable previousReferenceFailure = captureFailure(
                () -> FrozenNode.fromNode(invalidPreviousReference));

        // then
        assertTrue(referenceFailure
                instanceof IllegalArgumentException);
        assertTrue(previousReferenceFailure
                instanceof IllegalArgumentException);
    }

    @Test
    void shouldPreventImmutableViewMutationAndReturnFreshMutableCopiesFromToNode() {
        // given
        FrozenNode frozen = FrozenNode.fromNode(YAML_MAPPER.readValue(
                "a: 1\n" +
                "list:\n" +
                "  items:\n" +
                "    - x", Node.class));

        // when
        Throwable propertyMutationFailure = captureFailure(
                () -> frozen.getProperties().put("b", FrozenNode.empty()));
        Throwable itemMutationFailure = captureFailure(
                () -> frozen.property("list").getItems().add(FrozenNode.empty()));
        Node first = frozen.toNode();
        Node second = frozen.toNode();
        first.getProperties().put("mutated", new Node().value(true));
        String secondIdentity =
                BlueIdCalculator.calculateBlueId(second);
        String frozenIdentity = frozen.blueId();

        // then
        assertTrue(propertyMutationFailure
                instanceof UnsupportedOperationException);
        assertTrue(itemMutationFailure
                instanceof UnsupportedOperationException);
        assertNotSame(first, second);
        assertEquals(secondIdentity, frozenIdentity);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldOwnRawJsonValueContainersAsImmutableSnapshots() {
        // given
        List<Object> nested = new ArrayList<>();
        nested.add("before");
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("nested", nested);
        String[] array = new String[] {"first", "second"};
        raw.put("array", array);

        // when
        FrozenNode frozen = FrozenNode.fromNode(new Node().value(raw));
        String blueId = frozen.blueId();
        nested.set(0, "after");
        array[0] = "after";
        raw.put("extra", true);
        Map<String, Object> captured = (Map<String, Object>) frozen.getValue();
        List<Object> capturedNested = (List<Object>) captured.get("nested");
        List<Object> capturedNestedBeforeCallerMutation =
                new ArrayList<>(capturedNested);
        String[] capturedArrayBeforeCallerMutation =
                ((String[]) captured.get("array")).clone();
        boolean capturedExtraSourceMutation =
                captured.containsKey("extra");
        Throwable mapMutationFailure = captureFailure(
                () -> captured.put("mutation", true));
        Throwable listMutationFailure = captureFailure(
                () -> capturedNested.set(0, "mutation"));
        ((String[]) captured.get("array"))[0] = "caller mutation";
        String[] rereadArray =
                ((String[]) ((Map<?, ?>) frozen.getValue())
                        .get("array")).clone();
        Map<String, Object> materialized = (Map<String, Object>) frozen.toNode().getValue();
        ((List<Object>) materialized.get("nested")).set(0, "mutable copy");
        materialized.put("new", true);
        List<Object> capturedNestedAfterMaterialization =
                new ArrayList<>(capturedNested);
        boolean capturedNewMaterializedMutation =
                captured.containsKey("new");
        String frozenIdentityAfterMutations = frozen.blueId();

        // then
        assertEquals(Collections.singletonList("before"),
                capturedNestedBeforeCallerMutation);
        assertArrayEquals(new String[] {"first", "second"},
                capturedArrayBeforeCallerMutation);
        assertFalse(capturedExtraSourceMutation);
        assertTrue(mapMutationFailure
                instanceof UnsupportedOperationException);
        assertTrue(listMutationFailure
                instanceof UnsupportedOperationException);
        assertArrayEquals(new String[] {"first", "second"},
                rereadArray);
        assertEquals(Collections.singletonList("before"),
                capturedNestedAfterMaterialization);
        assertFalse(capturedNewMaterializedMutation);
        assertEquals(blueId, frozenIdentityAfterMutations);
    }

    @Test
    void shouldRejectNestedNonFiniteNumbersInRawJsonValueContainers() {
        // given
        Map<String, Object> nested = new LinkedHashMap<>();
        // when
        nested.put("values", Arrays.<Object>asList(1, Float.NaN));

        // then
        assertThrows(IllegalArgumentException.class,
                () -> FrozenNode.fromNode(new Node().value(nested)));
        assertThrows(IllegalArgumentException.class,
                () -> FrozenNode.fromNode(new Node().value(Double.POSITIVE_INFINITY)));
        assertThrows(IllegalArgumentException.class,
                () -> FrozenNode.fromNode(new Node().value(Float.NEGATIVE_INFINITY)));
        assertThrows(IllegalArgumentException.class,
                () -> FrozenNode.fromNode(new Node().value(
                        new float[] {1.0f, Float.NaN})));
        assertThrows(IllegalArgumentException.class,
                () -> FrozenNode.fromNode(new Node().value(new Object[] {
                        Collections.singletonMap("values",
                                new double[] {1.0d, Double.POSITIVE_INFINITY})})));
    }

    @Test
    void shouldPreserveLegacyRawArrayTypeBytesAndOwnershipAcrossAccessors() {
        // given
        byte[] source = new byte[] {1, 2};
        FrozenNode frozen = FrozenNode.fromNode(new Node().value(source));
        String expected = BlueIdCalculator.calculateBlueId(
                new Node().value(new byte[] {1, 2}));

        source[0] = 9;
        byte[] exposed = (byte[]) frozen.getValue();
        exposed[1] = 9;
        byte[] materialized = (byte[]) frozen.toNode().getValue();
        // when
        materialized[0] = 8;

        // then
        assertEquals(expected, frozen.blueId());
        assertArrayEquals(new byte[] {1, 2}, (byte[]) frozen.getValue());
        assertArrayEquals(new byte[] {1, 2}, (byte[]) frozen.toNode().getValue());
        assertEquals(frozen.resolvedStructuralKey(),
                FrozenNode.fromNode(new Node().value(new byte[] {1, 2}))
                        .resolvedStructuralKey());
        assertNotEquals(frozen.resolvedStructuralKey(),
                FrozenNode.fromNode(new Node().value(new Byte[] {1, 2}))
                        .resolvedStructuralKey());
    }

    @Test
    void shouldRetainLegacyRepresentationAndRuntimeTypeForCharactersAndCharacterArrays() {
        // given
        List<Node> cases = Arrays.asList(
                new Node().value(Character.valueOf('x')),
                new Node().value(new Character[] {'x', null, '\u20ac'}),
                new Node().value(new char[] {'x', '\u20ac'}));

        // when
        for (Node authored : cases) {
            FrozenNode frozen = FrozenNode.fromNode(authored);
            // then
            assertEquals(BlueIdCalculator.calculateBlueId(authored), frozen.blueId());
            assertEquals(authored.getValue().getClass(), frozen.getValue().getClass());
            assertEquals(authored.getValue().getClass(), frozen.toNode().getValue().getClass());
        }
        assertArrayEquals(new Character[] {'x', null, '\u20ac'},
                (Character[]) FrozenNode.fromNode(cases.get(1)).getValue());
        assertArrayEquals(new char[] {'x', '\u20ac'},
                (char[]) FrozenNode.fromNode(cases.get(2)).getValue());
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldKeepEnumValuesImmutableAndPreserveLegacyWireIdentity() {
        // given
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("default", DefaultWireEnum.DEFAULT_VALUE);
        raw.put("annotated", AnnotatedWireEnum.ANNOTATED_VALUE);
        Node authored = new Node().value(raw);

        FrozenNode frozen = FrozenNode.fromNode(authored);
        Map<String, Object> captured = (Map<String, Object>) frozen.getValue();
        // when
        Map<String, Object> materialized = (Map<String, Object>) frozen.toNode().getRawValue();

        // then
        assertSame(DefaultWireEnum.DEFAULT_VALUE, captured.get("default"));
        assertSame(AnnotatedWireEnum.ANNOTATED_VALUE, captured.get("annotated"));
        assertSame(DefaultWireEnum.DEFAULT_VALUE, materialized.get("default"));
        assertSame(AnnotatedWireEnum.ANNOTATED_VALUE, materialized.get("annotated"));
        assertEquals(BlueIdCalculator.calculateBlueId(authored), frozen.blueId());
        assertThrows(UnsupportedOperationException.class,
                () -> captured.put("mutation", DefaultWireEnum.DEFAULT_VALUE));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void shouldCloneAndFreezeConcreteAndInterfaceContainerArraysWithoutArrayStore() {
        // given
        TreeMap<String, Object> tree = new TreeMap<>();
        tree.put("key", "tree");
        List<Object> arrays = Arrays.asList(
                new ArrayList[] {new ArrayList<>(Collections.singletonList("array-list"))},
                new LinkedList[] {new LinkedList<>(Collections.singletonList("linked-list"))},
                new HashMap[] {new HashMap<>(Collections.singletonMap("key", "hash-map"))},
                new TreeMap[] {tree},
                new List[] {new ArrayList<>(Collections.singletonList("list"))},
                new Map[] {new HashMap<>(Collections.singletonMap("key", "map"))},
                new Object[] {
                        new ArrayList<>(Collections.singletonList("object-list")),
                        new HashMap<>(Collections.singletonMap("key", "object-map"))
                });

        // when
        List<ContainerArrayOwnershipObservation> observations =
                new ArrayList<>();
        for (Object array : arrays) {
            Node authored = new Node().value(array);
            Node cloned = authored.clone();
            FrozenNode frozen = FrozenNode.fromNode(authored);
            String identity = frozen.blueId();
            Object exposed = frozen.getValue();
            Object first = Array.get(exposed, 0);
            if (first instanceof List) {
                ((List) first).add("caller mutation");
            } else if (first instanceof Map) {
                ((Map) first).put("caller", "mutation");
            }
            observations.add(
                    new ContainerArrayOwnershipObservation(
                            array.getClass(),
                            cloned.getValue().getClass(),
                            frozen.getValue().getClass(),
                            frozen.toNode()
                                    .getValue()
                                    .getClass(),
                            BlueIdCalculator
                                    .calculateBlueId(authored),
                            identity,
                            frozen.blueId()));
        }

        // then
        for (ContainerArrayOwnershipObservation observation
                : observations) {
            assertEquals(observation.sourceType,
                    observation.clonedType);
            assertEquals(observation.sourceType,
                    observation.frozenType);
            assertEquals(observation.sourceType,
                    observation.materializedType);
            assertEquals(observation.expectedIdentity,
                    observation.initialIdentity);
            assertEquals(observation.initialIdentity,
                    observation.identityAfterCallerMutation);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldFallBackToOwnedObjectArraysForUnhandledConcreteContainerArrays() {
        // given
        List<Object> customNested = new ArrayList<>(Collections.<Object>singletonList("custom-before"));
        CustomJsonList custom = new CustomJsonList();
        custom.add(customNested);
        Object customArray = new CustomJsonList[] {custom};

        List<Object> singletonNested = new ArrayList<>(Collections.<Object>singletonList("singleton-before"));
        List<Object> singleton = Collections.<Object>singletonList(singletonNested);
        Object singletonArray = Array.newInstance(singleton.getClass(), 1);
        Array.set(singletonArray, 0, singleton);

        // when
        List<FallbackArrayObservation> observations =
                new ArrayList<>();
        for (Object sourceArray : Arrays.asList(customArray, singletonArray)) {
            Node authored = new Node().value(sourceArray);
            String expectedBlueId = BlueIdCalculator.calculateBlueId(authored);
            Node cloned = authored.clone();
            FrozenNode frozen = FrozenNode.fromNode(authored);
            observations.add(new FallbackArrayObservation(
                    cloned.getRawValue().getClass(),
                    frozen.getValue().getClass(),
                    frozen.toNode().getRawValue().getClass(),
                    expectedBlueId,
                    BlueIdCalculator.calculateBlueId(cloned),
                    frozen.blueId()));
        }
        customNested.set(0, "custom-after");
        singletonNested.set(0, "singleton-after");
        Node customClone = new Node().value(customArray).clone();
        FrozenNode singletonFrozen = FrozenNode.fromNode(new Node().value(singletonArray));
        customNested.set(0, "custom-later");
        singletonNested.set(0, "singleton-later");
        List<Object> clonedCustom = (List<Object>) ((List<?>)
                ((Object[]) customClone.getRawValue())[0]).get(0);
        List<Object> clonedCustomSnapshot =
                new ArrayList<>(clonedCustom);
        Object[] exposed = (Object[]) singletonFrozen.getValue();
        List<Object> exposedNested = (List<Object>) ((List<?>) exposed[0]).get(0);
        List<Object> exposedNestedSnapshot =
                new ArrayList<>(exposedNested);
        exposedNested.set(0, "caller-mutation");
        Object rereadNestedValue = ((List<?>) ((List<?>)
                ((Object[]) singletonFrozen.getValue())[0])
                .get(0)).get(0);

        // then
        for (FallbackArrayObservation observation
                : observations) {
            assertEquals(Object[].class,
                    observation.clonedType);
            assertEquals(Object[].class,
                    observation.frozenType);
            assertEquals(Object[].class,
                    observation.materializedType);
            assertEquals(observation.expectedIdentity,
                    observation.clonedIdentity);
            assertEquals(observation.expectedIdentity,
                    observation.frozenIdentity);
        }
        assertEquals(Collections.<Object>singletonList(
                        "custom-after"),
                clonedCustomSnapshot);
        assertEquals(Collections.<Object>singletonList(
                        "singleton-after"),
                exposedNestedSnapshot);
        assertEquals("singleton-after",
                rereadNestedValue);
    }

    @Test
    void shouldRejectNonJsonMutableValueObjectsAndCyclicContainersInFrozenNodes() {
        // given
        List<Object> cyclic = new ArrayList<>();
        cyclic.add(cyclic);
        Object[] cyclicArray = new Object[1];
        cyclicArray[0] = cyclicArray;

        // when
        Throwable mutableValueFailure = captureFailure(
                () -> FrozenNode.fromResolvedNode(
                        new Node().value(
                                new StringBuilder(
                                        "mutable"))));
        Throwable cyclicListFailure = captureFailure(
                () -> FrozenNode.fromResolvedNode(
                        new Node().value(cyclic)));
        Throwable cyclicArrayFailure = captureFailure(
                () -> FrozenNode.fromResolvedNode(new Node().value(cyclicArray)));

        // then
        assertTrue(mutableValueFailure
                instanceof IllegalArgumentException);
        assertTrue(cyclicListFailure
                instanceof IllegalArgumentException);
        assertTrue(cyclicArrayFailure
                instanceof IllegalArgumentException);
    }

    private static final class CustomJsonList extends ArrayList<Object> {
        private static final long serialVersionUID = 1L;
    }

    private enum DefaultWireEnum {
        DEFAULT_VALUE
    }

    private enum AnnotatedWireEnum {
        @JsonProperty("wire-value")
        ANNOTATED_VALUE
    }

    @Test
    void shouldResolveObjectAndListPointersWithoutMaterializingWholeTree() {
        // given
        Node source = YAML_MAPPER.readValue(
                "profile:\n" +
                "  label: Ana\n" +
                "rows:\n" +
                "  - id: a\n" +
                "  - id: b", Node.class);

        // when
        FrozenNode frozen = FrozenNode.fromNode(source);
        FrozenNode profileLabel =
                frozen.property("profile").property("label");
        FrozenNode resolvedProfileLabel =
                frozen.at("/profile/label");
        FrozenNode secondRowId =
                frozen.property("rows").item(1).property("id");
        FrozenNode resolvedSecondRowId =
                frozen.at("/rows/1/id");
        FrozenNode indexedSecondRowId =
                frozen.pathIndex().get("/rows/1/id");
        FrozenNode invalidListProperty =
                frozen.at("/rows/nope");
        FrozenNode missingListItem = frozen.at("/rows/9");

        // then
        assertEquals(profileLabel, resolvedProfileLabel);
        assertEquals(secondRowId, resolvedSecondRowId);
        assertEquals(resolvedSecondRowId, indexedSecondRowId);
        assertNull(invalidListProperty);
        assertNull(missingListItem);
    }

    @Test
    void shouldUseJsonPointerEscapingForSlashAndTildeKeysInPathLookup() throws Exception {
        // given
        Node source = YAML_MAPPER.readValue(
                "\"a/b\": slash\n" +
                "\"a~b\": tilde\n" +
                "nested:\n" +
                "  \"x/y\": value", Node.class);

        // when
        FrozenNode frozen = FrozenNode.fromNode(source);
        Object slashValue = frozen.at("/a~1b").getValue();
        Object tildeValue = frozen.at("/a~0b").getValue();
        Object nestedSlashValue =
                frozen.at("/nested/x~1y").getValue();
        FrozenNode slashProperty = frozen.property("a/b");
        FrozenNode indexedSlashProperty =
                frozen.pathIndex().get("/a~1b");
        FrozenNode tildeProperty = frozen.property("a~b");
        FrozenNode indexedTildeProperty =
                frozen.pathIndex().get("/a~0b");
        FrozenNode nestedSlashProperty =
                frozen.property("nested").property("x/y");
        FrozenNode indexedNestedSlashProperty =
                frozen.pathIndex().get("/nested/x~1y");

        // then
        assertEquals("slash", slashValue);
        assertEquals("tilde", tildeValue);
        assertEquals("value", nestedSlashValue);
        assertEquals(slashProperty, indexedSlashProperty);
        assertEquals(tildeProperty, indexedTildeProperty);
        assertEquals(nestedSlashProperty,
                indexedNestedSlashProperty);
    }

    @Test
    void shouldUseCachedElementHashesForListBlueId() {
        // given
        FrozenNode one = FrozenNode.fromNode(new Node().value("one"));
        FrozenNode two = FrozenNode.fromNode(new Node().value("two"));
        String frozenListId = FrozenNode.calculateBlueId(Arrays.asList(one, two));
        // when
        String mutableListId = BlueIdCalculator.calculateBlueId(Arrays.asList(one.toNode(), two.toNode()));

        // then
        assertEquals(mutableListId, frozenListId);
        assertEquals(BlueIdCalculator.calculateBlueId(Collections.emptyList()), FrozenNode.calculateBlueId(Collections.emptyList()));
    }

    @Test
    void shouldPreservePreviousEmptyAndNestedListIdentityInCachedListFold() {
        // given
        String previousBlueId = BlueIdCalculator.calculateBlueId(Collections.emptyList());
        String referenceBlueId = BlueIdCalculator.calculateBlueId(new Node().value("reference"));
        Node list = new Node().items(
                new Node().previousBlueId(previousBlueId),
                Nodes.emptyPlaceholder(),
                new Node().items(new Node().value("nested")),
                new Node().name("labeled").value("tail"),
                new Node().blueId(referenceBlueId),
                new Node().schema(new Schema().required(true)),
                new Node().value("contracted").contracts(
                        new Node().properties("audit", new Node().value(true))));
        // when
        FrozenNode frozen = FrozenNode.fromNode(list);

        // then
        assertEquals(BlueIdCalculator.calculateBlueId(list.getItems()),
                FrozenNode.calculateBlueId(frozen.getItems()));
        assertEquals(BlueIdCalculator.calculateBlueId(list), frozen.blueId());
    }

    @Test
    void shouldFallBackToListContextValidationInCachedListFold() {
        // given
        FrozenNode invalidEmptyMarker = FrozenNode.fromNode(new Node().properties(
                "$empty", new Node().value(false)));
        FrozenNode emptyObject = FrozenNode.empty();
        String previousBlueId = BlueIdCalculator.calculateBlueId(Collections.emptyList());
        // when
        FrozenNode anchored = FrozenNode.fromNode(new Node().items(
                new Node().previousBlueId(previousBlueId),
                new Node().value("value")));

        // then
        assertThrows(IllegalArgumentException.class,
                () -> FrozenNode.calculateBlueId(Collections.singletonList(invalidEmptyMarker)));
        assertThrows(IllegalArgumentException.class,
                () -> FrozenNode.calculateBlueId(Collections.singletonList(emptyObject)));
        assertThrows(IllegalArgumentException.class,
                () -> FrozenNode.calculateBlueId(Arrays.asList(anchored.item(1), anchored.item(0))));
    }

    @Test
    void shouldRetainUnchangedChildrenAndMatchMutableIdentityInFrozenObjectOverlay() {
        // given
        Schema originalSchema = new Schema().required(true);
        Schema overlaySchema = new Schema().maxFields(4);
        Node original = new Node()
                .name("Original")
                .description("kept")
                .schema(originalSchema)
                .contracts(new Node().properties("audit", new Node().value("old")))
                .properties("keep", new Node().value("same"),
                        "replace", new Node().value("old"));
        Node overlay = new Node()
                .name("Overlay")
                .schema(overlaySchema)
                .contracts(new Node().properties("audit", new Node().value("new")))
                .properties("replace", new Node().value("new"),
                        "add", new Node().value("added"));
        FrozenNode frozenOriginal = FrozenNode.fromNode(original);
        FrozenNode frozenOverlay = FrozenNode.fromNode(overlay);

        // when
        FrozenNode merged = frozenOriginal.overlayObject(frozenOverlay);
        Node expected = original.clone()
                .name("Overlay")
                .schema(overlaySchema.clone())
                .contracts(overlay.getContracts().clone())
                .properties("replace", overlay.getProperties().get("replace").clone())
                .properties("add", overlay.getProperties().get("add").clone());
        String expectedIdentity =
                BlueIdCalculator.calculateBlueId(expected);
        FrozenNode scalar = FrozenNode.fromNode(
                new Node().value("replacement"));
        FrozenNode scalarOverlay =
                frozenOriginal.overlayObject(scalar);
        FrozenNode nullOverlay =
                frozenOriginal.overlayObject(null);

        // then
        assertSame(frozenOriginal.property("keep"), merged.property("keep"));
        assertSame(frozenOverlay.property("replace"), merged.property("replace"));
        assertSame(frozenOverlay.getContracts(), merged.getContracts());
        assertEquals("Overlay", merged.getName());
        assertEquals("kept", merged.getDescription());
        assertNull(merged.getSchema().getRequired());
        assertEquals(BigInteger.valueOf(4), merged.getSchema().getMaxFieldsExact());
        assertEquals(expectedIdentity, merged.blueId());
        assertSame(scalar, scalarOverlay);
        assertNull(nullOverlay);
    }

    @Test
    void shouldCloneFrozenSchemaExactlyAtImmutableBoundary() {
        // given
        AtomicInteger cloneCalls = new AtomicInteger();
        CountingSchema source = new CountingSchema(cloneCalls);
        source.required(true);

        // when
        FrozenNode frozen = FrozenNode.fromResolvedNode(new Node().schema(source));
        int cloneCallsAfterFreeze = cloneCalls.get();
        source.required(false);
        Schema returned = frozen.getSchema();
        returned.required(false);
        boolean frozenRequired =
                frozen.getSchema().getRequiredValue();
        boolean returnedRequired =
                returned.getRequiredValue();
        int totalCloneCalls = cloneCalls.get();

        // then
        assertEquals(1, cloneCallsAfterFreeze);
        assertTrue(frozenRequired);
        assertFalse(returnedRequired);
        assertEquals(3, totalCloneCalls);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldDeeplyOwnRawJsonValuesAcrossFrozenSchemaBoundaries() {
        // given
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("label", "before");
        Schema source = new Schema().enumValues(Collections.singletonList(
                new Node().value(raw)));
        FrozenNode frozen = FrozenNode.fromNode(new Node().schema(source));
        String blueId = frozen.blueId();

        raw.put("label", "after");
        raw.put("extra", true);

        // when
        Map<String, Object> returned = (Map<String, Object>) frozen.getSchema()
                .getEnum().get(0).getValue();
        Object returnedLabelBeforeMutation =
                returned.get("label");
        boolean returnedContainsExtra =
                returned.containsKey("extra");
        returned.put("label", "caller mutation");
        Map<String, Object> reread = (Map<String, Object>) frozen.getSchema()
                .getEnum().get(0).getValue();
        Object rereadLabel = reread.get("label");
        String identityAfterReturnedMutation =
                frozen.blueId();
        Map<String, Object> materialized = (Map<String, Object>) frozen.toNode()
                .getSchema().getEnum().get(0).getValue();
        materialized.put("label", "materialized mutation");
        Object labelAfterMaterializedMutation =
                ((Map<?, ?>) frozen.getSchema()
                        .getEnum().get(0).getValue())
                        .get("label");

        // then
        assertEquals("before", returnedLabelBeforeMutation);
        assertFalse(returnedContainsExtra);
        assertEquals("before", rereadLabel);
        assertEquals(blueId, identityAfterReturnedMutation);
        assertEquals("before",
                labelAfterMaterializedMutation);
    }

    @Test
    void shouldRejectInvalidCanonicalPayloadShapes() {
        // given
        Node valueAndProperties = new Node()
                .value("x")
                .properties("y", new Node().value(1));
        Node referenceAndProperties = new Node()
                .blueId("ref")
                .properties("y", new Node().value(1));
        Node previousReferenceAndValue = new Node()
                .previousBlueId("prev")
                .value("x");
        Node positionedRoot = new Node().position(1);

        // when
        Throwable valueAndPropertiesFailure = captureFailure(
                () -> FrozenNode.fromNode(valueAndProperties));
        Throwable referenceAndPropertiesFailure = captureFailure(
                () -> FrozenNode.fromNode(referenceAndProperties));
        Throwable previousReferenceAndValueFailure = captureFailure(
                () -> FrozenNode.fromNode(previousReferenceAndValue));
        Throwable positionedRootFailure = captureFailure(
                () -> FrozenNode.fromNode(positionedRoot));

        // then
        assertTrue(valueAndPropertiesFailure
                instanceof IllegalArgumentException);
        assertTrue(referenceAndPropertiesFailure
                instanceof IllegalArgumentException);
        assertTrue(previousReferenceAndValueFailure
                instanceof IllegalArgumentException);
        assertTrue(positionedRootFailure
                instanceof IllegalArgumentException);
    }

    @Test
    void shouldRejectBlueDirectiveInStrictCanonicalMode() {
        // given
        Node node = YAML_MAPPER.readValue(
                "blue:\n" +
                "  items: []\n" +
                "value: hello", Node.class);

        // when
        Throwable failure = captureFailure(
                () -> FrozenNode.fromNode(node));

        // then
        assertTrue(failure instanceof IllegalArgumentException);
    }

    @Test
    void shouldRejectInvalidListControlFormsDuringHashing() {
        // given
        Node duplicatePosition = YAML_MAPPER.readValue(
                "items:\n" +
                "  - $pos: 1\n" +
                "    value: A\n" +
                "  - $pos: 1\n" +
                "    value: B", Node.class);
        // when
        Node previousNotFirst = YAML_MAPPER.readValue(
                "items:\n" +
                "  - value: A\n" +
                "  - $previous:\n" +
                "      blueId: PrevListHash", Node.class);

        // then
        assertThrows(IllegalArgumentException.class, () -> FrozenNode.fromNode(duplicatePosition));
        assertThrows(IllegalArgumentException.class, () -> FrozenNode.fromNode(previousNotFirst));
    }

    @Test
    void shouldAllowExpandedBlueIdMetadataOnlyInResolvedMode() {
        // given
        Node resolvedLike = new Node()
                .blueId("ReferenceMetadata")
                .name("Expanded node");

        // when
        FrozenNode resolved = FrozenNode.fromResolvedNode(resolvedLike);

        // then
        assertEquals(BlueIdCalculator.INSTANCE.calculate(Collections.singletonMap("name", "Expanded node")), resolved.blueId());
        assertThrows(IllegalArgumentException.class, () -> BlueIdCalculator.calculateBlueId(resolved.toNode()));
        assertThrows(IllegalArgumentException.class, () -> FrozenNode.fromNode(resolvedLike));
    }

    private JsonNode readFixtureResource(String path) throws Exception {
        try (InputStream stream = getClass().getClassLoader()
                .getResourceAsStream("blue-language-1.0/fixtures/" + path)) {
            if (stream == null) {
                throw new IllegalArgumentException("Missing fixture resource: " + path);
            }
            return YAML_MAPPER.readTree(stream);
        }
    }

    private List<JsonNode> behaviorFixtureEntries(JsonNode manifest) {
        JsonNode files = manifest.get("files");
        if (files == null || !files.isArray()) {
            throw new IllegalArgumentException("Blue Language 1.0 fixture manifest must contain a files list.");
        }
        List<JsonNode> entries = new ArrayList<>();
        for (JsonNode entry : files) {
            if ("behavior-fixture".equals(entry.path("role").asText())) {
                entries.add(entry);
            }
        }
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("Blue Language 1.0 fixture manifest contains no behavior fixtures.");
        }
        return entries;
    }

    private boolean expectsError(JsonNode fixture) {
        return fixture.path("expectError").asBoolean(false)
                || fixture.has("expectedErrorCategory");
    }

    private static final class ContainerArrayOwnershipObservation {
        private final Class<?> sourceType;
        private final Class<?> clonedType;
        private final Class<?> frozenType;
        private final Class<?> materializedType;
        private final String expectedIdentity;
        private final String initialIdentity;
        private final String identityAfterCallerMutation;

        private ContainerArrayOwnershipObservation(
                Class<?> sourceType,
                Class<?> clonedType,
                Class<?> frozenType,
                Class<?> materializedType,
                String expectedIdentity,
                String initialIdentity,
                String identityAfterCallerMutation) {
            this.sourceType = sourceType;
            this.clonedType = clonedType;
            this.frozenType = frozenType;
            this.materializedType = materializedType;
            this.expectedIdentity = expectedIdentity;
            this.initialIdentity = initialIdentity;
            this.identityAfterCallerMutation =
                    identityAfterCallerMutation;
        }
    }

    private static final class FallbackArrayObservation {
        private final Class<?> clonedType;
        private final Class<?> frozenType;
        private final Class<?> materializedType;
        private final String expectedIdentity;
        private final String clonedIdentity;
        private final String frozenIdentity;

        private FallbackArrayObservation(
                Class<?> clonedType,
                Class<?> frozenType,
                Class<?> materializedType,
                String expectedIdentity,
                String clonedIdentity,
                String frozenIdentity) {
            this.clonedType = clonedType;
            this.frozenType = frozenType;
            this.materializedType = materializedType;
            this.expectedIdentity = expectedIdentity;
            this.clonedIdentity = clonedIdentity;
            this.frozenIdentity = frozenIdentity;
        }
    }

    private static final class CountingSchema extends Schema {
        private final AtomicInteger cloneCalls;

        private CountingSchema(AtomicInteger cloneCalls) {
            this.cloneCalls = cloneCalls;
        }

        @Override
        public Schema clone() {
            cloneCalls.incrementAndGet();
            return super.clone();
        }
    }
}
