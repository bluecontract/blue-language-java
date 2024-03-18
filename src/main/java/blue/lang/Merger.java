package blue.lang;

import blue.lang.model.Limits;
import blue.lang.utils.BlueIdCalculator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Merger {

    private NodeProvider nodeProvider;
    private NodeProcessor nodeProcessor;

    public Merger(NodeProvider nodeProvider, NodeProcessor nodeProcessor) {
        this.nodeProvider = nodeProvider;
        this.nodeProcessor = nodeProcessor;
    }

    public void merge(Node target, Node source) {
        merge(target, source, Limits.NO_LIMITS);
    }

    public void merge(Node target, Node source, Limits limit) {
        if (source.getType() != null)
            merge(target, nodeProvider.fetchByBlueId(source.getType()), limit.next());
        mergeObject(target, source, limit);
    }

    private void mergeObject(Node target, Node source, Limits limit) {
        if (limit.canReadNext()) {
            nodeProcessor.process(target, source, nodeProvider);
            List<Node> children = source.getItems();
            if (children != null)
                mergeChildren(target, children, limit);
            Map<String, Node> properties = source.getProperties();
            if (properties != null)
                properties.forEach((key, value) -> mergeProperty(target, key, value, limit));
        } else {
            target.value(source.getBlueId());
        }
    }

    private void mergeChildren(Node target, List<Node> sourceChildren, Limits limit) {
        List<Node> targetChildren = target.getItems();
        if (targetChildren == null) {
            targetChildren = new ArrayList<>();
            for (int i = 0; i < sourceChildren.size(); i++)
                targetChildren.add(new Node());
            target.items(targetChildren);
        } else if (sourceChildren.size() != targetChildren.size())
            throw new IllegalArgumentException("Cannot merge two lists with different items size.");
        for (int i = 0; i < sourceChildren.size(); i++)
            merge(targetChildren.get(i), sourceChildren.get(i), limit.next());
    }

    private void mergeProperty(Node target, String sourceKey, Node sourceValue, Limits limit) {
        if (!limit.filter(sourceKey)) {
            return;
        }
        Node node = new Node();
        merge(node, sourceValue, limit.next());
        if (target.getProperties() == null)
            target.properties(new HashMap<>());
        Node targetValue = target.getProperties().get(sourceKey);
        if (targetValue == null)
            target.getProperties().put(sourceKey, node);
        else
            mergeObject(targetValue, node, limit);
    }

}