package blue.language.processor;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Deterministic, non-authoritative explanation of a non-successful run.
 *
 * <p>Only stable data belongs here. Host stack traces, exception class names,
 * cache state, and transport details are intentionally excluded.</p>
 */
public final class ProcessorDiagnostic {

    private final ProcessorErrorCategory category;
    private final String message;
    private final Map<String, String> details;

    private ProcessorDiagnostic(ProcessorErrorCategory category,
                                String message,
                                Map<String, String> details) {
        this.category = Objects.requireNonNull(category, "category");
        this.message = message;
        this.details = Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }

    /**
     * Creates a diagnostic containing only its stable category.
     *
     * @param category non-null public failure category
     * @return immutable categorized diagnostic
     * @throws NullPointerException when {@code category} is null
     */
    public static ProcessorDiagnostic of(ProcessorErrorCategory category) {
        return builder(category).build();
    }

    /**
     * Creates a categorized diagnostic with deterministic prose.
     *
     * @param category non-null public failure category
     * @param message deterministic explanation, or {@code null}
     * @return immutable categorized diagnostic
     * @throws NullPointerException when {@code category} is null
     */
    public static ProcessorDiagnostic of(ProcessorErrorCategory category, String message) {
        return builder(category).message(message).build();
    }

    /**
     * Creates an invocation-local builder bound to a stable category.
     *
     * @param category non-null public failure category
     * @return mutable diagnostic builder
     * @throws NullPointerException when {@code category} is null
     */
    public static Builder builder(ProcessorErrorCategory category) {
        return new Builder(category);
    }

    /**
     * Returns the stable public classification of the failure.
     *
     * @return stable failure category
     */
    public ProcessorErrorCategory category() {
        return category;
    }

    /**
     * Returns deterministic human-readable failure prose.
     *
     * @return deterministic message, or {@code null}
     */
    public String message() {
        return message;
    }

    /**
     * Returns stable machine-readable diagnostic details.
     *
     * @return immutable stable detail map
     */
    public Map<String, String> details() {
        return details;
    }

    /**
     * Looks up one stable detail value.
     *
     * @param key detail key
     * @return associated detail value, or {@code null}
     */
    public String detail(String key) {
        return details.get(key);
    }

    /**
     * Mutable invocation-local builder for an immutable diagnostic.
     */
    public static final class Builder {
        private final ProcessorErrorCategory category;
        private String message;
        private final Map<String, String> details = new LinkedHashMap<>();

        private Builder(ProcessorErrorCategory category) {
            this.category = Objects.requireNonNull(category, "category");
        }

        /**
         * Sets deterministic human-readable failure prose.
         *
         * @param message explanation to retain, or {@code null}
         * @return this builder
         */
        public Builder message(String message) {
            this.message = message;
            return this;
        }

        /**
         * Adds a stable detail after converting its value to text.
         *
         * <p>A null value is ignored, allowing optional detail construction
         * without manufacturing a textual null.</p>
         *
         * @param key non-empty stable detail key
         * @param value detail value, or {@code null} to omit it
         * @return this builder
         * @throws NullPointerException when {@code key} is null
         * @throws IllegalArgumentException when {@code key} is empty
         */
        public Builder detail(String key, Object value) {
            Objects.requireNonNull(key, "key");
            if (key.isEmpty()) {
                throw new IllegalArgumentException("Diagnostic detail key must not be empty");
            }
            if (value != null) {
                details.put(key, String.valueOf(value));
            }
            return this;
        }

        /**
         * Freezes the currently accumulated diagnostic data.
         *
         * @return immutable diagnostic snapshot
         */
        public ProcessorDiagnostic build() {
            return new ProcessorDiagnostic(category, message, details);
        }
    }
}
