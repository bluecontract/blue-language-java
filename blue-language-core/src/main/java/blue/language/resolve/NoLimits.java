package blue.language.resolve;

import blue.language.model.Node;

/** Stateless {@link ResolutionLimits} implementation that permits every operation. */
final class NoLimits implements ResolutionLimits {

    static final NoLimits INSTANCE = new NoLimits();

    private NoLimits() {
    }

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
