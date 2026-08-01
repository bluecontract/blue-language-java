package blue.language.graph;

import blue.language.model.wire.BlueLanguageConstants;

import blue.language.provider.NodeProvider;
import blue.language.registry.NodeProviderWrapper;
import blue.language.model.Node;
import blue.language.utils.limits.Limits;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static blue.language.model.wire.BlueLanguageConstants.CORE_TYPE_BLUE_IDS;

/**
 * Expands non-core BlueId references in a mutable node graph through a
 * {@link NodeProvider}.
 *
 * <p>Expansion materializes verified content for an existing node and
 * therefore preserves that node's BlueId. It mutates the supplied graph in
 * place, follows caller-provided {@link Limits}, and can reconstruct
 * list-history fragments before traversing their elements.</p>
 */
public final class NodeExpander {

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
     * Creates a fail-fast expander.
     *
     * @param nodeProvider provider used to materialize references
     */
    public NodeExpander(NodeProvider nodeProvider) {
        this(nodeProvider, MissingElementStrategy.THROW_EXCEPTION);
    }

    /**
     * Creates an expander with an explicit missing-reference policy.
     *
     * @param nodeProvider provider used to materialize references
     * @param strategy behavior when a referenced node is unavailable
     */
    public NodeExpander(NodeProvider nodeProvider, MissingElementStrategy strategy) {
        this.nodeProvider = NodeProviderWrapper.wrap(
                Objects.requireNonNull(nodeProvider, "nodeProvider"));
        this.strategy = Objects.requireNonNull(strategy, "strategy");
    }

    /**
     * Expands eligible references in {@code node} in place.
     *
     * @param node mutable graph root to expand
     * @param limits traversal and reference-expansion limits
     * @throws IllegalArgumentException when fail-fast lookup cannot resolve a
     *                                  reference
     */
    public void expand(Node node, Limits limits) {
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(limits, "limits");
        expandNode(node, limits, "");
    }

    private void expandNode(Node currentNode, Limits currentLimits, String currentSegment) {
        expandNode(currentNode, currentLimits, currentSegment, false);
    }

    private void expandNode(Node currentNode,
                            Limits currentLimits,
                            String currentSegment,
                            boolean skipLimitCheck) {
        if (!skipLimitCheck) {
            if (!currentLimits.shouldExpandPathSegment(currentSegment, currentNode)) {
                return;
            }

            currentLimits.enterPathSegment(currentSegment, currentNode);
        }

        try {
            if (currentNode.getBlueId() != null
                    && !CORE_TYPE_BLUE_IDS.contains(currentNode.getBlueId())) {
                List<Node> resolvedNodes = fetchNode(currentNode);
                if (resolvedNodes != null && !resolvedNodes.isEmpty()) {
                    if (resolvedNodes.size() == 1) {
                        mergeNodes(currentNode, resolvedNodes.get(0));
                    } else {
                        List<Node> mergedNodes = resolvedNodes.stream()
                                .map(Node::clone)
                                .collect(Collectors.toList());
                        mergeNodes(currentNode, new Node().items(mergedNodes));
                    }
                }
            }

            expandSemanticChildren(currentNode, currentLimits);
        } finally {
            if (!skipLimitCheck) {
                currentLimits.exitPathSegment();
            }
        }
    }

    private void expandSemanticChildren(Node currentNode, Limits currentLimits) {
        if (currentNode.getType() != null) {
            expandNode(currentNode.getType(), currentLimits, BlueLanguageConstants.OBJECT_TYPE, true);
        }
        if (currentNode.getItemType() != null) {
            expandNode(currentNode.getItemType(), currentLimits, BlueLanguageConstants.OBJECT_ITEM_TYPE, true);
        }
        if (currentNode.getKeyType() != null) {
            expandNode(currentNode.getKeyType(), currentLimits, BlueLanguageConstants.OBJECT_KEY_TYPE, true);
        }
        if (currentNode.getValueType() != null) {
            expandNode(currentNode.getValueType(), currentLimits, BlueLanguageConstants.OBJECT_VALUE_TYPE, true);
        }
        if (currentNode.getContracts() != null) {
            expandNode(currentNode.getContracts(), currentLimits, BlueLanguageConstants.OBJECT_CONTRACTS, false);
        }

        Map<String, Node> properties = currentNode.getProperties();
        if (properties != null) {
            properties.forEach((key, value) -> expandNode(value, currentLimits, key, false));
        }

        List<Node> items = currentNode.getItems();
        if (items != null && !items.isEmpty()) {
            if (currentLimits.shouldReconstructList(currentNode, items)) {
                reconstructList(items);
            }
            for (int i = 0; i < items.size(); i++) {
                expandNode(items.get(i), currentLimits, String.valueOf(i), false);
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
            }
            throw new IllegalArgumentException(
                    "No content found for blueId: " + node.getBlueId());
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
