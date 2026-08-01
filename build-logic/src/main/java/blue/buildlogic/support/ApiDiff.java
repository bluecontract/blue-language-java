package blue.buildlogic.support;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Stable set comparison for line-oriented public API baselines. */
public final class ApiDiff {

    private final List<String> added;
    private final List<String> removed;
    private final List<String> unchanged;

    private ApiDiff(List<String> added, List<String> removed, List<String> unchanged) {
        this.added = immutableCopy(added);
        this.removed = immutableCopy(removed);
        this.unchanged = immutableCopy(unchanged);
    }

    public static ApiDiff compare(Collection<String> baseline, Collection<String> current) {
        Set<String> baselineLines = normalizedLines(baseline);
        Set<String> currentLines = normalizedLines(current);

        List<String> added = new ArrayList<>(currentLines);
        added.removeAll(baselineLines);
        List<String> removed = new ArrayList<>(baselineLines);
        removed.removeAll(currentLines);
        List<String> unchanged = new ArrayList<>(baselineLines);
        unchanged.retainAll(currentLines);
        return new ApiDiff(added, removed, unchanged);
    }

    public List<String> getAdded() {
        return added;
    }

    public List<String> getRemoved() {
        return removed;
    }

    public List<String> getUnchanged() {
        return unchanged;
    }

    public String toJson() {
        java.util.Map<String, Object> report = new java.util.TreeMap<>();
        report.put("added", added);
        report.put("addedCount", added.size());
        report.put("removed", removed);
        report.put("removedCount", removed.size());
        report.put("schema", "blue-api-baseline-diff/1.0");
        report.put("unchangedCount", unchanged.size());
        return DeterministicJson.write(report);
    }

    private static Set<String> normalizedLines(Collection<String> values) {
        Set<String> result = new TreeSet<>();
        for (String value : values) {
            String normalized = value.trim();
            if (!normalized.isEmpty() && !normalized.startsWith("#")) {
                result.add(normalized);
            }
        }
        return result;
    }

    private static List<String> immutableCopy(List<String> values) {
        return Collections.unmodifiableList(new ArrayList<>(values));
    }
}
