package blue.language.provider;

import java.util.Objects;

/**
 * Exact preprocessing environment bound to Source-document provider evidence.
 *
 * <p>The retained field name {@code languageReleaseIdentity} denotes the
 * semantic Language source-preprocessing baseline. It is deliberately
 * independent of any aggregate Language/Contracts distribution package.</p>
 */
public final class SourceProviderEnvironment {

    /**
     * Source-preprocessing baseline required for Language 1.0 ingestion.
     *
     * <p>This is the canonical identity of
     * {@code provider/blue-language-source-preprocessing-environment-1.0.yaml},
     * not an aggregate distribution identity.</p>
     */
    public static final String LANGUAGE_1_0_RELEASE_IDENTITY =
            "blue-language-source-preprocessing-environment-1.0@"
                    + "sha256:74be75a1b0ca9932b88e00b5010beadf45010d8bf643b52fb83447d4c0a9640e";
    /** Domain used by the released explicit verifier overload. */
    public static final String EXPLICIT_VERIFIER_DOMAIN_IDENTITY =
            "blue-language-1.0:explicit-provider-evidence-verifier";
    /** Ordinary Language 1.0 Source Document identity strategy. */
    public static final String LANGUAGE_CONTENT_STRATEGY_IDENTITY =
            "blue-language-1.0:source-content-canonicalization";
    private final String languageVersion;
    private final String languageReleaseIdentity;
    private final String preprocessingEnvironmentId;
    private final String canonicalRegistryIdentity;
    private final String providerDomainIdentity;
    private final ProviderMode providerMode;
    private final String sourceContentStrategyIdentity;
    private final String sourceEvidenceIdentity;

    /**
     * Creates a fully specified environment; every field must be nonblank.
     *
     * @param languageVersion declared Blue language version
     * @param languageReleaseIdentity exact Language source-preprocessing
     *                                baseline identity
     * @param preprocessingEnvironmentId exact preprocessing configuration
     *                                   identity
     * @param canonicalRegistryIdentity exact canonical registry identity
     * @param sourceEvidenceIdentity exact authored-source identity
     * @throws NullPointerException when any field is {@code null}
     * @throws IllegalArgumentException when any field is blank
     */
    public SourceProviderEnvironment(String languageVersion,
                                     String languageReleaseIdentity,
                                     String preprocessingEnvironmentId,
                                     String canonicalRegistryIdentity,
                                     String sourceEvidenceIdentity) {
        this(languageVersion,
                languageReleaseIdentity,
                preprocessingEnvironmentId,
                canonicalRegistryIdentity,
                EXPLICIT_VERIFIER_DOMAIN_IDENTITY,
                ProviderMode.BOUND_SOURCE_CONTENT,
                LANGUAGE_CONTENT_STRATEGY_IDENTITY,
                sourceEvidenceIdentity);
    }

    /**
     * Creates a fully specified immutable source-provider environment.
     *
     * @param languageVersion declared Blue language version
     * @param languageReleaseIdentity exact Language source-preprocessing
     *                                baseline identity
     * @param preprocessingEnvironmentId exact preprocessing configuration
     *                                   identity
     * @param canonicalRegistryIdentity exact canonical registry identity
     * @param providerDomainIdentity exact provider implementation/domain
     *                               evidence identity
     * @param providerMode explicitly selected provider ingestion mode
     * @param sourceEvidenceIdentity exact imported authored-source identity
     * @throws NullPointerException when any field is {@code null}
     * @throws IllegalArgumentException when a text field is blank or the mode
     *                                  is not bound source content
     */
    public SourceProviderEnvironment(String languageVersion,
                                     String languageReleaseIdentity,
                                     String preprocessingEnvironmentId,
                                     String canonicalRegistryIdentity,
                                     String providerDomainIdentity,
                                     ProviderMode providerMode,
                                     String sourceEvidenceIdentity) {
        this(languageVersion,
                languageReleaseIdentity,
                preprocessingEnvironmentId,
                canonicalRegistryIdentity,
                providerDomainIdentity,
                providerMode,
                LANGUAGE_CONTENT_STRATEGY_IDENTITY,
                sourceEvidenceIdentity);
    }

    /**
     * Creates a fully specified immutable source-provider environment,
     * including the exact semantic Content BlueId strategy.
     *
     * @param languageVersion declared Blue language version
     * @param languageReleaseIdentity exact Language source-preprocessing
     *                                baseline identity
     * @param preprocessingEnvironmentId exact preprocessing configuration
     *                                   identity
     * @param canonicalRegistryIdentity exact canonical registry identity
     * @param providerDomainIdentity exact provider implementation/domain
     *                               evidence identity
     * @param providerMode explicitly selected provider ingestion mode
     * @param sourceContentStrategyIdentity exact source canonicalization
     *                                      strategy identity
     * @param sourceEvidenceIdentity exact imported authored-source identity
     */
    public SourceProviderEnvironment(String languageVersion,
                                     String languageReleaseIdentity,
                                     String preprocessingEnvironmentId,
                                     String canonicalRegistryIdentity,
                                     String providerDomainIdentity,
                                     ProviderMode providerMode,
                                     String sourceContentStrategyIdentity,
                                     String sourceEvidenceIdentity) {
        this.languageVersion = requireText(languageVersion, "languageVersion");
        this.languageReleaseIdentity = requireText(
                languageReleaseIdentity, "languageReleaseIdentity");
        this.preprocessingEnvironmentId = requireText(
                preprocessingEnvironmentId, "preprocessingEnvironmentId");
        this.canonicalRegistryIdentity = requireText(
                canonicalRegistryIdentity, "canonicalRegistryIdentity");
        this.providerDomainIdentity = requireText(
                providerDomainIdentity, "providerDomainIdentity");
        this.providerMode = Objects.requireNonNull(
                providerMode, "providerMode");
        if (providerMode != ProviderMode.BOUND_SOURCE_CONTENT) {
            throw new IllegalArgumentException(
                    "Source provider environment requires BOUND_SOURCE_CONTENT mode.");
        }
        this.sourceContentStrategyIdentity = requireText(
                sourceContentStrategyIdentity,
                "sourceContentStrategyIdentity");
        this.sourceEvidenceIdentity = requireText(
                sourceEvidenceIdentity, "sourceEvidenceIdentity");
    }

    /**
     * Returns the declared Blue language version.
     *
     * @return declared language version
     */
    public String languageVersion() {
        return languageVersion;
    }

    /**
     * Returns the exact preprocessing configuration identity.
     *
     * @return preprocessing configuration identity
     */
    public String preprocessingEnvironmentId() {
        return preprocessingEnvironmentId;
    }

    /**
     * Returns the exact Language source-preprocessing baseline identity.
     *
     * <p>The accessor name is retained for the current RC API shape; the value
     * is not an aggregate distribution package identity.</p>
     *
     * @return Language source-preprocessing baseline identity
     */
    public String languageReleaseIdentity() {
        return languageReleaseIdentity;
    }

    /**
     * Returns the exact canonical registry identity.
     *
     * @return canonical registry identity
     */
    public String canonicalRegistryIdentity() {
        return canonicalRegistryIdentity;
    }

    /**
     * Returns the exact identity of the provider implementation/domain that
     * imported the source evidence.
     *
     * @return provider domain identity
     */
    public String providerDomainIdentity() {
        return providerDomainIdentity;
    }

    /**
     * Returns the explicitly selected source-provider mode.
     *
     * @return bound source-content mode
     */
    public ProviderMode providerMode() {
        return providerMode;
    }

    /**
     * Returns the exact semantic strategy used to calculate Content BlueId.
     *
     * @return source-content strategy identity
     */
    public String sourceContentStrategyIdentity() {
        return sourceContentStrategyIdentity;
    }

    /**
     * Returns the exact authored-source evidence identity.
     *
     * @return source evidence identity
     */
    public String sourceEvidenceIdentity() {
        return sourceEvidenceIdentity;
    }

    /**
     * Tests whether every required evidence binding is present.
     *
     * @return whether the environment is fully bound
     */
    public boolean isFullyBound() {
        return languageVersion != null
                && languageReleaseIdentity != null
                && preprocessingEnvironmentId != null
                && canonicalRegistryIdentity != null
                && providerDomainIdentity != null
                && providerMode == ProviderMode.BOUND_SOURCE_CONTENT
                && sourceContentStrategyIdentity != null
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
