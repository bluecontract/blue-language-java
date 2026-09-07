package blue.language.merge;

import blue.language.identity.CanonicalIdentityInputBuilder;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.identity.NodeToBlueIdInput;
import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;

/** Records canonical identity evidence after bottom-up type completion. */
final class CanonicalTypeIdentityRecorder {

    String recordCompleted(
            CanonicalTypeIdentityIndex identityIndex,
            Node completedType,
            Node authoredInlineType,
            String requestedBlueId) {
        String canonicalBlueId;
        CanonicalTypeIdentityIndex.EvidenceKind evidenceKind;
        FrozenNode canonicalTypeIdentityInput = null;
        FrozenNode authoredTypeSource = null;
        Node normalizedAuthoredType = null;
        if (authoredInlineType != null) {
            Node canonicalResolvedType = withoutMaterializedRootBlueId(
                    completedType);
            normalizedAuthoredType = NodeToBlueIdInput
                    .stripResolvedBlueIdMetadata(
                            authoredInlineType.clone());
            Node canonicalType = new CanonicalIdentityInputBuilder()
                    .buildResolvedType(
                            canonicalResolvedType,
                            normalizedAuthoredType,
                            identityIndex);
            canonicalBlueId = DirectBlueIdCalculator.calculateBlueId(
                    canonicalType);
            canonicalTypeIdentityInput = FrozenNode.fromNode(canonicalType);
            /*
             * Keep the normalized authored declaration separate from the
             * reconstructed proof. In particular, nested inline types and
             * preprocessing list controls remain self-contained Source even
             * though they are replaced or consumed in canonical identity
             * input.
             */
            authoredTypeSource = FrozenNode.fromResolvedNode(
                    normalizedAuthoredType);
            evidenceKind = CanonicalTypeIdentityIndex.EvidenceKind
                    .AUTHORED_INLINE;
        } else {
            if (requestedBlueId == null) {
                throw new IllegalStateException(
                        "Completed reference type lost its requested BlueId");
            }
            canonicalBlueId = requestedBlueId;
            evidenceKind = CanonicalTypeIdentityIndex.EvidenceKind
                    .VERIFIED_REFERENCE;
        }
        identityIndex.record(
                completedType,
                canonicalBlueId,
                evidenceKind,
                canonicalTypeIdentityInput,
                authoredTypeSource);
        if (authoredInlineType != null) {
            /* The authored and completed forms are evidence from one run. */
            identityIndex.record(
                    normalizedAuthoredType,
                    canonicalBlueId,
                    evidenceKind,
                    canonicalTypeIdentityInput,
                    authoredTypeSource);
        }
        return canonicalBlueId;
    }

    private Node withoutMaterializedRootBlueId(Node type) {
        Node canonicalInput = type.clone();
        if (!canonicalInput.isReferenceOnly()) {
            canonicalInput.blueId(null);
        }
        return canonicalInput;
    }
}
