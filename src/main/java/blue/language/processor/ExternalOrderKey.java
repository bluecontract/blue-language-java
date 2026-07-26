package blue.language.processor;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable canonical external-order tuple supplied as verified environment
 * evidence.
 */
public final class ExternalOrderKey implements Comparable<ExternalOrderKey> {

    private final List<Component> components;

    private ExternalOrderKey(List<Component> components) {
        this.components = Collections.unmodifiableList(new ArrayList<>(components));
    }

    public static ExternalOrderKey of(List<?> values) {
        Objects.requireNonNull(values, "values");
        List<Component> components = new ArrayList<>();
        for (Object value : values) {
            components.add(Component.of(value));
        }
        return new ExternalOrderKey(components);
    }

    public List<Object> components() {
        List<Object> result = new ArrayList<>(components.size());
        for (Component component : components) {
            result.add(component.value());
        }
        return Collections.unmodifiableList(result);
    }

    @Override
    public int compareTo(ExternalOrderKey other) {
        Objects.requireNonNull(other, "other");
        int shared = Math.min(components.size(), other.components.size());
        for (int i = 0; i < shared; i++) {
            int comparison = components.get(i).compareTo(other.components.get(i));
            if (comparison != 0) {
                return comparison;
            }
        }
        return Integer.compare(components.size(), other.components.size());
    }

    @Override
    public boolean equals(Object other) {
        return this == other
                || (other instanceof ExternalOrderKey
                && components.equals(((ExternalOrderKey) other).components));
    }

    @Override
    public int hashCode() {
        return components.hashCode();
    }

    @Override
    public String toString() {
        return components().toString();
    }

    public static int compareTextCodePoints(String left, String right) {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        return Component.compareCodePoints(left, right);
    }

    private static final class Component implements Comparable<Component> {
        private final BigInteger integer;
        private final String text;

        private Component(BigInteger integer, String text) {
            this.integer = integer;
            this.text = text;
        }

        static Component of(Object value) {
            if (value instanceof BigInteger) {
                return new Component((BigInteger) value, null);
            }
            if (value instanceof Byte || value instanceof Short
                    || value instanceof Integer || value instanceof Long) {
                return new Component(BigInteger.valueOf(((Number) value).longValue()), null);
            }
            if (value instanceof String) {
                return new Component(null, (String) value);
            }
            throw new IllegalArgumentException(
                    "External order components must be Integer or Text");
        }

        Object value() {
            return integer != null ? integer : text;
        }

        @Override
        public int compareTo(Component other) {
            if (integer != null && other.integer != null) {
                return integer.compareTo(other.integer);
            }
            if (text != null && other.text != null) {
                return compareCodePoints(text, other.text);
            }
            return integer != null ? -1 : 1;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Component)) {
                return false;
            }
            Component component = (Component) other;
            return Objects.equals(integer, component.integer)
                    && Objects.equals(text, component.text);
        }

        @Override
        public int hashCode() {
            return Objects.hash(integer, text);
        }

        private static int compareCodePoints(String left, String right) {
            int leftIndex = 0;
            int rightIndex = 0;
            while (leftIndex < left.length() && rightIndex < right.length()) {
                int leftPoint = left.codePointAt(leftIndex);
                int rightPoint = right.codePointAt(rightIndex);
                if (leftPoint != rightPoint) {
                    return Integer.compare(leftPoint, rightPoint);
                }
                leftIndex += Character.charCount(leftPoint);
                rightIndex += Character.charCount(rightPoint);
            }
            return Integer.compare(left.length() - leftIndex, right.length() - rightIndex);
        }
    }
}
