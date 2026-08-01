package blue.language.merge;

import blue.language.snapshot.FrozenNode;

import java.util.HashSet;
import java.util.Set;

/** Traversal operations for reference reachability and structural interning. */
final class ResolvedReferenceGraphIndex {

    private ResolvedReferenceGraphIndex() {
    }

    static Set<String> referencedBlueIds(FrozenNode root) {
        Set<String> references = new HashSet<>();
        collectReferenceBlueIds(root, new HashSet<>(), references);
        return references;
    }

    static Set<FrozenNode.ResolvedStructuralKey> structuralKeys(
            FrozenNode root) {
        Set<FrozenNode.ResolvedStructuralKey> keys = new HashSet<>();
        collectStructuralKeys(root, keys);
        return keys;
    }

    static void remember(
            FrozenNode root,
            FrozenNode.ResolvedStructuralInterner interner) {
        remember(root, interner, new HashSet<>());
    }

    private static void collectStructuralKeys(
            FrozenNode node,
            Set<FrozenNode.ResolvedStructuralKey> reachable) {
        if (node == null
                || !reachable.add(node.resolvedStructuralKey())) {
            return;
        }
        collectStructuralKeys(node.getType(), reachable);
        collectStructuralKeys(node.getItemType(), reachable);
        collectStructuralKeys(node.getKeyType(), reachable);
        collectStructuralKeys(node.getValueType(), reachable);
        collectStructuralKeys(node.getBlue(), reachable);
        collectStructuralKeys(node.getContracts(), reachable);
        if (node.getItems() != null) {
            for (FrozenNode item : node.getItems()) {
                collectStructuralKeys(item, reachable);
            }
        }
        if (node.getProperties() != null) {
            for (FrozenNode child : node.getProperties().values()) {
                collectStructuralKeys(child, reachable);
            }
        }
    }

    private static void collectReferenceBlueIds(
            FrozenNode node,
            Set<FrozenNode.ResolvedStructuralKey> visited,
            Set<String> references) {
        if (node == null
                || !visited.add(node.resolvedStructuralKey())) {
            return;
        }
        if (node.getReferenceBlueId() != null) {
            references.add(node.getReferenceBlueId());
        }
        collectReferenceBlueIds(node.getType(), visited, references);
        collectReferenceBlueIds(node.getItemType(), visited, references);
        collectReferenceBlueIds(node.getKeyType(), visited, references);
        collectReferenceBlueIds(node.getValueType(), visited, references);
        collectReferenceBlueIds(node.getBlue(), visited, references);
        collectReferenceBlueIds(node.getContracts(), visited, references);
        if (node.getItems() != null) {
            for (FrozenNode item : node.getItems()) {
                collectReferenceBlueIds(item, visited, references);
            }
        }
        if (node.getProperties() != null) {
            for (FrozenNode child : node.getProperties().values()) {
                collectReferenceBlueIds(child, visited, references);
            }
        }
    }

    private static void remember(
            FrozenNode node,
            FrozenNode.ResolvedStructuralInterner interner,
            Set<FrozenNode.ResolvedStructuralKey> visited) {
        if (node == null) {
            return;
        }
        FrozenNode.ResolvedStructuralKey structuralKey =
                node.resolvedStructuralKey();
        if (!visited.add(structuralKey)) {
            return;
        }
        interner.intern(structuralKey, node);
        remember(node.getType(), interner, visited);
        remember(node.getItemType(), interner, visited);
        remember(node.getKeyType(), interner, visited);
        remember(node.getValueType(), interner, visited);
        remember(node.getBlue(), interner, visited);
        remember(node.getContracts(), interner, visited);
        if (node.getItems() != null) {
            for (FrozenNode item : node.getItems()) {
                remember(item, interner, visited);
            }
        }
        if (node.getProperties() != null) {
            for (FrozenNode child : node.getProperties().values()) {
                remember(child, interner, visited);
            }
        }
    }
}
