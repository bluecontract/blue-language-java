package blue.language;

import blue.language.model.Node;
import blue.language.model.limits.Limits;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.Nodes;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
        if (!limits.canReadNext()) {
            return;
        }
        mergingProcessor.process(target, source, nodeProvider, this);

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
            Limits limitsCopy = limits.copy();
            targetChildren = sourceChildren.stream()
                    .map(child -> resolve(child, limits))
                    .collect(Collectors.toList());
            target.items(targetChildren);
            return;
        } else if (sourceChildren.size() < targetChildren.size())
            throw new IllegalArgumentException(String.format(
                    "Subtype of element must not have more items (%d) than the element itself (%d).",
                    targetChildren.size(), sourceChildren.size()
            ));

        for (int i = 0; i < sourceChildren.size(); i++) {
            if (i >= targetChildren.size()) {
                targetChildren.add(sourceChildren.get(i));
                continue;
            }
            String sourceBlueId = BlueIdCalculator.calculateBlueId(sourceChildren.get(i));
            String targetBlueId = BlueIdCalculator.calculateBlueId(targetChildren.get(i));
            if (!sourceBlueId.equals(targetBlueId))
                throw new IllegalArgumentException(String.format(
                        "Mismatched items at index %d: source item has blueId '%s', but target item has blueId '%s'.",
                        i, sourceBlueId, targetBlueId
                ));
        }
        target.getItems().removeIf(Nodes::isEmptyNode);
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

    @Override
    public Node resolve(Node node, Limits limits) {
        Node resultNode = new Node();
        if (limits == Limits.END_LIMITS) {
            resultNode.blueId(BlueIdCalculator.calculateBlueId(node));
        } else {
            merge(resultNode, node, limits);
            resultNode.name(node.getName());
            resultNode.description(node.getDescription());
            resultNode.constraints(node.getConstraints());
        }

        return resultNode;
    }
}