package blue.language.snapshot;

import blue.language.model.Node;
import blue.language.merge.Merger.VerifiedReferenceResolution;

import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * Cache of content whose canonical identity has been verified against its BlueId.
 *
 * <p>Resolved graph nodes must never be inserted merely because they carry a
 * {@code blueId}: inherited schema and other contextual contributions can make
 * such a node differ from the standalone content addressed by that identity.</p>
 */
public final class ResolvedReferenceCache {

    private final ConcurrentMap<String, VerifiedReferenceEntry> entriesByBlueId = new ConcurrentHashMap<>();
    private final ConcurrentMap<FrozenNode.ResolvedStructuralKey, FrozenNode> resolvedGraphNodesByStructure =
            new ConcurrentHashMap<>();
    private final FrozenNode.ResolvedStructuralInterner resolvedGraphInterner =
            new FrozenNode.ResolvedStructuralInterner() {
                @Override
                public FrozenNode intern(FrozenNode.ResolvedStructuralKey structuralKey,
                                         FrozenNode node) {
                    FrozenNode existing = resolvedGraphNodesByStructure.putIfAbsent(
                            structuralKey, node);
                    return existing != null ? existing : node;
                }
            };

    public Optional<FrozenNode> getVerifiedCanonical(String blueId) {
        VerifiedReferenceEntry entry = entriesByBlueId.get(blueId);
        return Optional.ofNullable(entry != null ? entry.canonicalContent : null);
    }

    public Optional<FrozenNode> getVerifiedResolved(String blueId) {
        VerifiedReferenceEntry entry = entriesByBlueId.get(blueId);
        FrozenNode resolved = entry != null ? entry.fullyResolvedContent : null;
        if (resolved != null && resolved.isReferenceOnly()) {
            throw new IllegalStateException("Verified resolved content is reference-only for blueId: " + blueId);
        }
        return Optional.ofNullable(resolved);
    }

    public FrozenNode putVerifiedCanonical(String blueId, FrozenNode canonicalContent) {
        Objects.requireNonNull(blueId, "blueId");
        requireCanonical(blueId, canonicalContent);
        VerifiedReferenceEntry retained = entriesByBlueId.compute(blueId, (ignored, existing) -> {
            if (existing != null) {
                return existing;
            }
            return new VerifiedReferenceEntry(canonicalContent, null);
        });
        return retained.canonicalContent;
    }

    public FrozenNode getOrLoadVerifiedCanonical(String blueId,
                                                 Supplier<FrozenNode> canonicalLoader) {
        Objects.requireNonNull(blueId, "blueId");
        Objects.requireNonNull(canonicalLoader, "canonicalLoader");
        VerifiedReferenceEntry retained = entriesByBlueId.compute(blueId, (ignored, existing) -> {
            if (existing != null) {
                return existing;
            }
            FrozenNode loaded = canonicalLoader.get();
            requireCanonical(blueId, loaded);
            return new VerifiedReferenceEntry(loaded, null);
        });
        return retained.canonicalContent;
    }

    public FrozenNode putVerifiedResolved(VerifiedReferenceResolution verification) {
        Objects.requireNonNull(verification, "verification");
        String blueId = verification.requestedBlueId();
        FrozenNode canonicalContent = verification.canonicalRoot();
        FrozenNode fullyResolvedContent = verification.resolvedRoot();
        Objects.requireNonNull(blueId, "blueId");
        requireCanonical(blueId, canonicalContent);
        requireResolved(blueId, fullyResolvedContent);
        VerifiedReferenceEntry retained = entriesByBlueId.compute(blueId, (ignored, existing) -> {
            FrozenNode retainedCanonical = existing != null ? existing.canonicalContent : canonicalContent;
            FrozenNode retainedResolved = existing != null && existing.fullyResolvedContent != null
                    ? existing.fullyResolvedContent
                    : fullyResolvedContent;
            return new VerifiedReferenceEntry(retainedCanonical, retainedResolved);
        });
        return retained.fullyResolvedContent;
    }

    public FrozenNode freezeResolved(Node node) {
        return FrozenNode.fromResolvedNode(node, resolvedGraphInterner);
    }

    /**
     * Seeds structural sharing from a completed immutable graph without
     * promoting any node to verified provider content.
     */
    public void rememberResolvedGraph(FrozenNode node) {
        rememberResolvedGraph(node, new HashSet<>());
    }

    private void rememberResolvedGraph(FrozenNode node,
                                       Set<FrozenNode.ResolvedStructuralKey> visited) {
        if (node == null) {
            return;
        }
        FrozenNode.ResolvedStructuralKey structuralKey = node.resolvedStructuralKey();
        if (!visited.add(structuralKey)) {
            return;
        }
        resolvedGraphInterner.intern(structuralKey, node);
        rememberResolvedGraph(node.getType(), visited);
        rememberResolvedGraph(node.getItemType(), visited);
        rememberResolvedGraph(node.getKeyType(), visited);
        rememberResolvedGraph(node.getValueType(), visited);
        rememberResolvedGraph(node.getBlue(), visited);
        rememberResolvedGraph(node.getContracts(), visited);
        if (node.getItems() != null) {
            node.getItems().forEach(item -> rememberResolvedGraph(item, visited));
        }
        if (node.getProperties() != null) {
            node.getProperties().values().forEach(child -> rememberResolvedGraph(child, visited));
        }
    }

    public int size() {
        return entriesByBlueId.size();
    }

    public void clear() {
        entriesByBlueId.clear();
        resolvedGraphNodesByStructure.clear();
    }

    public int resolvedGraphSize() {
        return resolvedGraphNodesByStructure.size();
    }

    private void requireCanonical(String blueId, FrozenNode canonicalContent) {
        Objects.requireNonNull(canonicalContent, "canonicalContent");
        if (!canonicalContent.isStrictCanonical()) {
            throw new IllegalArgumentException("Verified canonical content must be strict canonical.");
        }
        if (!canonicalContent.isStrictBlueIdValidation()) {
            throw new IllegalArgumentException("Verified canonical content must pass strict BlueId validation.");
        }
        if (canonicalContent.isReferenceOnly()) {
            throw new IllegalArgumentException("A pure reference is not verified materialized content: " + blueId);
        }
        if (!blueId.equals(canonicalContent.blueId())) {
            throw new IllegalArgumentException("Verified canonical content hashes to "
                    + canonicalContent.blueId() + ", not cache key " + blueId + ".");
        }
    }

    private void requireResolved(String blueId, FrozenNode resolvedContent) {
        Objects.requireNonNull(resolvedContent, "fullyResolvedContent");
        if (resolvedContent.isReferenceOnly()) {
            throw new IllegalArgumentException("Verified resolved content must be materialized for blueId: " + blueId);
        }
    }

    private static final class VerifiedReferenceEntry {
        private final FrozenNode canonicalContent;
        private final FrozenNode fullyResolvedContent;

        private VerifiedReferenceEntry(FrozenNode canonicalContent, FrozenNode fullyResolvedContent) {
            if (canonicalContent == null) {
                throw new IllegalArgumentException("canonicalContent must not be null");
            }
            this.canonicalContent = canonicalContent;
            this.fullyResolvedContent = fullyResolvedContent;
        }
    }
}
