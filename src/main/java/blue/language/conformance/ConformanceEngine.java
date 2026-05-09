package blue.language.conformance;

import blue.language.NodeProvider;
import blue.language.merge.Merger;
import blue.language.merge.MergingProcessor;
import blue.language.model.Node;
import blue.language.processor.util.PointerUtils;
import blue.language.utils.MergeReverser;
import blue.language.utils.NodeProviderWrapper;
import blue.language.utils.limits.Limits;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ConformanceEngine {

    private final NodeProvider nodeProvider;
    private final MergingProcessor mergingProcessor;

    public ConformanceEngine(NodeProvider nodeProvider, MergingProcessor mergingProcessor) {
        this.nodeProvider = NodeProviderWrapper.wrap(nodeProvider);
        this.mergingProcessor = Objects.requireNonNull(mergingProcessor, "mergingProcessor");
    }

    public ConformanceResult check(Node node) {
        if (node == null) {
            return ConformanceResult.conformant();
        }
        try {
            new Merger(mergingProcessor, nodeProvider).resolve(node.clone(), Limits.NO_LIMITS);
            return ConformanceResult.conformant();
        } catch (RuntimeException ex) {
            return ConformanceResult.nonConformant(ex.getMessage());
        }
    }

    public boolean conforms(Node node) {
        return check(node).isConformant();
    }

    public void requireConformant(Node node) {
        ConformanceResult result = check(node);
        if (!result.isConformant()) {
            throw new IllegalArgumentException(result.getMessage());
        }
    }

    public void generalizeChangedPath(Node root, String changedPath) {
        Objects.requireNonNull(root, "root");
        String normalized = PointerUtils.normalizePointer(changedPath);
        List<Node> path = existingPathToNearestNode(root, normalized);
        for (int i = path.size() - 1; i >= 0; i--) {
            generalizeNode(path.get(i));
        }
        requireConformant(root);
    }

    private void generalizeNode(Node node) {
        if (node == null || node.getType() == null) {
            return;
        }

        ConformanceResult result = check(node);
        while (!result.isConformant()) {
            Node parentType = parentType(node.getType());
            if (parentType == null) {
                throw new IllegalArgumentException("Node cannot be generalized to a conforming type: " + result.getMessage());
            }
            node.replaceWith(generalizedNode(node, parentType));
            result = check(node);
        }
    }

    private Node generalizedNode(Node node, Node parentType) {
        Node canonical = new MergeReverser().reverse(node.clone());
        canonical.type(parentType);
        return new Merger(mergingProcessor, nodeProvider).resolve(canonical, Limits.NO_LIMITS);
    }

    private Node parentType(Node type) {
        if (type == null) {
            return null;
        }

        Node resolvedType = resolveType(type);
        Node parentType = resolvedType.getType();
        return parentType != null ? parentType.clone() : null;
    }

    private Node resolveType(Node type) {
        return new Merger(mergingProcessor, nodeProvider).resolve(type.clone(), Limits.NO_LIMITS);
    }

    private List<Node> existingPathToNearestNode(Node root, String pointer) {
        List<Node> nodes = new ArrayList<>();
        nodes.add(root);
        if ("/".equals(pointer)) {
            return nodes;
        }

        String[] segments = pointer.substring(1).split("/", -1);
        Node current = root;
        for (String segment : segments) {
            Node child = child(current, segment);
            if (child == null) {
                break;
            }
            nodes.add(child);
            current = child;
        }
        return nodes;
    }

    private Node child(Node node, String segment) {
        if (node == null) {
            return null;
        }
        if (node.getItems() != null) {
            if ("-".equals(segment)) {
                List<Node> items = node.getItems();
                return items.isEmpty() ? null : items.get(items.size() - 1);
            }
            try {
                int index = Integer.parseInt(segment);
                return index >= 0 && index < node.getItems().size() ? node.getItems().get(index) : null;
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        Map<String, Node> properties = node.getProperties();
        return properties != null ? properties.get(segment) : null;
    }
}
