package blue.language.utils;

import blue.language.NodeProvider;
import blue.language.model.Node;
import blue.language.utils.limits.Limits;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

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
        this.nodeProvider = nodeProvider;
        this.strategy = strategy;
    }
    
    public void extend(Node node, Limits limits) {
        extendNode(node, limits);
    }

    private void extendNode(Node currentNode, Limits currentLimits) {
        if (currentNode.getBlueId() != null) {
            Node resolvedNode = fetchNode(currentNode);
            if (resolvedNode != null) {
                currentNode.name(resolvedNode.getName());
                currentNode.description(resolvedNode.getDescription());
                currentNode.type(resolvedNode.getType());
                currentNode.value(resolvedNode.getValue());
                currentNode.items(resolvedNode.getItems());
                currentNode.properties(resolvedNode.getProperties());
                currentNode.constraints(resolvedNode.getConstraints());
            }
        }

        if (currentNode.getType() != null) {
            extendNode(currentNode.getType(), currentLimits);
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

        List<Node> children = currentNode.getItems();
        if (children != null) {
            if (children.size() == 1 && children.get(0).getBlueId() != null) {
                List<Node> resolvedNodes = nodeProvider.fetchByBlueId(children.get(0).getBlueId());
                if (resolvedNodes != null && !resolvedNodes.isEmpty()) {
                    List<Node> fullList = reconstructList(resolvedNodes);
                    currentNode.items(fullList);
                }
            }

            final List<Node> updatedChildren = currentNode.getItems();
            IntStream.range(0, updatedChildren.size())
                    .forEach(i -> {
                        if (currentLimits.shouldProcessPathSegment(String.valueOf(i))) {
                            currentLimits.enterPathSegment(String.valueOf(i));
                            extendNode(updatedChildren.get(i), currentLimits);
                            currentLimits.exitPathSegment();
                        }
                    });
        }
    }

    private Node fetchNode(Node node) {
        List<Node> resolvedNodes = nodeProvider.fetchByBlueId(node.getBlueId());
        if (resolvedNodes == null) {
            if (strategy == MissingElementStrategy.RETURN_EMPTY) {
                return null;
            } else {
                throw new IllegalArgumentException("No content found for blueId: " + node.getBlueId());
            }
        }

        if (resolvedNodes.size() == 1) {
            return resolvedNodes.get(0);
        } else if (resolvedNodes.size() == 2) {
            List<Node> fullList = reconstructList(resolvedNodes);
            Node listNode = new Node();
            listNode.items(fullList);
            return listNode;
        } else {
            throw new IllegalStateException("Unexpected number of nodes returned for blueId: " + node.getBlueId());
        }
    }

    private List<Node> reconstructList(List<Node> nodes) {
        List<Node> result = new ArrayList<>();
        Node firstElement = nodes.get(0);
        Node lastElement = nodes.get(1);

        if (firstElement.getBlueId() != null) {
            List<Node> nestedNodes = nodeProvider.fetchByBlueId(firstElement.getBlueId());
            if (nestedNodes == null) {
                if (strategy == MissingElementStrategy.RETURN_EMPTY) {
                    return result;
                } else {
                    throw new IllegalArgumentException("No content found for blueId: " + firstElement.getBlueId());
                }
            }
            if (nestedNodes.size() == 1) {
                result.add(firstElement);
            } else {
                result.addAll(reconstructList(nestedNodes));
            }
        }

        result.add(lastElement);
        return result;
    }
}