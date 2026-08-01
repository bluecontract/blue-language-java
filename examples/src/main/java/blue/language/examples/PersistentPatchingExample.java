package blue.language.examples;

import blue.language.merge.ResolvedSnapshot;
import blue.language.model.Node;
import blue.language.runtime.BlueLanguage;
import blue.language.snapshot.FrozenNode;
import blue.language.snapshot.ImmutableBluePatch;

/** Replaces one canonical path while retaining the old snapshot and unchanged spine. */
public final class PersistentPatchingExample {

    private static final String LEFT_FIELD = "left";
    private static final String RIGHT_FIELD = "right";
    private static final String RIGHT_POINTER = "/right";
    private static final String LEFT_VALUE = "unchanged";
    private static final String BEFORE_VALUE = "before";
    private static final String AFTER_VALUE = "after";

    private PersistentPatchingExample() {
    }

    /** Applies one immutable patch and verifies old-state and structural-sharing guarantees. */
    public static Result run() {
        Node canonical = new Node().properties(
                LEFT_FIELD, new Node().value(LEFT_VALUE),
                RIGHT_FIELD, new Node().value(BEFORE_VALUE));
        try (BlueLanguage language = BlueLanguage.builder().build()) {
            ResolvedSnapshot before = language.snapshots().load(canonical);
            String beforeBlueId = before.blueId();
            FrozenNode sharedLeft = before.frozenCanonicalRoot()
                    .property(LEFT_FIELD);

            ResolvedSnapshot after = language.patching().apply(
                    before,
                    ImmutableBluePatch.replace(
                            RIGHT_POINTER,
                            new Node().value(AFTER_VALUE)));

            ExampleSupport.require(BEFORE_VALUE.equals(
                            before.canonicalAt(RIGHT_POINTER).getValue()),
                    "The old snapshot must remain unchanged");
            ExampleSupport.require(AFTER_VALUE.equals(
                            after.canonicalAt(RIGHT_POINTER).getValue()),
                    "The new snapshot must expose the replacement");
            ExampleSupport.require(beforeBlueId.equals(before.blueId()),
                    "The old snapshot identity must remain stable");
            ExampleSupport.require(!beforeBlueId.equals(after.blueId()),
                    "Changing canonical content must change identity");
            ExampleSupport.require(sharedLeft == after.frozenCanonicalRoot()
                            .property(LEFT_FIELD),
                    "Persistent patching must share the unchanged branch");
            return new Result(before, after, sharedLeft);
        }
    }

    /** Runs from a shell and prints the new snapshot identity. */
    public static void main(String[] args) {
        System.out.println(run().getAfterBlueId());
    }

    /** Immutable summary of the persistent patch operation. */
    public static final class Result {
        private final ResolvedSnapshot before;
        private final ResolvedSnapshot after;
        private final FrozenNode sharedLeft;

        private Result(
                ResolvedSnapshot before,
                ResolvedSnapshot after,
                FrozenNode sharedLeft) {
            this.before = before;
            this.after = after;
            this.sharedLeft = sharedLeft;
        }

        public String getBeforeBlueId() {
            return before.blueId();
        }

        public String getAfterBlueId() {
            return after.blueId();
        }

        public Object getBeforeRightValue() {
            return before.canonicalAt(RIGHT_POINTER).getValue();
        }

        public Object getAfterRightValue() {
            return after.canonicalAt(RIGHT_POINTER).getValue();
        }

        public boolean isLeftBranchShared() {
            return sharedLeft == after.frozenCanonicalRoot()
                    .property(LEFT_FIELD);
        }
    }
}
