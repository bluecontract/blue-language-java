package blue.language.mapping;

import blue.language.Blue;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.BlueDescription;
import blue.language.model.BlueId;
import blue.language.model.BlueName;
import blue.language.model.Node;
import blue.language.model.TypeBlueId;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonPropertyMappingTest {

    private static final String JSON_PROPERTY_MAPPED_TYPE_BLUE_ID =
            "D5NXSGwiw9zjGi2dFCioN7m22rtdPYqjtzWt1Fe6PxLT";
    private static final String JSON_PROPERTY_METADATA_TYPE_BLUE_ID =
            "2myUR4YQ3VxbXYqvQm93L4KcTbDQybBkixPJsVCAyHUa";
    private static final String JSON_PROPERTY_BLUE_ID_METADATA_TYPE_BLUE_ID =
            "5KfaV4wCcpuL6woATfqyg6stKWTjDp3uFCudTrraGx2j";
    private static final String NODE_PAYLOAD_MAPPED_TYPE_BLUE_ID =
            "8GouLjnHLp2YbJ35hhxPUKN1htEcMdXf2xMZiywxJ5aB";
    private static final String CLASS_TARGET_BLUE_ID =
            "w7itmBeEMwx3aCertGMSDn5cj1JSHj3cMWsemKu7L2e";
    private static final String REQUEST_TYPE_BLUE_ID =
            "AKDNsp4DrMjuQePRCXm9iAeUCgZc6rmJ6egST3mEonVC";
    private static final String DOCUMENT_TARGET_BLUE_ID =
            "88dMGYQeu4vnorDNHyjqQsG1HoSVtq1pv96CGXgTDavJ";

    @Test
    void shouldReadJsonPropertyNameAndUseTypeResolver() {
        // given
        Blue blue = blueWithJsonPropertyTypes();
        Node node = new Node()
                .type(new Node().blueId(
                        JSON_PROPERTY_MAPPED_TYPE_BLUE_ID))
                .properties("package", new Node().value("Conversation"))
                .properties("class", new Node().blueId(
                        CLASS_TARGET_BLUE_ID));

        // when
        Object converted = blue.nodeToObject(node, Object.class);
        JsonPropertyMapped mapped = (JsonPropertyMapped) converted;

        // then
        assertTrue(converted instanceof JsonPropertyMapped);
        assertEquals("Conversation", mapped.packageValue);
        assertEquals(CLASS_TARGET_BLUE_ID, mapped.classBlueId);
    }

    @Test
    void shouldIgnoreStaticConstantsAtBothMappingBoundaries() {
        // given
        Blue blue = blueWithJsonPropertyTypes();
        Node source = new Node()
                .type(new Node().blueId(
                        JSON_PROPERTY_MAPPED_TYPE_BLUE_ID))
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
        mapped.classBlueId = CLASS_TARGET_BLUE_ID;

        // when
        Node node = blue.objectToNode(mapped);

        // then
        assertEquals(
                JSON_PROPERTY_MAPPED_TYPE_BLUE_ID,
                node.getType().getBlueId());
        assertNotNull(node.getProperties().get("package"));
        assertEquals("Conversation", node.getProperties().get("package").getValue());
        assertNotNull(node.getProperties().get("class"));
        assertEquals(
                CLASS_TARGET_BLUE_ID,
                node.getProperties().get("class").getBlueId());
        assertFalse(node.getProperties().containsKey("packageValue"));
        assertFalse(node.getProperties().containsKey("classBlueId"));
    }

    @Test
    void shouldRoundTripGeneratedKeywordFields() {
        // given
        Blue blue = blueWithJsonPropertyTypes();
        JsonPropertyMapped original = new JsonPropertyMapped();
        original.packageValue = "Conversation";
        original.classBlueId = CLASS_TARGET_BLUE_ID;

        // when
        Node node = blue.objectToNode(original);
        JsonPropertyMapped converted = blue.nodeToObject(node, JsonPropertyMapped.class);

        // then
        assertEquals(original.packageValue, converted.packageValue);
        assertEquals(original.classBlueId, converted.classBlueId);
    }

    @Test
    void shouldRejectMalformedReferenceForBlueIdAnnotatedField() {
        // given
        Blue blue = blueWithJsonPropertyTypes();
        Node source = new Node()
                .type(new Node().blueId(
                        JSON_PROPERTY_MAPPED_TYPE_BLUE_ID))
                .properties("class", new Node().blueId("not-a-blue-id"));

        // when
        Executable conversion = () -> blue.nodeToObject(
                source,
                JsonPropertyMapped.class);

        // then
        RuntimeException failure = assertThrows(
                RuntimeException.class,
                conversion);
        assertTrue(messageChain(failure).contains(
                "Expected canonical Base58 SHA-256 BlueId"));
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
                .type(new Node().blueId(
                        JSON_PROPERTY_BLUE_ID_METADATA_TYPE_BLUE_ID))
                .properties("package", target);

        // when
        JsonPropertyBlueIdMetadata converted = blue.nodeToObject(node, JsonPropertyBlueIdMetadata.class);

        // then
        assertEquals(
                DirectBlueIdCalculator.calculateBlueId(target),
                converted.packageBlueId);
    }

    @Test
    void shouldWriteNestedNodeFieldsAsBluePayloads() {
        // given
        Blue blue = blueWithJsonPropertyTypes();
        NodePayloadMapped mapped = new NodePayloadMapped()
                .request(new Node()
                        .type(new Node().blueId(REQUEST_TYPE_BLUE_ID))
                        .properties("amount", new Node().value(5)))
                .document(new Node().blueId(DOCUMENT_TARGET_BLUE_ID));

        // when
        Node node = blue.objectToNode(mapped);
        Node request = node.getProperties().get("request");
        Node document = node.getProperties().get("document");

        // then
        assertEquals(
                NODE_PAYLOAD_MAPPED_TYPE_BLUE_ID,
                node.getType().getBlueId());
        assertNotNull(request);
        assertEquals(REQUEST_TYPE_BLUE_ID, request.getType().getBlueId());
        assertEquals(new BigInteger("5"), request.getProperties().get("amount").getValue());
        assertFalse(request.getProperties().containsKey("properties"));
        assertFalse(request.getProperties().containsKey("value"));

        assertNotNull(document);
        assertTrue(document.isReferenceOnly());
        assertEquals(DOCUMENT_TARGET_BLUE_ID, document.getBlueId());
    }

    private static String messageChain(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        Throwable current = failure;
        while (current != null) {
            if (current.getMessage() != null) {
                messages.append(current.getMessage()).append('\n');
            }
            current = current.getCause();
        }
        return messages.toString();
    }

    private Blue blueWithJsonPropertyTypes() {
        TypeClassResolver resolver = new TypeClassResolver()
                .registerAnnotatedClass(JsonPropertyMapped.class)
                .registerAnnotatedClass(JsonPropertyMetadataMapped.class)
                .registerAnnotatedClass(JsonPropertyBlueIdMetadata.class)
                .registerAnnotatedClass(NodePayloadMapped.class);
        return new Blue(blueId -> null, resolver);
    }

    @TypeBlueId(JSON_PROPERTY_MAPPED_TYPE_BLUE_ID)
    public static class JsonPropertyMapped {
        public static final String PROPERTY_PACKAGE = "package";
        @JsonProperty("package")
        public String packageValue;
        @JsonProperty("class")
        @BlueId
        public String classBlueId;
    }

    @TypeBlueId(JSON_PROPERTY_METADATA_TYPE_BLUE_ID)
    public static class JsonPropertyMetadataMapped {
        @BlueName("packageValue")
        public String packageName;
        @BlueDescription("packageValue")
        public String packageDescription;
        @JsonProperty("package")
        public String packageValue;
    }

    @TypeBlueId(JSON_PROPERTY_BLUE_ID_METADATA_TYPE_BLUE_ID)
    public static class JsonPropertyBlueIdMetadata {
        @JsonProperty("package")
        @BlueId
        public String packageBlueId;
    }

    @TypeBlueId(NODE_PAYLOAD_MAPPED_TYPE_BLUE_ID)
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
