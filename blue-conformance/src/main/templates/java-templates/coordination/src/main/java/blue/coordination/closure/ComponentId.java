package blue.coordination.closure;

import java.util.Objects;

/** Stable host identity of one current graph component generation. */
public final class ComponentId implements Comparable<ComponentId> {
    private final String value;

    public ComponentId(String value) {
        this.value = Objects.requireNonNull(value, "value");
        if (value.isEmpty()) {
            throw new IllegalArgumentException("ComponentId must not be empty");
        }
    }

    public String value() {
        return value;
    }

    @Override
    public int compareTo(ComponentId other) {
        return compareUnicodeScalars(value, other.value);
    }

    private static int compareUnicodeScalars(String left, String right) {
        int leftIndex = 0;
        int rightIndex = 0;
        while (leftIndex < left.length() && rightIndex < right.length()) {
            int leftCodePoint = scalarAt(left, leftIndex);
            int rightCodePoint = scalarAt(right, rightIndex);
            if (leftCodePoint != rightCodePoint) {
                return Integer.compare(leftCodePoint, rightCodePoint);
            }
            leftIndex += Character.charCount(leftCodePoint);
            rightIndex += Character.charCount(rightCodePoint);
        }
        if (leftIndex < left.length()) return 1;
        if (rightIndex < right.length()) return -1;
        return 0;
    }

    private static int scalarAt(String text, int index) {
        char first = text.charAt(index);
        if (Character.isHighSurrogate(first)) {
            if (index + 1 >= text.length()
                    || !Character.isLowSurrogate(text.charAt(index + 1))) {
                throw new IllegalArgumentException("unpaired Unicode surrogate");
            }
            return Character.toCodePoint(first, text.charAt(index + 1));
        }
        if (Character.isLowSurrogate(first)) {
            throw new IllegalArgumentException("unpaired Unicode surrogate");
        }
        return first;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ComponentId && value.equals(((ComponentId) other).value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value;
    }
}
