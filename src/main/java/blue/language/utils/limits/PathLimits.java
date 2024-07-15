package blue.language.utils.limits;

import java.util.HashSet;
import java.util.Set;
import java.util.Stack;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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

    public PathLimits(Set<String> allowedPaths, int maxDepth) {
        this.allowedPaths = allowedPaths;
        this.maxDepth = maxDepth;
        this.currentPath = new Stack<>();
    }

    @Override
    public boolean shouldProcessPathSegment(String pathSegment) {
        if (currentPath.size() >= maxDepth) {
            return false;
        }

        String potentialPath = normalizePath(getCurrentFullPath() + "/" + pathSegment);
        return isAllowedPath(potentialPath);
    }

    private boolean isAllowedPath(String path) {
        for (String allowedPath : allowedPaths) {
            if (matchesAllowedPath(allowedPath, path)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesAllowedPath(String allowedPath, String path) {
        String[] allowedParts = allowedPath.split("/");
        String[] pathParts = path.split("/");

        if (pathParts.length > allowedParts.length) {
            return false;
        }

        for (int i = 1; i < pathParts.length; i++) {
            if (!allowedParts[i].equals("*") && !allowedParts[i].equals(pathParts[i])) {
                return false;
            }
        }

        return true;
    }

    @Override
    public void enterPathSegment(String pathSegment) {
        currentPath.push(pathSegment);
    }

    @Override
    public void exitPathSegment() {
        if (!currentPath.isEmpty()) {
            currentPath.pop();
        }
    }

    private String getCurrentFullPath() {
        return "/" + String.join("/", currentPath);
    }

    private String normalizePath(String path) {
        return "/" + Stream.of(path.split("/"))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.joining("/"));
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
}