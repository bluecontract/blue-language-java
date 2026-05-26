package blue.language.snapshot;

import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.util.PointerUtils;
import blue.language.utils.JsonPointer;

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
        String path = PointerUtils.canonicalizePointer(patch.getPath());
        List<String> segments = JsonPointer.split(path);
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("Canonical overlay patches cannot target the root document");
        }

        FrozenNode before = read(root, segments, patch.getOp() == JsonPatch.Op.ADD);
        FrozenNode value = patch.getOp() == JsonPatch.Op.REMOVE ? null : freezePatchValue(patch.getVal());
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

    private FrozenNode freezePatchValue(Node value) {
        if (root.isStrictCanonical()) {
            return root.isStrictBlueIdValidation()
                    ? FrozenNode.fromNode(value)
                    : FrozenNode.fromUncheckedCanonicalNode(value);
        }
        return FrozenNode.fromResolvedNode(value);
    }

    private FrozenNode emptyNodeForRootMode() {
        if (root.isStrictCanonical()) {
            return root.isStrictBlueIdValidation()
                    ? FrozenNode.empty()
                    : FrozenNode.fromUncheckedCanonicalNode(new Node());
        }
        return FrozenNode.fromResolvedNode(new Node());
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
            if (JsonPointer.isArrayIndexSegment(segment)) {
                throw new IllegalStateException("Expected array element to exist at path: " + path);
            }
            child = emptyNodeForRootMode();
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

        FrozenNode existing = node.property(leaf);
        if (mode == WriteMode.REMOVE && existing == null) {
            throw new IllegalStateException("Path does not exist for remove: " + path);
        }
        FrozenNode nextValue = mode == WriteMode.REPLACE ? mergeObjectReplacement(existing, value) : value;
        return node.withProperty(leaf, mode == WriteMode.REMOVE ? null : nextValue);
    }

    private FrozenNode mergeObjectReplacement(FrozenNode existing, FrozenNode replacement) {
        if (!isMergeableObject(existing) || !isMergeableObject(replacement)) {
            return replacement;
        }
        Node merged = existing.toNode();
        Node overlay = replacement.toNode();
        if (overlay.getProperties() != null) {
            overlay.getProperties().forEach((key, value) -> merged.properties(key, value.clone()));
        }
        if (overlay.getContracts() != null) {
            merged.contracts(overlay.getContracts().clone());
        }
        if (overlay.getType() != null) {
            merged.type(overlay.getType().clone());
        }
        if (overlay.getItemType() != null) {
            merged.itemType(overlay.getItemType().clone());
        }
        if (overlay.getKeyType() != null) {
            merged.keyType(overlay.getKeyType().clone());
        }
        if (overlay.getValueType() != null) {
            merged.valueType(overlay.getValueType().clone());
        }
        if (overlay.getBlue() != null) {
            merged.blue(overlay.getBlue().clone());
        }
        if (overlay.getSchema() != null) {
            merged.schema(overlay.getSchema().clone());
        }
        if (overlay.getName() != null) {
            merged.name(overlay.getName());
        }
        if (overlay.getDescription() != null) {
            merged.description(overlay.getDescription());
        }
        if (overlay.getMergePolicy() != null) {
            merged.mergePolicy(overlay.getMergePolicy());
        }
        if (overlay.getPreviousBlueId() != null) {
            merged.previousBlueId(overlay.getPreviousBlueId());
        }
        if (overlay.getPosition() != null) {
            merged.position(overlay.getPosition());
        }
        return freezePatchValue(merged);
    }

    private boolean isMergeableObject(FrozenNode node) {
        return node != null
                && node.getValue() == null
                && !node.hasItems()
                && !node.isReferenceOnly()
                && node.getPreviousBlueId() == null;
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
                current = current.item(parseArrayIndex(segment, JsonPointer.toPointer(segments)));
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

    private enum WriteMode {
        ADD,
        REPLACE,
        REMOVE
    }
}
