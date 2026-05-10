package blue.language.snapshot;

import blue.language.model.Node;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class ResolvedReferenceCache implements FrozenNode.ResolvedReferenceInterner {

    private final ConcurrentMap<String, FrozenNode> resolvedReferencesByBlueId = new ConcurrentHashMap<>();

    public Optional<FrozenNode> get(String blueId) {
        return Optional.ofNullable(resolvedReferencesByBlueId.get(blueId));
    }

    public Node mutableCopy(String blueId) {
        FrozenNode node = resolvedReferencesByBlueId.get(blueId);
        return node != null ? node.toNode() : null;
    }

    public FrozenNode freezeResolved(Node node) {
        return FrozenNode.fromResolvedNode(node, this);
    }

    public FrozenNode putIfAbsent(String blueId, FrozenNode node) {
        FrozenNode existing = resolvedReferencesByBlueId.putIfAbsent(blueId, node);
        return existing != null ? existing : node;
    }

    public void indexResolved(FrozenNode node) {
        if (node == null) {
            return;
        }
        if (node.getReferenceBlueId() != null && !node.isReferenceOnly()) {
            putIfAbsent(node.getReferenceBlueId(), node);
        }
        indexResolved(node.getType());
        indexResolved(node.getItemType());
        indexResolved(node.getKeyType());
        indexResolved(node.getValueType());
        indexResolved(node.getBlue());
        if (node.getItems() != null) {
            node.getItems().forEach(this::indexResolved);
        }
        if (node.getProperties() != null) {
            node.getProperties().values().forEach(this::indexResolved);
        }
    }

    public int size() {
        return resolvedReferencesByBlueId.size();
    }

    public void clear() {
        resolvedReferencesByBlueId.clear();
    }

    @Override
    public FrozenNode lookup(String blueId) {
        return resolvedReferencesByBlueId.get(blueId);
    }

    @Override
    public FrozenNode intern(String blueId, FrozenNode node) {
        return putIfAbsent(blueId, node);
    }
}
