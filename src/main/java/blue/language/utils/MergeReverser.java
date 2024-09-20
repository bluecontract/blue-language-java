package blue.language.utils;

import blue.language.model.Node;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Function;

import static blue.language.utils.Nodes.NodeField.*;
import static blue.language.utils.Nodes.hasFieldsAndMayHaveFields;

public class MergeReverser {

    public Node reverse(Node mergedNode) {
        Node minimalNode = new Node();
        reverseNode(minimalNode, mergedNode, null);
        return minimalNode;
    }

    private void reverseNode(Node minimal, Node merged, Node fromType) {

        if (merged.getBlueId() != null && fromType != null && merged.getBlueId().equals(fromType.getBlueId())) {
            return;
        }

        if (merged.getValue() != null && (fromType == null || fromType.getValue() == null)) {
            minimal.value(merged.getValue());
        }

        setTypeIfDifferent(merged, fromType, minimal, Node::getType, Node::type);
        setTypeIfDifferent(merged, fromType, minimal, Node::getItemType, Node::itemType);
        setTypeIfDifferent(merged, fromType, minimal, Node::getKeyType, Node::keyType);
        setTypeIfDifferent(merged, fromType, minimal, Node::getValueType, Node::valueType);

        if (merged.getName() != null && (fromType == null || !merged.getName().equals(fromType.getName()))) {
            minimal.name(merged.getName());
        }
        if (merged.getDescription() != null && (fromType == null || !merged.getDescription().equals(fromType.getDescription()))) {
            minimal.description(merged.getDescription());
        }

        if (merged.getBlueId() != null && (fromType == null || !merged.getBlueId().equals(fromType.getBlueId()))) {
            minimal.blueId(merged.getBlueId());
        }

        if (merged.getItems() != null) {
            int start = 0;
            List<Node> minimalItems = new ArrayList<>();
            if (fromType != null && fromType.getItems() != null) {
                String itemsBlueId = BlueIdCalculator.calculateBlueId(fromType.getItems());
                minimalItems.add(new Node().blueId(itemsBlueId));
                start = fromType.getItems().size();
            }
            if (merged.getItems().size() > start) {
                for (int i = start; i < merged.getItems().size(); i++) {
                    Node item = merged.getItems().get(i);
                    Node minimalItem = new Node();
                    reverseNode(minimalItem, item, null);
                    minimalItems.add(minimalItem);
                }
                minimal.items(minimalItems);
            }
        }

        if (merged.getProperties() != null) {
            Map<String, Node> minimalProperties = new HashMap<>();
            for (Map.Entry<String, Node> entry : merged.getProperties().entrySet()) {
                Node minimalProperty = new Node();
                Node typeProperty = (merged.getType() != null && merged.getType().getProperties() != null) ?
                        merged.getType().getProperties().get(entry.getKey()) : null;
                reverseNode(minimalProperty, entry.getValue(), typeProperty);
                if (!Nodes.isEmptyNode(minimalProperty)) {
                    minimalProperties.put(entry.getKey(), minimalProperty);
                }
            }
            if (!minimalProperties.isEmpty()) {
                minimal.properties(minimalProperties);
            }
        }

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
}