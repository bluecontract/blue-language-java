package blue.lang;

import blue.lang.model.Limits;
import blue.lang.utils.NodeToObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Merger implements NodeResolver {

    private MergingProcessor mergingProcessor;
    private NodeProvider nodeProvider;

    public Merger(MergingProcessor mergingProcessor, NodeProvider nodeProvider) {
        this.mergingProcessor = mergingProcessor;
        this.nodeProvider = nodeProvider;
    }

    public void merge(Node target, Node source) {
        if (source.getType() != null) {
            Node resolvedType = resolve(source.getType(), Limits.NO_LIMITS);
            source.type(resolvedType);
            merge(target, source.getType());
        }
        mergeObject(target, source);
    }

    private void mergeObject(Node target, Node source) {
        mergingProcessor.process(target, source, nodeProvider, this);
        List<Node> children = source.getItems();
        if (children != null)
            mergeChildren(target, children);
        Map<String, Node> properties = source.getProperties();
        if (properties != null)
            properties.forEach((key, value) -> mergeProperty(target, key, value));
    }

    private void mergeChildren(Node target, List<Node> sourceChildren) {
        List<Node> targetChildren = target.getItems();
        if (targetChildren == null) {
            targetChildren = new ArrayList<>();
            for (int i = 0; i < sourceChildren.size(); i++)
                targetChildren.add(new Node());
            target.items(targetChildren);
        } else if (sourceChildren.size() != targetChildren.size())
            throw new IllegalArgumentException("Cannot merge two lists with different items size.");
        for (int i = 0; i < sourceChildren.size(); i++)
            merge(targetChildren.get(i), sourceChildren.get(i));
    }

    private void mergeProperty(Node target, String sourceKey, Node sourceValue) {
        Node node = resolve(sourceValue, Limits.NO_LIMITS);
        if (target.getProperties() == null)
            target.properties(new HashMap<>());
        Node targetValue = target.getProperties().get(sourceKey);
        if (targetValue == null)
            target.getProperties().put(sourceKey, node);
        else
            mergeObject(targetValue, node);
    }

    @Override
    public Node resolve(Node node, Limits limits) {
        Node resultNode = new Node();
        merge(resultNode, node);
        return resultNode;
    }
}