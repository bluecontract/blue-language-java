package blue.language.utils.limits;

import blue.language.model.Node;

import java.util.Map;

public class NodeToPathLimitsConverter {

    public static PathLimits convert(Node node) {
        PathLimits.Builder builder = new PathLimits.Builder();
        traverseNode(node, "", builder);
        return builder.build();
    }

    private static void traverseNode(Node node, String currentPath, PathLimits.Builder builder) {
        if (node == null) {
            return;
        }

        if ((node.getProperties() == null || node.getProperties().isEmpty()) && node.getItems() == null) {
            builder.addPath(currentPath.isEmpty() ? "/" : currentPath);
            return;
        }

        if (node.getProperties() != null) {
            for (Map.Entry<String, Node> entry : node.getProperties().entrySet()) {
                String newPath = currentPath + "/" + entry.getKey();
                traverseNode(entry.getValue(), newPath, builder);
            }
        }

        if (node.getItems() != null) {
            for (int i = 0; i < node.getItems().size(); i++) {
                String newPath = currentPath + "/" + i;
                traverseNode(node.getItems().get(i), newPath, builder);
            }
        }
    }
}