package blue.language.utils;

import blue.language.model.Node;
import blue.language.model.Schema;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Validates the syntax of every BlueId reference in a complete input graph.
 */
public final class BlueIdReferenceValidator {

    private static final String BLUE_ID_PATH = "/blueId";
    private static final String PREVIOUS_BLUE_ID_PATH = "/$previous/blueId";

    private BlueIdReferenceValidator() {
    }

    /**
     * Validates every reference reachable from the supplied input graph.
     *
     * @param root complete input graph; {@code null} is accepted
     */
    public static void validate(Node root) {
        if (root == null) {
            return;
        }
        try {
            validateFast(root);
        } catch (IllegalArgumentException malformedReference) {
            validateDetailed(root);
            throw malformedReference;
        }
    }

    private static void validateFast(Node root) {
        IdentityHashMap<Node, Boolean> visited = new IdentityHashMap<Node, Boolean>();
        Deque<FastTraversalFrame> pending = new ArrayDeque<FastTraversalFrame>();
        Node next = root;

        while (next != null || !pending.isEmpty()) {
            if (next != null) {
                Node node = next;
                next = null;
                if (!isReferenceFreeLeaf(node)
                        && visited.put(node, Boolean.TRUE) == null) {
                    validateReferences(node, BLUE_ID_PATH, PREVIOUS_BLUE_ID_PATH);
                    validateSchemaReference(node.getSchema(), "/schema/blueId");
                    if (hasChildren(node)) {
                        pending.push(new FastTraversalFrame(node));
                    }
                }
            }
            while (next == null && !pending.isEmpty()) {
                next = pending.peek().nextChild();
                if (next == null) {
                    pending.pop();
                }
            }
        }
    }

    private static void validateDetailed(Node root) {
        IdentityHashMap<Node, Boolean> visited = new IdentityHashMap<Node, Boolean>();
        Deque<TraversalFrame> pending = new ArrayDeque<TraversalFrame>();
        Deque<TraversalFrame> children = new ArrayDeque<TraversalFrame>();
        pending.push(new TraversalFrame(root, null));

        while (!pending.isEmpty()) {
            TraversalFrame frame = pending.pop();
            if (visited.put(frame.node, Boolean.TRUE) != null) {
                continue;
            }

            validateReferencesDetailed(frame);
            appendChildrenInOrder(frame, children);
            while (!children.isEmpty()) {
                pending.push(children.removeLast());
            }
        }
    }

    private static boolean isReferenceFreeLeaf(Node node) {
        return node.getBlueId() == null
                && node.getPreviousBlueId() == null
                && node.getType() == null
                && node.getItemType() == null
                && node.getKeyType() == null
                && node.getValueType() == null
                && node.getBlue() == null
                && node.getContracts() == null
                && node.getItems() == null
                && node.getProperties() == null
                && node.getSchema() == null;
    }

    private static boolean hasChildren(Node node) {
        return node.getType() != null
                || node.getItemType() != null
                || node.getKeyType() != null
                || node.getValueType() != null
                || node.getBlue() != null
                || node.getContracts() != null
                || (node.getItems() != null && !node.getItems().isEmpty())
                || (node.getProperties() != null && !node.getProperties().isEmpty())
                || node.getSchema() != null;
    }

    private static void validateReferences(Node node, String blueIdPath, String previousBlueIdPath) {
        if (node.getBlueId() != null) {
            validateBlueId(node.getBlueId(), blueIdPath);
        }
        if (node.getPreviousBlueId() != null) {
            BlueIds.requirePlainBlueId(node.getPreviousBlueId(), previousBlueIdPath);
        }
    }

    private static void validateReferencesDetailed(TraversalFrame frame) {
        if (frame.node.getBlueId() != null) {
            try {
                validateBlueId(frame.node.getBlueId(), BLUE_ID_PATH);
            } catch (IllegalArgumentException malformedReference) {
                validateBlueId(frame.node.getBlueId(), pointer(frame.path, "blueId"));
                throw malformedReference;
            }
        }
        if (frame.node.getPreviousBlueId() != null) {
            try {
                BlueIds.requirePlainBlueId(frame.node.getPreviousBlueId(), PREVIOUS_BLUE_ID_PATH);
            } catch (IllegalArgumentException malformedReference) {
                BlueIds.requirePlainBlueId(frame.node.getPreviousBlueId(),
                        pointer(frame.path, "$previous", "blueId"));
                throw malformedReference;
            }
        }
        Schema schema = frame.node.getSchema();
        if (schema != null && schema.getBlueId() != null) {
            try {
                validateBlueId(schema.getBlueId(), "/schema/blueId");
            } catch (IllegalArgumentException malformedReference) {
                validateBlueId(schema.getBlueId(),
                        pointer(frame.path, "schema", "blueId"));
                throw malformedReference;
            }
        }
    }

    private static void validateSchemaReference(Schema schema, String path) {
        if (schema != null && schema.getBlueId() != null) {
            validateBlueId(schema.getBlueId(), path);
        }
    }

    private static void validateBlueId(String blueId, String path) {
        BlueIds.requireNoThisPlaceholderOutsideCyclicApi(blueId, path);
        BlueIds.requireBlueIdOrCyclicMember(blueId, path);
    }

    private static void appendChildrenInOrder(TraversalFrame frame,
                                              Deque<TraversalFrame> children) {
        add(children, frame.node.getType(), frame.path, "type");
        add(children, frame.node.getItemType(), frame.path, "itemType");
        add(children, frame.node.getKeyType(), frame.path, "keyType");
        add(children, frame.node.getValueType(), frame.path, "valueType");
        add(children, frame.node.getBlue(), frame.path, "blue");
        add(children, frame.node.getContracts(), frame.path, "contracts");

        List<Node> items = frame.node.getItems();
        if (items != null) {
            for (int index = 0; index < items.size(); index++) {
                add(children, items.get(index), frame.path, Integer.toString(index));
            }
        }
        Map<String, Node> properties = frame.node.getProperties();
        if (properties != null) {
            for (Map.Entry<String, Node> property : properties.entrySet()) {
                add(children, property.getValue(), frame.path, property.getKey());
            }
        }
        appendSchemaChildrenInOrder(frame.node.getSchema(), frame.path, children);
    }

    private static void appendSchemaChildrenInOrder(Schema schema,
                                                     PathSegment parent,
                                                     Deque<TraversalFrame> children) {
        if (schema == null) {
            return;
        }
        PathSegment schemaPath = new PathSegment(parent, "schema");
        add(children, schema.getRequired(), schemaPath, "required");
        add(children, schema.getMinLength(), schemaPath, "minLength");
        add(children, schema.getMaxLength(), schemaPath, "maxLength");
        add(children, schema.getMinimum(), schemaPath, "minimum");
        add(children, schema.getMaximum(), schemaPath, "maximum");
        add(children, schema.getExclusiveMinimum(), schemaPath, "exclusiveMinimum");
        add(children, schema.getExclusiveMaximum(), schemaPath, "exclusiveMaximum");
        add(children, schema.getMultipleOf(), schemaPath, "multipleOf");
        add(children, schema.getMinItems(), schemaPath, "minItems");
        add(children, schema.getMaxItems(), schemaPath, "maxItems");
        add(children, schema.getUniqueItems(), schemaPath, "uniqueItems");
        add(children, schema.getMinFields(), schemaPath, "minFields");
        add(children, schema.getMaxFields(), schemaPath, "maxFields");
        if (schema.getEnum() != null) {
            PathSegment enumPath = new PathSegment(schemaPath, "enum");
            for (int index = 0; index < schema.getEnum().size(); index++) {
                add(children, schema.getEnum().get(index), enumPath, Integer.toString(index));
            }
        }
    }

    private static void add(Deque<TraversalFrame> children,
                            Node child,
                            PathSegment parent,
                            String segment) {
        if (child != null) {
            children.addLast(new TraversalFrame(child, new PathSegment(parent, segment)));
        }
    }

    private static String pointer(PathSegment parent, String... finalSegments) {
        int parentDepth = parent == null ? 0 : parent.depth;
        String[] segments = new String[parentDepth + finalSegments.length];
        PathSegment current = parent;
        for (int index = parentDepth - 1; index >= 0; index--) {
            segments[index] = current.segment;
            current = current.parent;
        }
        System.arraycopy(finalSegments, 0, segments, parentDepth, finalSegments.length);

        StringBuilder result = new StringBuilder(segments.length * 8);
        for (String segment : segments) {
            result.append('/').append(JsonPointer.escape(segment));
        }
        return result.length() == 0 ? "/" : result.toString();
    }

    private static final class TraversalFrame {
        private final Node node;
        private final PathSegment path;

        private TraversalFrame(Node node, PathSegment path) {
            this.node = node;
            this.path = path;
        }
    }

    private static final class FastTraversalFrame {
        private final Node node;
        private int fixedIndex;
        private int itemIndex;
        private boolean propertiesStarted;
        private Iterator<Node> properties;
        private int schemaIndex;
        private int enumIndex;

        private FastTraversalFrame(Node node) {
            this.node = node;
        }

        private Node nextChild() {
            Node child;
            while (fixedIndex < 6) {
                child = fixedChild(fixedIndex++);
                if (child != null) {
                    return child;
                }
            }

            List<Node> items = node.getItems();
            while (items != null && itemIndex < items.size()) {
                child = items.get(itemIndex++);
                if (child != null) {
                    return child;
                }
            }

            if (!propertiesStarted) {
                propertiesStarted = true;
                if (node.getProperties() != null) {
                    properties = node.getProperties().values().iterator();
                }
            }
            while (properties != null && properties.hasNext()) {
                child = properties.next();
                if (child != null) {
                    return child;
                }
            }

            Schema schema = node.getSchema();
            while (schema != null && schemaIndex < 13) {
                child = schemaChild(schema, schemaIndex++);
                if (child != null) {
                    return child;
                }
            }
            List<Node> enumValues = schema == null ? null : schema.getEnum();
            while (enumValues != null && enumIndex < enumValues.size()) {
                child = enumValues.get(enumIndex++);
                if (child != null) {
                    return child;
                }
            }
            return null;
        }

        private Node fixedChild(int index) {
            switch (index) {
                case 0:
                    return node.getType();
                case 1:
                    return node.getItemType();
                case 2:
                    return node.getKeyType();
                case 3:
                    return node.getValueType();
                case 4:
                    return node.getBlue();
                case 5:
                    return node.getContracts();
                default:
                    return null;
            }
        }

        private static Node schemaChild(Schema schema, int index) {
            switch (index) {
                case 0:
                    return schema.getRequired();
                case 1:
                    return schema.getMinLength();
                case 2:
                    return schema.getMaxLength();
                case 3:
                    return schema.getMinimum();
                case 4:
                    return schema.getMaximum();
                case 5:
                    return schema.getExclusiveMinimum();
                case 6:
                    return schema.getExclusiveMaximum();
                case 7:
                    return schema.getMultipleOf();
                case 8:
                    return schema.getMinItems();
                case 9:
                    return schema.getMaxItems();
                case 10:
                    return schema.getUniqueItems();
                case 11:
                    return schema.getMinFields();
                case 12:
                    return schema.getMaxFields();
                default:
                    return null;
            }
        }
    }

    private static final class PathSegment {
        private final PathSegment parent;
        private final String segment;
        private final int depth;

        private PathSegment(PathSegment parent, String segment) {
            this.parent = parent;
            this.segment = segment;
            this.depth = parent == null ? 1 : parent.depth + 1;
        }
    }
}
