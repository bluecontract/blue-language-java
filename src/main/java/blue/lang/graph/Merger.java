package blue.lang.graph;

import lombok.AllArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@AllArgsConstructor
public class Merger {

    private NodeManager nodeManager;

    public void merge(Node target, Node source) {
//        System.out.println("merging " + target + " from source " + source);
        if (source.getType() != null)
            merge(target, nodeManager.getNode(source.getType()));
        mergeObject(target, source);
    }

    private void mergeObject(Node target, Node source) {
//        System.out.println("merging object " + target + " from source " + source);
        nodeManager.getNodeMergingProcessor().process(target, source, nodeManager);
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
                targetChildren.add(nodeManager.newNode());
            target.items(targetChildren);
        } else if (sourceChildren.size() != targetChildren.size())
            throw new IllegalArgumentException("Cannot merge two lists with different items size.");
        for (int i = 0; i < sourceChildren.size(); i++)
            merge(targetChildren.get(i), sourceChildren.get(i));
    }

    private void mergeProperty(Node target, String sourceKey, Node sourceValue) {
        Node node = nodeManager.newNode();
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