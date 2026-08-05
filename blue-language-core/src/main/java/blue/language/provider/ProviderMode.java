package blue.language.provider;

/**
 * Declares which canonicalization contract applies to supplied provider
 * evidence.
 *
 * <p>The enum retains its released constant names. The semantic aliases
 * {@link #DIRECT_NODE} and {@link #BOUND_SOURCE_CONTENT} make the two accepted
 * modes explicit without changing the binary enum shape.</p>
 */
public enum ProviderMode {
    /** Content is already strict direct BlueId input and must not be preprocessed. */
    BLUE_ID_INPUT,
    /** Content is authored source bound to an exact preprocessing environment. */
    SOURCE_DOCUMENT;

    /** Strict direct-node evidence mode. */
    public static final ProviderMode DIRECT_NODE = BLUE_ID_INPUT;

    /** Fully bound authored-source Content BlueId evidence mode. */
    public static final ProviderMode BOUND_SOURCE_CONTENT = SOURCE_DOCUMENT;

    /**
     * Returns the stable evidence-report label for this mode.
     *
     * @return {@code DIRECT_NODE} or {@code BOUND_SOURCE_CONTENT}
     */
    public String evidenceLabel() {
        return this == BLUE_ID_INPUT
                ? "DIRECT_NODE"
                : "BOUND_SOURCE_CONTENT";
    }
}
