package blue.language.mapping;

import blue.language.model.TypeBlueId;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.logging.Level;
import java.util.logging.Logger;

import static blue.language.codec.jackson.UncheckedObjectMapper.YAML_MAPPER;

/** Resolves annotation-owned type BlueIds for the optional mapping module. */
final class BlueIdResolver {

    private static final Logger LOGGER =
            Logger.getLogger(BlueIdResolver.class.getName());

    private BlueIdResolver() {
    }

    /** Returns the class's preferred annotated BlueId, or {@code null}. */
    static String resolveBlueId(Class<?> valueClass) {
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
        String repositoryLocation =
                annotation.defaultValueRepositoryLocation();
        String repositoryDirectory =
                annotation.defaultValueRepositoryDir();
        String repositoryKey = annotation.defaultValueRepositoryKey();
        String propertyFile = annotation.defaultValuePropertyFile();
        String resourcePath = repositoryLocation + "/"
                + repositoryDirectory + "/" + propertyFile;

        try (InputStream input = BlueIdResolver.class.getClassLoader()
                .getResourceAsStream(resourcePath)) {
            if (input == null) {
                LOGGER.warning("Could not find " + propertyFile
                        + " at: " + resourcePath
                        + ". Skipping BlueId resolution for class: "
                        + valueClass.getName());
                return null;
            }
            JsonNode root = YAML_MAPPER.readTree(input);
            if (repositoryKey.isEmpty()) {
                repositoryKey = resolveRepositoryKey(root, valueClass);
            }
            JsonNode blueIdNode = root.get(repositoryKey);
            if (blueIdNode == null || blueIdNode.isNull()) {
                LOGGER.warning("No mapping found for key: "
                        + repositoryKey + " in " + resourcePath
                        + ". Skipping BlueId resolution for class: "
                        + valueClass.getName());
                return null;
            }
            String blueId = blueIdNode.asText();
            if (blueId != null && !blueId.isEmpty()) {
                return blueId;
            }
            LOGGER.warning("Empty BlueId found for key: "
                    + repositoryKey + " in " + resourcePath
                    + ". Skipping BlueId resolution for class: "
                    + valueClass.getName());
            return null;
        } catch (IOException exception) {
            LOGGER.log(Level.SEVERE,
                    "Error reading " + propertyFile + " at: "
                            + resourcePath
                            + ". Skipping BlueId resolution for class: "
                            + valueClass.getName(),
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
