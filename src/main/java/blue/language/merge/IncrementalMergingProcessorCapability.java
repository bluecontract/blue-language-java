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
     */
    boolean supportsIncrementalValueResolution();
}
