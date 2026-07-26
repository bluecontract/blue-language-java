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
        this.category = Objects.requireNonNull(category, "category").normative();
        this.message = message;
        this.details = Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }

    public static ProcessorDiagnostic of(ProcessorErrorCategory category) {
        return builder(category).build();
    }

    public static ProcessorDiagnostic of(ProcessorErrorCategory category, String message) {
        return builder(category).message(message).build();
    }

    public static Builder builder(ProcessorErrorCategory category) {
        return new Builder(category);
    }

    public ProcessorErrorCategory category() {
        return category;
    }

    public String message() {
        return message;
    }

    public Map<String, String> details() {
        return details;
    }

    public String detail(String key) {
        return details.get(key);
    }

    public static final class Builder {
        private final ProcessorErrorCategory category;
        private String message;
        private final Map<String, String> details = new LinkedHashMap<>();

        private Builder(ProcessorErrorCategory category) {
            this.category = Objects.requireNonNull(category, "category");
        }

        public Builder message(String message) {
            this.message = message;
            return this;
        }

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

        public ProcessorDiagnostic build() {
            return new ProcessorDiagnostic(category, message, details);
        }
    }
}
