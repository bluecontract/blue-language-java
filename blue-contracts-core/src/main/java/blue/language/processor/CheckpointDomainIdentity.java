package blue.language.processor;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;

import java.util.Objects;

/** Representation-neutral exact identity for stored checkpoint domains. */
final class CheckpointDomainIdentity {

    private CheckpointDomainIdentity() {
    }

    static String exact(Node value) {
        Node selected = Objects.requireNonNull(
                value, "checkpoint domain");
        if (selected.isReferenceOnly()) {
            String blueId = selected.getBlueId();
            if (blueId == null || blueId.isEmpty()) {
                throw new IllegalArgumentException(
                        "Checkpoint domain reference requires a BlueId");
            }
            return blueId;
        }
        return DirectBlueIdCalculator.calculateBlueId(selected);
    }
}
