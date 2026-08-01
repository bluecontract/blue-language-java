package blue.language.mapping;

import blue.language.model.TypeBlueId;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;

import static blue.language.utils.UncheckedObjectMapper.YAML_MAPPER;

/** Resolves the preferred BlueId declared by a mapped Java type. */
public class BlueIdResolver {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(BlueIdResolver.class);

    /** Allows the legacy utility facade to inherit these operations. */
    protected BlueIdResolver() {
    }

    public static String resolveBlueId(Class<?> valueClass) {
        TypeBlueId annotation = valueClass.getAnnotation(TypeBlueId.class);
        if (annotation == null) {
            return null;
        }
        if (!annotation.defaultValue().isEmpty()) {
            return annotation.defaultValue();
        }
        String[] values = annotation.value();
        if (values.length > 0) {
            return values[0];
        }
        return getRepositoryBlueId(annotation, valueClass);
    }

    private static String getRepositoryBlueId(
            TypeBlueId annotation, Class<?> valueClass) {
        String repositoryLocation = annotation.defaultValueRepositoryLocation();
        String repositoryDirectory = annotation.defaultValueRepositoryDir();
        String repositoryKey = annotation.defaultValueRepositoryKey();
        String propertyFile = annotation.defaultValuePropertyFile();
        String resourcePath = repositoryLocation + "/"
                + repositoryDirectory + "/" + propertyFile;

        try (InputStream input = BlueIdResolver.class.getClassLoader()
                .getResourceAsStream(resourcePath)) {
            if (input == null) {
                LOGGER.warn(
                        "Could not find {} at: {}. Skipping BlueId resolution for class: {}",
                        propertyFile, resourcePath, valueClass.getName());
                return null;
            }
            JsonNode root = YAML_MAPPER.readTree(input);
            if (repositoryKey.isEmpty()) {
                repositoryKey = resolveRepositoryKey(root, valueClass);
            }
            JsonNode blueIdNode = root.get(repositoryKey);
            if (blueIdNode == null || blueIdNode.isNull()) {
                LOGGER.warn(
                        "No mapping found for key: {} in {}. Skipping BlueId resolution for class: {}",
                        repositoryKey, resourcePath, valueClass.getName());
                return null;
            }
            String blueId = blueIdNode.asText();
            if (blueId != null && !blueId.isEmpty()) {
                return blueId;
            }
            LOGGER.warn(
                    "Empty BlueId found for key: {} in {}. Skipping BlueId resolution for class: {}",
                    repositoryKey, resourcePath, valueClass.getName());
            return null;
        } catch (IOException exception) {
            LOGGER.error(
                    "Error reading {} at: {}. Skipping BlueId resolution for class: {}",
                    propertyFile, resourcePath, valueClass.getName(),
                    exception);
            return null;
        }
    }

    private static String resolveRepositoryKey(
            JsonNode root, Class<?> valueClass) {
        String camelCaseKey = valueClass.getSimpleName();
        String spacedKey = addSpacesToCamelCase(camelCaseKey);
        JsonNode blueIdNode = root.get(camelCaseKey);
        if (blueIdNode == null || blueIdNode.isNull()) {
            blueIdNode = root.get(spacedKey);
            return blueIdNode != null && !blueIdNode.isNull()
                    ? spacedKey : camelCaseKey;
        }
        return camelCaseKey;
    }

    private static String addSpacesToCamelCase(String input) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < input.length(); index++) {
            if (index > 0 && Character.isUpperCase(input.charAt(index))) {
                result.append(' ');
            }
            result.append(input.charAt(index));
        }
        return result.toString();
    }
}
