package blue.language.resolve;

import blue.language.model.Node;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Logical intersection of multiple stateful traversal limits.
 *
 * <p>A segment or list is allowed only when every member allows it. Enter and
 * exit notifications are forwarded in declaration order, so this composite
 * must be balanced exactly like an individual limit.</p>
 */
final class CompositeLimits implements ResolutionLimits {
    private final List<ResolutionLimits> limitsList;

    /**
     * Creates an intersection over supplied limits.
     *
     * @param limits policies consulted in order
     */
    CompositeLimits(ResolutionLimits... limits) {
        this.limitsList = Collections.unmodifiableList(
                Arrays.asList(limits.clone()));
    }

    @Override
    public boolean shouldExpandPathSegment(String pathSegment, Node currentNode) {
        return limitsList.stream().allMatch(
                limit -> limit.shouldExpandPathSegment(pathSegment, currentNode));
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
    public boolean retainsEveryAuthoredPath() {
        return limitsList.stream().allMatch(
                ResolutionLimits::retainsEveryAuthoredPath);
    }

    @Override
    public void enterPathSegment(String pathSegment, Node node) {
        limitsList.forEach(l -> l.enterPathSegment(pathSegment, node));
    }

    @Override
    public void exitPathSegment() {
        limitsList.forEach(ResolutionLimits::exitPathSegment);
    }
}
