package blue.language.snapshot;

import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.utils.BlueIdCalculator;
import blue.language.Blue;
import blue.language.utils.NodeToBlueIdInput;
import blue.language.utils.Nodes;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static blue.language.utils.UncheckedObjectMapper.YAML_MAPPER;
import static blue.language.utils.Properties.DOUBLE_TYPE_BLUE_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrozenNodeTest {

    @Test
    void blueIdMatchesMutableCalculatorForObjectsScalarsAndPureReferences() {
        String referenceBlueId = BlueIdCalculator.calculateBlueId(new Node().value("reference"));
        Node node = YAML_MAPPER.readValue(
                "name: Product\n" +
                "count: 1\n" +
                "nested:\n" +
                "  label: abc\n" +
                "ref:\n" +
                "  blueId: " + referenceBlueId, Node.class);

        FrozenNode frozen = FrozenNode.fromNode(node);

        assertEquals(BlueIdCalculator.calculateBlueId(node), frozen.blueId());
        assertEquals(referenceBlueId, FrozenNode.fromNode(new Node().blueId(referenceBlueId)).blueId());
    }

    @Test
    void frozenNodeBlueIdMatchesBlueIdCalculatorForEveryBlueIdFixture() throws Exception {
        JsonNode manifest = readFixtureResource("manifest.yaml");
        for (JsonNode entry : manifest.get("fixtures")) {
            JsonNode fixture = readFixtureResource(entry.get("path").asText());
            if (fixture.path("expectError").asBoolean(false)
                    || !"calculateBlueId".equals(fixture.path("operation").asText())) {
                continue;
            }
            Node input = YAML_MAPPER.treeToValue(fixture.get("input"), Node.class);

            assertEquals(
                    BlueIdCalculator.calculateBlueId(input),
                    FrozenNode.fromNode(input).blueId(),
                    "Frozen BlueId mismatch for fixture " + fixture.get("id").asText());
        }
    }

    @Test
    void frozenNodeToBlueIdInputMatchesNodeToBlueIdInputForCanonicalShapes() {
        String previousBlueId = BlueIdCalculator.calculateBlueId(new Node().items());
        String referenceBlueId = BlueIdCalculator.calculateBlueId(new Node().value("reference"));
        Node withSchema = new Node()
                .schema(new blue.language.model.Schema().minimum(new Node().type(new Node().blueId(
                        blue.language.utils.Properties.INTEGER_TYPE_BLUE_ID)).value("9007199254740992")));

        for (Node node : Arrays.asList(
                new Node().value("text"),
                new Node().items(new Node().value("A"), Nodes.emptyPlaceholder(), new Node().value("B")),
                new Node().items(new Node().previousBlueId(previousBlueId), new Node().value("A")),
                new Node().blueId(referenceBlueId),
                new Node().value("abc").contracts(new Node().properties("audit", new Node().value(true))),
                withSchema)) {
            assertEquals(
                    NodeToBlueIdInput.get(node),
                    FrozenNodeToBlueIdInput.get(FrozenNode.fromNode(node)),
                    "Frozen canonical input mismatch for " + node);
        }
    }

    @Test
    void frozenNodeToBlueIdInputHashesLikeNodeToBlueIdInputForEveryValidBlueIdFixture() throws Exception {
        JsonNode manifest = readFixtureResource("manifest.yaml");
        for (JsonNode entry : manifest.get("fixtures")) {
            if (!"BlueId".equals(entry.get("category").asText())) {
                continue;
            }
            JsonNode fixture = readFixtureResource(entry.get("path").asText());
            if (fixture.path("expectError").asBoolean(false)
                    || !"calculateBlueId".equals(fixture.path("operation").asText())) {
                continue;
            }
            Node input = YAML_MAPPER.treeToValue(fixture.get("input"), Node.class);

            assertEquals(
                    BlueIdCalculator.INSTANCE.calculate(NodeToBlueIdInput.get(input)),
                    BlueIdCalculator.INSTANCE.calculate(FrozenNodeToBlueIdInput.get(FrozenNode.fromNode(input))),
                    "Frozen canonical input mismatch for fixture " + fixture.get("id").asText());
        }
    }

    @Test
    void frozenNodeRejectsEveryInvalidBlueIdFixtureThatParsesAsNode() throws Exception {
        JsonNode manifest = readFixtureResource("manifest.yaml");
        for (JsonNode entry : manifest.get("fixtures")) {
            if (!"BlueId".equals(entry.get("category").asText())) {
                continue;
            }
            JsonNode fixture = readFixtureResource(entry.get("path").asText());
            if (!fixture.path("expectError").asBoolean(false)
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
    void repeatedFrozenBlueIdIsCached() {
        FrozenNode frozen = FrozenNode.fromNode(new Node().properties("a", new Node().value("b")));

        assertSame(frozen.blueId(), frozen.blueId());
    }

    @Test
    void repeatedFrozenBlueIdDoesNotRecompute() {
        FrozenNode frozen = FrozenNode.fromNode(new Node()
                .properties("a", new Node().value("b"))
                .properties("nested", new Node().properties("c", new Node().value("d"))));
        String first = frozen.blueId();

        for (int i = 0; i < 10; i++) {
            assertSame(first, frozen.blueId());
        }
    }

    @Test
    void repeatedResolvedStructuralKeyIsMemoized() {
        FrozenNode frozen = FrozenNode.fromResolvedNode(new Node()
                .properties("a", new Node().value("b"))
                .properties("nested", new Node().properties("c", new Node().value("d"))));

        assertSame(frozen.resolvedStructuralKey(), frozen.resolvedStructuralKey());
    }

    @Test
    void lazyResolvedIdentityAndStructuralKeyPublishSafelyAcrossThreads() throws Exception {
        FrozenNode frozen = FrozenNode.fromResolvedNode(new Node()
                .properties("a", new Node().value("b"))
                .properties("nested", new Node().properties("c", new Node().value("d"))));
        ExecutorService pool = Executors.newFixedThreadPool(8);
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
                assertSame(expectedIdentity, identity.get());
            }
            for (Future<FrozenNode.ResolvedStructuralKey> key : keys) {
                assertSame(expectedKey, key.get());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void strictCanonicalModeDropsEmptyObjectPropertiesLikeMutableCalculator() {
        Node node = YAML_MAPPER.readValue(
                "a: 1\n" +
                "empty: {}\n" +
                "nested:\n" +
                "  empty: {}\n" +
                "  label: ok", Node.class);

        FrozenNode frozen = FrozenNode.fromNode(node);

        assertEquals(BlueIdCalculator.calculateBlueId(node), frozen.blueId());
        assertEquals(null, frozen.property("empty"));
        assertEquals(null, frozen.property("nested").property("empty"));
        assertEquals(BlueIdCalculator.calculateBlueId(frozen.toNode()), frozen.blueId());
    }

    @Test
    void blueIdMatchesMutableCalculatorForEmptySingletonAndNestedLists() {
        Node empty = YAML_MAPPER.readValue("items: []", Node.class);
        Node singleton = YAML_MAPPER.readValue("items:\n  - one", Node.class);
        Node nested = YAML_MAPPER.readValue("items:\n  - items:\n      - one\n  - two", Node.class);

        assertEquals(BlueIdCalculator.calculateBlueId(empty), FrozenNode.fromNode(empty).blueId());
        assertEquals(BlueIdCalculator.calculateBlueId(singleton), FrozenNode.fromNode(singleton).blueId());
        assertEquals(BlueIdCalculator.calculateBlueId(nested), FrozenNode.fromNode(nested).blueId());
    }

    @Test
    void directEmptyObjectInsideListIsRejected() {
        Node withEmptyObject = YAML_MAPPER.readValue(
                "items:\n" +
                "  - {}", Node.class);

        assertThrows(IllegalArgumentException.class, () -> FrozenNode.fromNode(withEmptyObject));
    }

    @Test
    void sourceEmptyObjectInsideListNormalizesBeforeFreezing() {
        Node normalized = new Blue().yamlToNode(
                "items:\n" +
                "  - {}");

        assertEquals(BlueIdCalculator.calculateBlueId(normalized), FrozenNode.fromNode(normalized).blueId());
    }

    @Test
    void positionedListsAreRejectedByDirectFrozenBlueIdInput() {
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

        assertThrows(IllegalArgumentException.class, () -> FrozenNode.fromNode(positioned));
        assertEquals(BlueIdCalculator.calculateBlueId(previous), FrozenNode.fromNode(previous).blueId());
    }

    @Test
    void directFrozenBlueIdRejectsPositionControls() {
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

        assertEquals(BlueIdCalculator.calculateBlueId(node), FrozenNode.fromNode(node).blueId());
        assertThrows(IllegalArgumentException.class, () -> FrozenNode.fromNode(positioned));
    }

    @Test
    void frozenStrictRejectsRootPreviousOnlyNode() {
        String previousBlueId = BlueIdCalculator.calculateBlueId(new Node().items());

        assertThrows(IllegalArgumentException.class,
                () -> FrozenNode.fromNode(new Node().previousBlueId(previousBlueId)));
    }

    @Test
    void frozenStrictAllowsPreviousOnlyOnlyAsFirstListElement() {
        String previousBlueId = BlueIdCalculator.calculateBlueId(new Node().items());
        Node anchored = YAML_MAPPER.readValue(
                "items:\n" +
                "  - $previous:\n" +
                "      blueId: " + previousBlueId + "\n" +
                "  - value: A", Node.class);

        assertEquals(BlueIdCalculator.calculateBlueId(anchored), FrozenNode.fromNode(anchored).blueId());
    }

    @Test
    void blueIdMatchesMutableCalculatorForTypedDoubleCanonicalization() {
        Node node = YAML_MAPPER.readValue(
                "type:\n" +
                "  blueId: " + DOUBLE_TYPE_BLUE_ID + "\n" +
                "value: 0.33333333333333333333333333333333333333", Node.class);

        assertEquals(BlueIdCalculator.calculateBlueId(node), FrozenNode.fromNode(node).blueId());
    }

    @Test
    void strictCanonicalAllowsContractsAlongsideScalarAndListPayloads() {
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

        assertEquals(BlueIdCalculator.calculateBlueId(scalar), FrozenNode.fromNode(scalar).blueId());
        assertEquals(BlueIdCalculator.calculateBlueId(list), FrozenNode.fromNode(list).blueId());
        assertThrows(IllegalArgumentException.class,
                () -> FrozenNode.fromNode(new Node().value("abc").properties(
                        "contracts", new Node().properties("audit", new Node().value("enabled")),
                        "child", new Node().value("not allowed"))));
    }

    @Test
    void strictCanonicalRejectsInvalidReferenceBlueIds() {
        assertThrows(IllegalArgumentException.class, () -> FrozenNode.fromNode(new Node().blueId("invalid")));
        assertThrows(IllegalArgumentException.class, () -> FrozenNode.fromNode(new Node().previousBlueId("invalid")));
    }

    @Test
    void immutableViewsCannotBeMutatedAndToNodeReturnsFreshMutableCopies() {
        FrozenNode frozen = FrozenNode.fromNode(YAML_MAPPER.readValue(
                "a: 1\n" +
                "list:\n" +
                "  items:\n" +
                "    - x", Node.class));

        assertThrows(UnsupportedOperationException.class,
                () -> frozen.getProperties().put("b", FrozenNode.empty()));
        assertThrows(UnsupportedOperationException.class,
                () -> frozen.property("list").getItems().add(FrozenNode.empty()));

        Node first = frozen.toNode();
        Node second = frozen.toNode();
        first.getProperties().put("mutated", new Node().value(true));

        assertNotSame(first, second);
        assertEquals(BlueIdCalculator.calculateBlueId(second), frozen.blueId());
    }

    @Test
    void pathIndexAndAtResolveObjectAndListPointersWithoutMaterializingWholeTree() {
        FrozenNode frozen = FrozenNode.fromNode(YAML_MAPPER.readValue(
                "profile:\n" +
                "  label: Ana\n" +
                "rows:\n" +
                "  - id: a\n" +
                "  - id: b", Node.class));

        assertEquals(frozen.property("profile").property("label"), frozen.at("/profile/label"));
        assertEquals(frozen.property("rows").item(1).property("id"), frozen.at("/rows/1/id"));
        assertEquals(frozen.at("/rows/1/id"), frozen.pathIndex().get("/rows/1/id"));
        assertEquals(null, frozen.at("/rows/nope"));
        assertEquals(null, frozen.at("/rows/9"));
    }

    @Test
    void pathIndexAndAtUseJsonPointerEscapingForSlashAndTildeKeys() throws Exception {
        FrozenNode frozen = FrozenNode.fromNode(YAML_MAPPER.readValue(
                "\"a/b\": slash\n" +
                "\"a~b\": tilde\n" +
                "nested:\n" +
                "  \"x/y\": value", Node.class));

        assertEquals("slash", frozen.at("/a~1b").getValue());
        assertEquals("tilde", frozen.at("/a~0b").getValue());
        assertEquals("value", frozen.at("/nested/x~1y").getValue());
        assertEquals(frozen.property("a/b"), frozen.pathIndex().get("/a~1b"));
        assertEquals(frozen.property("a~b"), frozen.pathIndex().get("/a~0b"));
        assertEquals(frozen.property("nested").property("x/y"), frozen.pathIndex().get("/nested/x~1y"));
    }

    @Test
    void listBlueIdUsesCachedElementHashes() {
        FrozenNode one = FrozenNode.fromNode(new Node().value("one"));
        FrozenNode two = FrozenNode.fromNode(new Node().value("two"));
        String frozenListId = FrozenNode.calculateBlueId(Arrays.asList(one, two));
        String mutableListId = BlueIdCalculator.calculateBlueId(Arrays.asList(one.toNode(), two.toNode()));

        assertEquals(mutableListId, frozenListId);
        assertEquals(BlueIdCalculator.calculateBlueId(Collections.emptyList()), FrozenNode.calculateBlueId(Collections.emptyList()));
    }

    @Test
    void cachedListFoldPreservesPreviousEmptyAndNestedListIdentity() {
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
        FrozenNode frozen = FrozenNode.fromNode(list);

        assertEquals(BlueIdCalculator.calculateBlueId(list.getItems()),
                FrozenNode.calculateBlueId(frozen.getItems()));
        assertEquals(BlueIdCalculator.calculateBlueId(list), frozen.blueId());
    }

    @Test
    void cachedListFoldFallsBackToListContextValidation() {
        FrozenNode invalidEmptyMarker = FrozenNode.fromNode(new Node().properties(
                "$empty", new Node().value(false)));
        FrozenNode emptyObject = FrozenNode.empty();
        String previousBlueId = BlueIdCalculator.calculateBlueId(Collections.emptyList());
        FrozenNode anchored = FrozenNode.fromNode(new Node().items(
                new Node().previousBlueId(previousBlueId),
                new Node().value("value")));

        assertThrows(IllegalArgumentException.class,
                () -> FrozenNode.calculateBlueId(Collections.singletonList(invalidEmptyMarker)));
        assertThrows(IllegalArgumentException.class,
                () -> FrozenNode.calculateBlueId(Collections.singletonList(emptyObject)));
        assertThrows(IllegalArgumentException.class,
                () -> FrozenNode.calculateBlueId(Arrays.asList(anchored.item(1), anchored.item(0))));
    }

    @Test
    void frozenObjectOverlayRetainsUnchangedChildrenAndMatchesMutableIdentity() {
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

        FrozenNode merged = frozenOriginal.overlayObject(frozenOverlay);

        Node expected = original.clone()
                .name("Overlay")
                .schema(overlaySchema.clone())
                .contracts(overlay.getContracts().clone())
                .properties("replace", overlay.getProperties().get("replace").clone())
                .properties("add", overlay.getProperties().get("add").clone());
        assertSame(frozenOriginal.property("keep"), merged.property("keep"));
        assertSame(frozenOverlay.property("replace"), merged.property("replace"));
        assertSame(frozenOverlay.getContracts(), merged.getContracts());
        assertEquals("Overlay", merged.getName());
        assertEquals("kept", merged.getDescription());
        assertNull(merged.getSchema().getRequired());
        assertEquals(BigInteger.valueOf(4), merged.getSchema().getMaxFieldsExact());
        assertEquals(BlueIdCalculator.calculateBlueId(expected), merged.blueId());

        FrozenNode scalar = FrozenNode.fromNode(new Node().value("replacement"));
        assertSame(scalar, frozenOriginal.overlayObject(scalar));
        assertNull(frozenOriginal.overlayObject(null));
    }

    @Test
    void frozenSchemaIsClonedExactlyAtTheImmutableBoundary() {
        AtomicInteger cloneCalls = new AtomicInteger();
        CountingSchema source = new CountingSchema(cloneCalls);
        source.required(true);
        FrozenNode frozen = FrozenNode.fromResolvedNode(new Node().schema(source));

        assertEquals(1, cloneCalls.get());
        source.required(false);
        Schema returned = frozen.getSchema();
        returned.required(false);

        assertTrue(frozen.getSchema().getRequiredValue());
        assertFalse(returned.getRequiredValue());
        assertEquals(3, cloneCalls.get());
    }

    @Test
    void rejectsInvalidCanonicalPayloadShapes() {
        assertThrows(IllegalArgumentException.class,
                () -> FrozenNode.fromNode(new Node().value("x").properties("y", new Node().value(1))));
        assertThrows(IllegalArgumentException.class,
                () -> FrozenNode.fromNode(new Node().blueId("ref").properties("y", new Node().value(1))));
        assertThrows(IllegalArgumentException.class,
                () -> FrozenNode.fromNode(new Node().previousBlueId("prev").value("x")));
        assertThrows(IllegalArgumentException.class,
                () -> FrozenNode.fromNode(new Node().position(1)));
    }

    @Test
    void strictCanonicalModeRejectsBlueDirective() {
        Node node = YAML_MAPPER.readValue(
                "blue:\n" +
                "  items: []\n" +
                "value: hello", Node.class);

        assertThrows(IllegalArgumentException.class, () -> FrozenNode.fromNode(node));
    }

    @Test
    void rejectsInvalidListControlFormsDuringHashing() {
        Node duplicatePosition = YAML_MAPPER.readValue(
                "items:\n" +
                "  - $pos: 1\n" +
                "    value: A\n" +
                "  - $pos: 1\n" +
                "    value: B", Node.class);
        Node previousNotFirst = YAML_MAPPER.readValue(
                "items:\n" +
                "  - value: A\n" +
                "  - $previous:\n" +
                "      blueId: PrevListHash", Node.class);

        assertThrows(IllegalArgumentException.class, () -> FrozenNode.fromNode(duplicatePosition));
        assertThrows(IllegalArgumentException.class, () -> FrozenNode.fromNode(previousNotFirst));
    }

    @Test
    void resolvedModeAllowsExpandedBlueIdMetadataButCanonicalModeRejectsIt() {
        Node resolvedLike = new Node()
                .blueId("ReferenceMetadata")
                .name("Expanded node");

        FrozenNode resolved = FrozenNode.fromResolvedNode(resolvedLike);

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
