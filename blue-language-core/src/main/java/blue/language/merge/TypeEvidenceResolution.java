package blue.language.merge;

import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.snapshot.FrozenNode;

import java.util.Objects;

/**
 * Immutable resolved graph plus effective-type identity evidence from the
 * same resolver invocation.
 *
 * <p>This result deliberately has no canonical root. It is intended for
 * target-limited operations, such as matching and localized conformance
 * checks, that need exact identities only for the effective types they
 * reached. Its lookup may therefore have incomplete whole-graph coverage;
 * callers must not use it to canonicalize the entire resolved graph.</p>
 */
public final class TypeEvidenceResolution {

    private final FrozenNode resolvedRoot;
    private final CanonicalTypeIdentityLookup canonicalTypeIdentities;

    /**
     * Creates a resolved-only result with evidence produced by the same
     * resolution operation.
     *
     * @param resolvedRoot immutable resolved graph
     * @param canonicalTypeIdentities exact effective-type identity evidence
     * @throws NullPointerException if an argument is null
     */
    public TypeEvidenceResolution(
            FrozenNode resolvedRoot,
            CanonicalTypeIdentityLookup canonicalTypeIdentities) {
        this.resolvedRoot = Objects.requireNonNull(
                resolvedRoot, "resolvedRoot");
        this.canonicalTypeIdentities = Objects.requireNonNull(
                canonicalTypeIdentities, "canonicalTypeIdentities");
    }

    /**
     * Returns the immutable graph resolved under the caller's limits.
     *
     * @return resolved graph
     */
    public FrozenNode resolvedRoot() {
        return resolvedRoot;
    }

    /**
     * Returns invocation-local identities for effective types reached during
     * resolution.
     *
     * <p>The lookup is not necessarily complete for whole-graph
     * canonicalization. Individual covered lookups remain fail-closed.</p>
     *
     * @return exact resolver-issued type identity evidence
     */
    public CanonicalTypeIdentityLookup canonicalTypeIdentities() {
        return canonicalTypeIdentities;
    }
}
