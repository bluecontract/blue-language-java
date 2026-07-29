package blue.language.registry;

import blue.language.utils.Properties;

/**
 * Stable field names and categorical values used by released registry
 * manifests.
 *
 * <p>Language and Contracts registries share this vocabulary when loading and
 * hashing their manifests. Keeping one owner prevents identity calculations
 * from silently diverging because of a duplicated literal.</p>
 */
public final class RegistryManifestConstants {

    /** Manifest field identifying the registry. */
    public static final String FIELD_REGISTRY = "registry";
    /** Manifest field identifying the registry entry kind. */
    public static final String FIELD_REGISTRY_KIND = "registryKind";
    /** Manifest field containing the specification version. */
    public static final String FIELD_SPECIFICATION_VERSION =
            "specificationVersion";
    /** Manifest field containing the Language version. */
    public static final String FIELD_LANGUAGE_VERSION =
            "languageVersion";
    /** Manifest field containing the package identity. */
    public static final String FIELD_PACKAGE_IDENTITY =
            "packageIdentity";
    /** Manifest field binding the fixture package identity. */
    public static final String FIELD_FIXTURE_PACKAGE_IDENTITY =
            "fixturePackageIdentity";
    /** Manifest field containing ordered registry entries. */
    public static final String FIELD_ENTRIES = "entries";
    /** Rejected legacy field that contained a type map. */
    public static final String FIELD_LEGACY_TYPES = "types";
    /** Registry-entry field containing its stable key. */
    public static final String FIELD_KEY = "key";
    /** Registry-entry field containing its classpath-relative path. */
    public static final String FIELD_PATH = "path";
    /** Registry-entry field containing its published BlueId. */
    public static final String FIELD_BLUE_ID =
            Properties.OBJECT_BLUE_ID;
    /** Registry-entry field containing its resource SHA-256. */
    public static final String FIELD_SHA256 = "sha256";
    /** Entry flag making description text identity-bearing. */
    public static final String
            FIELD_SEMANTIC_DESCRIPTION_IDENTITY_BEARING =
            "semanticDescriptionIdentityBearing";
    /** Entry flag limiting a type to conformance fixtures. */
    public static final String FIELD_FIXTURE_ONLY = "fixtureOnly";

    /** Released Language/Contracts specification version. */
    public static final String VERSION_1_0 = "1.0";
    /** Registry discriminator for the Language core package. */
    public static final String REGISTRY_LANGUAGE_CORE =
            "blue-language-core";
    /** Entry-kind discriminator for Language core types. */
    public static final String KIND_CORE_TYPE = "core-type";
    /** Registry discriminator for the Contracts runtime package. */
    public static final String REGISTRY_CONTRACTS_RUNTIME =
            "blue-contracts-runtime";
    /** Entry-kind discriminator for Contracts runtime types. */
    public static final String KIND_RUNTIME_TYPE = "runtime-type";

    private RegistryManifestConstants() {
    }
}
