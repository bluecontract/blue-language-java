package blue.language.examples;

import blue.language.model.Node;
import blue.language.runtime.BlueLanguage;

import java.util.Arrays;
import java.util.List;

/** Calculates stable member identities for a closed two-document cycle. */
public final class CyclicSetIdentityExample {

    /** Normative prefix for invocation-local indexed {@code this} references. */
    private static final String INDEXED_THIS_PREFIX = "this#";
    private static final String NEXT_FIELD = "next";
    private static final String FIRST_NAME = "A";
    private static final String SECOND_NAME = "B";

    /** Released member vector for the first document in this example. */
    public static final String FIRST_MEMBER_BLUE_ID =
            "C18ETfS2A7MNmBGo67MYaQrRL9TrUSGwvvEu6KoMqC2R#0";

    /** Released member vector for the second document in this example. */
    public static final String SECOND_MEMBER_BLUE_ID =
            "C18ETfS2A7MNmBGo67MYaQrRL9TrUSGwvvEu6KoMqC2R#1";

    private CyclicSetIdentityExample() {
    }

    /**
     * Calculates member BlueIds in caller order from indexed cycle placeholders.
     *
     * @return the released identities of both cyclic-set members in caller order
     */
    public static Result run() {
        Node first = new Node()
                .name(FIRST_NAME)
                .properties(NEXT_FIELD,
                        ExampleSupport.reference(indexedThisPlaceholder(1)));
        Node second = new Node()
                .name(SECOND_NAME)
                .properties(NEXT_FIELD,
                        ExampleSupport.reference(indexedThisPlaceholder(0)));

        try (BlueLanguage language = BlueLanguage.builder().build()) {
            List<String> memberBlueIds = language.identity()
                    .circularBlueIds(Arrays.asList(first, second));

            ExampleSupport.require(Arrays.asList(
                            FIRST_MEMBER_BLUE_ID,
                            SECOND_MEMBER_BLUE_ID).equals(memberBlueIds),
                    "The cyclic-set identities must match released vectors");
            return new Result(memberBlueIds);
        }
    }

    /**
     * Formats the exact non-negative placeholder grammar accepted by the
     * public cyclic identity operation. Keeping this tiny formatter local
     * avoids exposing or depending on an internal utility package.
     */
    private static String indexedThisPlaceholder(int index) {
        if (index < 0) {
            throw new IllegalArgumentException(
                    "Indexed this reference must be non-negative");
        }
        return INDEXED_THIS_PREFIX + index;
    }

    /**
     * Runs from a shell and prints both member identities in caller order.
     *
     * @param args command-line arguments, which this example ignores
     */
    public static void main(String[] args) {
        for (String memberBlueId : run().getMemberBlueIds()) {
            System.out.println(memberBlueId);
        }
    }

    /** Immutable cyclic member identities in caller order. */
    public static final class Result {
        private final List<String> memberBlueIds;

        private Result(List<String> memberBlueIds) {
            this.memberBlueIds = java.util.Collections.unmodifiableList(
                    new java.util.ArrayList<>(memberBlueIds));
        }

        /**
         * Returns the cyclic member BlueIds in the order supplied by the caller.
         *
         * @return an unmodifiable list of member BlueIds
         */
        public List<String> getMemberBlueIds() {
            return memberBlueIds;
        }
    }
}
