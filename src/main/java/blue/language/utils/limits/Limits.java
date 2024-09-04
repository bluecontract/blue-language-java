package blue.language.utils.limits;

import blue.language.model.Node;

public interface Limits {

    Limits NO_LIMITS = new NoLimits();

    boolean shouldExtendPathSegment(String pathSegment, Node currentNode);

    boolean shouldMergePathSegment(String pathSegment, Node currentNode);

    default void enterPathSegment(String pathSegment) {
        enterPathSegment(pathSegment, null);
    }

    void enterPathSegment(String pathSegment, Node currentNode);
    void exitPathSegment();
}