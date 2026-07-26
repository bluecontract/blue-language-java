package blue.language.utils;

import blue.language.model.*;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.TreeNode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import static com.fasterxml.jackson.databind.DeserializationFeature.*;
import static com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT;
import static com.fasterxml.jackson.dataformat.yaml.YAMLGenerator.Feature.MINIMIZE_QUOTES;

public class UncheckedObjectMapper extends ObjectMapper {

    private static final Pattern YAML_TAG_PATTERN = Pattern.compile("(^|[\\s\\[{,])![^\\s]+");
    private static final Pattern YAML_ANCHOR_OR_ALIAS_PATTERN = Pattern.compile("(^|\\s)[&*][A-Za-z0-9_-]+");

    public static final UncheckedObjectMapper YAML_MAPPER =  new UncheckedObjectMapper(
            YAMLFactory.builder()
                    .enable(MINIMIZE_QUOTES)
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .build());

    public static final UncheckedObjectMapper JSON_MAPPER = new UncheckedObjectMapper(
            JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .build());

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
        // Numeric token kind and decimal scale are Language identity inputs.
        // In particular, a tree round trip must not collapse 1.0 into 1.
        setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));

        SimpleModule module = new SimpleModule();
        module.setSerializerModifier(new BlueAnnotationsBeanSerializerModifier());
        module.addSerializer(BigInteger.class, new JsonSerializer<BigInteger>() {
            @Override
            public void serialize(BigInteger value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
                BigInteger lowerBound = BigInteger.valueOf(-9007199254740991L);
                BigInteger upperBound = BigInteger.valueOf(9007199254740991L);
                if (value.compareTo(lowerBound) >= 0 && value.compareTo(upperBound) <= 0) {
                    gen.writeNumber(value);
                } else {
                    gen.writeString(value.toString());
                }
            }
        });
        registerModule(module);
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
            rejectYamlOnlySyntax(content);
            return rejectRootNull(super.readValue(content, valueType), valueType);
        } catch (IOException e) {
            throw new JsonException(e);
        }
    }

    @Override
    public <T> T readValue(InputStream src, Class<T> valueType) {
        try {
            if (getFactory() instanceof YAMLFactory) {
                return readValue(readUtf8(src), valueType);
            }
            return rejectRootNull(super.readValue(src, valueType), valueType);
        } catch (IOException e) {
            throw new JsonException(e);
        }
    }

    @Override
    public <T> T readValue(InputStream src, TypeReference<T> valueTypeRef) {
        try {
            if (getFactory() instanceof YAMLFactory) {
                return readValue(readUtf8(src), valueTypeRef);
            }
            return super.readValue(src, valueTypeRef);
        } catch (IOException e) {
            throw new JsonException(e);
        }
    }

    @Override
    public JsonNode readTree(String content) {
        try {
            rejectYamlOnlySyntax(content);
            return super.readTree(content);
        } catch (IOException e) {
            throw new JsonException(e);
        }
    }

    @Override
    public <T> T readValue(String content, TypeReference<T> valueTypeRef) {
        try {
            rejectYamlOnlySyntax(content);
            return super.readValue(content, valueTypeRef);
        } catch (IOException e) {
            throw new JsonException(e);
        }
    }

    @Override
    public <T> T readValue(String content, JavaType valueType) {
        try {
            rejectYamlOnlySyntax(content);
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
            return rejectRootNull(super.treeToValue(n, valueType), valueType);
        } catch (IllegalArgumentException | JsonProcessingException e) {
            throw new JsonException(e);
        }
    }

    private <T> T rejectRootNull(T result, Class<T> valueType) {
        if (result == null && Node.class.equals(valueType)) {
            throw new JsonException(new IllegalArgumentException("Root null is not a valid Blue document."));
        }
        return result;
    }

    private void rejectYamlOnlySyntax(String content) {
        if (!(getFactory() instanceof YAMLFactory) || content == null) {
            return;
        }
        if (YAML_TAG_PATTERN.matcher(content).find()) {
            throw new JsonException(new IllegalArgumentException("YAML tags are not part of the Blue JSON data model."));
        }
        if (YAML_ANCHOR_OR_ALIAS_PATTERN.matcher(content).find()) {
            throw new JsonException(new IllegalArgumentException("YAML anchors and aliases are not part of the Blue JSON data model."));
        }
    }

    private String readUtf8(InputStream src) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = src.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
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

        private final Throwable nestedException;

        public NestedJsonException(Throwable nestedException) {
            this.nestedException = nestedException;
        }
    }
}
