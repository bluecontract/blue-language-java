package blue.language.processor;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable, bounded context attached to a processing observation.
 *
 * <p>Only the closed set of {@link ProcessingObservationDimension} keys is
 * accepted. A value is limited to 64 printable identifier characters and the
 * complete context to four entries. These limits make accidental payload or
 * identity capture impossible through the typed API.</p>
 */
public final class ProcessingObservationContext {

    /** Maximum number of dimensions in one observation. */
    public static final int MAX_DIMENSIONS = 4;

    /** Maximum number of characters in one dimension value. */
    public static final int MAX_VALUE_LENGTH = 64;

    private static final ProcessingObservationContext EMPTY =
            new ProcessingObservationContext(
                    Collections.<ProcessingObservationDimension, String>emptyMap());

    private final Map<ProcessingObservationDimension, String> dimensions;

    private ProcessingObservationContext(
            Map<ProcessingObservationDimension, String> dimensions) {
        EnumMap<ProcessingObservationDimension, String> ordered =
                new EnumMap<>(ProcessingObservationDimension.class);
        ordered.putAll(dimensions);
        this.dimensions = Collections.unmodifiableMap(ordered);
    }

    /**
     * Returns the shared empty context.
     *
     * @return empty immutable context
     */
    public static ProcessingObservationContext empty() {
        return EMPTY;
    }

    /**
     * Creates a context with one typed dimension.
     *
     * @param dimension dimension key
     * @param value bounded stable category value
     * @return immutable one-entry context
     */
    public static ProcessingObservationContext of(
            ProcessingObservationDimension dimension,
            String value) {
        return builder().put(dimension, value).build();
    }

    /**
     * Creates a new bounded context builder.
     *
     * @return empty builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns a dimension value.
     *
     * @param dimension dimension key
     * @return value, or {@code null} when absent
     */
    public String value(ProcessingObservationDimension dimension) {
        return dimensions.get(Objects.requireNonNull(dimension, "dimension"));
    }

    /**
     * Returns all dimensions in enum declaration order.
     *
     * @return immutable dimension map
     */
    public Map<ProcessingObservationDimension, String> dimensions() {
        return dimensions;
    }

    /**
     * Reports whether this context contains no dimensions.
     *
     * @return {@code true} for the shared or equivalent empty context
     */
    public boolean isEmpty() {
        return dimensions.isEmpty();
    }

    /**
     * Produces a bounded, deterministic representation suitable for JFR.
     *
     * @return comma-separated {@code key=value} representation
     */
    public String compactString() {
        StringBuilder result = new StringBuilder();
        for (Map.Entry<ProcessingObservationDimension, String> entry
                : dimensions.entrySet()) {
            if (result.length() > 0) {
                result.append(',');
            }
            result.append(entry.getKey().externalName())
                    .append('=')
                    .append(entry.getValue());
        }
        return result.toString();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ProcessingObservationContext)) {
            return false;
        }
        ProcessingObservationContext that = (ProcessingObservationContext) other;
        return dimensions.equals(that.dimensions);
    }

    @Override
    public int hashCode() {
        return dimensions.hashCode();
    }

    @Override
    public String toString() {
        return compactString();
    }

    /** Builds a context while enforcing its cardinality and value bounds. */
    public static final class Builder {

        private final Map<ProcessingObservationDimension, String> dimensions =
                new LinkedHashMap<>();

        private Builder() {
        }

        /**
         * Adds one typed dimension.
         *
         * @param dimension dimension key
         * @param value stable category value
         * @return this builder
         */
        public Builder put(ProcessingObservationDimension dimension, String value) {
            Objects.requireNonNull(dimension, "dimension");
            validateValue(value);
            if (!dimensions.containsKey(dimension)
                    && dimensions.size() == MAX_DIMENSIONS) {
                throw new IllegalArgumentException(
                        "processing observation context exceeds "
                                + MAX_DIMENSIONS + " dimensions");
            }
            dimensions.put(dimension, value);
            return this;
        }

        /**
         * Creates the immutable context.
         *
         * @return immutable context, or the shared empty instance
         */
        public ProcessingObservationContext build() {
            if (dimensions.isEmpty()) {
                return EMPTY;
            }
            return new ProcessingObservationContext(dimensions);
        }

        private static void validateValue(String value) {
            if (value == null || value.isEmpty()) {
                throw new IllegalArgumentException("dimension value must not be empty");
            }
            if (value.length() > MAX_VALUE_LENGTH) {
                throw new IllegalArgumentException(
                        "dimension value exceeds " + MAX_VALUE_LENGTH + " characters");
            }
            for (int index = 0; index < value.length(); index++) {
                char character = value.charAt(index);
                boolean valid = character >= 'a' && character <= 'z'
                        || character >= 'A' && character <= 'Z'
                        || character >= '0' && character <= '9'
                        || character == '_'
                        || character == '-'
                        || character == '.'
                        || character == ':';
                if (!valid) {
                    throw new IllegalArgumentException(
                            "dimension value contains unsupported character at index " + index);
                }
            }
        }
    }
}
