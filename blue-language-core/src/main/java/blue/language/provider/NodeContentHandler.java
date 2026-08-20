package blue.language.provider;

import blue.language.model.wire.BlueLanguageConstants;

import blue.language.identity.CircularSetIdentityCalculator;
import blue.language.identity.CyclicSetFinalization;
import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.identity.BlueIds;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static blue.language.model.wire.BlueLanguageConstants.OBJECT_BLUE_ID;
import static blue.language.codec.jackson.UncheckedObjectMapper.JSON_MAPPER;
import static blue.language.codec.jackson.UncheckedObjectMapper.YAML_MAPPER;

/**
 * Parses provider source, preprocesses it, and calculates plain or cyclic-set
 * content identities.
 *
 * <p>{@code this} placeholders are retained in stored content and are resolved
 * only when content is fetched under its calculated identity.</p>
 */
public class NodeContentHandler {

    private static final CircularSetIdentityCalculator
            CIRCULAR_SET_IDENTITY_CALCULATOR =
            new CircularSetIdentityCalculator();

    private static final Pattern THIS_REFERENCE_PATTERN =
            Pattern.compile(
                    "^" + BlueIds.THIS_PLACEHOLDER
                            + "("
                            + Pattern.quote(
                                    BlueIds.CYCLIC_MEMBER_SEPARATOR)
                            + "\\d+)?$");
    /**
     * Creates a compatibility facade over the static content helpers.
     */
    public NodeContentHandler() {
    }

    /** Parsed canonical content plus the identity and storage-shape metadata. */
    public static class ParsedContent {
        /** Calculated plain or cyclic-set master BlueId. */
        public final String blueId;
        /** Preprocessed content retained with authored {@code this} placeholders. */
        public final JsonNode content;
        /** Whether the stored value is a multi-document set. */
        public final boolean isMultipleDocuments;

        /**
         * Creates parsed-content metadata.
         *
         * @param blueId calculated plain or cyclic-set master identity
         * @param content retained preprocessed JSON content
         * @param isMultipleDocuments whether the content is a document set
         */
        public ParsedContent(String blueId, JsonNode content, boolean isMultipleDocuments) {
            this.blueId = blueId;
            this.content = content;
            this.isMultipleDocuments = isMultipleDocuments;
        }
    }

    /**
     * Parses YAML or JSON source, applies preprocessing, and calculates its
     * identity.
     *
     * @param content source document or document set
     * @param preprocessor preprocessing function
     * @return parsed canonical content and identity metadata
     * @throws RuntimeException when the source cannot be parsed or normalized
     */
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

    /**
     * Applies preprocessing to one node and calculates its retained identity.
     *
     * @param node source node
     * @param preprocessor preprocessing function
     * @return parsed canonical content and identity metadata
     */
    public static ParsedContent parseAndCalculateBlueId(Node node, Function<Node, Node> preprocessor) {
        Node preprocessedNode = preprocessor.apply(node);
        return calculateParsedContent(preprocessedNode);
    }

    /**
     * Applies preprocessing to an ordered document set and calculates its
     * retained identity.
     *
     * @param nodes non-empty source document set
     * @param preprocessor preprocessing function
     * @return parsed canonical content and identity metadata
     * @throws IllegalArgumentException when {@code nodes} is null or empty
     */
    public static ParsedContent parseAndCalculateBlueId(List<Node> nodes, Function<Node, Node> preprocessor) {
        return parseAndCalculateBlueId(
                nodes,
                preprocessor,
                CIRCULAR_SET_IDENTITY_CALCULATOR);
    }

    static ParsedContent parseAndCalculateBlueId(
            List<Node> nodes,
            Function<Node, Node> preprocessor,
            CircularSetIdentityCalculator circularSetIdentityCalculator) {
        if (nodes == null || nodes.isEmpty()) {
            throw new IllegalArgumentException("List of nodes cannot be null or empty");
        }
        Objects.requireNonNull(
                circularSetIdentityCalculator,
                "circularSetIdentityCalculator");

        List<Node> preprocessedNodes = nodes.stream()
                .map(preprocessor)
                .collect(Collectors.toList());

        return calculateParsedContent(
                preprocessedNodes,
                circularSetIdentityCalculator);
    }

    private static ParsedContent calculateParsedContent(Node node) {
        List<ThisReference> references = findThisReferences(node);
        if (references.isEmpty()) {
            String blueId = DirectBlueIdCalculator.calculateBlueId(node);
            return new ParsedContent(blueId, JSON_MAPPER.valueToTree(node), false);
        }

        validateSingleDocumentReferences(references);
        Node preliminary = node.clone();
        rewriteThisReferences(
                preliminary,
                reference -> BlueIds.CYCLIC_CALCULATION_ZERO_PLACEHOLDER);

        String blueId = DirectBlueIdCalculator.calculateBlueIdAllowingCyclicPlaceholders(preliminary);
        return new ParsedContent(blueId, JSON_MAPPER.valueToTree(node), false);
    }

    private static ParsedContent calculateParsedContent(List<Node> nodes) {
        return calculateParsedContent(
                nodes,
                CIRCULAR_SET_IDENTITY_CALCULATOR);
    }

    private static ParsedContent calculateParsedContent(
            List<Node> nodes,
            CircularSetIdentityCalculator circularSetIdentityCalculator) {
        boolean isMultipleDocuments = nodes.size() > 1;
        List<ThisReference> references = findThisReferences(nodes);
        if (!isMultipleDocuments || references.isEmpty()) {
            String blueId = DirectBlueIdCalculator.calculateBlueId(nodes);
            return new ParsedContent(blueId, JSON_MAPPER.valueToTree(nodes), isMultipleDocuments);
        }

        CyclicSetFinalization finalization =
                circularSetIdentityCalculator.finalizeCyclicSet(nodes);
        return new ParsedContent(
                finalization.masterBlueId(),
                JSON_MAPPER.valueToTree(
                        finalization.canonicalMemberBodies()),
                true);
    }

    /**
     * Returns a deep copy with cyclic placeholders resolved relative to the
     * supplied calculated identity.
     *
     * @param content retained content containing authored placeholders
     * @param currentBlueId calculated plain or cyclic-set master identity
     * @param isMultipleDocuments whether content is a document set
     * @return deep copy with every {@code this} placeholder resolved
     * @throws IllegalArgumentException when placeholder syntax is incompatible
     *                                  with the storage shape
     */
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
            if (!textValue.startsWith(
                    BlueIds.THIS_MEMBER_PREFIX)) {
                throw new IllegalArgumentException(
                        "For multiple documents, 'this' references must "
                                + "include an index (e.g., '"
                                + BlueIds.indexedThisPlaceholder(0)
                                + "')");
            }
            return currentBlueId + textValue.substring(
                    BlueIds.THIS_PLACEHOLDER.length());
        } else {
            if (textValue.equals(
                    BlueIds.THIS_PLACEHOLDER)) {
                return currentBlueId;
            } else {
                throw new IllegalArgumentException(
                        "For a single document, only 'this' is allowed as a "
                                + "reference, not '"
                                + BlueIds.THIS_MEMBER_PREFIX
                                + "<id>'");
            }
        }
    }

    private static void validateSingleDocumentReferences(List<ThisReference> references) {
        for (ThisReference reference : references) {
            if (!BlueIds.THIS_PLACEHOLDER.equals(
                    reference.value)) {
                throw new IllegalArgumentException(
                        "For a single document, only 'this' is allowed as a "
                                + "reference, not '"
                                + BlueIds.THIS_MEMBER_PREFIX
                                + "<id>'");
            }
        }
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
        collectThisReferences(node.getContracts(), references);
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
        if (schema.getBlueId() != null
                && THIS_REFERENCE_PATTERN
                .matcher(schema.getBlueId()).matches()) {
            references.add(
                    new ThisReference(
                            schema.getBlueId()));
        }
        collectThisReferences(schema.getRequired(), references);
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
        rewriteThisReferences(node.getContracts(), replacement);
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
        if (schema.getBlueId() != null
                && THIS_REFERENCE_PATTERN
                .matcher(schema.getBlueId()).matches()) {
            schema.blueId(replacement.apply(
                    schema.getBlueId()));
        }
        rewriteThisReferences(schema.getRequired(), replacement);
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

}
