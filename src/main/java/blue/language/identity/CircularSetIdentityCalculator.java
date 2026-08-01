package blue.language.identity;

import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.utils.BlueIds;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Calculates stable member BlueIds for a closed set of mutually referencing
 * documents.
 *
 * <p>Members are ordered by their placeholder-based preliminary identity,
 * making the master fold independent of caller order. A member is never
 * hashed independently as final cyclic evidence.</p>
 */
public final class CircularSetIdentityCalculator {

    private static final CircularSetIdentityCalculator SHARED =
            new CircularSetIdentityCalculator();

    /** Calculates cyclic-set member BlueIds in source order. */
    public static List<String> calculateCircularSetBlueIds(
            List<Node> documents) {
        return SHARED.circularBlueIds(documents);
    }

    private static final Pattern THIS_REFERENCE_PATTERN = Pattern.compile(
            "^" + BlueIds.THIS_PLACEHOLDER
                    + "("
                    + Pattern.quote(BlueIds.CYCLIC_MEMBER_SEPARATOR)
                    + "\\d+)?$");
    private static final Pattern THIS_INDEX_REFERENCE_PATTERN = Pattern.compile(
            "^" + BlueIds.THIS_MEMBER_PREFIX + "(\\d+)$");

    private final DirectBlueIdCalculator directCalculator;

    /** Creates a calculator using the normative direct identity path. */
    public CircularSetIdentityCalculator() {
        this(new DirectBlueIdCalculator());
    }

    /**
     * Creates a calculator with an explicit direct identity implementation.
     *
     * @param directCalculator direct BlueId calculator
     */
    public CircularSetIdentityCalculator(
            DirectBlueIdCalculator directCalculator) {
        this.directCalculator = Objects.requireNonNull(
                directCalculator,
                "directCalculator");
    }

    /**
     * Returns member identifiers in the same order as {@code documents}.
     *
     * @param documents non-empty cyclic document set
     * @return calculated member BlueIds
     */
    public List<String> circularBlueIds(List<Node> documents) {
        if (documents == null || documents.isEmpty()) {
            throw new IllegalArgumentException(
                    "Circular BlueId calculation requires at least one document.");
        }
        List<ThisReference> references = findThisReferences(documents);
        if (references.isEmpty()) {
            throw new IllegalArgumentException(
                    "Circular BlueId calculation requires at least one internal this reference.");
        }
        validateMultiDocumentReferences(references, documents.size());

        List<IndexedNode> indexedNodes = new ArrayList<>();
        for (int index = 0; index < documents.size(); index++) {
            Node preliminary = documents.get(index).clone();
            rewriteThisReferences(
                    preliminary,
                    reference ->
                            BlueIds.CYCLIC_CALCULATION_ZERO_PLACEHOLDER);
            indexedNodes.add(new IndexedNode(
                    index,
                    documents.get(index),
                    directCalculator
                            .directBlueIdAllowingCyclicPlaceholders(
                                    preliminary)));
        }
        rejectDuplicatePreliminaryInputs(indexedNodes);

        indexedNodes.sort(Comparator
                .comparing((IndexedNode member) -> member.preliminaryBlueId)
                .thenComparingInt(member -> member.originalIndex));

        Map<Integer, Integer> sortedIndexByOriginalIndex = new HashMap<>();
        for (int sortedIndex = 0;
             sortedIndex < indexedNodes.size();
             sortedIndex++) {
            sortedIndexByOriginalIndex.put(
                    indexedNodes.get(sortedIndex).originalIndex,
                    sortedIndex);
        }

        List<Node> sortedNodes = new ArrayList<>();
        for (IndexedNode indexedNode : indexedNodes) {
            Node rewritten = indexedNode.node.clone();
            rewriteThisReferences(rewritten, reference -> {
                int targetIndex = parseThisIndex(reference);
                return BlueIds.indexedThisPlaceholder(
                        sortedIndexByOriginalIndex.get(targetIndex));
            });
            sortedNodes.add(rewritten);
        }

        String masterBlueId = directCalculator
                .directBlueIdAllowingCyclicPlaceholders(sortedNodes);
        List<String> result = new ArrayList<>(documents.size());
        for (int originalIndex = 0;
             originalIndex < documents.size();
             originalIndex++) {
            result.add(BlueIds.indexedCyclicMemberBlueId(
                    masterBlueId,
                    sortedIndexByOriginalIndex.get(originalIndex)));
        }
        return result;
    }

    private void rejectDuplicatePreliminaryInputs(
            List<IndexedNode> indexedNodes) {
        Map<String, Integer> firstIndexByBlueId = new HashMap<>();
        for (IndexedNode indexedNode : indexedNodes) {
            Integer firstIndex = firstIndexByBlueId.putIfAbsent(
                    indexedNode.preliminaryBlueId,
                    indexedNode.originalIndex);
            if (firstIndex != null) {
                throw new IllegalArgumentException(
                        "Duplicate preliminary cyclic BlueId input for members "
                                + firstIndex + " and "
                                + indexedNode.originalIndex + ".");
            }
        }
    }

    private void validateMultiDocumentReferences(
            List<ThisReference> references,
            int documentCount) {
        for (ThisReference reference : references) {
            Matcher matcher = THIS_INDEX_REFERENCE_PATTERN.matcher(
                    reference.value);
            if (!matcher.matches()) {
                throw new IllegalArgumentException(
                        "Cyclic BlueId calculation requires indexed 'this#<index>' references.");
            }
            int targetIndex = Integer.parseInt(matcher.group(1));
            if (targetIndex >= documentCount) {
                throw new IllegalArgumentException(
                        "'" + BlueIds.indexedThisPlaceholder(targetIndex)
                                + "' points outside the cyclic document set.");
            }
        }
    }

    private int parseThisIndex(String reference) {
        Matcher matcher = THIS_INDEX_REFERENCE_PATTERN.matcher(reference);
        if (!matcher.matches()) {
            throw new IllegalArgumentException(
                    "Expected indexed this reference but found: " + reference);
        }
        return Integer.parseInt(matcher.group(1));
    }

    private List<ThisReference> findThisReferences(List<Node> nodes) {
        List<ThisReference> references = new ArrayList<>();
        for (Node node : nodes) {
            collectThisReferences(node, references);
        }
        return references;
    }

    private void collectThisReferences(
            Node node,
            List<ThisReference> references) {
        if (node == null) {
            return;
        }
        if (node.getBlueId() != null
                && THIS_REFERENCE_PATTERN.matcher(node.getBlueId()).matches()) {
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
            for (Node item : node.getItems()) {
                collectThisReferences(item, references);
            }
        }
        if (node.getProperties() != null) {
            for (Node value : node.getProperties().values()) {
                collectThisReferences(value, references);
            }
        }
    }

    private void collectThisReferences(
            Schema schema,
            List<ThisReference> references) {
        if (schema == null) {
            return;
        }
        if (schema.getBlueId() != null
                && THIS_REFERENCE_PATTERN
                .matcher(schema.getBlueId()).matches()) {
            references.add(new ThisReference(schema.getBlueId()));
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
            for (Node value : schema.getEnum()) {
                collectThisReferences(value, references);
            }
        }
    }

    private void rewriteThisReferences(
            Node node,
            Function<String, String> replacement) {
        if (node == null) {
            return;
        }
        if (node.getBlueId() != null
                && THIS_REFERENCE_PATTERN.matcher(node.getBlueId()).matches()) {
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
            for (Node item : node.getItems()) {
                rewriteThisReferences(item, replacement);
            }
        }
        if (node.getProperties() != null) {
            for (Node value : node.getProperties().values()) {
                rewriteThisReferences(value, replacement);
            }
        }
    }

    private void rewriteThisReferences(
            Schema schema,
            Function<String, String> replacement) {
        if (schema == null) {
            return;
        }
        if (schema.getBlueId() != null
                && THIS_REFERENCE_PATTERN
                .matcher(schema.getBlueId()).matches()) {
            schema.blueId(replacement.apply(schema.getBlueId()));
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
            for (Node value : schema.getEnum()) {
                rewriteThisReferences(value, replacement);
            }
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

        private IndexedNode(
                int originalIndex,
                Node node,
                String preliminaryBlueId) {
            this.originalIndex = originalIndex;
            this.node = node;
            this.preliminaryBlueId = preliminaryBlueId;
        }
    }
}
