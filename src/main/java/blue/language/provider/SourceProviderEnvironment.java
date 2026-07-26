package blue.language.provider;

import java.util.Objects;

/**
 * Exact preprocessing environment bound to Source-document provider evidence.
 */
public final class SourceProviderEnvironment {

    public static final String LANGUAGE_1_0_RELEASE_IDENTITY =
            "blue-language-1.0-final-implementation-baseline@"
                    + "sha256:277418303ae10aade4029a398f880a8d0f2b321d4943492ac811287c21eb3dbb";

    private final String languageVersion;
    private final String languageReleaseIdentity;
    private final String preprocessingEnvironmentId;
    private final String canonicalRegistryIdentity;
    private final String sourceEvidenceIdentity;

    /**
     * @deprecated A language version and an ambient environment label do not
     * bind enough evidence for Source Document provider verification. Values
     * created by this constructor are deliberately rejected by the verifier.
     */
    @Deprecated
    public SourceProviderEnvironment(String languageVersion,
                                     String preprocessingEnvironmentId) {
        this.languageVersion = requireText(languageVersion, "languageVersion");
        this.preprocessingEnvironmentId = requireText(
                preprocessingEnvironmentId, "preprocessingEnvironmentId");
        this.languageReleaseIdentity = null;
        this.canonicalRegistryIdentity = null;
        this.sourceEvidenceIdentity = null;
    }

    public SourceProviderEnvironment(String languageVersion,
                                     String languageReleaseIdentity,
                                     String preprocessingEnvironmentId,
                                     String canonicalRegistryIdentity,
                                     String sourceEvidenceIdentity) {
        this.languageVersion = requireText(languageVersion, "languageVersion");
        this.languageReleaseIdentity = requireText(
                languageReleaseIdentity, "languageReleaseIdentity");
        this.preprocessingEnvironmentId = requireText(
                preprocessingEnvironmentId, "preprocessingEnvironmentId");
        this.canonicalRegistryIdentity = requireText(
                canonicalRegistryIdentity, "canonicalRegistryIdentity");
        this.sourceEvidenceIdentity = requireText(
                sourceEvidenceIdentity, "sourceEvidenceIdentity");
    }

    public String languageVersion() {
        return languageVersion;
    }

    public String preprocessingEnvironmentId() {
        return preprocessingEnvironmentId;
    }

    public String languageReleaseIdentity() {
        return languageReleaseIdentity;
    }

    public String canonicalRegistryIdentity() {
        return canonicalRegistryIdentity;
    }

    public String sourceEvidenceIdentity() {
        return sourceEvidenceIdentity;
    }

    public boolean isFullyBound() {
        return languageReleaseIdentity != null
                && canonicalRegistryIdentity != null
                && sourceEvidenceIdentity != null;
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank.");
        }
        return value;
    }
}
