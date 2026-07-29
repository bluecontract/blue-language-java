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

    /** Policy demanding the entire graph with no reference-expansion bound. */
    public static final BlueOperationLimits UNLIMITED =
            new BlueOperationLimits(Collections.singleton(""), Integer.MAX_VALUE);

    private final Set<String> demandedPaths;
    private final int maxReferenceExpansions;

    /**
     * Creates immutable demanded-path and reference-expansion limits.
     *
     * @param demandedPaths non-empty RFC 6901 pointer collection
     * @param maxReferenceExpansions non-negative expansion bound
     * @throws IllegalArgumentException when paths or the bound are invalid
     */
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

    /**
     * Demands supplied paths with no reference-expansion bound.
     *
     * @param demandedPaths non-empty pointer collection
     * @return unlimited-expansion demand policy
     */
    public static BlueOperationLimits demandedPaths(Collection<String> demandedPaths) {
        return new BlueOperationLimits(demandedPaths, Integer.MAX_VALUE);
    }

    /**
     * Demands one path with no reference-expansion bound.
     *
     * @param demandedPath RFC 6901 pointer
     * @return unlimited-expansion demand policy
     */
    public static BlueOperationLimits demandedPath(String demandedPath) {
        return demandedPaths(Collections.singleton(demandedPath));
    }

    /**
     * Returns a copy with a new reference-expansion bound.
     *
     * @param maximum non-negative expansion bound
     * @return copied policy
     */
    public BlueOperationLimits withMaxReferenceExpansions(int maximum) {
        return new BlueOperationLimits(demandedPaths, maximum);
    }

    /** Returns demanded pointers.
     * @return immutable demanded pointer set */
    public Set<String> demandedPaths() {
        return demandedPaths;
    }

    /** Returns the expansion bound.
     * @return maximum reference expansions */
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
