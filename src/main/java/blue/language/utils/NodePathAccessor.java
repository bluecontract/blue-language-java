package blue.language.utils;

import blue.language.model.Node;
import blue.language.model.path.NodePath;

import java.util.function.Function;

/**
 * @deprecated Model traversal is owned by {@link NodePath}.
 */
@Deprecated
public class NodePathAccessor {

    /** Creates the legacy path-access facade. */
    public NodePathAccessor() {
    }

    public static Object get(Node node, String path) {
        return NodePath.get(node, path);
    }

    public static Object get(
            Node node,
            String path,
            Function<Node, Node> linkingProvider) {
        return NodePath.get(node, path, linkingProvider);
    }

    public static Object get(
            Node node,
            String path,
            Function<Node, Node> linkingProvider,
            boolean resolveFinalLink) {
        return NodePath.get(
                node, path, linkingProvider, resolveFinalLink);
    }

    public static Node getNode(Node node, String path) {
        return NodePath.getNode(node, path);
    }
}
