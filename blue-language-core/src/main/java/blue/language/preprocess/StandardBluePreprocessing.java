package blue.language.preprocess;

import blue.language.model.Node;

import java.util.Objects;

/** Immutable service wrapper around one configured {@link Preprocessor}. */
public final class StandardBluePreprocessing implements BluePreprocessing {

    /** Identity of the specification-defined baseline-only environment. */
    public static final String BASELINE_ENVIRONMENT_IDENTITY =
            "blue-language-preprocessing/1.0/baseline";

    private final Preprocessor preprocessor;
    private final String environmentIdentity;

    /** Creates a service using only the mandatory Language baseline. */
    public StandardBluePreprocessing() {
        this(new Preprocessor(), BASELINE_ENVIRONMENT_IDENTITY);
    }

    /**
     * Creates a service for a frozen, explicitly identified environment.
     *
     * @param preprocessor configured immutable preprocessing pipeline
     * @param environmentIdentity stable host-provided environment identity
     */
    public StandardBluePreprocessing(
            Preprocessor preprocessor, String environmentIdentity) {
        this.preprocessor = Objects.requireNonNull(
                preprocessor, "preprocessor");
        this.environmentIdentity = Objects.requireNonNull(
                environmentIdentity, "environmentIdentity");
    }

    @Override
    public Node preprocess(Node source) {
        return preprocessor.preprocess(
                Objects.requireNonNull(source, "source"));
    }

    @Override
    public String environmentIdentity() {
        return environmentIdentity;
    }
}
