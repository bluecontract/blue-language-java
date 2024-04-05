package blue.lang;

import blue.lang.model.limits.Limits;
import blue.lang.utils.BlueIdCalculator;
import blue.lang.utils.NodeToObject;
import blue.lang.utils.Nodes;

import java.util.*;
import java.util.stream.Collectors;

public class Merger implements NodeResolver {

    private MergingProcessor mergingProcessor;
    private NodeProvider nodeProvider;

    public Merger(MergingProcessor mergingProcessor, NodeProvider nodeProvider) {
        this.mergingProcessor = mergingProcessor;
        this.nodeProvider = nodeProvider;
    }

    public void merge(Node target, Node source) {
        merge(target, source, Limits.NO_LIMITS);
    }

    public void merge(Node target, Node source, Limits limits) {
        if (limits.canReadNext()) {
            if (source.getType() != null) {
                Node resolvedType = resolve(source.getType(), limits.next(true));
                source.type(resolvedType);
                merge(target, source.getType(), limits.next(true));
            }
        }
        mergeObject(target, source, limits);
    }

    private void mergeObject(Node target, Node source, Limits limits) {
        mergingProcessor.process(target, source, nodeProvider, this);

        if (!limits.canReadNext()) {
            return;
        }

        stripType(target, limits.nextForTypeStrip());

        List<Node> children = source.getItems();
        if (children != null)
            mergeChildren(target, children, limits);
        Map<String, Node> properties = source.getProperties();
        if (properties != null)
            properties.forEach((key, value) -> mergeProperty(target, key, value, limits));
    }

    private void mergeChildren(Node target, List<Node> sourceChildren, Limits limits) {
        List<Node> targetChildren = target.getItems();
        if (targetChildren == null) {
            targetChildren = new ArrayList<>();
            for (int i = 0; i < sourceChildren.size(); i++)
                targetChildren.add(new Node());
            target.items(targetChildren);
        } else if (sourceChildren.size() != targetChildren.size())
            throw new IllegalArgumentException("Cannot merge two lists with different items size.");

        Limits limitsCopy = limits.copy();
        for (int i = 0; i < sourceChildren.size(); i++) {
            if (limits.canReadIndex(i)) {
                Limits l = limitsCopy.next(false);
                if (l == Limits.END_LIMITS) {
                    targetChildren.get(i).blueId(BlueIdCalculator.calculateBlueId(sourceChildren.get(i)));
                } else
                    targetChildren.set(i, resolve(sourceChildren.get(i), l));
            }
        }
        target.getItems().removeIf(Nodes::isEmptyNodeWithoutBlueId);
    }

    private void mergeProperty(Node target, String sourceKey, Node sourceValue, Limits limits) {
        if (!limits.filter(sourceKey)) {
            return;
        }

        Node node =  resolve(sourceValue, limits.next(sourceKey));

        if (target.getProperties() == null)
            target.properties(new HashMap<>());
        Node targetValue = target.getProperties().get(sourceKey);
        if (targetValue == null)
            target.getProperties().put(sourceKey, node);
        else
            mergeObject(targetValue, node, limits.next(sourceKey));
    }

    private void stripType(Node target, Limits limits) {
        if (target.getType() == null || limits == Limits.NO_LIMITS) {
            return;
        }
        if (limits == Limits.END_LIMITS) {
            Node resultNode = new Node();
            resultNode.blueId(BlueIdCalculator.calculateBlueId(target.getType()));
            target.type(resultNode);
        } else {
            stripType(target.getType(), limits.nextForTypeStrip());
        }
    }

    @Override
    public Node resolve(Node node, Limits limits) {
        Node resultNode = new Node();
        if (limits == Limits.END_LIMITS) {
            if (Nodes.isSingleValueNode(node)) {
                resultNode.value(node.getValue());
            } else {
                resultNode.blueId(BlueIdCalculator.calculateBlueId(node));
            }
        } else {
            merge(resultNode, node, limits);
            if (limits.canCopyMetadata()) {
                resultNode.name(node.getName());
                resultNode.description(node.getDescription());
            }
        }

        return resultNode;
    }
}