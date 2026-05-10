package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.util.PointerUtils;
import blue.language.snapshot.ResolvedSnapshot;

import java.util.Objects;

/**
 * Mutable processor-facing view generated from an immutable resolved snapshot.
 *
 * <p>The processor still exposes {@link Node} to existing handlers and result
 * objects, but the authoritative state is the snapshot. This adapter keeps the
 * mutable root synchronized with the latest canonical snapshot materialization,
 * while resolved reads come directly from {@link ResolvedSnapshot} indexes.</p>
 */
final class MaterializedDocumentView {

    private final Node root;

    MaterializedDocumentView(Node root) {
        this.root = Objects.requireNonNull(root, "root");
    }

    Node root() {
        return root;
    }

    Node copyRoot() {
        return root.clone();
    }

    Node nodeAt(String path) {
        return cloneNode(new PatchEngine(root).read(PointerUtils.normalizePointer(path)));
    }

    void replaceWith(Node nextRoot) {
        root.replaceWith(Objects.requireNonNull(nextRoot, "nextRoot"));
    }

    void replaceWithSnapshot(ResolvedSnapshot snapshot) {
        replaceWith(Objects.requireNonNull(snapshot, "snapshot").canonicalRoot());
    }

    private Node cloneNode(Node node) {
        return node != null ? node.clone() : null;
    }
}
