package blue.language.utils.limits;

import blue.language.model.Node;

import java.util.Arrays;
import java.util.List;

/**
 * Logical intersection of multiple stateful traversal limits.
 *
 * <p>A segment or list is allowed only when every member allows it. Enter and
 * exit notifications are forwarded in declaration order, so this composite
 * must be balanced exactly like an individual limit.</p>
 */
public class CompositeLimits implements blue.language.utils.limits.Limits {
    private List<blue.language.utils.limits.Limits> limitsList;

    /**
     * Creates an intersection over supplied limits.
     *
     * @param limits policies consulted in order
     */
    public CompositeLimits(blue.language.utils.limits.Limits... limits) {
        this.limitsList = Arrays.asList(limits);
    }

    @Override
    public boolean shouldExpandPathSegment(String pathSegment, Node currentNode) {
        return limitsList.stream().allMatch(
                limit -> limit.shouldExpandPathSegment(pathSegment, currentNode));
    }

    /** Legacy binary-API spelling delegated to the canonical method. */
    @Override
    public boolean shouldExtendPathSegment(String pathSegment, Node currentNode) {
        return shouldExpandPathSegment(pathSegment, currentNode);
    }

    @Override
    public boolean shouldMergePathSegment(String pathSegment, Node currentNode) {
        return limitsList.stream().allMatch(l -> l.shouldMergePathSegment(pathSegment, currentNode));
    }

    @Override
    public boolean shouldReconstructList(Node currentNode, List<Node> items) {
        return limitsList.stream().allMatch(l -> l.shouldReconstructList(currentNode, items));
    }

    @Override
    public void enterPathSegment(String pathSegment, Node node) {
        limitsList.forEach(l -> l.enterPathSegment(pathSegment, node));
    }

    @Override
    public void exitPathSegment() {
        limitsList.forEach(Limits::exitPathSegment);
    }
}
