package blue.lang.utils;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.TreeNode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import lombok.Getter;

import java.io.IOException;
import java.io.InputStream;

import static com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS;
import static com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_INTEGER_FOR_INTS;
import static com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT;

public class UncheckedObjectMapper extends ObjectMapper {

    public static final UncheckedObjectMapper YAML_MAPPER = new UncheckedObjectMapper(
            new YAMLFactory().enable(YAMLGenerator.Feature.MINIMIZE_QUOTES));

    public static final UncheckedObjectMapper JSON_MAPPER = new UncheckedObjectMapper(
            new JsonFactory());

    private UncheckedObjectMapper(JsonFactory jsonFactory) {
        super(jsonFactory);

        setVisibility(getSerializationConfig().getDefaultVisibilityChecker()
                .withFieldVisibility(JsonAutoDetect.Visibility.ANY)
                .withGetterVisibility(JsonAutoDetect.Visibility.NONE)
                .withSetterVisibility(JsonAutoDetect.Visibility.NONE)
                .withIsGetterVisibility(JsonAutoDetect.Visibility.NONE)
                .withCreatorVisibility(JsonAutoDetect.Visibility.NONE));

        enable(INDENT_OUTPUT);
        setSerializationInclusion(Include.NON_NULL);
        enable(USE_BIG_DECIMAL_FOR_FLOATS);
        enable(USE_BIG_INTEGER_FOR_INTS);
    }

    @Override
    public String writeValueAsString(Object value) {
        try {
            return super.writeValueAsString(value);
        } catch (IOException e) {
            throw new JsonException(e);
        }
    }

    @Override
    public <T> T readValue(String content, Class<T> valueType) {
        try {
            return super.readValue(content, valueType);
        } catch (IOException e) {
            throw new JsonException(e);
        }
    }

    @Override
    public <T> T readValue(InputStream src, Class<T> valueType) {
        try {
            return super.readValue(src, valueType);
        } catch (IOException e) {
            throw new JsonException(e);
        }
    }

    @Override
    public <T> T readValue(InputStream src, TypeReference<T> valueTypeRef) {
        try {
            return super.readValue(src, valueTypeRef);
        } catch (IOException e) {
            throw new JsonException(e);
        }
    }

    @Override
    public JsonNode readTree(String content) {
        try {
            return super.readTree(content);
        } catch (IOException e) {
            throw new JsonException(e);
        }
    }

    @Override
    public <T> T readValue(String content, TypeReference<T> valueTypeRef) {
        try {
            return super.readValue(content, valueTypeRef);
        } catch (IOException e) {
            throw new JsonException(e);
        }
    }

    @Override
    public <T> T readValue(String content, JavaType valueType) {
        try {
            return super.readValue(content, valueType);
        } catch (IOException e) {
            throw new JsonException(e);
        }
    }

    @Override
    public <T> T convertValue(Object fromValue, Class<T> toValueType) {
        try {
            return super.convertValue(fromValue, toValueType);
        } catch (IllegalArgumentException e) {
            if (e.getCause() instanceof JsonProcessingException) {
                throw new JsonException(e.getCause());
            }

            throw e;
        }
    }

    @Override
    public <T> T convertValue(Object fromValue, TypeReference<T> toValueTypeRef) {
        try {
            return super.convertValue(fromValue, toValueTypeRef);
        } catch (IllegalArgumentException e) {
            if (e.getCause() instanceof JsonProcessingException) {
                throw new JsonException(e.getCause());
            }

            throw e;
        }
    }

    @Override
    public <T> T treeToValue(TreeNode n, Class<T> valueType) {
        try {
            return super.treeToValue(n, valueType);
        } catch (IllegalArgumentException | JsonProcessingException e) {
            throw new JsonException(e);
        }
    }

    public <T> T nestedConvertValue(Object fromValue, Class<T> toValueType) {
        try {
            return super.convertValue(fromValue, toValueType);
        } catch (IllegalArgumentException e) {
            throw new NestedJsonException(e.getCause());
        } catch (Exception e) {
            throw new NestedJsonException(e);
        }
    }

    public <T> T nestedConvertValue(Object fromValue, TypeReference<T> toValueTypeRef) {
        try {
            return super.convertValue(fromValue, toValueTypeRef);
        } catch (IllegalArgumentException e) {
            throw new NestedJsonException(e.getCause());
        } catch (Exception e) {
            throw new NestedJsonException(e);
        }
    }

    @Override
    public UncheckedObjectMapper disable(SerializationFeature f) {
        super.disable(f);
        return this;
    }

    @Override
    public UncheckedObjectMapper disable(MapperFeature... f) {
        super.disable(f);
        return this;
    }

    public static class JsonException extends RuntimeException {

        public JsonException(Throwable cause) {
            super(cause);
        }
    }

    public static class NestedJsonException extends RuntimeException {

        @Getter
        private final Throwable nestedException; // needed to prevent exception discarding when ObjectMapper only propagates root cause

        public NestedJsonException(Throwable nestedException) {
            this.nestedException = nestedException;
        }
    }
}
