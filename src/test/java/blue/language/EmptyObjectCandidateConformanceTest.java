package blue.language;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.mapping.NodeToObjectConverter;
import blue.language.mapping.TypeClassResolver;
import blue.language.model.Node;
import blue.language.model.NodeWireForm;
import blue.language.model.Nodes;
import blue.language.model.Schema;
import blue.language.preprocess.provider.BasicNodeProvider;
import blue.language.snapshot.FrozenNode;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static blue.language.codec.jackson.UncheckedObjectMapper.YAML_MAPPER;
import static blue.language.model.NodeWireForm.Strategy.SIMPLE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Executes the machine-readable candidate corpus for exact empty objects. */
final class EmptyObjectCandidateConformanceTest {

    private static final String RESOURCE =
            "blue-language-candidate/empty-object-conformance.yaml";

    @TestFactory
    Stream<DynamicTest> shouldPassEveryCandidateEmptyObjectConformanceCase() {
        // given
        JsonNode suite = loadSuite();

        // when
        Stream<JsonNode> cases = StreamSupport.stream(
                suite.path("cases").spliterator(), false);

        // then
        return cases.map(caseSpec -> DynamicTest.dynamicTest(
                caseSpec.path("id").asText(),
                () -> assertCase(caseSpec)));
    }

    private static void assertCase(JsonNode caseSpec) {
        String operation = caseSpec.path("operation").asText();
        switch (operation) {
            case "preprocess":
                assertPreprocessCase(caseSpec);
                return;
            case "direct-blue-id":
                assertDirectBlueIdCase(caseSpec);
                return;
            case "direct-rejected":
                assertDirectRejectedCase(caseSpec);
                return;
            case "distinct-blue-ids":
                assertDistinctBlueIdsCase(caseSpec);
                return;
            case "canonical-empty-type-parity":
                assertCanonicalTypeParityCase(caseSpec);
                return;
            case "minimize-round-trip":
                assertMinimizeRoundTripCase(caseSpec);
                return;
            case "inherited-payload-conflict":
                assertInheritedPayloadConflictCase(caseSpec);
                return;
            case "schema":
                assertSchemaCase(caseSpec);
                return;
            case "map":
                assertMappingCase(caseSpec);
                return;
            case "freeze-round-trip":
                assertFreezeRoundTripCase(caseSpec);
                return;
            default:
                throw new AssertionError(
                        "Unsupported candidate operation: " + operation);
        }
    }

    private static void assertPreprocessCase(JsonNode caseSpec) {
        Node input = node(caseSpec.path("input"));
        Node expected = node(caseSpec.path("expected"));

        Node actual = new Blue().preprocess(input);
        Node twice = new Blue().preprocess(actual);

        assertEquals(wire(expected), wire(actual));
        assertEquals(wire(actual), wire(twice));
    }

    private static void assertDirectBlueIdCase(JsonNode caseSpec) {
        Node input = node(caseSpec.path("input"));

        String actual = DirectBlueIdCalculator.calculateBlueId(input);

        assertEquals(caseSpec.path("expectedBlueId").asText(), actual);
    }

    private static void assertDirectRejectedCase(JsonNode caseSpec) {
        Node input = node(caseSpec.path("input"));

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> DirectBlueIdCalculator.calculateBlueId(input));

        assertTrue(failure.getMessage().contains("null"));
    }

    private static void assertDistinctBlueIdsCase(JsonNode caseSpec) {
        List<String> blueIds = new ArrayList<>();
        for (JsonNode input : caseSpec.path("inputs")) {
            blueIds.add(DirectBlueIdCalculator.calculateBlueId(node(input)));
        }

        Set<String> distinct = new HashSet<>(blueIds);

        assertEquals(blueIds.size(), distinct.size());
    }

    private static void assertCanonicalTypeParityCase(JsonNode caseSpec) {
        String emptyBlueId = caseSpec.path("expectedTypeBlueId").asText();
        BasicNodeProvider provider = new BasicNodeProvider(
                Nodes.emptyObject());
        Blue blue = new Blue(provider);
        Node inline = node(caseSpec.path("inline"));
        Node reference = node(caseSpec.path("reference"));

        Node inlineCanonical = blue.canonicalize(inline);
        Node referenceCanonical = blue.canonicalize(reference);

        assertEquals(emptyBlueId,
                inlineCanonical.getType().getBlueId());
        assertTrue(inlineCanonical.getType().isReferenceOnly());
        assertEquals(wire(referenceCanonical), wire(inlineCanonical));
        assertEquals(blue.calculateSourceDocumentBlueId(reference),
                blue.calculateSourceDocumentBlueId(inline));
    }

    private static void assertMinimizeRoundTripCase(JsonNode caseSpec) {
        BasicNodeProvider provider = new BasicNodeProvider(
                Nodes.emptyObject());
        Blue blue = new Blue(provider);
        Node input = node(caseSpec.path("input"));

        Node minimized = blue.minimize(input);
        Node resolvedInput = blue.resolve(input);
        Node resolvedMinimized = blue.resolve(minimized);

        assertEquals(wire(resolvedInput), wire(resolvedMinimized));
        assertTrue(Nodes.isExactEmptyObject(
                minimized.getProperties().get("x")));
    }

    private static void assertInheritedPayloadConflictCase(
            JsonNode caseSpec) {
        String kind = caseSpec.path("inheritedKind").asText();
        Node inheritedValue = "scalar".equals(kind)
                ? new Node().value("fixed")
                : new Node().items(new Node().value("fixed"));
        BasicNodeProvider provider = new BasicNodeProvider();
        provider.addSingleNodes(new Node()
                .name("Candidate " + kind + " holder")
                .properties("x", inheritedValue));
        Node override = new Node()
                .type(reference(provider.getBlueIdByName(
                        "Candidate " + kind + " holder")))
                .properties("x", Nodes.emptyObject());

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> new Blue(provider).resolve(override));

        assertTrue(failure.getMessage().contains("payload"));
    }

    private static void assertSchemaCase(JsonNode caseSpec) {
        Schema schema = new Schema().required(
                caseSpec.path("required").asBoolean(false));
        if (caseSpec.has("minFields")) {
            schema.minFields(caseSpec.path("minFields").asInt());
        }
        BasicNodeProvider provider = new BasicNodeProvider();
        provider.addSingleNodes(new Node()
                .name("Candidate schema holder")
                .properties("x", new Node()
                        .type("Dictionary")
                        .schema(schema)));
        Node input = new Node()
                .type(reference(provider.getBlueIdByName(
                        "Candidate schema holder")))
                .properties("x", Nodes.emptyObject());

        boolean valid = resolves(new Blue(provider), input);

        assertEquals(caseSpec.path("expectValid").asBoolean(), valid);
    }

    private static void assertMappingCase(JsonNode caseSpec) {
        Node input = node(caseSpec.path("input"));
        NodeToObjectConverter converter = new NodeToObjectConverter(
                new TypeClassResolver());

        Map<?, ?> mapped = converter.convert(input, Map.class);

        assertTrue(caseSpec.path("expectNonNull").asBoolean());
        assertNotNull(mapped);
        assertEquals(caseSpec.path("expectedSize").asInt(), mapped.size());
    }

    private static void assertFreezeRoundTripCase(JsonNode caseSpec) {
        Node input = node(caseSpec.path("input"));

        FrozenNode frozen = FrozenNode.fromNode(input);
        Node roundTrip = frozen.toNode();

        assertEquals(wire(input), wire(roundTrip));
        assertEquals(DirectBlueIdCalculator.calculateBlueId(input),
                frozen.blueId());
    }

    private static boolean resolves(Blue blue, Node input) {
        try {
            blue.resolve(input);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static Node node(JsonNode tree) {
        return YAML_MAPPER.treeToValue(tree, Node.class);
    }

    private static Object wire(Node node) {
        return NodeWireForm.get(node, SIMPLE);
    }

    private static Node reference(String blueId) {
        return new Node().blueId(blueId);
    }

    private static JsonNode loadSuite() {
        InputStream stream = Objects.requireNonNull(
                EmptyObjectCandidateConformanceTest.class
                        .getClassLoader().getResourceAsStream(RESOURCE),
                "Missing candidate corpus " + RESOURCE);
        try (InputStream input = stream) {
            return YAML_MAPPER.readValue(input, JsonNode.class);
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Cannot load candidate corpus " + RESOURCE,
                    exception);
        }
    }
}
