package blue.language.processor;

import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;

/** Exact type identities used while cataloging executable contract bodies. */
final class ExecutableBodyTypeIdentity {

    private ExecutableBodyTypeIdentity() {
    }

    static String fromSource(
            Node contract,
            ProcessingSnapshotManager snapshotManager) {
        if (contract == null || contract.getType() == null) {
            return null;
        }
        return CanonicalIdentityEvidence.sourceTypeBlueId(
                contract.getType(),
                snapshotManager,
                "Executable contract type recognition");
    }

    static String fromResolved(
            FrozenNode contract,
            CanonicalTypeIdentityLookup canonicalTypeIdentities) {
        if (contract == null || contract.getType() == null) {
            return null;
        }
        return CanonicalIdentityEvidence.resolvedTypeBlueId(
                contract.getType(),
                canonicalTypeIdentities,
                "Resolved executable contract type recognition");
    }
}
