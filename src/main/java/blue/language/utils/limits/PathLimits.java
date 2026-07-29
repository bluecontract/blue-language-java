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
 * Stateful traversal limits based on allowed RFC 6901 path prefixes and a
 * maximum depth.
 *
 * <p>An allowed path may contain {@code *} as a single-segment wildcard; a
 * lone {@code *} allows every path. A candidate remains eligible while it is
 * a prefix of at least one allowed path.</p>
 */
public class PathLimits implements Limits {
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

    /** Mutable builder for {@link PathLimits}. */
    public static class Builder {
        private Set<String> allowedPaths = new HashSet<>();
        private int maxDepth = Integer.MAX_VALUE;

        /**
         * Creates an empty path-limits builder.
         */
        public Builder() {
        }

        /**
         * Adds one exact or wildcard allowed path.
         *
         * @param path allowed path
         * @return this builder
         */
        public Builder addPath(String path) {
            allowedPaths.add(path);
            return this;
        }

        /**
         * Sets the maximum number of entered path segments.
         *
         * @param maxDepth maximum traversal depth
         * @return this builder
         */
        public Builder setMaxDepth(int maxDepth) {
            this.maxDepth = maxDepth;
            return this;
        }

        /**
         * Creates an independent limits instance from current builder state.
         *
         * @return new path limits
         */
        public PathLimits build() {
            return new PathLimits(allowedPaths, maxDepth);
        }
    }

    /**
     * Allows every path up to a maximum depth.
     *
     * @param maxDepth maximum traversal depth
     * @return path limits allowing every path within the depth
     */
    public static PathLimits withMaxDepth(int maxDepth) {
        return new PathLimits.Builder().setMaxDepth(maxDepth).addPath("*").build();
    }

    /**
     * Allows one path and each of its prefixes.
     *
     * @param path exact or wildcard path to allow
     * @return path limits for the supplied path
     */
    public static PathLimits withSinglePath(String path) {
        return new PathLimits.Builder().addPath(path).build();
    }

    /**
     * Derives allowed terminal paths from a node graph.
     *
     * @param node graph root to inspect
     * @return path limits corresponding to terminal graph nodes
     */
    public static PathLimits fromNode(Node node) {
        return NodeToPathLimitsConverter.convert(node);
    }
}
