package blue.language.identity;

import blue.language.model.Node;

import java.util.Optional;

/**
 * Resolver-issued lookup for canonical identities of completed effective
 * types.
 *
 * <p>Consumers must require complete coverage for the graph they canonicalize.
 * An unlimited resolution covers the full graph. A resolver may also certify
 * a materialized graph whose only omitted subgraphs terminate at exact
 * authored pure references, because those terminals are already canonical
 * identity input and require no sidecar evidence. Individual materialized-
 * type lookups always fail closed when no verified or independently derived
 * identity evidence exists.</p>
 */
public interface CanonicalTypeIdentityLookup {

    /**
     * Returns a lookup that carries no resolver sidecar evidence.
     *
     * <p>Like every lookup, it still returns the exact BlueId of a valid pure
     * reference. It fails for every materialized effective type.</p>
     *
     * @return shared incomplete lookup
     */
    static CanonicalTypeIdentityLookup incomplete() {
        return IncompleteCanonicalTypeIdentityLookup.INSTANCE;
    }

    /**
     * Reports whether the producing resolution covered every effective type.
     *
     * @return {@code true} only when every effective type reachable by the
     *         corresponding canonical reconstruction is covered
     */
    boolean hasCompleteCoverage();

    /**
     * Rejects a partial lookup before whole-graph canonicalization begins.
     *
     * @throws IllegalStateException when coverage is incomplete
     */
    default void requireCompleteCoverage() {
        if (!hasCompleteCoverage()) {
            throw new IllegalStateException(
                    "Canonical type identity evidence is incomplete");
        }
    }

    /**
     * Finds the canonical identity of one effective type when this lookup
     * covers its completed structure.
     *
     * <p>An exact pure reference is already canonical identity input and is
     * therefore always returned after validation. An empty result means only
     * that this resolver-issued lookup does not cover the supplied
     * materialized type. Malformed input and conflicting evidence still fail
     * the operation.</p>
     *
     * <p>The default is deliberately fail-closed for external lookup
     * implementations: it delegates to the strict operation and never turns
     * an arbitrary failure into an apparent coverage miss. Resolver-owned
     * structural lookups override it to report an actual absent key.</p>
     *
     * @param completedType completed resolved effective type
     * @return exact identity when covered, otherwise empty
     * @throws NullPointerException if {@code completedType} is null
     * @throws IllegalArgumentException if a pure reference is malformed
     * @throws IllegalStateException if retained evidence conflicts
     */
    default Optional<String> findCanonicalTypeBlueId(Node completedType) {
        if (completedType == null) {
            throw new NullPointerException("completedType");
        }
        if (completedType.isReferenceOnly()) {
            BlueIdReferenceValidator.validate(completedType);
            return Optional.of(completedType.getBlueId());
        }
        return findCanonicalTypeIdentityEvidence(completedType)
                .map(CanonicalTypeIdentityEvidence::blueId);
    }

    /**
     * Returns the canonical identity of one effective type.
     *
     * <p>An exact pure reference returns its own validated BlueId without
     * consulting resolver sidecar evidence. Every materialized effective type
     * requires evidence issued or independently derived by the producing
     * resolver invocation.</p>
     *
     * @param completedType completed resolved effective type
     * @return canonical type BlueId
     * @throws NullPointerException when {@code completedType} is {@code null}
     * @throws IllegalArgumentException if a pure reference is malformed
     * @throws IllegalStateException when no evidence covers the supplied type
     *         or retained evidence conflicts
     */
    default String requireCanonicalTypeBlueId(Node completedType) {
        return findCanonicalTypeBlueId(completedType)
                .orElseThrow(() -> new IllegalStateException(
                        "No resolver-issued canonical type identity evidence "
                                + "is available"));
    }

    /**
     * Finds the complete identity-and-origin evidence retained for a type.
     *
     * <p>This is the primary lookup operation. Implementations must state
     * representation provenance explicitly; an identity-only implementation
     * uses {@link CanonicalTypeIdentityEvidence#identityOnly(String)}. Making
     * this operation mandatory prevents wrappers from silently discarding
     * authored or reference Source evidence.</p>
     *
     * @param completedType completed effective type
     * @return exact evidence when this lookup covers the type
     * @throws NullPointerException if {@code completedType} is null
     * @throws IllegalArgumentException if a pure reference is malformed
     * @throws IllegalStateException if retained evidence conflicts
     */
    Optional<CanonicalTypeIdentityEvidence>
    findCanonicalTypeIdentityEvidence(Node completedType);

    /**
     * Finds identity evidence using the exact authored type declaration to
     * disambiguate completed types with the same physical structure.
     *
     * <p>The default accepts only an absent Source hint or two identical pure
     * references. Implementations that retain inline provenance must override
     * this operation. A Source hint is never silently ignored.</p>
     *
     * @param completedType completed effective type
     * @param authoredTypeSource exact authored type declaration, when known
     * @return exact evidence when this lookup covers the declaration
     */
    default Optional<CanonicalTypeIdentityEvidence>
    findCanonicalTypeIdentityEvidence(
            Node completedType,
            Node authoredTypeSource) {
        if (authoredTypeSource == null) {
            return findCanonicalTypeIdentityEvidence(completedType);
        }
        if (completedType == null) {
            throw new NullPointerException("completedType");
        }
        if (completedType.isReferenceOnly()
                && authoredTypeSource.isReferenceOnly()) {
            BlueIdReferenceValidator.validate(completedType);
            BlueIdReferenceValidator.validate(authoredTypeSource);
            if (completedType.getBlueId().equals(
                    authoredTypeSource.getBlueId())) {
                return findCanonicalTypeIdentityEvidence(completedType);
            }
        }
        throw new IllegalStateException(
                "This canonical type identity lookup cannot verify the "
                        + "supplied authored type representation");
    }

    /**
     * Requires identity evidence for a completed/authored representation pair.
     *
     * @param completedType completed effective type
     * @param authoredTypeSource exact authored type declaration, when known
     * @return canonical type BlueId
     */
    default String requireCanonicalTypeBlueId(
            Node completedType,
            Node authoredTypeSource) {
        return findCanonicalTypeIdentityEvidence(
                completedType, authoredTypeSource)
                .map(CanonicalTypeIdentityEvidence::blueId)
                .orElseThrow(() -> new IllegalStateException(
                        "No resolver-issued canonical type identity evidence "
                                + "is available"));
    }

    /**
     * Returns the approximate retained heap weight of this evidence graph.
     *
     * <p>Unknown third-party implementations default to an uncacheable weight
     * so bounded caches cannot silently under-account an arbitrary retained
     * object graph. Resolver-issued immutable snapshots override this value.</p>
     *
     * @return approximate retained bytes, saturated at {@link Long#MAX_VALUE}
     */
    default long approximateRetainedWeightBytes() {
        return Long.MAX_VALUE;
    }

}

/** Package-private singleton keeps the fail-closed implementation out of API. */
final class IncompleteCanonicalTypeIdentityLookup
        implements CanonicalTypeIdentityLookup {

    static final CanonicalTypeIdentityLookup INSTANCE =
            new IncompleteCanonicalTypeIdentityLookup();

    private IncompleteCanonicalTypeIdentityLookup() {
    }

    @Override
    public boolean hasCompleteCoverage() {
        return false;
    }

    @Override
    public Optional<String> findCanonicalTypeBlueId(Node completedType) {
        if (completedType == null) {
            throw new NullPointerException("completedType");
        }
        if (completedType.isReferenceOnly()) {
            BlueIdReferenceValidator.validate(completedType);
            return Optional.of(completedType.getBlueId());
        }
        return Optional.empty();
    }

    @Override
    public Optional<CanonicalTypeIdentityEvidence>
    findCanonicalTypeIdentityEvidence(Node completedType) {
        if (completedType == null) {
            throw new NullPointerException("completedType");
        }
        if (completedType.isReferenceOnly()) {
            BlueIdReferenceValidator.validate(completedType);
            return Optional.of(
                    CanonicalTypeIdentityEvidence.referenceSource(
                            completedType.getBlueId()));
        }
        return Optional.empty();
    }

    @Override
    public String requireCanonicalTypeBlueId(Node completedType) {
        return findCanonicalTypeBlueId(completedType)
                .orElseThrow(() -> new IllegalStateException(
                        "No resolver-issued canonical type identity evidence is "
                                + "available"));
    }

    @Override
    public long approximateRetainedWeightBytes() {
        return 0L;
    }
}
