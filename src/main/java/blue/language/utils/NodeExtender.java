package blue.language.utils;

import blue.language.NodeProvider;
import blue.language.model.Node;
import blue.language.utils.limits.Limits;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static blue.language.utils.Properties.CORE_TYPE_BLUE_IDS;

/**
 * Expands non-core BlueId references in a mutable node graph through a
 * {@link NodeProvider}.
 *
 * <p>Expansion mutates the supplied graph in place, follows caller-provided
 * {@link Limits}, and can reconstruct list-history fragments before traversing
 * their elements.</p>
 */
public class NodeExtender {

    /** Policy used when a referenced BlueId cannot be materialized. */
    public enum MissingElementStrategy {
        /** Fail the expansion immediately. */
        THROW_EXCEPTION,
        /** Leave the unresolved reference in place. */
        RETURN_EMPTY
    }

    private final NodeProvider nodeProvider;
    private final MissingElementStrategy strategy;

    /**
     * Creates a fail-fast extender.
     *
     * @param nodeProvider provider used to materialize references
     */
    public NodeExtender(NodeProvider nodeProvider) {
        this(nodeProvider, MissingElementStrategy.THROW_EXCEPTION);
    }

    /**
     * Creates an extender with an explicit missing-reference policy.
     *
     * @param nodeProvider provider used to materialize references
     * @param strategy behavior when a referenced node is unavailable
     */
    public NodeExtender(NodeProvider nodeProvider, MissingElementStrategy strategy) {
        this.nodeProvider = NodeProviderWrapper.wrap(nodeProvider);
        this.strategy = strategy;
    }

    /**
     * Expands eligible references in {@code node} in place.
     *
     * @param node mutable graph root to expand
     * @param limits traversal and reference-expansion limits
     * @throws IllegalArgumentException when fail-fast lookup cannot resolve a
     *                                  reference
     */
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
                extendNode(currentNode.getType(), currentLimits, Properties.OBJECT_TYPE, true);
            }
            if (currentNode.getItemType() != null) {
                extendNode(currentNode.getItemType(), currentLimits, Properties.OBJECT_ITEM_TYPE, true);
            }
            if (currentNode.getKeyType() != null) {
                extendNode(currentNode.getKeyType(), currentLimits, Properties.OBJECT_KEY_TYPE, true);
            }
            if (currentNode.getValueType() != null) {
                extendNode(currentNode.getValueType(), currentLimits, Properties.OBJECT_VALUE_TYPE, true);
            }
            if (currentNode.getContracts() != null) {
                extendNode(currentNode.getContracts(), currentLimits, Properties.OBJECT_CONTRACTS, false);
            }

            Map<String, Node> properties = currentNode.getProperties();
            if (properties != null) {
                properties.forEach((key, value) -> {
                    extendNode(value, currentLimits, key, false);
                });
            }

            List<Node> items = currentNode.getItems();
            if (items != null && !items.isEmpty()) {
                if (currentLimits.shouldReconstructList(currentNode, items)) {
                    reconstructList(items);
                }
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
        target.contracts(source.getContracts());
        target.schema(source.getSchema());
        target.mergePolicy(source.getMergePolicy());
        target.previousBlueId(source.getPreviousBlueId());
        target.position(source.getPosition());
    }
}
