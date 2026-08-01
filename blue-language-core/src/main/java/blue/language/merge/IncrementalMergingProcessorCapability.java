package blue.language.merge;

/**
 * Optional capability advertised by merging processors whose behavior is
 * understood by the conservative patch-impact analyzer.
 *
 * <p>Unknown and custom processors intentionally do not participate. They
 * retain the historical authoritative whole-snapshot resolution path.</p>
 */
public interface IncrementalMergingProcessorCapability {

    /**
     * Whether value-only replacements may use dependency-proven incremental
     * snapshot resolution.
     *
     * @return {@code true} when this processor supports incremental value resolution
     */
    boolean supportsIncrementalValueResolution();

    /**
     * Request-aware variant for transparent wrappers. Existing implementations
     * keep their historical behavior through this conservative default.
     *
     * @param request immutable evidence describing the proposed incremental resolution
     * @return {@code true} when this processor supports the supplied request
     */
    default boolean supportsIncrementalValueResolution(
            IncrementalValueResolutionRequest request) {
        return supportsIncrementalValueResolution();
    }
}
