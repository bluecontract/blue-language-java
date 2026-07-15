package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.util.PointerUtils;
import blue.language.snapshot.ResolvedSnapshot;

import java.util.Objects;

/**
 * Mutable processor-facing document view.
 *
 * <p>For Node-backed processing this root is the supplied Selected Document.
 * For snapshot-backed processing it is the snapshot's resolved root. Canonical
 * identity remains available separately from {@link ResolvedSnapshot}.</p>
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
        return cloneNode(ImmutablePatchPlanner.readNode(root, PointerUtils.normalizePointer(path)));
    }

    void replaceWith(Node nextRoot) {
        root.replaceWith(Objects.requireNonNull(nextRoot, "nextRoot"));
    }

    void replaceWithSnapshot(ResolvedSnapshot snapshot) {
        replaceWith(Objects.requireNonNull(snapshot, "snapshot").resolvedRoot());
    }

    private Node cloneNode(Node node) {
        return node != null ? node.clone() : null;
    }
}
