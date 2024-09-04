package blue.language.utils.limits;

import blue.language.model.Node;

import java.util.Arrays;
import java.util.List;

public class CompositeLimits implements blue.language.utils.limits.Limits {
    private List<blue.language.utils.limits.Limits> limitsList;

    public CompositeLimits(blue.language.utils.limits.Limits... limits) {
        this.limitsList = Arrays.asList(limits);
    }

    @Override
    public boolean shouldExtendPathSegment(String pathSegment, Node currentNode) {
        return limitsList.stream().allMatch(l -> l.shouldExtendPathSegment(pathSegment, currentNode));
    }

    @Override
    public boolean shouldMergePathSegment(String pathSegment, Node currentNode) {
        return limitsList.stream().allMatch(l -> l.shouldMergePathSegment(pathSegment, currentNode));
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