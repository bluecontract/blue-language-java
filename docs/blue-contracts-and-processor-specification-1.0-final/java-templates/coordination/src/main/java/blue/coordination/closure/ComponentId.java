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
        return value.compareTo(other.value);
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
