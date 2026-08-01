package blue.language.examples;

import blue.language.identity.CanonicalJsonHasher;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.identity.ListBlueIdFold;
import blue.language.model.Node;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Reuses an established list prefix identity for append and suffix recomputation. */
public final class IncrementalListIdentityExample {

    private static final String FIRST_VALUE = "A";
    private static final String SECOND_VALUE = "B";
    private static final String UPDATED_SECOND_VALUE = "B2";
    private static final String THIRD_VALUE = "C";

    private IncrementalListIdentityExample() {
    }

    /** Applies the normative recursive list fold without rehashing an unchanged prefix. */
    public static Result run() {
        Node first = new Node().value(FIRST_VALUE);
        Node second = new Node().value(SECOND_VALUE);
        Node updatedSecond = new Node().value(UPDATED_SECOND_VALUE);
        Node third = new Node().value(THIRD_VALUE);
        ListBlueIdFold fold = new ListBlueIdFold(new CanonicalJsonHasher());

        String establishedPrefixBlueId =
                DirectBlueIdCalculator.calculateBlueId(
                        Arrays.asList(first, second));
        String appendedBlueId = fold.appendBlueId(
                establishedPrefixBlueId,
                DirectBlueIdCalculator.calculateBlueId(third));
        String completeBlueId = DirectBlueIdCalculator.calculateBlueId(
                Arrays.asList(first, second, third));

        String unchangedFirstPrefixBlueId =
                DirectBlueIdCalculator.calculateBlueId(
                        Collections.singletonList(first));
        List<String> changedSuffixBlueIds = Arrays.asList(
                DirectBlueIdCalculator.calculateBlueId(updatedSecond),
                DirectBlueIdCalculator.calculateBlueId(third));
        String recomputedSuffixBlueId = fold.foldSuffix(
                unchangedFirstPrefixBlueId, changedSuffixBlueIds);
        String updatedCompleteBlueId =
                DirectBlueIdCalculator.calculateBlueId(
                        Arrays.asList(first, updatedSecond, third));

        ExampleSupport.require(completeBlueId.equals(appendedBlueId),
                "One append step must equal direct whole-list identity");
        ExampleSupport.require(updatedCompleteBlueId.equals(
                        recomputedSuffixBlueId),
                "An earlier edit must recompute only the affected suffix");
        return new Result(
                establishedPrefixBlueId,
                appendedBlueId,
                recomputedSuffixBlueId,
                updatedCompleteBlueId);
    }

    /** Runs from a shell and prints the appended list identity. */
    public static void main(String[] args) {
        System.out.println(run().getAppendedBlueId());
    }

    /** Immutable identities from append and earlier-edit paths. */
    public static final class Result {
        private final String prefixBlueId;
        private final String appendedBlueId;
        private final String recomputedSuffixBlueId;
        private final String updatedCompleteBlueId;

        private Result(
                String prefixBlueId,
                String appendedBlueId,
                String recomputedSuffixBlueId,
                String updatedCompleteBlueId) {
            this.prefixBlueId = prefixBlueId;
            this.appendedBlueId = appendedBlueId;
            this.recomputedSuffixBlueId = recomputedSuffixBlueId;
            this.updatedCompleteBlueId = updatedCompleteBlueId;
        }

        public String getPrefixBlueId() {
            return prefixBlueId;
        }

        public String getAppendedBlueId() {
            return appendedBlueId;
        }

        public String getRecomputedSuffixBlueId() {
            return recomputedSuffixBlueId;
        }

        public String getUpdatedCompleteBlueId() {
            return updatedCompleteBlueId;
        }
    }
}
