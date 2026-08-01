package blue.language.merge;

import blue.language.model.Node;

/** One work item in the iterative fixed-content provenance traversal. */
final class FixedContentTask {
    final Node node;
    final boolean typeRoot;

    FixedContentTask(Node node, boolean typeRoot) {
        this.node = node;
        this.typeRoot = typeRoot;
    }
}
