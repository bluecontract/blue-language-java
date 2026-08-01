package blue.language.codec.jackson;

import blue.language.model.value.BlueNumbers;

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

/**
 * Language-configured JSON/YAML mapper that converts checked Jackson failures
 * to runtime exceptions.
 *
 * <p>Both shared instances reject duplicate keys and preserve arbitrary
 * precision numeric tokens. The YAML instance additionally rejects tags,
 * anchors, and aliases because they are outside the Blue data model.</p>
 */
public class UncheckedObjectMapper extends ObjectMapper {

    private static final Pattern YAML_TAG_PATTERN = Pattern.compile("(^|[\\s\\[{,])![^\\s]+");
    private static final Pattern YAML_ANCHOR_OR_ALIAS_PATTERN = Pattern.compile("(^|\\s)[&*][A-Za-z0-9_-]+");

    /**
     * Shared strict YAML mapper. Treat it as process configuration and do not
     * reconfigure it after application startup.
     */
    public static final UncheckedObjectMapper YAML_MAPPER =  new UncheckedObjectMapper(
            YAMLFactory.builder()
                    .enable(MINIMIZE_QUOTES)
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .build());

    /**
     * Shared strict JSON mapper. Treat it as process configuration and do not
     * reconfigure it after application startup.
     */
    public static final UncheckedObjectMapper JSON_MAPPER = new UncheckedObjectMapper(
            JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .build());

    protected UncheckedObjectMapper(JsonFactory jsonFactory) {
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
        module.addSerializer(BigInteger.class, new JsonSerializer<BigInteger>() {
            @Override
            public void serialize(BigInteger value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
                if (value.compareTo(BlueNumbers.MIN_INTEROPERABLE_INTEGER) >= 0
                        && value.compareTo(BlueNumbers.MAX_INTEROPERABLE_INTEGER) <= 0) {
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

    /**
     * Converts nested mapping failures to {@link NestedJsonException}.
     *
     * @param <T> target value type
     * @param fromValue source value
     * @param toValueType target class
     * @return converted target value
     */
    public <T> T nestedConvertValue(Object fromValue, Class<T> toValueType) {
        try {
            return super.convertValue(fromValue, toValueType);
        } catch (IllegalArgumentException e) {
            throw new NestedJsonException(e.getCause());
        } catch (Exception e) {
            throw new NestedJsonException(e);
        }
    }

    /**
     * Converts nested generic mapping failures to {@link NestedJsonException}.
     *
     * @param <T> target value type
     * @param fromValue source value
     * @param toValueTypeRef target generic type reference
     * @return converted target value
     */
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

    /** Runtime wrapper used by ordinary top-level mapping operations. */
    public static class JsonException extends RuntimeException {

        /**
         * Creates an unchecked wrapper for a mapping failure.
         *
         * @param cause underlying mapping failure
         */
        public JsonException(Throwable cause) {
            super(cause);
        }
    }

    /** Runtime wrapper that preserves the innermost nested conversion failure. */
    public static class NestedJsonException extends RuntimeException {

        /** Innermost nested conversion failure retained for compatibility. */
        private final Throwable nestedException;

        /**
         * Creates a wrapper retaining the innermost conversion failure.
         *
         * @param nestedException innermost nested conversion failure
         */
        public NestedJsonException(Throwable nestedException) {
            this.nestedException = nestedException;
        }
    }
}
