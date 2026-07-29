package blue.language.mapping;

import blue.language.Blue;
import blue.language.model.BlueDescription;
import blue.language.model.BlueId;
import blue.language.model.BlueName;
import blue.language.model.Node;
import blue.language.model.TypeBlueId;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.TypeClassResolver;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonPropertyMappingTest {

    @Test
    void shouldReadJsonPropertyNameAndUseTypeResolver() {
        // given
        Blue blue = blueWithJsonPropertyTypes();
        Node node = new Node()
                .type(new Node().blueId("JsonProperty-Mapped"))
                .properties("package", new Node().value("Conversation"))
                .properties("class", new Node().blueId("Class-BlueId"));

        // when
        Object converted = blue.nodeToObject(node, Object.class);
        JsonPropertyMapped mapped = (JsonPropertyMapped) converted;

        // then
        assertTrue(converted instanceof JsonPropertyMapped);
        assertEquals("Conversation", mapped.packageValue);
        assertEquals("Class-BlueId", mapped.classBlueId);
    }

    @Test
    void shouldIgnoreStaticConstantsAtBothMappingBoundaries() {
        // given
        Blue blue = blueWithJsonPropertyTypes();
        Node source = new Node()
                .type(new Node().blueId("JsonProperty-Mapped"))
                .properties("package", new Node().value("Conversation"));

        // when
        JsonPropertyMapped converted =
                blue.nodeToObject(source, JsonPropertyMapped.class);
        Node serialized = blue.objectToNode(converted);

        // then
        assertEquals("Conversation", converted.packageValue);
        assertEquals(
                "Conversation",
                serialized.getProperties().get("package").getValue());
        assertFalse(
                serialized.getProperties().containsKey("PROPERTY_PACKAGE"));
        assertEquals("package", JsonPropertyMapped.PROPERTY_PACKAGE);
    }

    @Test
    void shouldWriteJsonPropertyNameAndReferenceFields() {
        // given
        Blue blue = blueWithJsonPropertyTypes();
        JsonPropertyMapped mapped = new JsonPropertyMapped();
        mapped.packageValue = "Conversation";
        mapped.classBlueId = "Class-BlueId";

        // when
        Node node = blue.objectToNode(mapped);

        // then
        assertEquals("JsonProperty-Mapped", node.getType().getBlueId());
        assertNotNull(node.getProperties().get("package"));
        assertEquals("Conversation", node.getProperties().get("package").getValue());
        assertNotNull(node.getProperties().get("class"));
        assertEquals("Class-BlueId", node.getProperties().get("class").getBlueId());
        assertFalse(node.getProperties().containsKey("packageValue"));
        assertFalse(node.getProperties().containsKey("classBlueId"));
    }

    @Test
    void shouldRoundTripGeneratedKeywordFields() {
        // given
        Blue blue = blueWithJsonPropertyTypes();
        JsonPropertyMapped original = new JsonPropertyMapped();
        original.packageValue = "Conversation";
        original.classBlueId = "Class-BlueId";

        // when
        Node node = blue.objectToNode(original);
        JsonPropertyMapped converted = blue.nodeToObject(node, JsonPropertyMapped.class);

        // then
        assertEquals(original.packageValue, converted.packageValue);
        assertEquals(original.classBlueId, converted.classBlueId);
    }

    @Test
    void shouldApplyMetadataAnnotationsToJsonPropertyBackedFields() {
        // given
        Blue blue = blueWithJsonPropertyTypes();
        JsonPropertyMetadataMapped original = new JsonPropertyMetadataMapped();
        original.packageName = "Package label";
        original.packageDescription = "Package description";
        original.packageValue = "Conversation";

        // when
        Node node = blue.objectToNode(original);
        Node packageNode = node.getProperties().get("package");
        JsonPropertyMetadataMapped converted =
                blue.nodeToObject(node, JsonPropertyMetadataMapped.class);

        // then
        assertNotNull(packageNode);
        assertEquals("Package label", packageNode.getName());
        assertEquals("Package description", packageNode.getDescription());
        assertEquals("Conversation", packageNode.getValue());
        assertFalse(node.getProperties().containsKey("packageValue"));
        assertEquals(original.packageName, converted.packageName);
        assertEquals(original.packageDescription, converted.packageDescription);
        assertEquals(original.packageValue, converted.packageValue);
    }

    @Test
    void shouldCalculateBlueIdFromJsonPropertyBackedField() {
        // given
        Blue blue = blueWithJsonPropertyTypes();
        Node target = new Node().value("Conversation");
        Node node = new Node()
                .type(new Node().blueId("JsonProperty-BlueId-Metadata"))
                .properties("package", target);

        // when
        JsonPropertyBlueIdMetadata converted = blue.nodeToObject(node, JsonPropertyBlueIdMetadata.class);

        // then
        assertEquals(BlueIdCalculator.calculateUncheckedBlueId(target), converted.packageBlueId);
    }

    @Test
    void shouldWriteNestedNodeFieldsAsBluePayloads() {
        // given
        Blue blue = blueWithJsonPropertyTypes();
        NodePayloadMapped mapped = new NodePayloadMapped()
                .request(new Node()
                        .type(new Node().blueId("Request-Type"))
                        .properties("amount", new Node().value(5)))
                .document(new Node().blueId("Document-BlueId"));

        // when
        Node node = blue.objectToNode(mapped);
        Node request = node.getProperties().get("request");
        Node document = node.getProperties().get("document");

        // then
        assertEquals("Node-Payload-Mapped", node.getType().getBlueId());
        assertNotNull(request);
        assertEquals("Request-Type", request.getType().getBlueId());
        assertEquals(new BigInteger("5"), request.getProperties().get("amount").getValue());
        assertFalse(request.getProperties().containsKey("properties"));
        assertFalse(request.getProperties().containsKey("value"));

        assertNotNull(document);
        assertTrue(document.isReferenceOnly());
        assertEquals("Document-BlueId", document.getBlueId());
    }

    private Blue blueWithJsonPropertyTypes() {
        TypeClassResolver resolver = new TypeClassResolver()
                .registerAnnotatedClass(JsonPropertyMapped.class)
                .registerAnnotatedClass(JsonPropertyMetadataMapped.class)
                .registerAnnotatedClass(JsonPropertyBlueIdMetadata.class)
                .registerAnnotatedClass(NodePayloadMapped.class);
        return new Blue(blueId -> null, resolver);
    }

    @TypeBlueId("JsonProperty-Mapped")
    public static class JsonPropertyMapped {
        public static final String PROPERTY_PACKAGE = "package";
        @JsonProperty("package")
        public String packageValue;
        @JsonProperty("class")
        @BlueId
        public String classBlueId;
    }

    @TypeBlueId("JsonProperty-Metadata-Mapped")
    public static class JsonPropertyMetadataMapped {
        @BlueName("packageValue")
        public String packageName;
        @BlueDescription("packageValue")
        public String packageDescription;
        @JsonProperty("package")
        public String packageValue;
    }

    @TypeBlueId("JsonProperty-BlueId-Metadata")
    public static class JsonPropertyBlueIdMetadata {
        @JsonProperty("package")
        @BlueId
        public String packageBlueId;
    }

    @TypeBlueId("Node-Payload-Mapped")
    public static class NodePayloadMapped {
        private Node request;
        private Node document;

        public NodePayloadMapped request(Node request) {
            this.request = request;
            return this;
        }

        public NodePayloadMapped document(Node document) {
            this.document = document;
            return this;
        }
    }
}
