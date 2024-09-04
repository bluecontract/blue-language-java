package blue.language.utils.limits;

import blue.language.model.Node;

class NoLimits implements Limits {

    @Override
    public boolean shouldExtendPathSegment(String pathSegment, Node currentNode) {
        return true;
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