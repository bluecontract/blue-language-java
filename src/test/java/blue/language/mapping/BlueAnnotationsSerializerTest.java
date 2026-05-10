package blue.language.mapping;

import blue.language.model.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BlueAnnotationsSerializerTest {

    private ObjectMapper mapper;

    @BeforeEach
    void setup() {
        mapper = new ObjectMapper();
        SimpleModule module = new SimpleModule();
        module.setSerializerModifier(new BlueAnnotationsBeanSerializerModifier());
        mapper.registerModule(module);
    }

    @Test
    void testTypeBlueIdSerialization() throws Exception {
        TypeBlueIdExample obj = new TypeBlueIdExample();
        obj.field = "value";

        String json = mapper.writeValueAsString(obj);
        String expected = "{\"type\":{\"blueId\":\"Example-BlueId\"},\"field\":\"value\"}";
        assertEquals(expected, json);
    }

    @Test
    void testBlueIdSerialization() throws Exception {
        BlueIdExample obj = new BlueIdExample();
        obj.id = "123";

        String json = mapper.writeValueAsString(obj);
        String expected = "{\"type\":{\"blueId\":\"BlueId-Example\"},\"id\":{\"blueId\":\"123\"}}";
        assertEquals(expected, json);
    }

    @Test
    void testBlueNameAndDescriptionForCollection() throws Exception {
        CollectionExample obj = new CollectionExample();
        obj.teamName = "Dream Team";
        obj.teamDescription = "The best team ever";
        obj.team = Arrays.asList("Alice", "Bob", "Charlie");

        String json = mapper.writeValueAsString(obj);
        String expected = "{\"type\":{\"blueId\":\"Collection-Example\"},\"team\":{\"name\":\"Dream Team\",\"description\":\"The best team ever\",\"items\":[\"Alice\",\"Bob\",\"Charlie\"]}}";
        assertEquals(expected, json);
    }

    @Test
    void testBlueNameAndDescriptionForNonCollection() throws Exception {
        NonCollectionExample obj = new NonCollectionExample();
        obj.fieldName = "Important Field";
        obj.fieldDescription = "This field is very important";
        obj.field = "Crucial data";

        String json = mapper.writeValueAsString(obj);
        String expected = "{\"type\":{\"blueId\":\"NonCollection-Example\"},\"field\":{\"name\":\"Important Field\",\"description\":\"This field is very important\",\"value\":\"Crucial data\"}}";
        assertEquals(expected, json);
    }

    @Test
    void serializesJsonPropertyNamesForGeneratedKeywordFields() throws Exception {
        JsonPropertyExample obj = new JsonPropertyExample();
        obj.packageValue = "Conversation";
        obj.classBlueId = "Class-BlueId";

        String json = mapper.writeValueAsString(obj);
        String expected = "{\"type\":{\"blueId\":\"JsonProperty-Example\"},\"class\":{\"blueId\":\"Class-BlueId\"},\"package\":\"Conversation\"}";
        assertEquals(expected, json);
    }

    @Test
    void serializesBlueNameAndDescriptionToJsonPropertyTarget() throws Exception {
        JsonPropertyMetadataExample obj = new JsonPropertyMetadataExample();
        obj.packageName = "Package label";
        obj.packageDescription = "Package description";
        obj.packageValue = "Conversation";

        String json = mapper.writeValueAsString(obj);
        String expected = "{\"type\":{\"blueId\":\"JsonProperty-Metadata-Example\"},\"package\":{\"name\":\"Package label\",\"description\":\"Package description\",\"value\":\"Conversation\"}}";
        assertEquals(expected, json);
    }

    @TypeBlueId("Example-BlueId")
    public static class TypeBlueIdExample {
        public String field;
    }

    @TypeBlueId("BlueId-Example")
    public static class BlueIdExample {
        @BlueId
        public String id;
    }

    @TypeBlueId("Collection-Example")
    public static class CollectionExample {
        @BlueName("team")
        public String teamName;
        @BlueDescription("team")
        public String teamDescription;
        public List<String> team;
    }

    @TypeBlueId("NonCollection-Example")
    public static class NonCollectionExample {
        @BlueName("field")
        public String fieldName;
        @BlueDescription("field")
        public String fieldDescription;
        public String field;
    }

    @TypeBlueId("JsonProperty-Example")
    public static class JsonPropertyExample {
        @JsonProperty("package")
        public String packageValue;
        @JsonProperty("class")
        @BlueId
        public String classBlueId;
    }

    @TypeBlueId("JsonProperty-Metadata-Example")
    public static class JsonPropertyMetadataExample {
        @BlueName("packageValue")
        public String packageName;
        @BlueDescription("packageValue")
        public String packageDescription;
        @JsonProperty("package")
        public String packageValue;
    }
}
