package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.util.PointerUtils;
import blue.language.snapshot.CanonicalOverlayPatchEngine;
import blue.language.snapshot.CanonicalPatchResult;
import blue.language.snapshot.FrozenNode;
import blue.language.snapshot.ResolvedSnapshot;

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

    PatchPlan plan(String originScopePath, JsonPatch patch) {
        Objects.requireNonNull(originScopePath, "originScopePath");
        Objects.requireNonNull(patch, "patch");
        String normalizedScope = PointerUtils.normalizeScope(originScopePath);
        CanonicalPatchResult result = new CanonicalOverlayPatchEngine(root).apply(patch);
        return new PatchPlan(result.root(),
                result.before(),
                result.after(),
                result.op(),
                result.path(),
                normalizedScope,
                computeCascadeScopes(normalizedScope));
    }

    FrozenNode read(String path) {
        return read(root, path, LookupMode.AFTER);
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
        String normalized = PointerUtils.normalizePointer(path);
        List<String> segments = splitPointer(normalized);
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
            int idx = current.lastIndexOf('/');
            if (idx <= 0) {
                current = "/";
            } else {
                current = current.substring(0, idx);
            }
        }
        return Collections.unmodifiableList(scopes);
    }

    private static List<String> splitPointer(String path) {
        if ("/".equals(path)) {
            return new ArrayList<>();
        }
        String raw = path.substring(1);
        if (raw.isEmpty()) {
            return new ArrayList<>();
        }
        String[] parts = raw.split("/", -1);
        List<String> segments = new ArrayList<>(parts.length);
        Collections.addAll(segments, parts);
        return segments;
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
