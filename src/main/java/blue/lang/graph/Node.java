package blue.lang.graph;

import java.util.List;
import java.util.Map;

public interface Node extends Cloneable {
    String getName();
    String getType();
    Object getValue();
    List<Node> getItems();
    Map<String, Node> getProperties();
    String getRef();
    List<Feature> getFeatures();

    Node name(String name);
    Node type(String type);
    BasicNode value(Object value);
    Node items(List<Node> items);
    Node items(Node... items);
    Node properties(Map<String, Node> properties);
    Node ref(String ref);
    Node features(List<Feature> features);
}