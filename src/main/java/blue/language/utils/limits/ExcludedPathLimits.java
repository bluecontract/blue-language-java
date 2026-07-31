package blue.language.utils.limits;

import blue.language.model.Node;
import blue.language.utils.JsonPointer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Stack;
import java.util.stream.Collectors;

/**
 * Prevents merge/expansion work at specific JSON Pointer paths.
 *
 * <p>This is intentionally contract-agnostic. Callers decide which authored
 * subtrees need to be preserved for later runtime processing; the language
 * resolver only skips those paths.</p>
 */
public class ExcludedPathLimits implements Limits {
    private final Set<String> excludedPaths;
    private final Stack<String> currentPath = new Stack<>();
    private final Stack<Boolean> enteredPathSegment = new Stack<>();

    /**
     * Creates limits from canonicalized RFC 6901 paths; null means no exclusions.
     *
     * @param excludedPaths paths to exclude, or {@code null}
     */
    public ExcludedPathLimits(Collection<String> excludedPaths) {
        this.excludedPaths = excludedPaths == null
                ? new HashSet<>()
                : excludedPaths.stream()
                    .map(JsonPointer::canonicalize)
                    .collect(Collectors.toSet());
    }

    /**
     * Factory equivalent to {@link #ExcludedPathLimits(Collection)}.
     *
     * @param excludedPaths paths to exclude, or {@code null}
     * @return new stateful limits instance
     */
    public static ExcludedPathLimits excluding(Collection<String> excludedPaths) {
        return new ExcludedPathLimits(excludedPaths);
    }

    @Override
    public boolean shouldExpandPathSegment(String pathSegment, Node currentNode) {
        return !isExcluded(potentialPath(pathSegment));
    }

    /** Legacy binary-API spelling delegated to the canonical method. */
    @Override
    public boolean shouldExtendPathSegment(String pathSegment, Node currentNode) {
        return shouldExpandPathSegment(pathSegment, currentNode);
    }

    @Override
    public boolean shouldMergePathSegment(String pathSegment, Node currentNode) {
        return !isExcluded(potentialPath(pathSegment));
    }

    @Override
    public void enterPathSegment(String pathSegment, Node currentNode) {
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

    private List<String> potentialPath(String pathSegment) {
        List<String> potentialPath = new ArrayList<>(currentPath);
        if (pathSegment != null && !pathSegment.isEmpty()) {
            potentialPath.add(pathSegment);
        }
        return potentialPath;
    }

    private boolean isExcluded(List<String> path) {
        String pointer = JsonPointer.toPointer(path);
        for (String excludedPath : excludedPaths) {
            if (pointer.equals(excludedPath) || isDescendantOf(pointer, excludedPath)) {
                return true;
            }
        }
        return false;
    }

    private boolean isDescendantOf(String pointer, String ancestor) {
        if ("/".equals(ancestor)) {
            return true;
        }
        return pointer.startsWith(ancestor + "/");
    }
}
