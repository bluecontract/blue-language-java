package blue.language.snapshot;

import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.util.PointerUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class CanonicalOverlayPatchEngine {

    private final FrozenNode root;

    public CanonicalOverlayPatchEngine(FrozenNode root) {
        this.root = Objects.requireNonNull(root, "root");
    }

    public static CanonicalOverlayPatchEngine forNode(Node canonicalRoot) {
        return new CanonicalOverlayPatchEngine(FrozenNode.fromNode(canonicalRoot));
    }

    public FrozenNode root() {
        return root;
    }

    public CanonicalPatchResult apply(JsonPatch patch) {
        Objects.requireNonNull(patch, "patch");
        String path = PointerUtils.normalizePointer(patch.getPath());
        List<String> segments = splitPointer(path);
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("Canonical overlay patches cannot target the root document");
        }

        FrozenNode before = read(root, segments, patch.getOp() == JsonPatch.Op.ADD);
        FrozenNode value = patch.getOp() == JsonPatch.Op.REMOVE ? null : FrozenNode.fromNode(patch.getVal());
        FrozenNode nextRoot;
        switch (patch.getOp()) {
            case ADD:
                nextRoot = add(root, segments, value, path);
                break;
            case REPLACE:
                nextRoot = replace(root, segments, value, path);
                break;
            case REMOVE:
                nextRoot = remove(root, segments, path);
                break;
            default:
                throw new UnsupportedOperationException("Unsupported patch op: " + patch.getOp());
        }

        FrozenNode after = patch.getOp() == JsonPatch.Op.REMOVE ? null : read(nextRoot, segments, false);
        return new CanonicalPatchResult(nextRoot, before, after, patch.getOp(), path);
    }

    private FrozenNode add(FrozenNode node, List<String> segments, FrozenNode value, String path) {
        return write(node, segments, value, path, WriteMode.ADD);
    }

    private FrozenNode replace(FrozenNode node, List<String> segments, FrozenNode value, String path) {
        return write(node, segments, value, path, WriteMode.REPLACE);
    }

    private FrozenNode remove(FrozenNode node, List<String> segments, String path) {
        return write(node, segments, null, path, WriteMode.REMOVE);
    }

    private FrozenNode write(FrozenNode node,
                             List<String> segments,
                             FrozenNode value,
                             String path,
                             WriteMode mode) {
        if (segments.size() == 1) {
            return writeLeaf(node, segments.get(0), value, path, mode);
        }

        String segment = segments.get(0);
        List<String> tail = segments.subList(1, segments.size());
        if (node.hasItems()) {
            int index = parseArrayIndex(segment, path);
            FrozenNode child = node.item(index);
            if (child == null) {
                throw new IllegalStateException("Array index out of bounds: " + path);
            }
            FrozenNode nextChild = write(child, tail, value, path, mode);
            List<FrozenNode> nextItems = new ArrayList<>(node.getItems());
            nextItems.set(index, nextChild);
            return node.withItems(nextItems);
        }

        if (node.getValue() != null) {
            throw new IllegalStateException("Cannot traverse into scalar at path: " + path);
        }

        FrozenNode child = node.property(segment);
        if (child == null) {
            if (isArrayIndexSegment(segment)) {
                throw new IllegalStateException("Expected array element to exist at path: " + path);
            }
            child = FrozenNode.empty();
        }
        FrozenNode nextChild = write(child, tail, value, path, mode);
        return node.withProperty(segment, nextChild);
    }

    private FrozenNode writeLeaf(FrozenNode node,
                                 String leaf,
                                 FrozenNode value,
                                 String path,
                                 WriteMode mode) {
        if (node.hasItems()) {
            List<FrozenNode> nextItems = new ArrayList<>(node.getItems());
            if ("-".equals(leaf)) {
                if (mode == WriteMode.REMOVE || mode == WriteMode.REPLACE) {
                    throw new IllegalStateException("Only add supports append token '-' at path: " + path);
                }
                nextItems.add(value);
                return node.withItems(nextItems);
            }

            int index = parseArrayIndex(leaf, path);
            switch (mode) {
                case ADD:
                    if (index < 0 || index > nextItems.size()) {
                        throw new IllegalStateException("Array index out of bounds for add: " + path);
                    }
                    nextItems.add(index, value);
                    return node.withItems(nextItems);
                case REPLACE:
                    if (index < 0 || index >= nextItems.size()) {
                        throw new IllegalStateException("Array index out of bounds for replace: " + path);
                    }
                    nextItems.set(index, value);
                    return node.withItems(nextItems);
                case REMOVE:
                    if (index < 0 || index >= nextItems.size()) {
                        throw new IllegalStateException("Array index out of bounds for remove: " + path);
                    }
                    nextItems.remove(index);
                    return node.withItems(nextItems);
                default:
                    throw new UnsupportedOperationException("Unsupported patch mode: " + mode);
            }
        }

        if (node.getValue() != null) {
            throw new IllegalStateException("Cannot traverse into scalar at path: " + path);
        }

        if ("-".equals(leaf)) {
            throw new IllegalStateException("Append token '-' requires array parent at path: " + path);
        }

        if (mode == WriteMode.REMOVE && node.property(leaf) == null) {
            throw new IllegalStateException("Path does not exist for remove: " + path);
        }
        return node.withProperty(leaf, mode == WriteMode.REMOVE ? null : value);
    }

    private FrozenNode read(FrozenNode node, List<String> segments, boolean beforeAdd) {
        FrozenNode current = node;
        for (int i = 0; i < segments.size(); i++) {
            if (current == null) {
                return null;
            }
            String segment = segments.get(i);
            boolean last = i == segments.size() - 1;
            if (current.hasItems()) {
                if ("-".equals(segment)) {
                    return beforeAdd && last ? null : current.item(current.getItems().size() - 1);
                }
                current = current.item(parseArrayIndex(segment, "/" + String.join("/", segments)));
            } else {
                current = current.property(segment);
            }
        }
        return current;
    }

    private int parseArrayIndex(String segment, String path) {
        try {
            int value = Integer.parseInt(segment);
            if (value < 0) {
                throw new IllegalStateException("Negative array index in path: " + path);
            }
            return value;
        } catch (NumberFormatException ex) {
            throw new IllegalStateException("Expected numeric array index in path: " + path);
        }
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

    private enum WriteMode {
        ADD,
        REPLACE,
        REMOVE
    }
}
