package blue.language.conformance.api;

import java.util.Locale;

/**
 * Closed category vocabulary used by Blue Language 1.0 fixture manifests and
 * machine-readable reports.
 */
public enum BlueFixtureCategory {
    /** BlueId calculation. */
    BLUE_ID("BlueId"),
    /** Serialization behavior. */
    SERIALIZATION("Serialization"),
    /** Schema behavior. */
    SCHEMA("Schema"),
    /** Resolution behavior. */
    RESOLUTION("Resolution"),
    /** Type-and-overlay specialization behavior. */
    SPECIALIZATION("Specialization"),
    /** Canonicalization behavior. */
    CANONICALIZATION("Canonicalization"),
    /** Overlay minimization. */
    MINIMIZATION("Minimization"),
    /** Matching behavior. */
    MATCHING("Matching"),
    /** Provider behavior. */
    PROVIDER("Provider"),
    /** Demand-limited expansion. */
    LIMITED_EXPANSION("LimitedExpansion"),
    /** Demand-limited resolution. */
    LIMITED_RESOLUTION("LimitedResolution"),
    /** Harness meta-conformance. */
    META_CONFORMANCE("MetaConformance"),
    /** Circular-set behavior. */
    CIRCULAR("Circular"),
    /** Circular-reference behavior. */
    CIRCULAR_REFERENCES("CircularReferences"),
    /** Registry behavior. */
    REGISTRY("Registry"),
    /** Publishable documentation lint. */
    DOCUMENTATION_LINT("DocumentationLint");

    private final String label;

    BlueFixtureCategory(String label) {
        this.label = label;
    }

    /**
     * Returns the manifest-facing label.
     *
     * @return category label
     */
    public String getLabel() {
        return label;
    }

    /**
     * Resolves either the enum spelling or the manifest-facing label.
     *
     * @param value category spelling or label
     * @return resolved category
     * @throws IllegalArgumentException when the label is null or unknown
     */
    public static BlueFixtureCategory fromLabel(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Fixture category is required.");
        }
        String normalized = value.replace("-", "_").replace(" ", "_").toUpperCase(Locale.ROOT);
        for (BlueFixtureCategory category : values()) {
            if (category.name().equals(normalized) || category.label.equalsIgnoreCase(value)) {
                return category;
            }
        }
        throw new IllegalArgumentException("Unknown Blue fixture category: " + value);
    }
}
