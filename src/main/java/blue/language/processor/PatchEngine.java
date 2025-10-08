package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.util.PointerUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Encapsulates document mutation logic: JSON Patch application, direct writes, and pointer helpers.
 */
final class PatchEngine {

    private final Node document;

    PatchEngine(Node document) {
        this.document = Objects.requireNonNull(document, "document");
    }

    PatchResult applyPatch(String originScopePath, JsonPatch patch) {
        Objects.requireNonNull(originScopePath, "originScopePath");
        Objects.requireNonNull(patch, "patch");

        String normalizedScope = PointerUtils.normalizeScope(originScopePath);
        String targetPath = PointerUtils.normalizePointer(patch.getPath());
        List<String> segments = splitPointer(targetPath);

        Node before = cloneNode(readNode(document, segments, LookupMode.BEFORE, targetPath));
        JsonPatch.Op op = patch.getOp();

        switch (patch.getOp()) {
            case ADD:
                applyAdd(document, segments, patch.getVal().clone(), targetPath);
                break;
            case REPLACE:
                applyReplace(document, segments, patch.getVal().clone(), targetPath);
                break;
            case REMOVE:
                applyRemove(document, segments, targetPath);
                break;
            default:
                throw new UnsupportedOperationException("Unsupported patch op: " + patch.getOp());
        }

        Node after = patch.getOp() == JsonPatch.Op.REMOVE
                ? null
                : cloneNode(readNode(document, segments, LookupMode.AFTER, targetPath));
        List<String> cascadeScopes = computeCascadeScopes(normalizedScope);
        return new PatchResult(targetPath, before, after, op, normalizedScope, cascadeScopes);
    }

    void directWrite(String path, Node value) {
        String normalized = PointerUtils.normalizePointer(path);
        if ("/".equals(normalized)) {
            throw new IllegalArgumentException("Direct write cannot target root document");
        }
        List<String> segments = splitPointer(normalized);
        ParentContext ctx = resolveParent(document, segments, true, normalized);
        Node parent = ctx.parent;
        String leaf = ctx.leaf;

        List<Node> items = parent.getItems();
        if (items != null) {
            if ("-".equals(leaf)) {
                throw new IllegalArgumentException("Direct write does not support append token '-' for path " + normalized);
            }
            List<Node> mutable = ensureMutableItems(parent);
            int index = parseArrayIndex(leaf, normalized);
            if (value == null) {
                if (index < 0 || index >= mutable.size()) {
                    return;
                }
                mutable.remove(index);
            } else {
                if (index == mutable.size()) {
                    mutable.add(value.clone());
                } else if (index >= 0 && index < mutable.size()) {
                    mutable.set(index, value.clone());
                } else {
                    throw new IllegalStateException("Array index out of bounds for direct write: " + normalized);
                }
            }
            return;
        }

        Map<String, Node> properties = ensureMutableProperties(parent);
        if (value == null) {
            properties.remove(leaf);
        } else {
            properties.put(leaf, value.clone());
        }
    }

    private void applyAdd(Node root, List<String> segments, Node value, String path) {
        ParentContext ctx = resolveParent(root, segments, true, path);
        try {
            Node parent = ctx.parent;
            String leaf = ctx.leaf;

            List<Node> items = parent.getItems();
            if (items != null) {
                List<Node> mutable = ensureMutableItems(parent);
                if ("-".equals(leaf)) {
                    mutable.add(value);
                    return;
                }
                int index = parseArrayIndex(leaf, path);
                if (index < 0 || index > mutable.size()) {
                    throw new IllegalStateException("Array index out of bounds for add: " + path);
                }
                mutable.add(index, value);
                return;
            }

            if ("-".equals(leaf)) {
                throw new IllegalStateException("Append token '-' requires array parent at path: " + path);
            }
            parent.properties(leaf, value);
        } catch (RuntimeException ex) {
            ctx.rollback(this);
            throw ex;
        }
    }

    private void applyReplace(Node root, List<String> segments, Node value, String path) {
        ParentContext ctx = resolveParent(root, segments, true, path);
        try {
            Node parent = ctx.parent;
            String leaf = ctx.leaf;

            List<Node> items = parent.getItems();
            if (items != null) {
                if ("-".equals(leaf)) {
                    throw new IllegalStateException("Replace does not support append token at path: " + path);
                }
                List<Node> mutable = ensureMutableItems(parent);
                int index = parseArrayIndex(leaf, path);
                if (index < 0 || index >= mutable.size()) {
                    throw new IllegalStateException("Array index out of bounds for replace: " + path);
                }
                mutable.set(index, value);
                return;
            }

            if ("-".equals(leaf)) {
                throw new IllegalStateException("Append token '-' requires array parent at path: " + path);
            }
            parent.properties(leaf, value);
        } catch (RuntimeException ex) {
            ctx.rollback(this);
            throw ex;
        }
    }

    private void applyRemove(Node root, List<String> segments, String path) {
        ParentContext ctx = resolveParent(root, segments, false, path);
        try {
            Node parent = ctx.parent;
            String leaf = ctx.leaf;

            List<Node> items = parent.getItems();
            if (items != null) {
                if ("-".equals(leaf)) {
                    throw new IllegalStateException("Remove does not support append token at path: " + path);
                }
                List<Node> mutable = ensureMutableItems(parent);
                int index = parseArrayIndex(leaf, path);
                if (index < 0 || index >= mutable.size()) {
                    throw new IllegalStateException("Array index out of bounds for remove: " + path);
                }
                mutable.remove(index);
                return;
            }

            if ("-".equals(leaf)) {
                throw new IllegalStateException("Append token '-' requires array parent at path: " + path);
            }
            Map<String, Node> properties = ensureMutableProperties(parent, false);
            if (properties == null || !properties.containsKey(leaf)) {
                throw new IllegalStateException("Path does not exist for remove: " + path);
            }
            properties.remove(leaf);
        } catch (RuntimeException ex) {
            ctx.rollback(this);
            throw ex;
        }
    }

    private Node readNode(Node root,
                          List<String> segments,
                          LookupMode mode,
                          String path) {
        if (segments.isEmpty()) {
            return root;
        }
        Node current = root;
        for (int i = 0; i < segments.size(); i++) {
            String segment = segments.get(i);
            boolean last = i == segments.size() - 1;
            current = descendForRead(current, segment, last, mode, path);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    private Node descendForRead(Node current,
                                String segment,
                                boolean last,
                                LookupMode mode,
                                String path) {
        if (current == null) {
            return null;
        }

        List<Node> items = current.getItems();
        if (items != null) {
            if ("-".equals(segment)) {
                if (!last) {
                    throw new IllegalStateException("Append token '-' must be final segment: " + path);
                }
                if (mode == LookupMode.BEFORE) {
                    return null;
                }
                return items.isEmpty() ? null : items.get(items.size() - 1);
            }
            int index = parseArrayIndex(segment, path);
            if (index < 0 || index >= items.size()) {
                return null;
            }
            return items.get(index);
        }

        Map<String, Node> properties = ensureMutableProperties(current, false);
        if (properties == null || !properties.containsKey(segment)) {
            return null;
        }
        return properties.get(segment);
    }

    private ParentContext resolveParent(Node root,
                                        List<String> segments,
                                        boolean createMissingObjects,
                                        String path) {
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("Cannot apply patch to document root");
        }

        Node current = root;
        List<CreatedNode> createdNodes = createMissingObjects ? new ArrayList<>() : null;
        try {
            for (int i = 0; i < segments.size() - 1; i++) {
                String segment = segments.get(i);
                current = descendForMutation(current, segment, createMissingObjects, path, i, segments, createdNodes);
            }
        } catch (RuntimeException ex) {
            if (createdNodes != null && !createdNodes.isEmpty()) {
                rollbackCreatedNodes(createdNodes);
            }
            throw ex;
        }

        return new ParentContext(current, segments.get(segments.size() - 1), createdNodes);
    }

    private Node descendForMutation(Node current,
                                    String segment,
                                    boolean createMissingObjects,
                                    String fullPath,
                                    int index,
                                    List<String> segments,
                                    List<CreatedNode> createdNodes) {
        if (current == null) {
            throw new IllegalStateException("Path does not exist: " + pointerPrefix(segments, index));
        }

        List<Node> items = current.getItems();
        if (items != null) {
            if ("-".equals(segment)) {
                throw new IllegalStateException("Append token '-' must be final segment: " + fullPath);
            }
            int arrayIndex = parseArrayIndex(segment, fullPath);
            if (arrayIndex < 0 || arrayIndex >= items.size()) {
                throw new IllegalStateException("Array index out of bounds: " + pointerPrefix(segments, index + 1));
            }
            Node child = items.get(arrayIndex);
            if (child == null) {
                if (createMissingObjects) {
                    child = new Node();
                    List<Node> mutable = ensureMutableItems(current);
                    mutable.set(arrayIndex, child);
                    if (createdNodes != null) {
                        createdNodes.add(CreatedNode.forArray(current, arrayIndex));
                    }
                } else {
                    throw new IllegalStateException("Path does not exist: " + pointerPrefix(segments, index + 1));
                }
            }
            return child;
        }

        Map<String, Node> properties = ensureMutableProperties(current, false);
        if (properties == null && current.getValue() != null) {
            throw new IllegalStateException("Cannot traverse into scalar at path: " + pointerPrefix(segments, index + 1));
        }
        Node child = properties != null ? properties.get(segment) : null;
        if (child == null) {
            if (!createMissingObjects) {
                throw new IllegalStateException("Path does not exist: " + pointerPrefix(segments, index + 1));
            }
            if (isArrayIndexSegment(segment)) {
                throw new IllegalStateException("Expected array element to exist at path: " + pointerPrefix(segments, index + 1));
            }
            child = new Node();
            boolean mapWasAbsent = properties == null;
            ensureMutableProperties(current).put(segment, child);
            if (createdNodes != null) {
                createdNodes.add(CreatedNode.forProperty(current, segment, mapWasAbsent));
            }
        }
        return child;
    }

    private void rollbackCreatedNodes(List<CreatedNode> createdNodes) {
        for (int i = createdNodes.size() - 1; i >= 0; i--) {
            createdNodes.get(i).rollback();
        }
    }

    private List<Node> ensureMutableItems(Node node) {
        List<Node> items = node.getItems();
        if (items == null) {
            items = new ArrayList<>();
            node.items(items);
        } else if (!(items instanceof ArrayList)) {
            items = new ArrayList<>(items);
            node.items(items);
        }
        return items;
    }

    private Map<String, Node> ensureMutableProperties(Node node) {
        return ensureMutableProperties(node, true);
    }

    private Map<String, Node> ensureMutableProperties(Node node, boolean create) {
        Map<String, Node> properties = node.getProperties();
        if (properties == null) {
            if (!create) {
                return null;
            }
            properties = new LinkedHashMap<>();
            node.properties(properties);
            return node.getProperties();
        }
        return properties;
    }

    private int parseArrayIndex(String segment, String path) {
        final int value;
        try {
            value = Integer.parseInt(segment);
        } catch (NumberFormatException ex) {
            throw new IllegalStateException("Expected numeric array index in path: " + path);
        }
        if (value < 0) {
            throw new IllegalStateException("Negative array index in path: " + path);
        }
        return value;
    }

    private boolean isArrayIndexSegment(String segment) {
        return "-".equals(segment) || (!segment.isEmpty() && segment.chars().allMatch(Character::isDigit));
    }

    private List<String> splitPointer(String path) {
        if ("/".equals(path)) {
            return new ArrayList<>();
        }
        String raw = path.substring(1);
        if (raw.isEmpty()) {
            return new ArrayList<>();
        }
        String[] parts = raw.split("/", -1);
        List<String> segments = new ArrayList<>(parts.length);
        for (String part : parts) {
            segments.add(part);
        }
        return segments;
    }

    private String pointerPrefix(List<String> segments, int length) {
        if (length <= 0) {
            return "/";
        }
        int limit = Math.min(length, segments.size());
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < limit; i++) {
            builder.append('/');
            String segment = segments.get(i);
            if (segment != null) {
                builder.append(segment);
            }
        }
        return builder.length() == 0 ? "/" : builder.toString();
    }

    private List<String> computeCascadeScopes(String scopePath) {
        List<String> scopes = new ArrayList<>();
        String current = scopePath;
        while (true) {
            scopes.add(current);
            if ("/".equals(current)) {
                break;
            }
            int idx = current.lastIndexOf('/');
            if (idx <= 0) {
                current = "/";
            } else {
                current = current.substring(0, idx);
            }
        }
        return scopes;
    }

    private Node cloneNode(Node node) {
        return node != null ? node.clone() : null;
    }

    private enum LookupMode {
        BEFORE,
        AFTER
    }

    static final class PatchResult {
        private final String path;
        private final Node before;
        private final Node after;
        private final JsonPatch.Op op;
        private final String originScope;
        private final List<String> cascadeScopes;

        PatchResult(String path,
                    Node before,
                    Node after,
                    JsonPatch.Op op,
                    String originScope,
                    List<String> cascadeScopes) {
            this.path = path;
            this.before = before;
            this.after = after;
            this.op = op;
            this.originScope = originScope;
            this.cascadeScopes = cascadeScopes;
        }

        String path() {
            return path;
        }

        Node before() {
            return before;
        }

        Node after() {
            return after;
        }

        JsonPatch.Op op() {
            return op;
        }

        String originScope() {
            return originScope;
        }

        List<String> cascadeScopes() {
            return cascadeScopes;
        }
    }

    private static final class ParentContext {
        final Node parent;
        final String leaf;
        final List<CreatedNode> createdNodes;

        ParentContext(Node parent, String leaf, List<CreatedNode> createdNodes) {
            this.parent = parent;
            this.leaf = leaf;
            this.createdNodes = createdNodes;
        }

        void rollback(PatchEngine engine) {
            if (createdNodes != null && !createdNodes.isEmpty()) {
                engine.rollbackCreatedNodes(createdNodes);
            }
        }
    }

    private static final class CreatedNode {
        private final Node parent;
        private final String propertyKey;
        private final Integer arrayIndex;
        private final boolean mapWasAbsent;

        private CreatedNode(Node parent, String propertyKey, Integer arrayIndex, boolean mapWasAbsent) {
            this.parent = parent;
            this.propertyKey = propertyKey;
            this.arrayIndex = arrayIndex;
            this.mapWasAbsent = mapWasAbsent;
        }

        static CreatedNode forProperty(Node parent, String propertyKey, boolean mapWasAbsent) {
            return new CreatedNode(parent, propertyKey, null, mapWasAbsent);
        }

        static CreatedNode forArray(Node parent, int index) {
            return new CreatedNode(parent, null, index, false);
        }

        void rollback() {
            if (arrayIndex != null) {
                List<Node> items = parent.getItems();
                if (items != null && arrayIndex >= 0 && arrayIndex < items.size()) {
                    items.remove(arrayIndex.intValue());
                }
                return;
            }
            if (mapWasAbsent) {
                parent.properties(null);
                return;
            }
            Map<String, Node> properties = parent.getProperties();
            if (properties != null) {
                properties.remove(propertyKey);
            }
        }
    }
}
