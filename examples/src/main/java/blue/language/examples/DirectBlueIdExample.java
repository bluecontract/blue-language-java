package blue.language.examples;

import blue.language.codec.BlueFormat;
import blue.language.model.Node;
import blue.language.runtime.BlueLanguage;

/** Calculates one normative direct BlueId from equivalent exact inputs. */
public final class DirectBlueIdExample {

    /** Released Blue Language 1.0 vector for the numeric scalar {@code 1}. */
    public static final String INTEGER_ONE_BLUE_ID =
            "GhNUbi6oXA1HArr2uTqwpcgegPv8kxUuj11riBtoMJXz";

    private static final String INLINE_INPUT = "1";
    private static final String WRAPPED_INPUT = "value: 1";

    private DirectBlueIdExample() {
    }

    /**
     * Runs the exact-input path without preprocessing or resolution.
     *
     * @return the identities calculated from the inline and wrapped inputs
     */
    public static Result run() {
        try (BlueLanguage language = BlueLanguage.builder().build()) {
            Node inline = language.codec().parseBlueIdInput(
                    INLINE_INPUT, BlueFormat.YAML);
            Node wrapped = language.codec().parseBlueIdInput(
                    WRAPPED_INPUT, BlueFormat.YAML);

            String inlineBlueId = language.identity().directBlueId(inline);
            String wrappedBlueId = language.identity().directBlueId(wrapped);

            ExampleSupport.require(INTEGER_ONE_BLUE_ID.equals(inlineBlueId),
                    "Inline scalar must match the released direct vector");
            ExampleSupport.require(inlineBlueId.equals(wrappedBlueId),
                    "Inline and wrapped exact inputs must have one identity");
            return new Result(inlineBlueId, wrappedBlueId);
        }
    }

    /**
     * Runs from a shell and prints the direct BlueId.
     *
     * @param args command-line arguments, which this example ignores
     */
    public static void main(String[] args) {
        System.out.println(run().getInlineBlueId());
    }

    /** Immutable identities produced from the two equivalent wire forms. */
    public static final class Result {
        private final String inlineBlueId;
        private final String wrappedBlueId;

        private Result(String inlineBlueId, String wrappedBlueId) {
            this.inlineBlueId = inlineBlueId;
            this.wrappedBlueId = wrappedBlueId;
        }

        /**
         * Returns the BlueId calculated from the inline scalar input.
         *
         * @return the inline input's direct BlueId
         */
        public String getInlineBlueId() {
            return inlineBlueId;
        }

        /**
         * Returns the BlueId calculated from the wrapped scalar input.
         *
         * @return the wrapped input's direct BlueId
         */
        public String getWrappedBlueId() {
            return wrappedBlueId;
        }
    }
}
