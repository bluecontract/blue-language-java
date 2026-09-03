package blue.language.processor;

import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.identity.NodeToBlueIdInput;
import blue.language.merge.ResolvedSnapshot;
import blue.language.model.Node;
import blue.language.processor.util.PointerUtils;
import blue.language.snapshot.FrozenNode;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/** Builds one representation-neutral effective runtime contribution. */
final class NormalizedRuntimeContribution {

    private NormalizedRuntimeContribution() {
    }

    /**
     * Re-resolves a Channel contribution from canonical Source while retaining
     * the registered exact header fields as opaque values.
     *
     * <p>A whole-contract reference is opened for recognition before this
     * boundary, but that exact provider body has not necessarily been completed
     * as a standalone runtime value. Rebuilding Source and resolving it here
     * gives inline and referenced declarations one identical effective
     * contribution. Exact event matchers and other registered Node headers stay
     * values; they are never executed or reinterpreted as standalone instances.
     * Resolved reference-provenance metadata is stripped before the result is
     * frozen and later hashed.</p>
     */
    static FrozenNode channel(
            FrozenNode contribution,
            String effectiveTypeBlueId,
            Collection<String> exactHeaderFields,
            CanonicalTypeIdentityLookup typeIdentities,
            ProcessingSnapshotManager snapshotManager) {
        FrozenNode checkedContribution = Objects.requireNonNull(
                contribution, "contribution");
        String checkedTypeBlueId = requireText(
                effectiveTypeBlueId, "effectiveTypeBlueId");
        Node source = checkedContribution.toNode();
        source.type(new Node().blueId(checkedTypeBlueId));
        CanonicalEffectSourceProjection.projectResolvedOwned(
                source,
                Objects.requireNonNull(typeIdentities, "typeIdentities"));
        NodeToBlueIdInput.stripResolvedBlueIdMetadata(source);
        if (snapshotManager == null) {
            return FrozenNode.fromResolvedNode(source);
        }

        Set<String> preserved = new LinkedHashSet<>(
                ExecutableBodyPathCatalog.ordinaryReferencePaths(source));
        if (exactHeaderFields != null && source.getProperties() != null) {
            for (String field : exactHeaderFields) {
                if (field != null
                        && source.getProperties().containsKey(field)) {
                    preserved.add(PointerUtils.toPointer(
                            java.util.Collections.singletonList(field)));
                }
            }
        }
        ResolvedSnapshot resolved = Objects.requireNonNull(
                preserved.isEmpty()
                        ? snapshotManager.fromDocumentTransient(source)
                        : snapshotManager.fromDocumentTransientPreservingPaths(
                                source, preserved),
                "normalizedRuntimeContributionSnapshot");
        Node exact = resolved.frozenResolvedRoot().toNode();
        requireEffectiveType(
                exact,
                checkedTypeBlueId,
                resolved.canonicalTypeIdentities());
        CanonicalEffectSourceProjection.projectResolvedOwned(
                exact, resolved.canonicalTypeIdentities());
        NodeToBlueIdInput.stripResolvedBlueIdMetadata(exact);
        return FrozenNode.fromNode(exact);
    }

    private static void requireEffectiveType(
            Node contribution,
            String expectedBlueId,
            CanonicalTypeIdentityLookup identities) {
        Node type = contribution.getType();
        if (type == null) {
            throw new IllegalStateException(
                    "Normalized runtime contribution has no effective type");
        }
        String actualBlueId = type.isReferenceOnly()
                ? type.getBlueId()
                : identities.requireCanonicalTypeBlueId(type);
        if (!expectedBlueId.equals(actualBlueId)) {
            throw new IllegalStateException(
                    "Normalized runtime contribution type changed: expected "
                            + expectedBlueId + " but resolved " + actualBlueId);
        }
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(label + " must be non-empty");
        }
        return value;
    }
}
