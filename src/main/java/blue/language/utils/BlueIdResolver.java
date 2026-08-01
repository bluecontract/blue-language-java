package blue.language.utils;

/**
 * Resolves the default BlueId associated with a {@link TypeBlueId}-annotated
 * Java class.
 *
 * <p>Inline annotation values take precedence over the optional classpath
 * repository. Missing annotations, resources, or mappings resolve to
 * {@code null}; repository failures are logged rather than thrown.</p>
 */
@Deprecated
public class BlueIdResolver extends blue.language.mapping.BlueIdResolver {

    /** Creates a compatibility facade over static type-resolution helpers. */
    public BlueIdResolver() {
    }
}
