package blue.language.resolve;

import blue.language.model.wire.BlueLanguageConstants;

import blue.language.model.Node;
import blue.language.model.wire.JsonPointer;

import java.util.Map;

import static blue.language.model.wire.BlueLanguageConstants.OBJECT_CONTRACTS;

/**
 * Converts the leaf shape of a node graph into exact path-based traversal
 * limits.
 */
final class NodeToPathLimitsConverter {

    /**
     * Creates a node-to-path-limits converter.
     */
    private NodeToPathLimitsConverter() {
    }

    /**
     * Returns limits whose allowed paths correspond to terminal graph nodes.
     *
     * @param node graph root to inspect
     * @return exact path limits for the graph's terminal nodes
     */
    static ResolutionLimits convert(Node node) {
        ResolutionLimits.Builder builder = ResolutionLimits.builder();
        traverseNode(node, JsonPointer.ROOT, builder);
        return builder.build();
    }

    private static void traverseNode(
            Node node,
            String currentPath,
            ResolutionLimits.Builder builder) {
        if (node == null) {
            return;
        }

        if ((node.getProperties() == null || node.getProperties().isEmpty())
                && node.getItems() == null
                && node.getContracts() == null) {
            builder.addPath(currentPath);
            return;
        }

        if (node.getContracts() != null) {
            traverseNode(
                    node.getContracts(),
                    JsonPointer.append(currentPath, OBJECT_CONTRACTS),
                    builder);
        }

        if (node.getProperties() != null) {
            for (Map.Entry<String, Node> entry : node.getProperties().entrySet()) {
                String newPath = JsonPointer.append(currentPath, entry.getKey());
                traverseNode(entry.getValue(), newPath, builder);
            }
        }

        if (node.getItems() != null) {
            for (int i = 0; i < node.getItems().size(); i++) {
                String newPath = JsonPointer.append(currentPath, String.valueOf(i));
                traverseNode(node.getItems().get(i), newPath, builder);
            }
        }
    }
}
