package blue.language.utils;

import blue.language.model.Node;
import blue.language.model.Schema;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Function;

import static blue.language.utils.Nodes.NodeField.*;
import static blue.language.utils.Nodes.hasFieldsAndMayHaveFields;

public class MergeReverser {

    /**
     * @deprecated Use {@link #reverseToCanonicalOverlay(Node)} for Content
     * BlueId identity or {@link #reverseToMinimizedOverlay(Node)} for
     * author-facing minimized output.
     */
    @Deprecated
    public Node reverse(Node mergedNode) {
        return reverseToMinimizedOverlay(mergedNode);
    }

    public Node reverseToMinimizedOverlay(Node mergedNode) {
        Node minimalNode = new Node();
        reverseNode(minimalNode, mergedNode, mergedNode.getType(), false);
        return minimalNode;
    }

    public Node reverseToCanonicalOverlay(Node mergedNode) {
        Node minimalNode = new Node();
        reverseNode(minimalNode, mergedNode, mergedNode.getType(), true);
        return minimalNode;
    }

    private void reverseNode(Node minimal, Node merged, Node fromType, boolean canonicalOverlay) {

        if (merged.getBlueId() != null && fromType != null && merged.getBlueId().equals(fromType.getBlueId())) {
            return;
        }

        if (merged.getValue() != null
                && (fromType == null
                || fromType.getValue() == null
                || !Objects.equals(merged.getValue(), fromType.getValue()))) {
            minimal.value(merged.getValue());
        }

        setTypeIfDifferent(merged, fromType, minimal, Node::getType, Node::type);
        setTypeIfDifferent(merged, fromType, minimal, Node::getItemType, Node::itemType);
        setTypeIfDifferent(merged, fromType, minimal, Node::getKeyType, Node::keyType);
        setTypeIfDifferent(merged, fromType, minimal, Node::getValueType, Node::valueType);
        preservePayloadTypeForMetadataOverride(merged, minimal);

        if (merged.getName() != null && (fromType == null || !merged.getName().equals(fromType.getName()))) {
            minimal.name(merged.getName());
        }
        if (merged.getDescription() != null && (fromType == null || !merged.getDescription().equals(fromType.getDescription()))) {
            minimal.description(merged.getDescription());
        }

        if (merged.isReferenceOnly() && (fromType == null || !merged.getBlueId().equals(fromType.getBlueId()))) {
            minimal.blueId(merged.getBlueId());
        }
        if (merged.getMergePolicy() != null && (fromType == null || !merged.getMergePolicy().equals(fromType.getMergePolicy()))) {
            minimal.mergePolicy(merged.getMergePolicy());
        }
        if (merged.getSchema() != null && (fromType == null || !sameSchema(merged.getSchema(), fromType.getSchema()))) {
            minimal.schema(merged.getSchema().clone());
        }
        if (merged.getContracts() != null) {
            Node fromTypeContracts = fromType != null ? fromType.getContracts() : null;
            if (!sameNodeBlueId(merged.getContracts(), fromTypeContracts)) {
                Node minimalContracts = new Node();
                reverseNode(minimalContracts, merged.getContracts(), fromTypeContracts, canonicalOverlay);
                if (!Nodes.isEmptyNode(minimalContracts)) {
                    minimal.contracts(minimalContracts);
                }
            }
        }

        if (merged.getItems() != null) {
            List<Node> minimalItems = new ArrayList<>();
            if (canonicalOverlay) {
                for (Node item : merged.getItems()) {
                    Node minimalItem = new Node();
                    reverseNode(minimalItem, item, null, true);
                    if (Nodes.isEmptyNode(minimalItem)) {
                        minimalItems.add(Nodes.emptyPlaceholder());
                    } else {
                        minimalItems.add(minimalItem);
                    }
                }
                minimal.items(minimalItems);
            } else if (fromType != null && fromType.getItems() != null) {
                List<Node> inheritedItems = fromType.getItems();
                int inheritedSize = inheritedItems.size();
                if (merged.getItems().size() < inheritedSize) {
                    throw new IllegalStateException("Cannot reverse-minimize a list shorter than its inherited list without an explicit list-deletion control.");
                }
                int commonSize = Math.min(merged.getItems().size(), inheritedSize);

                for (int i = 0; i < commonSize; i++) {
                    if (sameNodeBlueId(merged.getItems().get(i), inheritedItems.get(i))) {
                        continue;
                    }
                    Node minimalItem = new Node();
                    reverseNode(minimalItem, merged.getItems().get(i), inheritedItems.get(i), false);
                    if (!Nodes.isEmptyNode(minimalItem)) {
                        minimalItem.position(i);
                        minimalItems.add(minimalItem);
                    }
                }

                for (int i = inheritedSize; i < merged.getItems().size(); i++) {
                    Node minimalItem = new Node();
                    reverseNode(minimalItem, merged.getItems().get(i), null, false);
                    minimalItems.add(minimalItem);
                }

                if (!minimalItems.isEmpty()) {
                    String itemsBlueId = BlueIdCalculator.calculateBlueId(inheritedItems);
                    minimalItems.add(0, new Node().previousBlueId(itemsBlueId));
                    minimal.items(minimalItems);
                }
            } else {
                for (Node item : merged.getItems()) {
                    Node minimalItem = new Node();
                    reverseNode(minimalItem, item, null, false);
                    minimalItems.add(minimalItem);
                }
                minimal.items(minimalItems);
            }
        }

        if (merged.getProperties() != null) {
            Map<String, Node> minimalProperties = new LinkedHashMap<>();
            for (Map.Entry<String, Node> entry : merged.getProperties().entrySet()) {
                String key = entry.getKey();
                Node mergedProperty = entry.getValue();
                Node fromTypeProperty = null;
                if (fromType != null && fromType.getProperties() != null) {
                    fromTypeProperty = fromType.getProperties().get(key);
                }
                if (sameNodeBlueId(mergedProperty, fromTypeProperty)) {
                    continue;
                }
                Node minimalProperty = new Node();
                reverseNode(minimalProperty, mergedProperty, fromTypeProperty, canonicalOverlay);
                if (!Nodes.isEmptyNode(minimalProperty)) {
                    minimalProperties.put(key, minimalProperty);
                }
            }
            if (!minimalProperties.isEmpty()) {
                minimal.properties(minimalProperties);
            }
        }

    }

    private boolean sameSchema(Schema left, Schema right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        return BlueIdCalculator.calculateBlueId(new Node().schema(left))
                .equals(BlueIdCalculator.calculateBlueId(new Node().schema(right)));
    }

    private boolean sameNodeBlueId(Node left, Node right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        return comparisonBlueId(left).equals(comparisonBlueId(right));
    }

    private String comparisonBlueId(Node node) {
        return BlueIdCalculator.INSTANCE.calculate(NodeToBlueIdInput.getWithResolvedBlueIdMetadata(node));
    }

    private void setTypeIfDifferent(Node merged, Node fromType, Node minimal,
                                    Function<Node, Node> typeGetter,
                                    BiConsumer<Node, Node> typeSetter) {
        Node mergedType = typeGetter.apply(merged);
        if (mergedType != null && (fromType == null || typeGetter.apply(fromType) == null ||
                                   !typeGetter.apply(fromType).getBlueId().equals(mergedType.getBlueId()))) {
            Node typeNode = new Node().blueId(mergedType.getBlueId());
            typeSetter.accept(minimal, typeNode);
        }
    }

    private void preservePayloadTypeForMetadataOverride(Node merged, Node minimal) {
        if (minimal.getType() != null || merged.getType() == null) {
            return;
        }
        if (minimal.getItemType() == null && minimal.getKeyType() == null && minimal.getValueType() == null) {
            return;
        }

        Node mergedType = merged.getType();
        Node typeNode = mergedType.getBlueId() != null
                ? new Node().blueId(mergedType.getBlueId())
                : mergedType.clone();
        minimal.type(typeNode);
    }
}
