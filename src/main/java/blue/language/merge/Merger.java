package blue.language.merge;

import blue.language.NodeProvider;
import blue.language.model.Node;
import blue.language.utils.NodeExtender;
import blue.language.utils.NodeProviderWrapper;
import blue.language.utils.limits.Limits;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.limits.PathLimits;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class Merger implements NodeResolver {

    private MergingProcessor mergingProcessor;
    private NodeProvider nodeProvider;

    public Merger(MergingProcessor mergingProcessor, NodeProvider nodeProvider) {
        this.mergingProcessor = mergingProcessor;
        this.nodeProvider = NodeProviderWrapper.wrap(nodeProvider);
    }

    public void merge(Node target, Node source, Limits limits) {
        if (source.getBlue() != null) {
            throw new IllegalArgumentException("Document contains \"blue\" attribute. Preprocess document before merging.");
        }

        if (source.getType() != null) {
            Node typeNode = source.getType();
            if (typeNode.getBlueId() != null) {
                new NodeExtender(nodeProvider).extend(typeNode, PathLimits.withSinglePath("/"));
            }

            Node resolvedType = resolve(typeNode, limits);
            source.type(resolvedType);
            merge(target, typeNode, limits);
        }
        mergeObject(target, source, limits);
    }

    private void mergeObject(Node target, Node source, Limits limits) {

        mergingProcessor.process(target, source, nodeProvider, this);

        List<Node> children = source.getItems();
        if (children != null) {
            mergeChildren(target, children, limits);
        }

        Map<String, Node> properties = source.getProperties();
        if (properties != null) {
            properties.forEach((key, value) -> {
                if (limits.shouldMergePathSegment(key, value)) {
                    limits.enterPathSegment(key, value);
                    mergeProperty(target, key, value, limits);
                    limits.exitPathSegment();
                }
            });
        }

        if (source.getBlueId() != null) {
            target.blueId(source.getBlueId());
        }

        mergingProcessor.postProcess(target, source, nodeProvider, this);
    }

    private void mergeChildren(Node target, List<Node> sourceChildren, Limits limits) {
        List<Node> targetChildren = target.getItems();
        if (targetChildren == null) {
            targetChildren = sourceChildren.stream()
                    .filter(child -> limits.shouldMergePathSegment(String.valueOf(sourceChildren.indexOf(child)), target))
                    .map(child -> {
                        limits.enterPathSegment(String.valueOf(sourceChildren.indexOf(child)), target);
                        Node resolvedChild = resolve(child, limits);
                        limits.exitPathSegment();
                        return resolvedChild;
                    })
                    .collect(Collectors.toList());
            target.items(targetChildren);
            return;
        } else if (sourceChildren.size() < targetChildren.size())
            throw new IllegalArgumentException(String.format(
                    "Subtype of element must not have more items (%d) than the element itself (%d).",
                    targetChildren.size(), sourceChildren.size()
            ));

        for (int i = 0; i < sourceChildren.size(); i++) {
            if (!limits.shouldMergePathSegment(String.valueOf(i), sourceChildren.get(i))) {
                continue;
            }
            limits.enterPathSegment(String.valueOf(i), sourceChildren.get(i));
            if (i >= targetChildren.size()) {
                targetChildren.add(sourceChildren.get(i));
                limits.exitPathSegment();
                continue;
            }
            String sourceBlueId = BlueIdCalculator.calculateBlueId(sourceChildren.get(i));
            String targetBlueId = BlueIdCalculator.calculateBlueId(targetChildren.get(i));
            if (!sourceBlueId.equals(targetBlueId))
                throw new IllegalArgumentException(String.format(
                        "Mismatched items at index %d: source item has blueId '%s', but target item has blueId '%s'.",
                        i, sourceBlueId, targetBlueId
                ));
            limits.exitPathSegment();
        }
    }

    private void mergeProperty(Node target, String sourceKey, Node sourceValue, Limits limits) {
        Node node = resolve(sourceValue, limits);

        if (target.getProperties() == null)
            target.properties(new HashMap<>());
        Node targetValue = target.getProperties().get(sourceKey);
        if (targetValue == null)
            target.getProperties().put(sourceKey, node);
        else
            mergeObject(targetValue, node, limits);
    }

    @Override
    public Node resolve(Node node, Limits limits) {
        Node resultNode = new Node();
        merge(resultNode, node, limits);
        resultNode.name(node.getName());
        resultNode.description(node.getDescription());
        resultNode.blueId(node.getBlueId());
        return resultNode;
    }
}