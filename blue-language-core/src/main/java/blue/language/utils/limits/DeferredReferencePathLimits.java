package blue.language.utils.limits;

import blue.language.model.Node;
import blue.language.model.wire.JsonPointer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Defers reference expansion below selected paths while retaining ordinary
 * merge behavior at those paths.
 */
public final class DeferredReferencePathLimits implements Limits {

    private final Set<String> deferredPaths;
    private final List<String> currentPath = new ArrayList<>();
    private final List<Boolean> enteredSegments = new ArrayList<>();

    /**
     * Creates limits from canonicalized RFC 6901 paths.
     *
     * @param deferredPaths paths below which reference expansion is deferred;
     *                      {@code null} means no deferred paths
     */
    public DeferredReferencePathLimits(Collection<String> deferredPaths) {
        this.deferredPaths = new LinkedHashSet<>();
        if (deferredPaths != null) {
            for (String path : deferredPaths) {
                this.deferredPaths.add(JsonPointer.canonicalize(path));
            }
        }
    }

    @Override
    public boolean shouldExpandPathSegment(String pathSegment, Node currentNode) {
        return !isDeferred(potentialPath(pathSegment));
    }

    /** Legacy binary-API spelling delegated to the canonical method. */
    @Override
    public boolean shouldExtendPathSegment(String pathSegment, Node currentNode) {
        return shouldExpandPathSegment(pathSegment, currentNode);
    }

    @Override
    public boolean shouldMergePathSegment(String pathSegment, Node currentNode) {
        return true;
    }

    @Override
    public void enterPathSegment(String pathSegment, Node currentNode) {
        boolean entered = pathSegment != null && !pathSegment.isEmpty();
        enteredSegments.add(entered);
        if (entered) {
            currentPath.add(pathSegment);
        }
    }

    @Override
    public void exitPathSegment() {
        if (enteredSegments.isEmpty()) {
            return;
        }
        boolean entered = enteredSegments.remove(enteredSegments.size() - 1);
        if (entered && !currentPath.isEmpty()) {
            currentPath.remove(currentPath.size() - 1);
        }
    }

    private List<String> potentialPath(String segment) {
        List<String> path = new ArrayList<>(currentPath);
        if (segment != null && !segment.isEmpty()) {
            path.add(segment);
        }
        return path;
    }

    private boolean isDeferred(List<String> path) {
        String pointer = JsonPointer.toPointer(path);
        for (String deferred : deferredPaths) {
            if (pointer.equals(deferred)
                    || pointer.startsWith(deferred + "/")) {
                return true;
            }
        }
        return false;
    }
}
