package blue.language.merge;

import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Exact verified content of value references materialized by one resolver
 * invocation, keyed by requested BlueId.
 *
 * <p>Canonical identity reconstruction uses this content as the authored
 * lane of a referenced child so that a verified reference and the same
 * content written inline reconstruct through one path. Lookups hand out one
 * detached, read-only materialization per BlueId.</p>
 */
final class VerifiedReferenceContents {

    private static final long ENTRY_OVERHEAD_BYTES = 96L;
    private static final long CHARACTER_BYTES = 2L;

    static final VerifiedReferenceContents EMPTY =
            new VerifiedReferenceContents(
                    Collections.<String, FrozenNode>emptyMap());

    private final Map<String, FrozenNode> contents;

    private VerifiedReferenceContents(Map<String, FrozenNode> contents) {
        this.contents = contents;
    }

    /** Creates a mutable, insertion-ordered registry. */
    static VerifiedReferenceContents mutable() {
        return new VerifiedReferenceContents(
                new LinkedHashMap<String, FrozenNode>());
    }

    void record(String blueId, FrozenNode content) {
        contents.put(
                Objects.requireNonNull(blueId, "reference BlueId"),
                Objects.requireNonNull(content, "content"));
    }

    void putAll(VerifiedReferenceContents other) {
        contents.putAll(other.contents);
    }

    boolean isEmpty() {
        return contents.isEmpty();
    }

    Set<String> blueIds() {
        return contents.keySet();
    }

    /**
     * Returns a fresh detached materialization; callers may mutate it
     * freely and nothing beyond the frozen content is retained.
     */
    Optional<Node> find(String blueId) {
        FrozenNode content = contents.get(blueId);
        return content != null
                ? Optional.of(content.toNode())
                : Optional.<Node>empty();
    }

    /** Returns an immutable copy for transport inside evidence snapshots. */
    VerifiedReferenceContents frozen() {
        return contents.isEmpty()
                ? EMPTY
                : new VerifiedReferenceContents(Collections.unmodifiableMap(
                        new LinkedHashMap<>(contents)));
    }

    /** Returns an immutable union of two registries. */
    VerifiedReferenceContents union(VerifiedReferenceContents other) {
        if (other.isEmpty()) {
            return frozen();
        }
        Map<String, FrozenNode> combined = new LinkedHashMap<>(contents);
        combined.putAll(other.contents);
        return new VerifiedReferenceContents(
                Collections.unmodifiableMap(combined));
    }

    /**
     * Selects the contents of references materialized strictly inside
     * {@code root}, excluding the root's own reference: a cached resolved
     * reference already retains its own exact content, so its evidence
     * carries only the dependencies of its nested references.
     */
    VerifiedReferenceContents nestedWithin(Node root) {
        if (contents.isEmpty()) {
            return EMPTY;
        }
        Map<String, FrozenNode> selected = new LinkedHashMap<>();
        Set<Node> visited = Collections.newSetFromMap(
                new IdentityHashMap<Node, Boolean>());
        Deque<Node> pending = new ArrayDeque<>();
        pending.push(root);
        while (!pending.isEmpty()) {
            Node current = pending.pop();
            if (current == null || !visited.add(current)) {
                continue;
            }
            String blueId = current.getBlueId();
            if (blueId != null && current != root) {
                FrozenNode content = contents.get(blueId);
                if (content != null) {
                    selected.put(blueId, content);
                }
            }
            if (current.getProperties() != null) {
                for (Node child : current.getProperties().values()) {
                    pending.push(child);
                }
            }
            if (current.getItems() != null) {
                for (Node item : current.getItems()) {
                    pending.push(item);
                }
            }
            if (current.getContracts() != null) {
                pending.push(current.getContracts());
            }
        }
        return selected.isEmpty()
                ? EMPTY
                : new VerifiedReferenceContents(
                        Collections.unmodifiableMap(selected));
    }

    /**
     * Approximates the weight this registry adds to a cache entry, content
     * bodies included: a snapshot keeps them reachable even after the cache
     * evicts the entries that share them.
     */
    long approximateRetainedWeightBytes() {
        long weight = 0L;
        for (Map.Entry<String, FrozenNode> entry : contents.entrySet()) {
            weight += ENTRY_OVERHEAD_BYTES
                    + CHARACTER_BYTES * (long) entry.getKey().length()
                    + entry.getValue().approximateRetainedWeightBytes();
            if (weight < 0) {
                return Long.MAX_VALUE;
            }
        }
        return weight;
    }
}
