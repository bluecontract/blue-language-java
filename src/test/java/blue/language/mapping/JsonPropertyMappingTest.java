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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonPropertyMappingTest {

    @Test
    void nodeToObjectReadsJsonPropertyNameAndUsesTypeResolver() {
        Blue blue = blueWithJsonPropertyTypes();
        Node node = new Node()
                .type(new Node().blueId("JsonProperty-Mapped"))
                .properties("package", new Node().value("Conversation"))
                .properties("class", new Node().blueId("Class-BlueId"));

        Object converted = blue.nodeToObject(node, Object.class);

        assertTrue(converted instanceof JsonPropertyMapped);
        JsonPropertyMapped mapped = (JsonPropertyMapped) converted;
        assertEquals("Conversation", mapped.packageValue);
        assertEquals("Class-BlueId", mapped.classBlueId);
    }

    @Test
    void objectToNodeWritesJsonPropertyNameAndReferenceFields() {
        Blue blue = blueWithJsonPropertyTypes();
        JsonPropertyMapped mapped = new JsonPropertyMapped();
        mapped.packageValue = "Conversation";
        mapped.classBlueId = "Class-BlueId";

        Node node = blue.objectToNode(mapped);

        assertEquals("JsonProperty-Mapped", node.getType().getBlueId());
        assertNotNull(node.getProperties().get("package"));
        assertEquals("Conversation", node.getProperties().get("package").getValue());
        assertNotNull(node.getProperties().get("class"));
        assertEquals("Class-BlueId", node.getProperties().get("class").getBlueId());
        assertFalse(node.getProperties().containsKey("packageValue"));
        assertFalse(node.getProperties().containsKey("classBlueId"));
    }

    @Test
    void objectToNodeAndNodeToObjectRoundTripGeneratedKeywordFields() {
        Blue blue = blueWithJsonPropertyTypes();
        JsonPropertyMapped original = new JsonPropertyMapped();
        original.packageValue = "Conversation";
        original.classBlueId = "Class-BlueId";

        Node node = blue.objectToNode(original);
        JsonPropertyMapped converted = blue.nodeToObject(node, JsonPropertyMapped.class);

        assertEquals(original.packageValue, converted.packageValue);
        assertEquals(original.classBlueId, converted.classBlueId);
    }

    @Test
    void metadataAnnotationsCanTargetJsonPropertyBackedFields() {
        Blue blue = blueWithJsonPropertyTypes();
        JsonPropertyMetadataMapped original = new JsonPropertyMetadataMapped();
        original.packageName = "Package label";
        original.packageDescription = "Package description";
        original.packageValue = "Conversation";

        Node node = blue.objectToNode(original);

        Node packageNode = node.getProperties().get("package");
        assertNotNull(packageNode);
        assertEquals("Package label", packageNode.getName());
        assertEquals("Package description", packageNode.getDescription());
        assertEquals("Conversation", packageNode.getValue());
        assertFalse(node.getProperties().containsKey("packageValue"));

        JsonPropertyMetadataMapped converted = blue.nodeToObject(node, JsonPropertyMetadataMapped.class);
        assertEquals(original.packageName, converted.packageName);
        assertEquals(original.packageDescription, converted.packageDescription);
        assertEquals(original.packageValue, converted.packageValue);
    }

    @Test
    void blueIdAnnotationCalculatesHashFromJsonPropertyBackedField() {
        Blue blue = blueWithJsonPropertyTypes();
        Node target = new Node().value("Conversation");
        Node node = new Node()
                .type(new Node().blueId("JsonProperty-BlueId-Metadata"))
                .properties("package", target);

        JsonPropertyBlueIdMetadata converted = blue.nodeToObject(node, JsonPropertyBlueIdMetadata.class);

        assertEquals(BlueIdCalculator.calculateBlueId(target), converted.packageBlueId);
    }

    private Blue blueWithJsonPropertyTypes() {
        TypeClassResolver resolver = new TypeClassResolver()
                .registerAnnotatedClass(JsonPropertyMapped.class)
                .registerAnnotatedClass(JsonPropertyMetadataMapped.class)
                .registerAnnotatedClass(JsonPropertyBlueIdMetadata.class);
        return new Blue(blueId -> null, resolver);
    }

    @TypeBlueId("JsonProperty-Mapped")
    public static class JsonPropertyMapped {
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
}
