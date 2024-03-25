package blue.lang.utils;

import blue.lang.Node;

import java.util.Iterator;
import java.util.Map.Entry;

public class Flattener {

    public static void flatten(Node node) {
        node.type((Node) null);

        if (node.getItems() != null) {
            Iterator<Node> itemIterator = node.getItems().iterator();
            while (itemIterator.hasNext()) {
                Node item = itemIterator.next();
                flatten(item);
                if (shouldRemoveNode(item))
                    itemIterator.remove();
            }
            if (node.getItems().isEmpty())
                node.items((Node) null);
        }

        if (node.getProperties() != null) {
            Iterator<Entry<String, Node>> propertyIterator = node.getProperties().entrySet().iterator();
            while (propertyIterator.hasNext()) {
                Entry<String, Node> entry = propertyIterator.next();
                flatten(entry.getValue());
                if (shouldRemoveNode(entry.getValue()))
                    propertyIterator.remove();
            }
            if (node.getProperties().isEmpty())
                node.properties(null);
        }
    }

    private static boolean shouldRemoveNode(Node node) {
        return node.getValue() == null && (node.getItems() == null || node.getItems().isEmpty()) &&
                (node.getProperties() == null || node.getProperties().isEmpty());
    }

}
