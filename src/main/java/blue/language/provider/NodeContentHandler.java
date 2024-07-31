package blue.language.provider;

import blue.language.model.Node;
import blue.language.utils.BlueIdCalculator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static blue.language.utils.UncheckedObjectMapper.JSON_MAPPER;
import static blue.language.utils.UncheckedObjectMapper.YAML_MAPPER;

public class NodeContentHandler {

    private static final Pattern THIS_REFERENCE_PATTERN = Pattern.compile("^this(#\\d+)?$");

    public static class ParsedContent {
        public final String blueId;
        public final JsonNode content;
        public final boolean isMultipleDocuments;

        public ParsedContent(String blueId, JsonNode content, boolean isMultipleDocuments) {
            this.blueId = blueId;
            this.content = content;
            this.isMultipleDocuments = isMultipleDocuments;
        }
    }

    public static ParsedContent parseAndCalculateBlueId(String content, Function<Node, Node> preprocessor) {
        JsonNode jsonNode;
        try {
            jsonNode = YAML_MAPPER.readTree(content);
        } catch (Exception e) {
            try {
                jsonNode = JSON_MAPPER.readTree(content);
            } catch (Exception ex) {
                throw new RuntimeException("Failed to parse content as YAML or JSON", ex);
            }
        }

        String blueId;
        boolean isMultipleDocuments = jsonNode.isArray() && jsonNode.size() > 1;

        if (isMultipleDocuments) {
            List<Node> nodes = StreamSupport.stream(jsonNode.spliterator(), false)
                    .map(item -> JSON_MAPPER.convertValue(item, Node.class))
                    .map(preprocessor)
                    .collect(Collectors.toList());
            blueId = BlueIdCalculator.calculateBlueId(nodes);
            jsonNode = JSON_MAPPER.valueToTree(nodes);
        } else {
            Node node = JSON_MAPPER.convertValue(jsonNode, Node.class);
            node = preprocessor.apply(node);
            blueId = BlueIdCalculator.calculateBlueId(node);
            jsonNode = JSON_MAPPER.valueToTree(node);
        }

        return new ParsedContent(blueId, jsonNode, isMultipleDocuments);
    }

    public static ParsedContent parseAndCalculateBlueId(Node node, Function<Node, Node> preprocessor) {
        String blueId;
        Node preprocessedNode = preprocessor.apply(node);
        blueId = BlueIdCalculator.calculateBlueId(preprocessedNode);
        JsonNode jsonNode = JSON_MAPPER.valueToTree(preprocessedNode);

        return new ParsedContent(blueId, jsonNode, false);
    }

    public static ParsedContent parseAndCalculateBlueId(List<Node> nodes, Function<Node, Node> preprocessor) {
        if (nodes == null || nodes.isEmpty()) {
            throw new IllegalArgumentException("List of nodes cannot be null or empty");
        }

        List<Node> preprocessedNodes = nodes.stream()
                .map(preprocessor)
                .collect(Collectors.toList());

        String blueId = BlueIdCalculator.calculateBlueId(preprocessedNodes);
        JsonNode jsonNode = JSON_MAPPER.valueToTree(preprocessedNodes);
        boolean isMultipleDocuments = nodes.size() > 1;

        return new ParsedContent(blueId, jsonNode, isMultipleDocuments);
    }

    public static JsonNode resolveThisReferences(JsonNode content, String currentBlueId, boolean isMultipleDocuments) {
        return resolveThisReferencesRecursive(content, currentBlueId, isMultipleDocuments);
    }

    private static JsonNode resolveThisReferencesRecursive(JsonNode content, String currentBlueId, boolean isMultipleDocuments) {
        if (content.isObject()) {
            ObjectNode objectNode = (ObjectNode) content;
            objectNode.fields().forEachRemaining(entry -> {
                JsonNode value = entry.getValue();
                if (value.isTextual()) {
                    String textValue = value.asText();
                    if (THIS_REFERENCE_PATTERN.matcher(textValue).matches()) {
                        String newValue = resolveThisReference(textValue, currentBlueId, isMultipleDocuments);
                        objectNode.set(entry.getKey(), new TextNode(newValue));
                    }
                } else if (value.isObject() || value.isArray()) {
                    objectNode.set(entry.getKey(), resolveThisReferencesRecursive(value, currentBlueId, isMultipleDocuments));
                }
            });
            return objectNode;
        } else if (content.isArray()) {
            ArrayNode arrayNode = (ArrayNode) content;
            for (int i = 0; i < arrayNode.size(); i++) {
                JsonNode element = arrayNode.get(i);
                if (element.isTextual()) {
                    String textValue = element.asText();
                    if (THIS_REFERENCE_PATTERN.matcher(textValue).matches()) {
                        String newValue = resolveThisReference(textValue, currentBlueId, isMultipleDocuments);
                        arrayNode.set(i, new TextNode(newValue));
                    }
                } else if (element.isObject() || element.isArray()) {
                    arrayNode.set(i, resolveThisReferencesRecursive(element, currentBlueId, isMultipleDocuments));
                }
            }
            return arrayNode;
        }
        return content;
    }

    private static String resolveThisReference(String textValue, String currentBlueId, boolean isMultipleDocuments) {
        if (isMultipleDocuments) {
            if (!textValue.startsWith("this#")) {
                throw new IllegalArgumentException("For multiple documents, 'this' references must include an index (e.g., 'this#0')");
            }
            return currentBlueId + textValue.substring(4);
        } else {
            if (textValue.equals("this")) {
                return currentBlueId;
            } else {
                throw new IllegalArgumentException("For a single document, only 'this' is allowed as a reference, not 'this#<id>'");
            }
        }
    }
}