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

    @Override
    public boolean shouldMergePathSegment(String pathSegment, Node currentNode) {
        return true;
    }

    @Override
    public boolean retainsEveryAuthoredPath() {
        return true;
    }

    @Override
    public void enterPathSegment(String pathSegment, Node node) {
    }

    @Override
    public void exitPathSegment() {
    }
}
