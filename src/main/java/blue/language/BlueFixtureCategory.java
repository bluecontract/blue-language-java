package blue.language;

import java.util.Locale;

public enum BlueFixtureCategory {
    BLUE_ID("BlueId"),
    SERIALIZATION("Serialization"),
    SCHEMA("Schema"),
    RESOLUTION("Resolution"),
    CANONICALIZATION("Canonicalization"),
    PROVIDER("Provider"),
    CIRCULAR("Circular"),
    REGISTRY("Registry"),
    DOCUMENTATION_LINT("DocumentationLint");

    private final String label;

    BlueFixtureCategory(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

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
