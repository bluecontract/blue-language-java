package blue.language.utils;

import blue.language.model.TypeBlueId;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;

import static blue.language.utils.UncheckedObjectMapper.YAML_MAPPER;

public class BlueIdResolver {
    private static final Logger logger = LoggerFactory.getLogger(BlueIdResolver.class);

    public static String resolveBlueId(Class<?> clazz) {
        TypeBlueId annotation = clazz.getAnnotation(TypeBlueId.class);
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

        return getRepositoryBlueId(annotation, clazz);
    }

    private static String getRepositoryBlueId(TypeBlueId annotation, Class<?> clazz) {
        String repositoryLocation = annotation.defaultValueRepositoryLocation();
        String repositoryDir = annotation.defaultValueRepositoryDir();
        String repositoryKey = annotation.defaultValueRepositoryKey();
        String yamlFile = annotation.defaultValuePropertyFile();

        String packageYamlPath = repositoryLocation + "/" + repositoryDir + "/" + yamlFile;
        try (InputStream is = BlueIdResolver.class.getClassLoader().getResourceAsStream(packageYamlPath)) {
            if (is == null) {
                logger.warn("Could not find {} at: {}. Skipping BlueId resolution for class: {}", yamlFile, packageYamlPath, clazz.getName());
                return null;
            }

            JsonNode root = YAML_MAPPER.readTree(is);

            if (repositoryKey.isEmpty()) {
                repositoryKey = resolveRepositoryKey(root, clazz);
            }

            JsonNode blueIdNode = root.get(repositoryKey);

            if (blueIdNode == null || blueIdNode.isNull()) {
                logger.warn("No mapping found for key: {} in {}. Skipping BlueId resolution for class: {}", repositoryKey, packageYamlPath, clazz.getName());
                return null;
            }

            String blueId = blueIdNode.asText();
            if (blueId != null && !blueId.isEmpty()) {
                return blueId;
            } else {
                logger.warn("Empty BlueId found for key: {} in {}. Skipping BlueId resolution for class: {}", repositoryKey, packageYamlPath, clazz.getName());
                return null;
            }
        } catch (IOException e) {
            logger.error("Error reading {} at: {}. Skipping BlueId resolution for class: {}", yamlFile, packageYamlPath, clazz.getName(), e);
            return null;
        }
    }

    private static String resolveRepositoryKey(JsonNode root, Class<?> clazz) {
        String camelCaseKey = clazz.getSimpleName();
        String spacedKey = addSpacesToCamelCase(camelCaseKey);

        JsonNode blueIdNode = root.get(camelCaseKey);
        if (blueIdNode == null || blueIdNode.isNull()) {
            blueIdNode = root.get(spacedKey);
            return (blueIdNode != null && !blueIdNode.isNull()) ? spacedKey : camelCaseKey;
        } else {
            return camelCaseKey;
        }
    }

    private static String addSpacesToCamelCase(String input) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            if (i > 0 && Character.isUpperCase(input.charAt(i))) {
                result.append(' ');
            }
            result.append(input.charAt(i));
        }
        return result.toString();
    }
}