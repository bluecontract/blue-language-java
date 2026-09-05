package blue.language.testing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/** Exact IDs or terminal prefix*, applied before scheduling any work. Test sources only. */
public final class CaseSelection {
    private CaseSelection() {}

    public static <T> List<T> select(List<T> inventory, String selector, Function<T, String> id) {
        Set<String> ids = new LinkedHashSet<>();
        for (T entry : inventory) {
            String key = id.apply(entry);
            if (key == null || key.isEmpty() || !ids.add(key)) {
                throw new IllegalArgumentException("Empty or duplicate case ID: " + key);
            }
        }
        if (ids.isEmpty()) throw new IllegalArgumentException("Empty case inventory");
        if (selector == null) return Collections.unmodifiableList(new ArrayList<>(inventory));
        Set<String> selected = new LinkedHashSet<>();
        for (String raw : selector.split(",", -1)) {
            String token = raw.trim();
            if (token.isEmpty()) throw new IllegalArgumentException("Empty case selector");
            int star = token.indexOf('*');
            if (star >= 0 && star != token.length() - 1) {
                throw new IllegalArgumentException("Only a terminal prefix wildcard is supported: " + token);
            }
            boolean matched = false;
            for (String candidate : ids) {
                if (star < 0 ? candidate.equals(token) : candidate.startsWith(token.substring(0, star))) {
                    selected.add(candidate);
                    matched = true;
                }
            }
            if (!matched) throw new IllegalArgumentException("Selector matched zero cases: " + token);
        }
        List<T> result = new ArrayList<>();
        for (T entry : inventory) if (selected.contains(id.apply(entry))) result.add(entry);
        return Collections.unmodifiableList(result);
    }
}
