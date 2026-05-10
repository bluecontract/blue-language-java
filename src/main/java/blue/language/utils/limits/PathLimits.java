package blue.language.utils.limits;

import blue.language.model.Node;
import blue.language.utils.JsonPointer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Stack;
import java.util.stream.Collectors;

/**
 * Supported features:
 * 1. Exact path matching (e.g., "/a/b/c")
 * 2. Single-level wildcards (e.g., "/a/{wildcard}/c")
 * 3. Maximum depth limitation
 */
public class PathLimits implements Limits {
    private final Set<String> allowedPaths;
    private final int maxDepth;
    private final Stack<String> currentPath;
    private final Stack<Boolean> enteredPathSegment;

    public PathLimits(Set<String> allowedPaths, int maxDepth) {
        this.allowedPaths = allowedPaths.stream()
                .map(PathLimits::canonicalAllowedPath)
                .collect(Collectors.toSet());
        this.maxDepth = maxDepth;
        this.currentPath = new Stack<>();
        this.enteredPathSegment = new Stack<>();
    }

    @Override
    public boolean shouldExtendPathSegment(String pathSegment, Node node) {
        if (currentPath.size() >= maxDepth) {
            return false;
        }

        List<String> potentialPath = new ArrayList<>(currentPath);
        if (pathSegment != null && !pathSegment.isEmpty()) {
            potentialPath.add(pathSegment);
        }
        return isAllowedPath(potentialPath);
    }

    @Override
    public boolean shouldMergePathSegment(String pathSegment, Node currentNode) {
        return shouldExtendPathSegment(pathSegment, currentNode);
    }

    private boolean isAllowedPath(List<String> path) {
        for (String allowedPath : allowedPaths) {
            if (matchesAllowedPath(allowedPath, path)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesAllowedPath(String allowedPath, List<String> path) {
        if ("*".equals(allowedPath)) {
            return true;
        }
        List<String> allowedParts = JsonPointer.split(allowedPath);
        if (path.size() > allowedParts.size()) {
            return false;
        }
        for (int i = 0; i < path.size(); i++) {
            String allowedPart = allowedParts.get(i);
            if (!allowedPart.equals("*") && !allowedPart.equals(path.get(i))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void enterPathSegment(String pathSegment, Node noe) {
        boolean realSegment = pathSegment != null && !pathSegment.isEmpty();
        enteredPathSegment.push(realSegment);
        if (realSegment) {
            currentPath.push(pathSegment);
        }
    }

    @Override
    public void exitPathSegment() {
        if (enteredPathSegment.isEmpty()) {
            return;
        }
        if (enteredPathSegment.pop() && !currentPath.isEmpty()) {
            currentPath.pop();
        }
    }

    private static String canonicalAllowedPath(String path) {
        if ("*".equals(path)) {
            return path;
        }
        return JsonPointer.canonicalize(path);
    }

    public static class Builder {
        private Set<String> allowedPaths = new HashSet<>();
        private int maxDepth = Integer.MAX_VALUE;

        public Builder addPath(String path) {
            allowedPaths.add(path);
            return this;
        }

        public Builder setMaxDepth(int maxDepth) {
            this.maxDepth = maxDepth;
            return this;
        }

        public PathLimits build() {
            return new PathLimits(allowedPaths, maxDepth);
        }
    }

    public static PathLimits withMaxDepth(int maxDepth) {
        return new PathLimits.Builder().setMaxDepth(maxDepth).addPath("*").build();
    }

    public static PathLimits withSinglePath(String path) {
        return new PathLimits.Builder().addPath(path).build();
    }

    public static PathLimits fromNode(Node node) {
        return NodeToPathLimitsConverter.convert(node);
    }
}
