package blue.language.examples;

import blue.language.merge.ResolvedSnapshot;
import blue.language.model.Node;
import blue.language.runtime.BlueLanguage;
import blue.language.snapshot.FrozenNode;

/** Reads immutable snapshot paths while mutable materializations stay detached. */
public final class ImmutableSnapshotExample {

    private static final String MESSAGE_FIELD = "message";
    private static final String MESSAGE_POINTER = "/message";
    private static final String ORIGINAL_MESSAGE = "stable";
    private static final String MUTATED_MESSAGE = "caller mutation";

    private ImmutableSnapshotExample() {
    }

    /** Resolves one Source and proves later caller mutation cannot enter the snapshot. */
    public static Result run() {
        Node source = new Node().properties(
                MESSAGE_FIELD, new Node().value(ORIGINAL_MESSAGE));
        try (BlueLanguage language = BlueLanguage.builder().build()) {
            ResolvedSnapshot snapshot = language.snapshots().resolve(source);
            String blueIdBeforeMutation = snapshot.blueId();
            FrozenNode frozenMessage = snapshot.resolvedAt(MESSAGE_POINTER);
            Node detached = snapshot.resolvedRoot();
            detached.getProperties().get(MESSAGE_FIELD)
                    .value(MUTATED_MESSAGE);

            Object frozenValueAfterMutation = snapshot
                    .resolvedAt(MESSAGE_POINTER).getValue();
            Node secondDetachedView = snapshot.resolvedRoot();
            ExampleSupport.require(blueIdBeforeMutation.equals(
                            snapshot.blueId()),
                    "Snapshot identity must remain stable after caller mutation");
            ExampleSupport.require(ORIGINAL_MESSAGE.equals(
                            frozenValueAfterMutation),
                    "Frozen path access must not observe caller mutation");
            ExampleSupport.require(frozenMessage == snapshot.resolvedAt(
                            MESSAGE_POINTER),
                    "Frozen path access may safely reuse immutable nodes");
            ExampleSupport.require(detached != secondDetachedView,
                    "Every mutable root accessor must return a detached graph");
            return new Result(
                    blueIdBeforeMutation,
                    frozenMessage,
                    detached,
                    secondDetachedView);
        }
    }

    /** Runs from a shell and prints the immutable snapshot identity. */
    public static void main(String[] args) {
        System.out.println(run().getBlueId());
    }

    /** Result retaining safe frozen state and independent mutable views. */
    public static final class Result {
        private final String blueId;
        private final FrozenNode frozenMessage;
        private final Node mutatedDetachedView;
        private final Node freshDetachedView;

        private Result(
                String blueId,
                FrozenNode frozenMessage,
                Node mutatedDetachedView,
                Node freshDetachedView) {
            this.blueId = blueId;
            this.frozenMessage = frozenMessage;
            this.mutatedDetachedView = mutatedDetachedView;
            this.freshDetachedView = freshDetachedView;
        }

        public String getBlueId() {
            return blueId;
        }

        public FrozenNode getFrozenMessage() {
            return frozenMessage;
        }

        public Node getMutatedDetachedView() {
            return mutatedDetachedView.clone();
        }

        public Node getFreshDetachedView() {
            return freshDetachedView.clone();
        }
    }
}
