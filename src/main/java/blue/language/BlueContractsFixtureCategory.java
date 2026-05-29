package blue.language;

import java.util.Locale;

public enum BlueContractsFixtureCategory {
    REGISTRY,
    CONTRACT_KEY,
    PROCESSING_DOCUMENT,
    MUST_UNDERSTAND,
    INITIALIZATION,
    PATCHING,
    DOCUMENT_UPDATE,
    EFFECTS,
    EVENTS,
    TRIGGERED_FIFO,
    EMBEDDED,
    CHECKPOINT,
    GENERALIZATION,
    TERMINATION,
    NORMALIZATION,
    GAS,
    DISPATCH_SNAPSHOT,
    POINTER;

    public static BlueContractsFixtureCategory fromLabel(String label) {
        if (label == null) {
            throw new IllegalArgumentException("Fixture category is required");
        }
        String normalized = label.trim()
                .replaceAll("([a-z])([A-Z])", "$1_$2")
                .replace('-', '_')
                .replace(' ', '_')
                .toUpperCase(Locale.ROOT);
        return BlueContractsFixtureCategory.valueOf(normalized);
    }
}
