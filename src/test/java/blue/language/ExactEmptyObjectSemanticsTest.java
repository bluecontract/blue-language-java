package blue.language;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.NodeWireForm;
import blue.language.model.Nodes;
import blue.language.model.Schema;
import blue.language.mapping.NodeToObjectConverter;
import blue.language.mapping.TypeClassResolver;
import blue.language.preprocess.provider.BasicNodeProvider;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;

import static blue.language.codec.jackson.UncheckedObjectMapper.JSON_MAPPER;
import static blue.language.codec.jackson.UncheckedObjectMapper.YAML_MAPPER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ExactEmptyObjectSemanticsTest {

    private static final String EMPTY_OBJECT_BLUE_ID =
            "5ajuwjHoLj33yG5t5UFsJtUb3vnRaJQEMPqSLz6VyoHK";

    @Test
    void shouldConsumeOnlySourceNullDuringMandatoryPreprocessing() {
        // given
        Node rawObject = YAML_MAPPER.readValue(
                "removed: null\n"
                        + "empty: {}\n"
                        + "nested:\n"
                        + "  removed: null\n"
                        + "emptyList: []",
                Node.class);
        Node rawList = YAML_MAPPER.readValue(
                "items: [A, null, {}, {removed: null}, [], B]",
                Node.class);
        Node rawMetadata = YAML_MAPPER.readValue(
                "name: Metadata\nremoved: null", Node.class);
        Blue blue = new Blue();

        // when
        Node object = blue.preprocess(rawObject);
        Node list = blue.preprocess(rawList);
        Node metadata = blue.preprocess(rawMetadata);
        Node twice = blue.preprocess(object);

        // then
        assertFalse(object.getProperties().containsKey("removed"));
        assertTrue(Nodes.isExactEmptyObject(
                object.getProperties().get("empty")));
        assertTrue(Nodes.isExactEmptyObject(
                object.getProperties().get("nested")));
        assertNotNull(object.getProperties().get("emptyList").getItems());
        assertTrue(Nodes.isEmptyPlaceholder(list.getItems().get(1)));
        assertTrue(Nodes.isExactEmptyObject(list.getItems().get(2)));
        assertTrue(Nodes.isExactEmptyObject(list.getItems().get(3)));
        assertNotNull(list.getItems().get(4).getItems());
        assertNull(metadata.getProperties());
        assertEquals(NodeWireForm.get(object), NodeWireForm.get(twice));
    }

    @Test
    void shouldOmitReservedNullWhileRetainingExplicitEmptyType() {
        // given
        Blue blue = new Blue();

        // when
        Node nullType = blue.preprocess(YAML_MAPPER.readValue(
                "type: null", Node.class));
        Node emptyType = blue.preprocess(YAML_MAPPER.readValue(
                "type: {}", Node.class));

        // then
        assertNull(nullType.getType());
        assertTrue(Nodes.isExactEmptyObject(nullType));
        assertNotNull(emptyType.getType());
        assertTrue(Nodes.isExactEmptyObject(emptyType.getType()));
    }

    @Test
    void shouldRoundTripEmptyObjectWithIndependentCanonicalIdentity() {
        // given
        Node empty = Nodes.emptyObject();

        // when
        String json = JSON_MAPPER.writeValueAsString(empty);
        Node roundTrip = JSON_MAPPER.readValue(json, Node.class);

        // then
        assertTrue(JSON_MAPPER.readTree(json).isObject());
        assertEquals(0, JSON_MAPPER.readTree(json).size());
        assertTrue(Nodes.isExactEmptyObject(roundTrip));
        assertEquals(EMPTY_OBJECT_BLUE_ID,
                DirectBlueIdCalculator.calculateBlueId(empty));
        assertEquals(EMPTY_OBJECT_BLUE_ID,
                DirectBlueIdCalculator.calculateBlueId(roundTrip));
    }

    @Test
    void shouldIncludeEmptyObjectChildAndListElementInExactIdentity() {
        // given
        Node expandedChild = new Node().properties(
                "x", Nodes.emptyObject());
        Node referencedChild = new Node().properties(
                "x", new Node().blueId(EMPTY_OBJECT_BLUE_ID));
        Node compact = list(new Node().value("A"), new Node().value("B"));
        Node placeholder = list(
                new Node().value("A"), Nodes.emptyPlaceholder(),
                new Node().value("B"));
        Node emptyObject = list(
                new Node().value("A"), Nodes.emptyObject(),
                new Node().value("B"));
        Node emptyList = list(
                new Node().value("A"), new Node().items(),
                new Node().value("B"));
        Node rawNullList = YAML_MAPPER.readValue(
                "items: [null]", Node.class);

        // when
        int distinctListIdentities = new HashSet<>(Arrays.asList(
                DirectBlueIdCalculator.calculateBlueId(compact),
                DirectBlueIdCalculator.calculateBlueId(placeholder),
                DirectBlueIdCalculator.calculateBlueId(emptyObject),
                DirectBlueIdCalculator.calculateBlueId(emptyList))).size();

        // then
        assertEquals(
                DirectBlueIdCalculator.calculateBlueId(referencedChild),
                DirectBlueIdCalculator.calculateBlueId(expandedChild));
        assertNotEquals(EMPTY_OBJECT_BLUE_ID,
                DirectBlueIdCalculator.calculateBlueId(expandedChild));
        assertEquals(4, distinctListIdentities);
        assertThrows(IllegalArgumentException.class,
                () -> DirectBlueIdCalculator.calculateBlueId(rawNullList));
    }

    @Test
    void shouldPreserveEveryEmptyObjectEdgeThroughFreezeAndMaterialization() {
        // given
        Node object = new Node().properties(
                "empty", Nodes.emptyObject());
        Node list = list(Nodes.emptyObject());
        Node sourceNull = new Node().inlineValue(true);

        // when
        FrozenNode frozenObject = FrozenNode.fromNode(object);
        FrozenNode frozenList = FrozenNode.fromNode(list);
        Node materialized = frozenObject.toNode();

        // then
        assertNotNull(FrozenNode.empty().getProperties());
        assertTrue(FrozenNode.empty().getProperties().isEmpty());
        assertEquals(EMPTY_OBJECT_BLUE_ID, FrozenNode.empty().blueId());
        assertNotNull(frozenObject.property("empty"));
        assertTrue(frozenObject.property("empty").getProperties().isEmpty());
        assertNotNull(frozenList.item(0).getProperties());
        assertTrue(frozenList.item(0).getProperties().isEmpty());
        assertTrue(Nodes.isExactEmptyObject(
                materialized.getProperties().get("empty")));
        assertEquals(DirectBlueIdCalculator.calculateBlueId(object),
                frozenObject.blueId());
        assertEquals(DirectBlueIdCalculator.calculateBlueId(list),
                frozenList.blueId());
        assertThrows(IllegalArgumentException.class,
                () -> FrozenNode.fromNode(sourceNull));
    }

    @Test
    void shouldRecognizeEmptyObjectForSchemaPresenceAndFieldCount() {
        // given
        BasicNodeProvider provider = new BasicNodeProvider();
        provider.addSingleNodes(new Node()
                .name("Required Empty Holder")
                .properties("x", new Node()
                        .schema(new Schema().required(true))));
        provider.addSingleNodes(new Node()
                .name("Nonempty Dictionary Holder")
                .properties("x", new Node()
                        .type("Dictionary")
                        .schema(new Schema().required(true).minFields(1))));
        Blue blue = new Blue(provider);
        Node requiredSource = new Node()
                .type(reference(provider.getBlueIdByName(
                        "Required Empty Holder")))
                .properties("x", Nodes.emptyObject());
        Node nonemptySource = new Node()
                .type(reference(provider.getBlueIdByName(
                        "Nonempty Dictionary Holder")))
                .properties("x", Nodes.emptyObject());

        // when
        Node resolved = blue.resolve(requiredSource);

        // then
        assertTrue(Nodes.hasObjectPayload(
                resolved.getProperties().get("x")));
        assertThrows(IllegalArgumentException.class,
                () -> blue.resolve(nonemptySource));
    }

    @Test
    void shouldRejectEmptyObjectOverInheritedScalarAndListPayloads() {
        // given
        BasicNodeProvider provider = new BasicNodeProvider();
        provider.addSingleNodes(new Node()
                .name("Fixed Payload Holder")
                .properties(
                        "scalar", new Node().value("fixed"),
                        "list", new Node().items(
                                new Node().value("A"),
                                new Node().value("B"))));
        String holderId = provider.getBlueIdByName(
                "Fixed Payload Holder");
        Blue blue = new Blue(provider);

        // when
        Node inherited = blue.resolve(new Node().type(reference(holderId)));

        // then
        assertEquals("fixed", inherited.getProperties()
                .get("scalar").getValue());
        assertEquals(2, inherited.getProperties().get("list")
                .getItems().size());
        assertThrows(IllegalArgumentException.class,
                () -> blue.resolve(new Node()
                        .type(reference(holderId))
                        .properties("scalar", Nodes.emptyObject())));
        assertThrows(IllegalArgumentException.class,
                () -> blue.resolve(new Node()
                        .type(reference(holderId))
                        .properties("list", Nodes.emptyObject())));
    }

    @Test
    void shouldRetainEmptyObjectAcrossCanonicalMinimizedProviderAndMappingPaths() {
        // given
        BasicNodeProvider provider = new BasicNodeProvider(
                Nodes.emptyObject());
        Blue blue = new Blue(provider);
        Node expanded = new Node().properties(
                "x", Nodes.emptyObject());
        Node referenced = new Node().properties(
                "x", reference(EMPTY_OBJECT_BLUE_ID));

        // when
        Node resolvedReference = blue.resolve(referenced);
        Node expandedReference = blue.expand(referenced);
        Node canonicalExpanded = blue.canonicalize(expanded);
        Node canonicalReference = blue.canonicalize(referenced);
        Node minimized = blue.minimize(expanded);
        Map<?, ?> mapped = new NodeToObjectConverter(
                new TypeClassResolver()).convert(
                Nodes.emptyObject(), Map.class);

        // then
        Node resolvedEmpty = resolvedReference.getProperties().get("x");
        assertTrue(resolvedEmpty.isReferenceOnly());
        assertEquals(EMPTY_OBJECT_BLUE_ID, resolvedEmpty.getBlueId());
        assertTrue(Nodes.isExactEmptyObject(
                expandedReference.getProperties().get("x")));
        assertEquals(
                DirectBlueIdCalculator.calculateBlueId(canonicalExpanded),
                DirectBlueIdCalculator.calculateBlueId(canonicalReference));
        assertEquals(
                blue.calculateSourceDocumentBlueId(expanded),
                blue.calculateSourceDocumentBlueId(referenced));
        assertTrue(Nodes.isExactEmptyObject(
                minimized.getProperties().get("x")));
        assertEquals(NodeWireForm.get(blue.resolve(expanded)),
                NodeWireForm.get(blue.resolve(minimized)));
        assertNotNull(mapped);
        assertTrue(mapped.isEmpty());
    }

    @Test
    void shouldCanonicalizeInlineAndReferencedEmptyTypesToOnePureReference() {
        // given
        BasicNodeProvider provider = new BasicNodeProvider(
                Nodes.emptyObject());
        Blue blue = new Blue(provider);
        Node inline = new Node().name("X").type(Nodes.emptyObject());
        Node referenced = new Node().name("X")
                .type(reference(EMPTY_OBJECT_BLUE_ID));

        // when
        Node inlineCanonical = blue.canonicalize(inline);
        Node referencedCanonical = blue.canonicalize(referenced);

        // then
        assertTrue(inlineCanonical.getType().isReferenceOnly());
        assertEquals(EMPTY_OBJECT_BLUE_ID,
                inlineCanonical.getType().getBlueId());
        assertEquals(NodeWireForm.get(referencedCanonical),
                NodeWireForm.get(inlineCanonical));
        assertEquals(blue.calculateSourceDocumentBlueId(referenced),
                blue.calculateSourceDocumentBlueId(inline));
    }

    private static Node list(Node... items) {
        return new Node().items(Arrays.asList(items));
    }

    private static Node reference(String blueId) {
        return new Node().blueId(blueId);
    }
}
