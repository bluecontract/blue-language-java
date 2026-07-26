package blue.language;

import blue.language.utils.JsonPointer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Independent semantic-demand limits for expansion and resolution.
 */
public final class BlueOperationLimits {

    public static final BlueOperationLimits UNLIMITED =
            new BlueOperationLimits(Collections.singleton(""), Integer.MAX_VALUE);

    private final Set<String> demandedPaths;
    private final int maxReferenceExpansions;

    public BlueOperationLimits(Collection<String> demandedPaths, int maxReferenceExpansions) {
        if (demandedPaths == null || demandedPaths.isEmpty()) {
            throw new IllegalArgumentException("At least one demanded path is required.");
        }
        if (maxReferenceExpansions < 0) {
            throw new IllegalArgumentException("maxReferenceExpansions must be non-negative.");
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String path : demandedPaths) {
            if (path == null) {
                throw new IllegalArgumentException("Demanded paths must not contain null.");
            }
            JsonPointer.split(path);
            normalized.add(path);
        }
        this.demandedPaths = Collections.unmodifiableSet(normalized);
        this.maxReferenceExpansions = maxReferenceExpansions;
    }

    public static BlueOperationLimits demandedPaths(Collection<String> demandedPaths) {
        return new BlueOperationLimits(demandedPaths, Integer.MAX_VALUE);
    }

    public static BlueOperationLimits demandedPath(String demandedPath) {
        return demandedPaths(Collections.singleton(demandedPath));
    }

    public BlueOperationLimits withMaxReferenceExpansions(int maximum) {
        return new BlueOperationLimits(demandedPaths, maximum);
    }

    public Set<String> demandedPaths() {
        return demandedPaths;
    }

    public int maxReferenceExpansions() {
        return maxReferenceExpansions;
    }

    List<List<String>> demandedSegments() {
        List<List<String>> result = new ArrayList<>(demandedPaths.size());
        for (String path : demandedPaths) {
            result.add(Collections.unmodifiableList(JsonPointer.split(path)));
        }
        return Collections.unmodifiableList(result);
    }
}
