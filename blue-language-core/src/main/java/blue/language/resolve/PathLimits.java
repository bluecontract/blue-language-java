package blue.language.resolve;

import blue.language.model.Node;
import blue.language.model.wire.JsonPointer;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.Stack;
import java.util.stream.Collectors;

/**
 * Stateful traversal limits based on allowed RFC 6901 path prefixes and a
 * maximum depth.
 *
 * <p>An allowed path may contain {@code *} as a single-segment wildcard; a
 * lone {@code *} allows every path. A candidate remains eligible while it is
 * a prefix of at least one allowed path.</p>
 */
final class PathLimits implements ResolutionLimits {
    private final Set<String> allowedPaths;
    private final int maxDepth;
    private final Stack<String> currentPath;
    private final Stack<Boolean> enteredPathSegment;

    /**
     * Creates limits from the supplied allowed paths and maximum depth.
     *
     * @param allowedPaths exact or wildcard paths that may be traversed
     * @param maxDepth maximum number of entered path segments
     */
    PathLimits(Set<String> allowedPaths, int maxDepth) {
        this.allowedPaths = allowedPaths.stream()
                .map(PathLimits::canonicalAllowedPath)
                .collect(Collectors.toSet());
        this.maxDepth = maxDepth;
        this.currentPath = new Stack<>();
        this.enteredPathSegment = new Stack<>();
    }

    @Override
    public boolean shouldExpandPathSegment(String pathSegment, Node node) {
        if (currentPath.size() >= maxDepth) {
            return false;
        }

        List<String> potentialPath = new ArrayList<>(currentPath);
        if (pathSegment != null && !pathSegment.isEmpty()) {
            potentialPath.add(pathSegment);
        }
        return isAllowedPath(potentialPath);
    }

    /** Legacy binary-API spelling delegated to the canonical method. */
    @Override
    public boolean shouldExtendPathSegment(String pathSegment, Node node) {
        return shouldExpandPathSegment(pathSegment, node);
    }

    @Override
    public boolean shouldMergePathSegment(String pathSegment, Node currentNode) {
        return shouldExpandPathSegment(pathSegment, currentNode);
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

}
