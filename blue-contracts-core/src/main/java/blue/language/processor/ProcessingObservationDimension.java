package blue.language.processor;

/**
 * Closed vocabulary of low-cardinality processing-observation dimensions.
 *
 * <p>Document paths, BlueIds, payload values, and other caller-controlled data
 * are deliberately absent. The closed vocabulary prevents telemetry from
 * becoming an unbounded copy of processed documents.</p>
 */
public enum ProcessingObservationDimension {

    /** Stable processor-owned cache name. */
    CACHE_NAME("cache"),

    /** Stable incremental-resolution fallback category. */
    FALLBACK_REASON("fallbackReason"),

    /** Stable patch-production category. */
    PATCH_SOURCE("patchSource"),

    /** Stable internal node-clone purpose. */
    CLONE_PURPOSE("clonePurpose");

    private final String externalName;

    ProcessingObservationDimension(String externalName) {
        this.externalName = externalName;
    }

    /**
     * Returns the stable manifest/JFR field name.
     *
     * @return stable dimension name
     */
    public String externalName() {
        return externalName;
    }
}
