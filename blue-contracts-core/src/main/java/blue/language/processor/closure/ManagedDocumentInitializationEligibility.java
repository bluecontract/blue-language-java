package blue.language.processor.closure;

import java.util.List;
import java.util.Objects;

/**
 * Classifies whether an uninitialized managed document participates in the
 * current invocation or is only reserved by inactive prospective evidence.
 *
 * <p>A prospective row is closed evidence for a possible future edge.  Its
 * target must remain in the authoritative inventory, but that reservation by
 * itself is not processing participation and therefore must not trigger
 * lifecycle work or an initialized marker.</p>
 */
final class ManagedDocumentInitializationEligibility {

    private ManagedDocumentInitializationEligibility() {
    }

    /**
     * Returns whether the document is exclusively a dormant prospective
     * target in the supplied current closure state.
     */
    static boolean isDormantProspectiveOnlyTarget(
            ManagedDocumentSnapshot document,
            List<ManagedOccurrenceBinding> bindings,
            List<DirectLogicalDelivery> directDeliveries) {
        ManagedDocumentSnapshot selected = Objects.requireNonNull(
                document, "document");
        List<ManagedOccurrenceBinding> rows = Objects.requireNonNull(
                bindings, "bindings");
        List<DirectLogicalDelivery> deliveries = Objects.requireNonNull(
                directDeliveries, "directDeliveries");

        if (selected.publicRoot()) {
            return false;
        }
        DocumentId documentId = selected.documentId();
        for (DirectLogicalDelivery delivery : deliveries) {
            if (delivery.targetDocumentId().equals(documentId)) {
                return false;
            }
        }

        boolean prospectiveTarget = false;
        for (ManagedOccurrenceBinding binding : rows) {
            boolean source = binding.sourceDocumentId().equals(documentId);
            boolean target = binding.targetDocumentId().equals(documentId);
            if (!source && !target) {
                continue;
            }
            // A prospective-only document cannot itself own occurrence
            // surface, even when every row it owns is presently inactive.
            if (source) {
                return false;
            }
            if (binding.active()
                    || binding.pendingHistoricalEpoch() != null) {
                return false;
            }
            prospectiveTarget = true;
        }
        return prospectiveTarget;
    }
}
