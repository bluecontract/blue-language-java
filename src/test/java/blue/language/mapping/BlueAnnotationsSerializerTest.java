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
    void shouldSerializeAnnotatedTypeBlueId() throws Exception {
        // given
        TypeBlueIdExample obj = new TypeBlueIdExample();
        obj.field = "value";
        String expected =
                "{\"type\":{\"blueId\":\"Example-BlueId\"},\"field\":\"value\"}";

        // when
        String json = mapper.writeValueAsString(obj);

        // then
        assertEquals(expected, json);
    }

    @Test
    void shouldSerializeAnnotatedFieldAsBlueIdReference() throws Exception {
        // given
        BlueIdExample obj = new BlueIdExample();
        obj.id = "123";
        String expected =
                "{\"type\":{\"blueId\":\"BlueId-Example\"},\"id\":{\"blueId\":\"123\"}}";

        // when
        String json = mapper.writeValueAsString(obj);

        // then
        assertEquals(expected, json);
    }

    @Test
    void shouldSerializeBlueNameAndDescriptionForCollectionField() throws Exception {
        // given
        CollectionExample obj = new CollectionExample();
        obj.teamName = "Dream Team";
        obj.teamDescription = "The best team ever";
        obj.team = Arrays.asList("Alice", "Bob", "Charlie");
        String expected =
                "{\"type\":{\"blueId\":\"Collection-Example\"},\"team\":{\"name\":\"Dream Team\",\"description\":\"The best team ever\",\"items\":[\"Alice\",\"Bob\",\"Charlie\"]}}";

        // when
        String json = mapper.writeValueAsString(obj);

        // then
        assertEquals(expected, json);
    }

    @Test
    void shouldSerializeBlueNameAndDescriptionForScalarField() throws Exception {
        // given
        NonCollectionExample obj = new NonCollectionExample();
        obj.fieldName = "Important Field";
        obj.fieldDescription = "This field is very important";
        obj.field = "Crucial data";
        String expected =
                "{\"type\":{\"blueId\":\"NonCollection-Example\"},\"field\":{\"name\":\"Important Field\",\"description\":\"This field is very important\",\"value\":\"Crucial data\"}}";

        // when
        String json = mapper.writeValueAsString(obj);

        // then
        assertEquals(expected, json);
    }

    @Test
    void shouldSerializeJsonPropertyNamesForGeneratedKeywordFields() throws Exception {
        // given
        JsonPropertyExample obj = new JsonPropertyExample();
        obj.packageValue = "Conversation";
        obj.classBlueId = "Class-BlueId";
        String expected =
                "{\"type\":{\"blueId\":\"JsonProperty-Example\"},\"class\":{\"blueId\":\"Class-BlueId\"},\"package\":\"Conversation\"}";

        // when
        String json = mapper.writeValueAsString(obj);

        // then
        assertEquals(expected, json);
    }

    @Test
    void shouldSerializeBlueNameAndDescriptionToJsonPropertyTarget() throws Exception {
        // given
        JsonPropertyMetadataExample obj = new JsonPropertyMetadataExample();
        obj.packageName = "Package label";
        obj.packageDescription = "Package description";
        obj.packageValue = "Conversation";
        String expected =
                "{\"type\":{\"blueId\":\"JsonProperty-Metadata-Example\"},\"package\":{\"name\":\"Package label\",\"description\":\"Package description\",\"value\":\"Conversation\"}}";

        // when
        String json = mapper.writeValueAsString(obj);

        // then
        assertEquals(expected, json);
    }

    @TypeBlueId("Example-BlueId")
    public static class TypeBlueIdExample {
        public static final String PROPERTY_FIELD = "field";
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
