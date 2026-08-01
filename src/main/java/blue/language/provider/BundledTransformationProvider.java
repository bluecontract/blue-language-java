package blue.language.provider;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Exact provider for the transformations shipped with the Language core.
 *
 * <p>The resource names and their evaluation order are closed release input.
 * No directory enumeration or classpath scanning participates in bootstrap
 * construction.</p>
 */
final class BundledTransformationProvider extends AbstractNodeProvider {

    private static final String TRANSFORMATION_RESOURCE =
            "transformation/Transformation.blue";
    private static final String REPLACE_INLINE_TYPES_RESOURCE =
            "transformation/ReplaceInlineTypesWithBlueIds.blue";
    private static final String INFER_BASIC_TYPES_RESOURCE =
            "transformation/InferBasicTypesForUntypedValues.blue";
    private static final String[] ORDERED_RESOURCES = {
            TRANSFORMATION_RESOURCE,
            REPLACE_INLINE_TYPES_RESOURCE,
            INFER_BASIC_TYPES_RESOURCE
    };

    private final Map<String, JsonNode> contentByBlueId;

    BundledTransformationProvider() throws IOException {
        Map<String, JsonNode> loaded = new LinkedHashMap<>();
        for (String resource : ORDERED_RESOURCES) {
            NodeContentHandler.ParsedContent parsed =
                    NodeContentHandler.parseAndCalculateBlueId(
                            readResource(resource),
                            node -> node);
            JsonNode previous = loaded.put(parsed.blueId, parsed.content);
            if (previous != null) {
                throw new IOException(
                        "Duplicate bundled transformation BlueId: "
                                + parsed.blueId);
            }
        }
        contentByBlueId = Collections.unmodifiableMap(loaded);
    }

    @Override
    protected JsonNode fetchContentByBlueId(String baseBlueId) {
        return contentByBlueId.get(baseBlueId);
    }

    private String readResource(String resource) throws IOException {
        ClassLoader classLoader = BundledTransformationProvider.class
                .getClassLoader();
        try (InputStream input = classLoader.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException(
                        "Missing bundled transformation: " + resource);
            }
            try (ByteArrayOutputStream output =
                         new ByteArrayOutputStream()) {
                byte[] buffer = new byte[1024];
                int length;
                while ((length = input.read(buffer)) != -1) {
                    output.write(buffer, 0, length);
                }
                return output.toString(StandardCharsets.UTF_8.name());
            }
        }
    }
}
