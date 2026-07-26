package blue.language;

import java.util.Locale;

/**
 * Closed category vocabulary published by the Blue Contracts 1.0 fixture
 * envelope.
 */
public enum BlueContractsFixtureCategory {
    CHK,
    DISC,
    E2E,
    EMB,
    EVT,
    FAIL,
    FEED,
    GAS,
    IDX,
    INIT,
    LIFE,
    PROT,
    REP,
    SND,
    UPD;

    public String getLabel() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static BlueContractsFixtureCategory fromLabel(String label) {
        if (label == null || label.trim().isEmpty()) {
            throw new IllegalArgumentException("Fixture category is required");
        }
        String normalized = label.trim()
                .replace('-', '_')
                .replace(' ', '_')
                .toUpperCase(Locale.ROOT);
        try {
            return BlueContractsFixtureCategory.valueOf(normalized);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "Unsupported Blue Contracts 1.0 fixture category: " + label, ex);
        }
    }
}
