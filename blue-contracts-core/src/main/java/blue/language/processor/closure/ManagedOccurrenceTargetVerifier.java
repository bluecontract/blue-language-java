package blue.language.processor.closure;

import blue.language.model.Node;
import blue.language.model.NodeWireForm;

import java.util.Objects;

/** Representation-neutral exact-target predicate for managed occurrences. */
final class ManagedOccurrenceTargetVerifier {

    private ManagedOccurrenceTargetVerifier() {
    }

    /**
     * Returns whether an occurrence value establishes the exact authoritative
     * target, regardless of whether it is encoded as a pure reference or as
     * the already-verified complete target body.
     */
    static boolean establishesExactTarget(
            Node occurrenceValue,
            ManagedDocumentSnapshot target) {
        if (occurrenceValue == null || target == null) {
            return false;
        }
        ManagedDocumentSnapshot selected = Objects.requireNonNull(
                target, "target");
        if (occurrenceValue.isReferenceOnly()) {
            return selected.blueId().equals(occurrenceValue.getBlueId());
        }
        return NodeWireForm.get(
                occurrenceValue, NodeWireForm.Strategy.SIMPLE)
                .equals(NodeWireForm.get(
                        selected.document(), NodeWireForm.Strategy.SIMPLE));
    }
}
