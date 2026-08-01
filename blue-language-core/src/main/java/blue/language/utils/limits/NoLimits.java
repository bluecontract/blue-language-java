package blue.language.utils.limits;

import blue.language.model.Node;

/** Stateless {@link Limits} implementation that permits every operation. */
class NoLimits implements Limits {

    @Override
    public boolean shouldExpandPathSegment(String pathSegment, Node currentNode) {
        return true;
    }

    /** Legacy binary-API spelling delegated to the canonical method. */
    @Override
    public boolean shouldExtendPathSegment(String pathSegment, Node currentNode) {
        return shouldExpandPathSegment(pathSegment, currentNode);
    }

    @Override
    public boolean shouldMergePathSegment(String pathSegment, Node currentNode) {
        return true;
    }

    @Override
    public void enterPathSegment(String pathSegment, Node node) {
    }

    @Override
    public void exitPathSegment() {
    }
}
