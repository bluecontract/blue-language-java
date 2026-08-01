package blue.language.snapshot;

import blue.language.utils.Properties;

import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import blue.language.utils.JsonPointer;
import blue.language.utils.ParsedJsonPointer;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static blue.language.utils.Properties.OBJECT_CONTRACTS;
import static blue.language.utils.Properties.OBJECT_VALUE;

/**
 * Applies JSON Patch operations to an immutable canonical or resolved frozen
 * tree using structural sharing.
 *
 * <p>The original root is never modified. Root replacement is forbidden;
 * canonical overlay paths may create absent containers because an effective
 * parent can be inherited, while the processor boundary separately requires
 * that the final effective parent already exists.</p>
 */
public final class CanonicalOverlayPatchEngine {

    private static final String ARRAY_APPEND_TOKEN = "-";

    private final FrozenNode root;

    /**
     * Creates an engine retaining an immutable root.
     *
     * @param root canonical or resolved frozen root
     */
    public CanonicalOverlayPatchEngine(FrozenNode root) {
        this.root = Objects.requireNonNull(root, "root");
    }

    /**
     * Strictly freezes a mutable canonical root.
     *
     * @param canonicalRoot mutable canonical root
     * @return patch engine
     */
    public static CanonicalOverlayPatchEngine forNode(Node canonicalRoot) {
        return new CanonicalOverlayPatchEngine(FrozenNode.fromNode(canonicalRoot));
    }

    /** Returns the retained root.
     * @return immutable root */
    public FrozenNode root() {
        return root;
    }

    /**
     * Applies one patch and returns the new root plus before/after evidence.
     *
     * @param patch mutable patch input
     * @return immutable patch result
     * @throws IllegalArgumentException for malformed/root paths
     * @throws IllegalStateException for shape or existence violations
     */
    public CanonicalPatchResult apply(JsonPatch patch) {
        Objects.requireNonNull(patch, "patch");
        ParsedJsonPointer path = ParsedJsonPointer.parse(patch.getPath());
        FrozenNode value = patch.getOp() == JsonPatch.Op.REMOVE ? null : freezePatchValue(patch.getVal());
        return apply(patch.getOp(), path, value);
    }

    /**
     * Applies a patch whose pointer and immutable value were prepared at the
     * transaction boundary. This avoids reparsing paths and refreezing values
     * in each canonical/resolved planning layer.
     *
     * @param op patch operation
     * @param parsedPath parsed non-root pointer
     * @param value frozen value, or {@code null} for REMOVE
     * @return immutable patch result
     */
    public CanonicalPatchResult apply(JsonPatch.Op op,
                                      ParsedJsonPointer parsedPath,
                                      FrozenNode value) {
        Objects.requireNonNull(op, "op");
        Objects.requireNonNull(parsedPath, "parsedPath");
        String path = parsedPath.pointer();
        List<String> segments = parsedPath.segments();
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("Canonical overlay patches cannot target the root document");
        }
        if (op != JsonPatch.Op.REMOVE) {
            Objects.requireNonNull(value, Properties.OBJECT_VALUE);
        }

        FrozenNode before = read(root, segments, op == JsonPatch.Op.ADD, path);
        FrozenNode nextRoot;
        switch (op) {
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
                throw new UnsupportedOperationException("Unsupported patch op: " + op);
        }

        FrozenNode after = op == JsonPatch.Op.REMOVE ? null : read(nextRoot, segments, false, path);
        return new CanonicalPatchResult(nextRoot, before, after, op, path);
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
        if (isContractsMetadata(segment)) {
            FrozenNode child = node.property(segment);
            if (child == null) {
                child = emptyNodeForRootMode();
            }
            FrozenNode nextChild =
                    write(child, tail, value, path, mode);
            return node.withPropertyForPatch(
                    segment, nextChild);
        }
        if (node.hasItems()) {
            int index = parseArrayIndex(segment, path);
            FrozenNode child = node.item(index);
            if (child == null) {
                throw new IllegalStateException("Array index out of bounds: " + path);
            }
            FrozenNode nextChild = write(child, tail, value, path, mode);
            List<FrozenNode> nextItems = new ArrayList<>(node.getItems());
            nextItems.set(index, nextChild);
            return node.withItemsForPatch(nextItems);
        }

        if (node.getValue() != null) {
            throw new IllegalStateException("Cannot traverse into scalar at path: " + path);
        }

        FrozenNode child = node.property(segment);
        if (child == null) {
            if (JsonPointer.isArrayIndexSegment(segment)) {
                throw new IllegalStateException(
                        "Expected array element to exist at path: " + path);
            }
            child = emptyNodeForRootMode();
        }
        FrozenNode nextChild = write(child, tail, value, path, mode);
        return node.withPropertyForPatch(segment, nextChild);
    }

    private FrozenNode writeLeaf(FrozenNode node,
                                 String leaf,
                                 FrozenNode value,
                                 String path,
                                 WriteMode mode) {
        if (OBJECT_VALUE.equals(leaf)) {
            Object nextValue = mode == WriteMode.REMOVE
                    ? null
                    : scalarPatchValue(value, path);
            return node.withValueForPatch(nextValue);
        }
        if (isContractsMetadata(leaf)) {
            return writePropertyLeaf(
                    node, leaf, value, path, mode);
        }
        if (node.hasItems()) {
            List<FrozenNode> nextItems = new ArrayList<>(node.getItems());
            if (ARRAY_APPEND_TOKEN.equals(leaf)) {
                if (mode == WriteMode.REMOVE || mode == WriteMode.REPLACE) {
                    throw new IllegalStateException("Only add supports append token '-' at path: " + path);
                }
                nextItems.add(value);
                return node.withItemsForPatch(nextItems);
            }

            int index = parseArrayIndex(leaf, path);
            switch (mode) {
                case ADD:
                    if (index < 0 || index > nextItems.size()) {
                        throw new IllegalStateException("Array index out of bounds for add: " + path);
                    }
                    nextItems.add(index, value);
                    return node.withItemsForPatch(nextItems);
                case REPLACE:
                    if (index < 0 || index >= nextItems.size()) {
                        throw new IllegalStateException("Array index out of bounds for replace: " + path);
                    }
                    nextItems.set(index, value);
                    return node.withItemsForPatch(nextItems);
                case REMOVE:
                    if (index < 0 || index >= nextItems.size()) {
                        throw new IllegalStateException("Array index out of bounds for remove: " + path);
                    }
                    nextItems.remove(index);
                    return node.withItemsForPatch(nextItems);
                default:
                    throw new UnsupportedOperationException("Unsupported patch mode: " + mode);
            }
        }

        if (node.getValue() != null) {
            throw new IllegalStateException("Cannot traverse into scalar at path: " + path);
        }

        if (ARRAY_APPEND_TOKEN.equals(leaf)) {
            throw new IllegalStateException("Append token '-' requires array parent at path: " + path);
        }

        return writePropertyLeaf(
                node, leaf, value, path, mode);
    }

    private FrozenNode writePropertyLeaf(
            FrozenNode node,
            String leaf,
            FrozenNode value,
            String path,
            WriteMode mode) {
        FrozenNode existing = node.property(leaf);
        if (mode == WriteMode.REMOVE && existing == null) {
            throw new IllegalStateException("Path does not exist for remove: " + path);
        }
        FrozenNode nextValue = mode == WriteMode.REPLACE ? mergeObjectReplacement(existing, value) : value;
        return node.withPropertyForPatch(leaf, mode == WriteMode.REMOVE ? null : nextValue);
    }

    private FrozenNode mergeObjectReplacement(FrozenNode existing, FrozenNode replacement) {
        if (!isMergeableObject(existing) || !isMergeableObject(replacement)) {
            return replacement;
        }
        if (canUseFrozenOverlay(existing, replacement)) {
            return existing.overlayObjectForPatch(replacement);
        }

        Node merged = existing.toNode();
        Node overlay = replacement.toNode();
        if (overlay.getProperties() != null) {
            overlay.getProperties().forEach((key, value) -> merged.properties(key, value.clone()));
        }
        if (overlay.getContracts() != null) merged.contracts(overlay.getContracts().clone());
        if (overlay.getType() != null) merged.type(overlay.getType().clone());
        if (overlay.getItemType() != null) merged.itemType(overlay.getItemType().clone());
        if (overlay.getKeyType() != null) merged.keyType(overlay.getKeyType().clone());
        if (overlay.getValueType() != null) merged.valueType(overlay.getValueType().clone());
        if (overlay.getBlue() != null) merged.blue(overlay.getBlue().clone());
        if (overlay.getSchema() != null) merged.schema(overlay.getSchema().clone());
        if (overlay.getName() != null) merged.name(overlay.getName());
        if (overlay.getDescription() != null) merged.description(overlay.getDescription());
        if (overlay.getMergePolicy() != null) merged.mergePolicy(overlay.getMergePolicy());
        if (overlay.getPreviousBlueId() != null) merged.previousBlueId(overlay.getPreviousBlueId());
        if (overlay.getPosition() != null) merged.position(overlay.getPosition());
        return freezePatchValue(merged);
    }

    private boolean canUseFrozenOverlay(FrozenNode existing, FrozenNode replacement) {
        return sameFreezeMode(root, existing)
                && sameFreezeMode(root, replacement)
                && !existing.isListElementContext()
                && !replacement.isListElementContext()
                && existing.isConstructionModeNormalized()
                && replacement.isConstructionModeNormalized();
    }

    private boolean sameFreezeMode(FrozenNode left, FrozenNode right) {
        return left.isStrictCanonical() == right.isStrictCanonical()
                && left.isStrictBlueIdValidation() == right.isStrictBlueIdValidation();
    }

    private boolean isMergeableObject(FrozenNode node) {
        return node != null
                && node.getValue() == null
                && !node.hasItems()
                && !node.isReferenceOnly()
                && node.getPreviousBlueId() == null;
    }

    private FrozenNode read(FrozenNode node,
                            List<String> segments,
                            boolean beforeAdd,
                            String renderedPath) {
        FrozenNode current = node;
        for (int i = 0; i < segments.size(); i++) {
            if (current == null) {
                return null;
            }
            String segment = segments.get(i);
            boolean last = i == segments.size() - 1;
            if (OBJECT_VALUE.equals(segment)) {
                if (!last || current.getValue() == null) {
                    return null;
                }
                current = freezePatchValue(
                        new Node().value(current.getValue()));
            } else if (isContractsMetadata(segment)) {
                current = current.property(segment);
            } else if (current.hasItems()) {
                if (ARRAY_APPEND_TOKEN.equals(segment)) {
                    return beforeAdd && last ? null : current.item(current.getItems().size() - 1);
                }
                current = current.item(parseArrayIndex(segment, renderedPath));
            } else {
                current = current.property(segment);
            }
        }
        return current;
    }

    private Object scalarPatchValue(FrozenNode value, String path) {
        if (value == null
                || value.getValue() == null
                || value.hasItems()
                || value.hasProperties()
                || value.getContracts() != null) {
            throw new IllegalStateException(
                    "Node intrinsic 'value' requires a scalar patch value at path: "
                            + path);
        }
        return value.getValue();
    }

    private boolean isContractsMetadata(String segment) {
        return OBJECT_CONTRACTS.equals(segment);
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
