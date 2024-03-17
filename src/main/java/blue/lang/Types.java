package blue.lang;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class Types {

    private final Map<String, Node> types;

    public Types(List<? extends Node> nodes) {
        types = nodes.stream()
                .collect(Collectors.toMap(Node::getName, node -> node));
    }

    public Map<String, Node> getTypes() {
        return types;
    }

    public boolean isSubtype(Node node, String supertype) {
        return node.getType() != null && isSubtype(node.getType(), supertype);
    }

    public boolean isSubtype(String subtype, String supertype) {
        if (subtype.equals(supertype))
            return true;
        Node current = types.get(subtype);
        while (current != null && current.getType() != null) {
            if (current.getType().equals(supertype))
                return true;
            current = types.get(current.getType());
        }
        return false;
    }
}
