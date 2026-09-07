package blue.language.mapping;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.merge.ResolvedSnapshot;
import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.model.wire.JsonPointer;
import blue.language.snapshot.FrozenNode;
import blue.language.snapshot.FrozenNodeNavigator;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

import static blue.language.model.wire.BlueLanguageConstants.OBJECT_CONTRACTS;

/** Invocation-local Content BlueIds proven by a canonical snapshot lane. */
final class CanonicalContentIdentityLookup {

    private static final CanonicalContentIdentityLookup DIRECT_ONLY =
            new CanonicalContentIdentityLookup(
                    Collections.<Node, String>emptyMap(),
                    Collections.<Node>emptySet());

    private final Map<Node, String> canonicalBlueIds;
    private final Set<Node> snapshotNodes;

    private CanonicalContentIdentityLookup(
            Map<Node, String> canonicalBlueIds,
            Set<Node> snapshotNodes) {
        this.canonicalBlueIds = canonicalBlueIds;
        this.snapshotNodes = snapshotNodes;
    }

    static CanonicalContentIdentityLookup directOnly() {
        return DIRECT_ONLY;
    }

    /**
     * Marks a detached resolved graph as requiring authoritative content
     * evidence for every {@code @BlueId} lookup.
     *
     * <p>This is deliberately distinct from {@link #directOnly()}: callers
     * that only retain effective-type evidence must not hash materialized
     * Resolved Form as though it were authored Source.</p>
     */
    static CanonicalContentIdentityLookup resolvedOnly(Node resolvedRoot) {
        Set<Node> resolvedNodes = Collections.newSetFromMap(
                new IdentityHashMap<Node, Boolean>());
        collectSnapshotNodes(resolvedRoot, resolvedNodes);
        return new CanonicalContentIdentityLookup(
                Collections.<Node, String>emptyMap(),
                resolvedNodes);
    }

    static CanonicalContentIdentityLookup fromSnapshot(
            ResolvedSnapshot snapshot,
            Node resolvedRoot,
            String rootPointer) {
        Map<Node, String> identities = new IdentityHashMap<>();
        Set<Node> snapshotNodes = Collections.newSetFromMap(
                new IdentityHashMap<Node, Boolean>());
        collectSnapshotNodes(resolvedRoot, snapshotNodes);
        String pointer = JsonPointer.canonicalize(rootPointer);
        if (snapshot.hasCanonicalIdentity()
                && hasStableObjectPath(snapshot, pointer)) {
            index(
                    snapshot,
                    resolvedRoot,
                    pointer,
                    identities,
                    Collections.newSetFromMap(
                            new IdentityHashMap<Node, Boolean>()));
        }
        return new CanonicalContentIdentityLookup(
                identities,
                snapshotNodes);
    }

    String requireBlueId(Node node) {
        String canonicalBlueId = canonicalBlueIds.get(node);
        if (canonicalBlueId != null) {
            return canonicalBlueId;
        }
        if (snapshotNodes.contains(node)) {
            throw new IllegalStateException(
                    "Mapping @BlueId requires a corresponding node in the "
                            + "authoritative Canonical Identity Input; "
                            + "resolved list positions and inherited-only "
                            + "fields are not assumed to share canonical "
                            + "paths");
        }
        requireDirectCanonicalTypePositions(node);
        return DirectBlueIdCalculator.calculateBlueId(node);
    }

    private static void index(
            ResolvedSnapshot snapshot,
            Node resolved,
            String pointer,
            Map<Node, String> identities,
            Set<Node> visited) {
        if (resolved == null || !visited.add(resolved)) {
            return;
        }
        FrozenNode authoritativeResolved = snapshot.resolvedAt(pointer);
        FrozenNode canonical = snapshot.canonicalAt(pointer);
        if (canonical != null
                && authoritativeResolved != null
                && authoritativeResolved.sameResolvedStructure(
                        FrozenNode.fromResolvedNode(resolved))) {
            identities.put(resolved, canonical.blueId());
        }
        if (resolved.getItems() != null) {
            /*
             * Source list overlays and inherited list items need not occupy
             * the same indexes in Canonical Identity Input and Resolved Form.
             * Without resolver-issued element provenance, correlating those
             * positions would be an identity guess. The list node itself is
             * still safe; descendants fail closed unless selected through a
             * future provenance-bearing list API.
             */
            return;
        }
        if (resolved.getProperties() != null) {
            for (Map.Entry<String, Node> entry
                    : resolved.getProperties().entrySet()) {
                index(
                        snapshot,
                        entry.getValue(),
                        JsonPointer.append(pointer, entry.getKey()),
                        identities,
                        visited);
            }
        }
        index(
                snapshot,
                resolved.getContracts(),
                JsonPointer.append(pointer, OBJECT_CONTRACTS),
                identities,
                visited);
    }

    private static void collectSnapshotNodes(
            Node root,
            Set<Node> snapshotNodes) {
        Deque<Node> pending = new ArrayDeque<>();
        push(pending, root);
        while (!pending.isEmpty()) {
            Node node = pending.pop();
            if (!snapshotNodes.add(node)) {
                continue;
            }
            push(pending, node.getContracts());
            if (node.getItems() != null) {
                for (Node item : node.getItems()) {
                    push(pending, item);
                }
            }
            if (node.getProperties() != null) {
                for (Node property : node.getProperties().values()) {
                    push(pending, property);
                }
            }
        }
    }

    private static boolean hasStableObjectPath(
            ResolvedSnapshot snapshot,
            String pointer) {
        FrozenNode resolved = snapshot.frozenResolvedRoot();
        FrozenNode canonical = snapshot.frozenCanonicalRoot();
        for (String segment : JsonPointer.split(pointer)) {
            if (resolved == null
                    || resolved.getItems() != null
                    || (canonical != null
                    && canonical.getItems() != null)) {
                return false;
            }
            resolved = FrozenNodeNavigator.INSTANCE.property(
                    resolved,
                    segment);
            canonical = canonical != null
                    ? FrozenNodeNavigator.INSTANCE.property(
                    canonical,
                    segment)
                    : null;
        }
        return resolved != null;
    }

    private static void requireDirectCanonicalTypePositions(Node root) {
        Deque<Node> pending = new ArrayDeque<>();
        Set<Node> visited = Collections.newSetFromMap(
                new IdentityHashMap<Node, Boolean>());
        pending.push(root);
        while (!pending.isEmpty()) {
            Node node = pending.pop();
            if (!visited.add(node)) {
                continue;
            }
            requirePureTypeReference(node.getType());
            requirePureTypeReference(node.getItemType());
            requirePureTypeReference(node.getKeyType());
            requirePureTypeReference(node.getValueType());
            push(pending, node.getBlue());
            push(pending, node.getContracts());
            if (node.getItems() != null) {
                for (Node item : node.getItems()) {
                    push(pending, item);
                }
            }
            if (node.getProperties() != null) {
                for (Node property : node.getProperties().values()) {
                    push(pending, property);
                }
            }
            pushSchemaNodes(pending, node.getSchema());
        }
    }

    private static void requirePureTypeReference(Node type) {
        if (type != null && !type.isReferenceOnly()) {
            throw new IllegalStateException(
                    "Mapping @BlueId from materialized or inline type "
                            + "content requires an authoritative "
                            + "ResolvedSnapshot canonical identity lane");
        }
    }

    private static void pushSchemaNodes(
            Deque<Node> pending,
            Schema schema) {
        if (schema == null) {
            return;
        }
        push(pending, schema.getRequired());
        push(pending, schema.getMinLength());
        push(pending, schema.getMaxLength());
        push(pending, schema.getMinimum());
        push(pending, schema.getMaximum());
        push(pending, schema.getExclusiveMinimum());
        push(pending, schema.getExclusiveMaximum());
        push(pending, schema.getMultipleOf());
        push(pending, schema.getMinItems());
        push(pending, schema.getMaxItems());
        push(pending, schema.getUniqueItems());
        push(pending, schema.getMinFields());
        push(pending, schema.getMaxFields());
        if (schema.getEnum() != null) {
            for (Node enumValue : schema.getEnum()) {
                push(pending, enumValue);
            }
        }
    }

    private static void push(Deque<Node> pending, Node node) {
        if (node != null) {
            pending.push(node);
        }
    }
}
