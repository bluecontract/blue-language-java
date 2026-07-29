package blue.language;

import java.util.Locale;

/**
 * Closed category vocabulary published by the Blue Contracts 1.0 fixture
 * envelope.
 */
public enum BlueContractsFixtureCategory {
    /** Checkpoint behavior. */
    CHK,
    /** Discovery behavior. */
    DISC,
    /** End-to-end behavior. */
    E2E,
    /** Embedded-scope behavior. */
    EMB,
    /** Event behavior. */
    EVT,
    /** Required failure behavior. */
    FAIL,
    /** Feeder behavior. */
    FEED,
    /** Gas behavior. */
    GAS,
    /** Index behavior. */
    IDX,
    /** Initialization behavior. */
    INIT,
    /** Lifecycle behavior. */
    LIFE,
    /** Protected-state behavior. */
    PROT,
    /** Representation behavior. */
    REP,
    /** Sending behavior. */
    SND,
    /** Update behavior. */
    UPD;

    /**
     * Returns the manifest-facing lowercase label.
     *
     * @return category label
     */
    public String getLabel() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Resolves a manifest-facing category label.
     *
     * @param label category label
     * @return resolved category
     * @throws IllegalArgumentException when the label is null, blank, or
     *                                  unsupported
     */
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
