package blue.language.processor;

import blue.language.model.wire.BlueLanguageConstants;

import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.util.PointerUtils;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.snapshot.CanonicalOverlayPatchEngine;
import blue.language.snapshot.CanonicalPatchResult;
import blue.language.snapshot.BluePatchOperation;
import blue.language.snapshot.FrozenNode;
import blue.language.merge.ResolvedSnapshot;
import blue.language.utils.BlueIds;
import blue.language.model.wire.JsonPointer;
import blue.language.model.wire.ParsedJsonPointer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable JSON Patch planner over frozen snapshot roots.
 *
 * <p>The planner validates patch shape, computes before/after metadata, and
 * returns a new frozen root. It does not mutate the processor's materialized
 * view; callers decide when the planned root becomes visible.</p>
 */
final class ImmutablePatchPlanner {

    private final FrozenNode root;

    ImmutablePatchPlanner(FrozenNode root) {
        this.root = Objects.requireNonNull(root, "root");
    }

    static ImmutablePatchPlanner forSnapshot(ResolvedSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        return new ImmutablePatchPlanner(snapshot.frozenCanonicalRoot());
    }

    static ImmutablePatchPlanner forFrozen(FrozenNode root) {
        return new ImmutablePatchPlanner(root);
    }

    static ImmutablePatchPlanner forMaterialized(Node root) {
        Objects.requireNonNull(root, "root");
        return new ImmutablePatchPlanner(FrozenNode.fromResolvedNode(root));
    }

    FrozenNode root() {
        return root;
    }

    PatchPlan plan(String originScopePath, JsonPatch patch) {
        return plan(originScopePath, patch, false);
    }

    PatchPlan planWithExactReplacement(String originScopePath, JsonPatch patch) {
        return plan(originScopePath, patch, true);
    }

    PatchPlan plan(String originScopePath, ImmutableJsonPatch patch) {
        return plan(originScopePath, patch, false);
    }

    PatchPlan planWithExactReplacement(String originScopePath, ImmutableJsonPatch patch) {
        return plan(originScopePath, patch, true);
    }

    /**
     * Replaces a proven scalar value while retaining its already-resolved basic
     * type metadata. Only the scalar leaf is materialized; the surrounding
     * frozen tree is spliced with structural sharing.
     */
    PatchPlan planWithPreservedResolvedScalarMetadata(String originScopePath,
                                                      ImmutableJsonPatch patch) {
        Objects.requireNonNull(originScopePath, "originScopePath");
        Objects.requireNonNull(patch, "patch");
        if (patch.op() != JsonPatch.Op.REPLACE || patch.path().isRoot()) {
            throw new IllegalArgumentException(
                    "Resolved scalar metadata preservation requires a non-root replace patch");
        }
        validateMutationPath(patch.path());
        FrozenNode existing = read(patch.path());
        FrozenNode replacement = patch.valueFor(root);
        if (!PatchImpact.isValueOnlyScalar(existing)
                || !PatchImpact.isValueOnlyScalar(replacement)) {
            throw new IllegalArgumentException(
                    "Resolved scalar metadata preservation requires basic scalar leaves");
        }

        Node preservedNode = existing.toNode().value(replacement.getValue());
        FrozenNode preserved = root.isStrictCanonical()
                ? root.isStrictBlueIdValidation()
                ? FrozenNode.fromNode(preservedNode)
                : FrozenNode.fromUncheckedCanonicalNode(preservedNode)
                : FrozenNode.fromResolvedNode(preservedNode);
        String normalizedScope = PointerUtils.normalizeScope(originScopePath);
        CanonicalPatchResult replaced = new CanonicalOverlayPatchEngine(root)
                .apply(BluePatchOperation.REPLACE,
                        patch.path(), preserved);
        return new PatchPlan(replaced.root(),
                replaced.before(),
                replaced.after(),
                patch.op(),
                patch.normalizedPath(),
                normalizedScope,
                computeCascadeScopes(normalizedScope));
    }

    private PatchPlan plan(String originScopePath, JsonPatch patch, boolean exactReplacement) {
        Objects.requireNonNull(originScopePath, "originScopePath");
        Objects.requireNonNull(patch, "patch");
        String normalizedScope = PointerUtils.normalizeScope(originScopePath);
        String path = PointerUtils.canonicalizePointer(patch.getPath());
        validateMutationPath(path);
        if ((patch.getOp() == JsonPatch.Op.ADD || patch.getOp() == JsonPatch.Op.REPLACE)
                && JsonPointer.split(path).isEmpty()) {
            return rootReplacement(normalizedScope,
                    patch.getOp(), path, freezeValueForRoot(patch.getVal()));
        }
        if (exactReplacement
                && (patch.getOp() == JsonPatch.Op.ADD || patch.getOp() == JsonPatch.Op.REPLACE)) {
            return planExactValueWrite(normalizedScope, patch);
        }
        CanonicalPatchResult result = new CanonicalOverlayPatchEngine(root).apply(patch);
        return new PatchPlan(result.root(),
                result.before(),
                result.after(),
                JsonPatch.Op.fromBlueOperation(result.op()),
                result.path(),
                normalizedScope,
                computeCascadeScopes(normalizedScope));
    }

    private PatchPlan plan(String originScopePath,
                           ImmutableJsonPatch patch,
                           boolean exactReplacement) {
        Objects.requireNonNull(originScopePath, "originScopePath");
        Objects.requireNonNull(patch, "patch");
        String normalizedScope = PointerUtils.normalizeScope(originScopePath);
        validateMutationPath(patch.path());
        if ((patch.op() == JsonPatch.Op.ADD || patch.op() == JsonPatch.Op.REPLACE)
                && patch.path().isRoot()) {
            return rootReplacement(normalizedScope,
                    patch.op(), patch.normalizedPath(), patch.valueFor(root));
        }
        if (exactReplacement
                && (patch.op() == JsonPatch.Op.ADD || patch.op() == JsonPatch.Op.REPLACE)) {
            return planExactValueWrite(normalizedScope, patch);
        }
        CanonicalPatchResult result = new CanonicalOverlayPatchEngine(root)
                .apply(patch.blueOperation(),
                        patch.path(), patch.valueFor(root));
        return new PatchPlan(result.root(),
                result.before(),
                result.after(),
                JsonPatch.Op.fromBlueOperation(result.op()),
                result.path(),
                normalizedScope,
                computeCascadeScopes(normalizedScope));
    }

    private PatchPlan planExactValueWrite(String normalizedScope, JsonPatch patch) {
        String path = PointerUtils.canonicalizePointer(patch.getPath());
        if (patch.getOp() == JsonPatch.Op.ADD && targetsListMember(path)) {
            CanonicalPatchResult result = new CanonicalOverlayPatchEngine(root).apply(patch);
            return new PatchPlan(result.root(),
                    result.before(),
                    result.after(),
                    JsonPatch.Op.fromBlueOperation(result.op()),
                    result.path(),
                    normalizedScope,
                    computeCascadeScopes(normalizedScope));
        }
        FrozenNode existing = read(path);
        if (existing == null) {
            CanonicalPatchResult added = new CanonicalOverlayPatchEngine(root)
                    .apply(JsonPatch.add(path, patch.getVal()));
            return new PatchPlan(added.root(),
                    null,
                    added.after(),
                    patch.getOp(),
                    path,
                    normalizedScope,
                    computeCascadeScopes(normalizedScope));
        }
        CanonicalPatchResult removed = new CanonicalOverlayPatchEngine(root).apply(JsonPatch.remove(path));
        CanonicalPatchResult added = new CanonicalOverlayPatchEngine(removed.root())
                .apply(JsonPatch.add(path, patch.getVal()));
        return new PatchPlan(added.root(),
                removed.before(),
                added.after(),
                patch.getOp(),
                path,
                normalizedScope,
                computeCascadeScopes(normalizedScope));
    }

    private PatchPlan planExactValueWrite(String normalizedScope, ImmutableJsonPatch patch) {
        String path = patch.normalizedPath();
        if (patch.op() == JsonPatch.Op.ADD && targetsListMember(patch.path())) {
            CanonicalPatchResult result = new CanonicalOverlayPatchEngine(root)
                    .apply(patch.blueOperation(),
                            patch.path(), patch.valueFor(root));
            return new PatchPlan(result.root(),
                    result.before(),
                    result.after(),
                    JsonPatch.Op.fromBlueOperation(result.op()),
                    result.path(),
                    normalizedScope,
                    computeCascadeScopes(normalizedScope));
        }
        FrozenNode existing = read(patch.path());
        if (existing == null) {
            CanonicalPatchResult added = new CanonicalOverlayPatchEngine(root)
                    .apply(BluePatchOperation.ADD,
                            patch.path(), patch.valueFor(root));
            return new PatchPlan(added.root(),
                    null,
                    added.after(),
                    patch.op(),
                    path,
                    normalizedScope,
                    computeCascadeScopes(normalizedScope));
        }
        CanonicalPatchResult removed = new CanonicalOverlayPatchEngine(root)
                .apply(BluePatchOperation.REMOVE,
                        patch.path(), null);
        CanonicalPatchResult added = new CanonicalOverlayPatchEngine(removed.root())
                .apply(BluePatchOperation.ADD,
                        patch.path(), patch.valueFor(root));
        return new PatchPlan(added.root(),
                removed.before(),
                added.after(),
                patch.op(),
                path,
                normalizedScope,
                computeCascadeScopes(normalizedScope));
    }

    private FrozenNode freezeValueForRoot(Node value) {
        if (!root.isStrictCanonical()) {
            return FrozenNode.fromResolvedNode(value);
        }
        return root.isStrictBlueIdValidation()
                ? FrozenNode.fromNode(value)
                : FrozenNode.fromUncheckedCanonicalNode(value);
    }

    private PatchPlan rootReplacement(String normalizedScope,
                                      JsonPatch.Op op,
                                      String path,
                                      FrozenNode replacement) {
        return new PatchPlan(Objects.requireNonNull(replacement, "replacement"),
                root,
                replacement,
                op,
                path,
                normalizedScope,
                computeCascadeScopes(normalizedScope));
    }

    private boolean targetsListMember(String path) {
        List<String> segments = JsonPointer.split(path);
        if (segments.isEmpty()) {
            return false;
        }
        FrozenNode parent = read(JsonPointer.toPointer(segments.subList(0, segments.size() - 1)));
        return parent != null && parent.hasItems();
    }

    private boolean targetsListMember(ParsedJsonPointer path) {
        if (path.isRoot()) {
            return false;
        }
        FrozenNode parent = read(path.parent());
        return parent != null && parent.hasItems();
    }

    FrozenNode read(String path) {
        return read(root, path, LookupMode.AFTER);
    }

    FrozenNode read(ParsedJsonPointer path) {
        return read(root, path, LookupMode.AFTER);
    }

    void validateMutationPath(String path) {
        validatePath(
                ParsedJsonPointer.parse(path),
                "Mutation",
                false);
    }

    void validateMutationPath(ParsedJsonPointer path) {
        validatePath(path, "Mutation", false);
    }

    void validateProcessEmbeddedTraversalPath(String path) {
        validatePath(
                ParsedJsonPointer.parse(path),
                "Process Embedded traversal",
                true);
    }

    private void validatePath(
            ParsedJsonPointer path,
            String operation,
            boolean rejectCyclicEndpoint) {
        Objects.requireNonNull(path, "path");
        if (path.isRoot() || !root.containsCyclicSetReference()) {
            return;
        }
        FrozenNode current = root;
        List<String> segments = path.segments();
        for (int index = 0; index < segments.size() && current != null; index++) {
            if (isCyclicSetMemberReference(current)) {
                String boundary = JsonPointer.toPointer(segments.subList(0, index));
                throw new ProcessorFailureException(
                        rejectCyclicEndpoint
                                ? ProcessorErrorCategory
                                .CyclicSetEmbeddedBoundaryUnsupported
                                : ProcessorErrorCategory
                                .CyclicSetMutationUnsupported,
                        operation
                                + " below cyclic-set member reference is "
                                + "unsupported at "
                                + boundary + ": " + path.pointer());
            }
            String segment = segments.get(index);
            if (isIntrinsicMutationPathChild(segment)) {
                current = intrinsicMutationPathChild(current, segment);
            } else if (current.hasItems()) {
                if ("-".equals(segment)) {
                    return;
                }
                int arrayIndex;
                try {
                    arrayIndex = Integer.parseInt(segment);
                } catch (NumberFormatException ignored) {
                    return;
                }
                current = current.item(arrayIndex);
            } else {
                current = current.property(segment);
            }
        }
        if (rejectCyclicEndpoint
                && isCyclicSetMemberReference(current)) {
            throw new ProcessorFailureException(
                    ProcessorErrorCategory
                            .CyclicSetEmbeddedBoundaryUnsupported,
                    operation
                            + " into cyclic-set member reference is "
                            + "unsupported at " + path.pointer());
        }
    }

    /**
     * Mirrors the intrinsic {@link Node} children addressable by processor
     * paths. {@link FrozenNode#property(String)} deliberately exposes only
     * authored object properties and {@code contracts}; mutation preflight must
     * additionally follow the other intrinsic node-valued fields so a cyclic
     * member cannot be hidden behind one of them.
     */
    private static FrozenNode intrinsicMutationPathChild(FrozenNode node,
                                                         String segment) {
        if (BlueLanguageConstants.OBJECT_TYPE.equals(segment)) {
            return node.getType();
        }
        if (BlueLanguageConstants.OBJECT_ITEM_TYPE.equals(segment)) {
            return node.getItemType();
        }
        if (BlueLanguageConstants.OBJECT_KEY_TYPE.equals(segment)) {
            return node.getKeyType();
        }
        if (BlueLanguageConstants.OBJECT_VALUE_TYPE.equals(segment)) {
            return node.getValueType();
        }
        if (BlueLanguageConstants.OBJECT_BLUE.equals(segment)) {
            return node.getBlue();
        }
        if (ProcessorContractConstants.KEY_CONTRACTS.equals(segment)) {
            return node.getContracts();
        }
        throw new IllegalArgumentException(
                "Not an intrinsic node child: " + segment);
    }

    private static boolean isIntrinsicMutationPathChild(String segment) {
        return BlueLanguageConstants.OBJECT_TYPE.equals(segment)
                || BlueLanguageConstants.OBJECT_ITEM_TYPE.equals(segment)
                || BlueLanguageConstants.OBJECT_KEY_TYPE.equals(segment)
                || BlueLanguageConstants.OBJECT_VALUE_TYPE.equals(segment)
                || BlueLanguageConstants.OBJECT_BLUE.equals(segment)
                || ProcessorContractConstants.KEY_CONTRACTS.equals(segment);
    }

    FrozenNode applyMutationPreflight(JsonPatch.Op op,
                                      ParsedJsonPointer path,
                                      FrozenNode value,
                                      boolean exactReplacement) {
        Objects.requireNonNull(op, "op");
        Objects.requireNonNull(path, "path");
        validateMutationPath(path);
        if (path.isRoot()
                && (op == JsonPatch.Op.ADD || op == JsonPatch.Op.REPLACE)) {
            return Objects.requireNonNull(value, BlueLanguageConstants.OBJECT_VALUE);
        }
        CanonicalOverlayPatchEngine engine =
                new CanonicalOverlayPatchEngine(root);
        if (!exactReplacement
                || op == JsonPatch.Op.REMOVE) {
            return engine.apply(op.blueOperation(), path, value).root();
        }
        if (op == JsonPatch.Op.ADD && targetsListMember(path)) {
            return engine.apply(op.blueOperation(), path, value).root();
        }
        if (read(path) == null) {
            return engine.apply(
                    BluePatchOperation.ADD, path, value).root();
        }
        FrozenNode removed = engine
                .apply(BluePatchOperation.REMOVE, path, null)
                .root();
        return new CanonicalOverlayPatchEngine(removed)
                .apply(BluePatchOperation.ADD, path, value)
                .root();
    }

    private static boolean isCyclicSetMemberReference(FrozenNode node) {
        if (!node.isReferenceOnly()) {
            return false;
        }
        String blueId = node.getReferenceBlueId();
        if (!BlueIds.hasCyclicMemberSeparator(blueId)) {
            return false;
        }
        try {
            BlueIds.requireBlueIdOrCyclicMember(blueId, "cyclic-set member reference");
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    static FrozenNode readAfter(ResolvedSnapshot snapshot, String path, boolean resolved) {
        return readSnapshot(snapshot, path, resolved, LookupMode.AFTER);
    }

    static FrozenNode readBefore(ResolvedSnapshot snapshot, String path, boolean resolved) {
        return readSnapshot(snapshot, path, resolved, LookupMode.BEFORE);
    }

    private static FrozenNode readSnapshot(ResolvedSnapshot snapshot, String path, boolean resolved, LookupMode mode) {
        Objects.requireNonNull(snapshot, "snapshot");
        String normalized = PointerUtils.normalizePointer(path);
        if (!normalized.endsWith("/-")) {
            return resolved ? snapshot.resolvedAt(normalized) : snapshot.canonicalAt(normalized);
        }
        FrozenNode root = resolved ? snapshot.frozenResolvedRoot() : snapshot.frozenCanonicalRoot();
        return read(root, normalized, mode);
    }

    static Node readNode(Node root, String path) {
        FrozenNode node = forMaterialized(root).read(path);
        return node != null ? node.toNode() : null;
    }

    private static FrozenNode read(FrozenNode root, String path, LookupMode mode) {
        return read(root, ParsedJsonPointer.parse(path), mode);
    }

    private static FrozenNode read(FrozenNode root, ParsedJsonPointer path, LookupMode mode) {
        String normalized = path.pointer();
        List<String> segments = path.segments();
        FrozenNode current = root;
        for (int i = 0; i < segments.size(); i++) {
            if (current == null) {
                return null;
            }
            String segment = segments.get(i);
            boolean last = i == segments.size() - 1;
            if (current.hasItems()) {
                if ("-".equals(segment)) {
                    if (!last) {
                        throw new IllegalStateException("Append token '-' must be final segment: " + normalized);
                    }
                    return mode == LookupMode.BEFORE ? null : current.item(current.getItems().size() - 1);
                }
                current = current.item(parseArrayIndex(segment, normalized));
            } else {
                current = current.property(segment);
            }
        }
        return current;
    }

    private static List<String> computeCascadeScopes(String scopePath) {
        List<String> scopes = new ArrayList<>();
        String current = scopePath;
        while (true) {
            scopes.add(current);
            if ("/".equals(current)) {
                break;
            }
            List<String> segments = JsonPointer.split(current);
            current = JsonPointer.toPointer(segments.subList(0, segments.size() - 1));
        }
        return Collections.unmodifiableList(scopes);
    }

    private static int parseArrayIndex(String segment, String path) {
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

    private enum LookupMode {
        BEFORE,
        AFTER
    }

    static final class PatchPlan {
        private final FrozenNode root;
        private final FrozenNode before;
        private final FrozenNode after;
        private final JsonPatch.Op op;
        private final String path;
        private final String originScope;
        private final List<String> cascadeScopes;

        private PatchPlan(FrozenNode root,
                          FrozenNode before,
                          FrozenNode after,
                          JsonPatch.Op op,
                          String path,
                          String originScope,
                          List<String> cascadeScopes) {
            this.root = root;
            this.before = before;
            this.after = after;
            this.op = op;
            this.path = path;
            this.originScope = originScope;
            this.cascadeScopes = cascadeScopes;
        }

        FrozenNode root() {
            return root;
        }

        FrozenNode before() {
            return before;
        }

        FrozenNode after() {
            return after;
        }

        Node rootNode() {
            return root.toNode();
        }

        Node beforeNode() {
            return before != null ? before.toNode() : null;
        }

        Node afterNode() {
            return after != null ? after.toNode() : null;
        }

        JsonPatch.Op op() {
            return op;
        }

        String path() {
            return path;
        }

        String originScope() {
            return originScope;
        }

        List<String> cascadeScopes() {
            return cascadeScopes;
        }
    }
}
