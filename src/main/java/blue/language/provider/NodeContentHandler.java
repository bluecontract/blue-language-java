package blue.language.provider;

import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.utils.BlueIdCalculator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static blue.language.utils.Properties.OBJECT_BLUE_ID;
import static blue.language.utils.UncheckedObjectMapper.JSON_MAPPER;
import static blue.language.utils.UncheckedObjectMapper.YAML_MAPPER;

public class NodeContentHandler {

    public static final String ZERO_BLUE_ID = "00000000000000000000000000000000000000000000";
    private static final Pattern THIS_REFERENCE_PATTERN = Pattern.compile("^this(#\\d+)?$");
    private static final Pattern THIS_INDEX_REFERENCE_PATTERN = Pattern.compile("^this#(\\d+)$");

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
            ParsedContent parsedContent = calculateParsedContent(nodes);
            blueId = parsedContent.blueId;
            jsonNode = parsedContent.content;
        } else {
            Node node = JSON_MAPPER.convertValue(jsonNode, Node.class);
            node = preprocessor.apply(node);
            ParsedContent parsedContent = calculateParsedContent(node);
            blueId = parsedContent.blueId;
            jsonNode = parsedContent.content;
        }

        return new ParsedContent(blueId, jsonNode, isMultipleDocuments);
    }

    public static ParsedContent parseAndCalculateBlueId(Node node, Function<Node, Node> preprocessor) {
        Node preprocessedNode = preprocessor.apply(node);
        return calculateParsedContent(preprocessedNode);
    }

    public static ParsedContent parseAndCalculateBlueId(List<Node> nodes, Function<Node, Node> preprocessor) {
        if (nodes == null || nodes.isEmpty()) {
            throw new IllegalArgumentException("List of nodes cannot be null or empty");
        }

        List<Node> preprocessedNodes = nodes.stream()
                .map(preprocessor)
                .collect(Collectors.toList());

        return calculateParsedContent(preprocessedNodes);
    }

    private static ParsedContent calculateParsedContent(Node node) {
        List<ThisReference> references = findThisReferences(node);
        if (references.isEmpty()) {
            String blueId = BlueIdCalculator.calculateBlueId(node);
            return new ParsedContent(blueId, JSON_MAPPER.valueToTree(node), false);
        }

        validateSingleDocumentReferences(references);
        Node preliminary = node.clone();
        rewriteThisReferences(preliminary, reference -> ZERO_BLUE_ID);

        String blueId = BlueIdCalculator.calculateBlueId(preliminary);
        return new ParsedContent(blueId, JSON_MAPPER.valueToTree(node), false);
    }

    private static ParsedContent calculateParsedContent(List<Node> nodes) {
        boolean isMultipleDocuments = nodes.size() > 1;
        List<ThisReference> references = findThisReferences(nodes);
        if (!isMultipleDocuments || references.isEmpty()) {
            String blueId = BlueIdCalculator.calculateBlueId(nodes);
            return new ParsedContent(blueId, JSON_MAPPER.valueToTree(nodes), isMultipleDocuments);
        }

        validateMultiDocumentReferences(references, nodes.size());

        List<IndexedNode> indexedNodes = new ArrayList<>();
        for (int i = 0; i < nodes.size(); i++) {
            Node preliminary = nodes.get(i).clone();
            rewriteThisReferences(preliminary, reference -> ZERO_BLUE_ID);
            indexedNodes.add(new IndexedNode(i, nodes.get(i), BlueIdCalculator.calculateBlueId(preliminary)));
        }

        indexedNodes.sort(Comparator
                .comparing((IndexedNode indexedNode) -> indexedNode.preliminaryBlueId)
                .thenComparingInt(indexedNode -> indexedNode.originalIndex));

        Map<Integer, Integer> originalIndexToSortedIndex = new HashMap<>();
        for (int sortedIndex = 0; sortedIndex < indexedNodes.size(); sortedIndex++) {
            originalIndexToSortedIndex.put(indexedNodes.get(sortedIndex).originalIndex, sortedIndex);
        }

        List<Node> sortedNodes = new ArrayList<>();
        for (IndexedNode indexedNode : indexedNodes) {
            Node rewritten = indexedNode.node.clone();
            rewriteThisReferences(rewritten, reference -> {
                int targetIndex = parseThisIndex(reference);
                return "this#" + originalIndexToSortedIndex.get(targetIndex);
            });
            sortedNodes.add(rewritten);
        }

        String blueId = BlueIdCalculator.calculateBlueId(sortedNodes);
        return new ParsedContent(blueId, JSON_MAPPER.valueToTree(sortedNodes), true);
    }

    public static JsonNode resolveThisReferences(JsonNode content, String currentBlueId, boolean isMultipleDocuments) {
        return resolveThisReferencesRecursive(content.deepCopy(), currentBlueId, isMultipleDocuments);
    }

    private static JsonNode resolveThisReferencesRecursive(JsonNode content, String currentBlueId, boolean isMultipleDocuments) {
        if (content.isObject()) {
            ObjectNode objectNode = (ObjectNode) content;
            objectNode.fields().forEachRemaining(entry -> {
                JsonNode value = entry.getValue();
                if (OBJECT_BLUE_ID.equals(entry.getKey()) && value.isTextual()) {
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
                if (element.isObject() || element.isArray()) {
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

    private static void validateSingleDocumentReferences(List<ThisReference> references) {
        for (ThisReference reference : references) {
            if (!"this".equals(reference.value)) {
                throw new IllegalArgumentException("For a single document, only 'this' is allowed as a reference, not 'this#<id>'");
            }
        }
    }

    private static void validateMultiDocumentReferences(List<ThisReference> references, int documentCount) {
        for (ThisReference reference : references) {
            Matcher matcher = THIS_INDEX_REFERENCE_PATTERN.matcher(reference.value);
            if (!matcher.matches()) {
                throw new IllegalArgumentException("For multiple documents, 'this' references must include an index (e.g., 'this#0')");
            }
            int targetIndex = Integer.parseInt(matcher.group(1));
            if (targetIndex >= documentCount) {
                throw new IllegalArgumentException("'this#" + targetIndex + "' points outside the cyclic document set.");
            }
        }
    }

    private static int parseThisIndex(String reference) {
        Matcher matcher = THIS_INDEX_REFERENCE_PATTERN.matcher(reference);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Expected indexed this reference but found: " + reference);
        }
        return Integer.parseInt(matcher.group(1));
    }

    private static List<ThisReference> findThisReferences(List<Node> nodes) {
        List<ThisReference> references = new ArrayList<>();
        nodes.forEach(node -> collectThisReferences(node, references));
        return references;
    }

    private static List<ThisReference> findThisReferences(Node node) {
        List<ThisReference> references = new ArrayList<>();
        collectThisReferences(node, references);
        return references;
    }

    private static void collectThisReferences(Node node, List<ThisReference> references) {
        if (node == null) {
            return;
        }
        if (node.getBlueId() != null && THIS_REFERENCE_PATTERN.matcher(node.getBlueId()).matches()) {
            references.add(new ThisReference(node.getBlueId()));
        }
        collectThisReferences(node.getType(), references);
        collectThisReferences(node.getItemType(), references);
        collectThisReferences(node.getKeyType(), references);
        collectThisReferences(node.getValueType(), references);
        collectThisReferences(node.getBlue(), references);
        collectThisReferences(node.getSchema(), references);
        if (node.getItems() != null) {
            node.getItems().forEach(item -> collectThisReferences(item, references));
        }
        if (node.getProperties() != null) {
            node.getProperties().values().forEach(value -> collectThisReferences(value, references));
        }
    }

    private static void collectThisReferences(Schema schema, List<ThisReference> references) {
        if (schema == null) {
            return;
        }
        collectThisReferences(schema.getRequired(), references);
        collectThisReferences(schema.getAllowMultiple(), references);
        collectThisReferences(schema.getMinLength(), references);
        collectThisReferences(schema.getMaxLength(), references);
        collectThisReferences(schema.getMinimum(), references);
        collectThisReferences(schema.getMaximum(), references);
        collectThisReferences(schema.getExclusiveMinimum(), references);
        collectThisReferences(schema.getExclusiveMaximum(), references);
        collectThisReferences(schema.getMultipleOf(), references);
        collectThisReferences(schema.getMinItems(), references);
        collectThisReferences(schema.getMaxItems(), references);
        collectThisReferences(schema.getUniqueItems(), references);
        collectThisReferences(schema.getMinFields(), references);
        collectThisReferences(schema.getMaxFields(), references);
        if (schema.getEnum() != null) {
            schema.getEnum().forEach(node -> collectThisReferences(node, references));
        }
    }

    private static void rewriteThisReferences(Node node, java.util.function.Function<String, String> replacement) {
        if (node == null) {
            return;
        }
        if (node.getBlueId() != null && THIS_REFERENCE_PATTERN.matcher(node.getBlueId()).matches()) {
            node.blueId(replacement.apply(node.getBlueId()));
        }
        rewriteThisReferences(node.getType(), replacement);
        rewriteThisReferences(node.getItemType(), replacement);
        rewriteThisReferences(node.getKeyType(), replacement);
        rewriteThisReferences(node.getValueType(), replacement);
        rewriteThisReferences(node.getBlue(), replacement);
        rewriteThisReferences(node.getSchema(), replacement);
        if (node.getItems() != null) {
            node.getItems().forEach(item -> rewriteThisReferences(item, replacement));
        }
        if (node.getProperties() != null) {
            node.getProperties().values().forEach(value -> rewriteThisReferences(value, replacement));
        }
    }

    private static void rewriteThisReferences(Schema schema, java.util.function.Function<String, String> replacement) {
        if (schema == null) {
            return;
        }
        rewriteThisReferences(schema.getRequired(), replacement);
        rewriteThisReferences(schema.getAllowMultiple(), replacement);
        rewriteThisReferences(schema.getMinLength(), replacement);
        rewriteThisReferences(schema.getMaxLength(), replacement);
        rewriteThisReferences(schema.getMinimum(), replacement);
        rewriteThisReferences(schema.getMaximum(), replacement);
        rewriteThisReferences(schema.getExclusiveMinimum(), replacement);
        rewriteThisReferences(schema.getExclusiveMaximum(), replacement);
        rewriteThisReferences(schema.getMultipleOf(), replacement);
        rewriteThisReferences(schema.getMinItems(), replacement);
        rewriteThisReferences(schema.getMaxItems(), replacement);
        rewriteThisReferences(schema.getUniqueItems(), replacement);
        rewriteThisReferences(schema.getMinFields(), replacement);
        rewriteThisReferences(schema.getMaxFields(), replacement);
        if (schema.getEnum() != null) {
            schema.getEnum().forEach(node -> rewriteThisReferences(node, replacement));
        }
    }

    private static class ThisReference {
        private final String value;

        private ThisReference(String value) {
            this.value = value;
        }
    }

    private static class IndexedNode {
        private final int originalIndex;
        private final Node node;
        private final String preliminaryBlueId;

        private IndexedNode(int originalIndex, Node node, String preliminaryBlueId) {
            this.originalIndex = originalIndex;
            this.node = node;
            this.preliminaryBlueId = preliminaryBlueId;
        }
    }
}
