package blue.language.merge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Immutable structural path used while classifying authored labels. */
final class LabelPath {

    private final List<String> segments;

    LabelPath(List<String> segments) {
        this.segments = Collections.unmodifiableList(
                new ArrayList<>(segments));
    }

    static LabelPath root() {
        return new LabelPath(Collections.<String>emptyList());
    }

    LabelPath child(String segment) {
        List<String> childSegments = new ArrayList<>(segments);
        childSegments.add(segment);
        return new LabelPath(childSegments);
    }

    boolean isRoot() {
        return segments.isEmpty();
    }

    boolean isAtOrBelow(LabelPath ancestor) {
        if (segments.size() < ancestor.segments.size()) {
            return false;
        }
        for (int index = 0; index < ancestor.segments.size(); index++) {
            if (!Objects.equals(
                    segments.get(index), ancestor.segments.get(index))) {
                return false;
            }
        }
        return true;
    }

    List<String> segments() {
        return segments;
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || other instanceof LabelPath
                && segments.equals(((LabelPath) other).segments);
    }

    @Override
    public int hashCode() {
        return segments.hashCode();
    }
}
