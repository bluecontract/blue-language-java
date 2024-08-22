package blue.language.utils;

import blue.language.NodeProvider;
import blue.language.model.Node;
import blue.language.utils.limits.Limits;
import blue.language.utils.limits.PathLimits;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static blue.language.utils.Properties.CORE_TYPES;
import static blue.language.utils.Properties.CORE_TYPE_BLUE_IDS;

public class NodeExtender {

    public enum MissingElementStrategy {
        THROW_EXCEPTION,
        RETURN_EMPTY
    }

    private final NodeProvider nodeProvider;
    private final MissingElementStrategy strategy;

    public NodeExtender(NodeProvider nodeProvider) {
        this(nodeProvider, MissingElementStrategy.THROW_EXCEPTION);
    }

    public NodeExtender(NodeProvider nodeProvider, MissingElementStrategy strategy) {
        this.nodeProvider = NodeProviderWrapper.wrap(nodeProvider);
        this.strategy = strategy;
    }

    public void extend(Node node, Limits limits) {
        extendNode(node, limits);
    }

    private void extendNode(Node currentNode, Limits currentLimits) {
        if (currentNode.getBlueId() != null && !CORE_TYPE_BLUE_IDS.contains(currentNode.getBlueId())) {
            List<Node> resolvedNodes = fetchNode(currentNode);
            if (resolvedNodes != null && !resolvedNodes.isEmpty()) {
                if (resolvedNodes.size() == 1) {
                    Node resolvedNode = resolvedNodes.get(0);
                    mergeNodes(currentNode, resolvedNode);
                } else {
                    List<Node> mergedNodes = resolvedNodes.stream()
                            .map(Node::clone)
                            .collect(Collectors.toList());
                    Node listNode = new Node().items(mergedNodes);
                    mergeNodes(currentNode, listNode);
                }
            }
        }

        if (currentNode.getType() != null) {
            extendNode(currentNode.getType(), currentLimits);
        }
        if (currentNode.getItemType() != null) {
            extendNode(currentNode.getItemType(), currentLimits);
        }
        if (currentNode.getKeyType() != null) {
            extendNode(currentNode.getKeyType(), currentLimits);
        }
        if (currentNode.getValueType() != null) {
            extendNode(currentNode.getValueType(), currentLimits);
        }

        Map<String, Node> properties = currentNode.getProperties();
        if (properties != null) {
            properties.forEach((key, value) -> {
                if (currentLimits.shouldProcessPathSegment(key)) {
                    currentLimits.enterPathSegment(key);
                    extendNode(value, currentLimits);
                    currentLimits.exitPathSegment();
                }
            });
        }

        List<Node> items = currentNode.getItems();
        if (items != null && !items.isEmpty()) {
            reconstructList(items);
            for (int i = 0; i < items.size(); i++) {
                if (currentLimits.shouldProcessPathSegment(String.valueOf(i))) {
                    currentLimits.enterPathSegment(String.valueOf(i));
                    extendNode(items.get(i), currentLimits);
                    currentLimits.exitPathSegment();
                }
            }
        }
    }

    private void reconstructList(List<Node> items) {
        while (!items.isEmpty()) {
            Node firstItem = items.get(0);
            String blueId = firstItem.getBlueId();
            if (blueId == null) {
                break;
            }
            List<Node> resolved = nodeProvider.fetchByBlueId(blueId);
            if (resolved == null || resolved.size() == 1) {
                break;
            }
            items.remove(0);
            items.addAll(0, resolved);
        }
    }

    private List<Node> fetchNode(Node node) {
        List<Node> resolvedNodes = nodeProvider.fetchByBlueId(node.getBlueId());
        if (resolvedNodes == null || resolvedNodes.isEmpty()) {
            if (strategy == MissingElementStrategy.RETURN_EMPTY) {
                return null;
            } else {
                throw new IllegalArgumentException("No content found for blueId: " + node.getBlueId());
            }
        }
        return resolvedNodes;
    }

    private void mergeNodes(Node target, Node source) {
        target.name(source.getName());
        target.description(source.getDescription());
        target.type(source.getType());
        target.itemType(source.getItemType());
        target.keyType(source.getKeyType());
        target.valueType(source.getValueType());
        target.value(source.getValue());
        target.items(source.getItems());
        target.properties(source.getProperties());
        target.constraints(source.getConstraints());
    }

}