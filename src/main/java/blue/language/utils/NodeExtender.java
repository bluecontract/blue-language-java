package blue.language.utils;

import blue.language.NodeProvider;
import blue.language.model.Node;
import blue.language.utils.limits.Limits;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
        extendNode(node, limits, "");
    }

    private void extendNode(Node currentNode, Limits currentLimits, String currentSegment) {
        extendNode(currentNode, currentLimits, currentSegment, false);
    }

    private void extendNode(Node currentNode, Limits currentLimits, String currentSegment, boolean skipLimitCheck) {
        if (!skipLimitCheck) {
            if (!currentLimits.shouldExtendPathSegment(currentSegment, currentNode)) {
                return;
            }

            currentLimits.enterPathSegment(currentSegment, currentNode);
        }

        try {
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

            // Handle type nodes
            if (currentNode.getType() != null) {
                extendNode(currentNode.getType(), currentLimits, "type", true);
            }
            if (currentNode.getItemType() != null) {
                extendNode(currentNode.getItemType(), currentLimits, "itemType", true);
            }
            if (currentNode.getKeyType() != null) {
                extendNode(currentNode.getKeyType(), currentLimits, "keyType", true);
            }
            if (currentNode.getValueType() != null) {
                extendNode(currentNode.getValueType(), currentLimits, "valueType", true);
            }

            Map<String, Node> properties = currentNode.getProperties();
            if (properties != null) {
                properties.forEach((key, value) -> {
                    extendNode(value, currentLimits, key, false);
                });
            }

            List<Node> items = currentNode.getItems();
            if (items != null && !items.isEmpty()) {
                for (int i = 0; i < items.size(); i++) {
                    extendNode(items.get(i), currentLimits, String.valueOf(i), false);
                }
            }
        } finally {
            if (!skipLimitCheck) {
                currentLimits.exitPathSegment();
            }
        }
    }

    private String appendPath(String currentPath, String segment) {
        if (currentPath.isEmpty()) {
            return segment;
        } else if (currentPath.equals("/")) {
            return "/" + segment;
        } else {
            return currentPath + "/" + segment;
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