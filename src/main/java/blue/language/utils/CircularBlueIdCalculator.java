package blue.language.utils;

import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.provider.NodeContentHandler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CircularBlueIdCalculator {

    private static final Pattern THIS_REFERENCE_PATTERN = Pattern.compile("^this(#\\d+)?$");
    private static final Pattern THIS_INDEX_REFERENCE_PATTERN = Pattern.compile("^this#(\\d+)$");

    private CircularBlueIdCalculator() {
    }

    public static List<String> calculateCircularSetBlueIds(List<Node> documents) {
        if (documents == null || documents.isEmpty()) {
            throw new IllegalArgumentException("Circular BlueId calculation requires at least one document.");
        }
        List<ThisReference> references = findThisReferences(documents);
        if (references.isEmpty()) {
            throw new IllegalArgumentException("Circular BlueId calculation requires at least one internal this reference.");
        }
        validateMultiDocumentReferences(references, documents.size());

        List<IndexedNode> indexedNodes = new ArrayList<>();
        for (int i = 0; i < documents.size(); i++) {
            Node preliminary = documents.get(i).clone();
            rewriteThisReferences(preliminary, reference -> NodeContentHandler.ZERO_BLUE_ID);
            indexedNodes.add(new IndexedNode(i, documents.get(i),
                    BlueIdCalculator.calculateBlueIdAllowingCyclicPlaceholders(preliminary)));
        }
        rejectDuplicatePreliminaryInputs(indexedNodes);

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

        String masterBlueId = BlueIdCalculator.calculateBlueIdAllowingCyclicPlaceholders(sortedNodes);
        List<String> result = new ArrayList<>(documents.size());
        for (int originalIndex = 0; originalIndex < documents.size(); originalIndex++) {
            result.add(masterBlueId + "#" + originalIndexToSortedIndex.get(originalIndex));
        }
        return result;
    }

    private static void rejectDuplicatePreliminaryInputs(List<IndexedNode> indexedNodes) {
        Map<String, Integer> firstIndexByPreliminaryBlueId = new HashMap<>();
        for (IndexedNode indexedNode : indexedNodes) {
            Integer firstIndex = firstIndexByPreliminaryBlueId.putIfAbsent(
                    indexedNode.preliminaryBlueId,
                    indexedNode.originalIndex);
            if (firstIndex != null) {
                throw new IllegalArgumentException("Duplicate preliminary cyclic BlueId input for members "
                        + firstIndex + " and " + indexedNode.originalIndex + ".");
            }
        }
    }

    private static void validateMultiDocumentReferences(List<ThisReference> references, int documentCount) {
        for (ThisReference reference : references) {
            Matcher matcher = THIS_INDEX_REFERENCE_PATTERN.matcher(reference.value);
            if (!matcher.matches()) {
                throw new IllegalArgumentException("Cyclic BlueId calculation requires indexed 'this#<index>' references.");
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

    private static final class ThisReference {
        private final String value;

        private ThisReference(String value) {
            this.value = value;
        }
    }

    private static final class IndexedNode {
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
