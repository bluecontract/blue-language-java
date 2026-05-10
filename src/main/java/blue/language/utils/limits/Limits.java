package blue.language.utils.limits;

import blue.language.model.Node;

import java.util.List;

public interface Limits {

    Limits NO_LIMITS = new NoLimits();

    boolean shouldExtendPathSegment(String pathSegment, Node currentNode);

    boolean shouldMergePathSegment(String pathSegment, Node currentNode);

    default boolean shouldReconstructList(Node currentNode, List<Node> items) {
        return true;
    }

    default void enterPathSegment(String pathSegment) {
        enterPathSegment(pathSegment, null);
    }

    void enterPathSegment(String pathSegment, Node currentNode);
    void exitPathSegment();
}
