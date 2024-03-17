package blue.lang;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Merger {

    private NodeProvider nodeProvider;
    private NodeProcessor nodeProcessor;
    private Resolver resolver;

    public Merger(NodeProvider nodeProvider, NodeProcessor nodeProcessor, Resolver resolver) {
        this.nodeProvider = nodeProvider;
        this.nodeProcessor = nodeProcessor;
        this.resolver = resolver;
    }

    public void merge(Node target, Node source) {
        if (source.getType() != null)
            merge(target, nodeProvider.fetchByBlueId(source.getType()));
        mergeObject(target, source);
    }

    private void mergeObject(Node target, Node source) {
        nodeProcessor.process(target, source, resolver);
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
        Node node = new Node();
        merge(node, sourceValue);
        if (target.getProperties() == null)
            target.properties(new HashMap<>());
        Node targetValue = target.getProperties().get(sourceKey);
        if (targetValue == null)
            target.getProperties().put(sourceKey, node);
        else
            mergeObject(targetValue, node);
    }

}