package blue.language.matching;

import blue.language.identity.CanonicalTypeIdentityEvidence;
import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static blue.language.matching.MatchingPlanCache.Region.TYPE_IDENTITY_EVIDENCE;

/**
 * Resolver-issued canonical type evidence retained by one matcher.
 *
 * <p>Materialization evidence is visible immediately within the invoking
 * thread and may also be retained in the bounded matcher cache. The active
 * list prevents an accepted materialization from losing its evidence when
 * the cache declines or evicts the corresponding retained entry.</p>
 */
final class MatchingCanonicalTypeIdentityEvidence {

    private final MatchingPlanCache planCache;
    private final CanonicalTypeIdentityLookup suppliedTypeIdentities;
    private final ThreadLocal<List<CanonicalTypeIdentityLookup>>
            activeMaterializationEvidence =
            new ThreadLocal<List<CanonicalTypeIdentityLookup>>() {
                @Override
                protected List<CanonicalTypeIdentityLookup> initialValue() {
                    return new ArrayList<>();
                }
            };

    MatchingCanonicalTypeIdentityEvidence(
            MatchingPlanCache planCache,
            CanonicalTypeIdentityLookup suppliedTypeIdentities) {
        this.planCache = Objects.requireNonNull(planCache, "planCache");
        this.suppliedTypeIdentities = suppliedTypeIdentities;
    }

    void beginInvocation() {
        activeMaterializationEvidence.get().clear();
    }

    void endInvocation() {
        activeMaterializationEvidence.remove();
    }

    CanonicalTypeIdentityLookup effectiveLookup() {
        return new CanonicalTypeIdentityLookup() {
            @Override
            public boolean hasCompleteCoverage() {
                return false;
            }

            @Override
            public Optional<CanonicalTypeIdentityEvidence>
            findCanonicalTypeIdentityEvidence(Node completedType) {
                return Optional.ofNullable(
                        MatchingCanonicalTypeIdentityEvidence.this
                                .findCanonicalTypeIdentityEvidence(
                                        completedType, null));
            }

            @Override
            public Optional<CanonicalTypeIdentityEvidence>
            findCanonicalTypeIdentityEvidence(
                    Node completedType,
                    Node authoredTypeSource) {
                return Optional.ofNullable(
                        MatchingCanonicalTypeIdentityEvidence.this
                                .findCanonicalTypeIdentityEvidence(
                                        completedType,
                                        authoredTypeSource));
            }
        };
    }

    String requireCanonicalTypeBlueId(FrozenNode type) {
        String identity = canonicalTypeBlueId(type);
        if (identity == null) {
            throw new IllegalStateException(
                    "Canonical identity evidence is unavailable for completed "
                            + "inline type");
        }
        return identity;
    }

    String canonicalTypeBlueId(FrozenNode type) {
        if (type == null) {
            return null;
        }
        if (type.isReferenceOnly()) {
            return type.getReferenceBlueId();
        }
        CanonicalTypeIdentityEvidence evidence =
                findCanonicalTypeIdentityEvidence(type.toNode());
        return evidence != null ? evidence.blueId() : null;
    }

    boolean hasRetainedMaterializationEvidence(String blueId) {
        return planCache.get(TYPE_IDENTITY_EVIDENCE, blueId) != null;
    }

    void retainMaterializedTypeEvidence(
            String requestedBlueId,
            FrozenNode materializedType,
            CanonicalTypeIdentityLookup typeIdentities) {
        CanonicalTypeIdentityLookup checked = Objects.requireNonNull(
                typeIdentities,
                "materialized canonical type identities");
        String establishedBlueId = checked.requireCanonicalTypeBlueId(
                materializedType.toNode());
        if (!requestedBlueId.equals(establishedBlueId)) {
            throw new IllegalStateException(
                    "Verified reference materialization identity mismatch: "
                            + "expected " + requestedBlueId
                            + " but resolver established "
                            + establishedBlueId);
        }
        activeMaterializationEvidence.get().add(checked);
        planCache.put(
                TYPE_IDENTITY_EVIDENCE,
                requestedBlueId,
                new RetainedTypeIdentityEvidence(
                        checked,
                        saturatedAdd(
                                materializedType
                                        .approximateRetainedWeightBytes(),
                                checked.approximateRetainedWeightBytes())));
    }

    private CanonicalTypeIdentityEvidence findCanonicalTypeIdentityEvidence(
            Node completedType) {
        return findCanonicalTypeIdentityEvidence(completedType, null);
    }

    private CanonicalTypeIdentityEvidence findCanonicalTypeIdentityEvidence(
            Node completedType,
            Node authoredTypeSource) {
        CanonicalTypeIdentityEvidence established = null;
        for (CanonicalTypeIdentityLookup lookup
                : activeMaterializationEvidence.get()) {
            established = reconcileCanonicalTypeIdentityEvidence(
                    established,
                    lookup,
                    completedType,
                    authoredTypeSource);
        }
        for (Object retained : planCache.values(TYPE_IDENTITY_EVIDENCE)) {
            CanonicalTypeIdentityLookup lookup =
                    ((RetainedTypeIdentityEvidence) retained).lookup;
            established = reconcileCanonicalTypeIdentityEvidence(
                    established,
                    lookup,
                    completedType,
                    authoredTypeSource);
        }
        if (suppliedTypeIdentities != null) {
            established = reconcileCanonicalTypeIdentityEvidence(
                    established,
                    suppliedTypeIdentities,
                    completedType,
                    authoredTypeSource);
        }
        return established;
    }

    private static long saturatedAdd(long left, long right) {
        return Long.MAX_VALUE - left < right
                ? Long.MAX_VALUE
                : left + right;
    }

    private CanonicalTypeIdentityEvidence
    reconcileCanonicalTypeIdentityEvidence(
            CanonicalTypeIdentityEvidence established,
            CanonicalTypeIdentityLookup lookup,
            Node completedType,
            Node authoredTypeSource) {
        Optional<CanonicalTypeIdentityEvidence> candidate = lookup
                .findCanonicalTypeIdentityEvidence(
                        completedType, authoredTypeSource);
        if (!candidate.isPresent()) {
            return established;
        }
        return established == null
                ? candidate.get()
                : established.combine(candidate.get());
    }

    private static final class RetainedTypeIdentityEvidence
            implements MatchingPlanCache.Weighted {
        private final CanonicalTypeIdentityLookup lookup;
        private final long retainedWeightBytes;

        private RetainedTypeIdentityEvidence(
                CanonicalTypeIdentityLookup lookup,
                long retainedWeightBytes) {
            this.lookup = Objects.requireNonNull(lookup, "lookup");
            this.retainedWeightBytes = retainedWeightBytes;
        }

        @Override
        public long retainedWeightBytes() {
            return retainedWeightBytes;
        }
    }
}
