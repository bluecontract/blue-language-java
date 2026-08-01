package blue.language.examples;

import blue.language.codec.BlueFormat;
import blue.language.model.Node;
import blue.language.runtime.BlueLanguage;

import static blue.language.model.wire.BlueLanguageConstants.TEXT_TYPE_BLUE_ID;

/** Shows how authored Source reaches the same direct canonical identity path. */
public final class SourceDocumentBlueIdExample {

    private static final String SOURCE_YAML =
            "blue:\n"
                    + "  imports:\n"
                    + "    Message:\n"
                    + "      blueId: " + TEXT_TYPE_BLUE_ID + "\n"
                    + "type: Message\n"
                    + "value: hello\n";

    private SourceDocumentBlueIdExample() {
    }

    /**
     * Runs preprocess, resolve, canonicalize, and then the direct identity
     * path.
     *
     * @return detached canonical input and the two equivalent BlueIds
     */
    public static Result run() {
        // tag::source-document-blueid[]
        try (BlueLanguage language = BlueLanguage.builder().build()) {
            Node source = language.codec().parseSource(
                    SOURCE_YAML, BlueFormat.YAML);
            Node canonical = language.identity()
                    .canonicalIdentityInput(source);
            String sourceBlueId = language.identity()
                    .sourceDocumentBlueId(source);
            String directBlueId = language.identity()
                    .directBlueId(canonical);

            ExampleSupport.require(canonical.getBlue() == null,
                    "Canonical input must not retain the Source blue directive");
            ExampleSupport.require(TEXT_TYPE_BLUE_ID.equals(
                            canonical.getType().getBlueId()),
                    "The imported alias must resolve to the exact Text type");
            ExampleSupport.require(sourceBlueId.equals(directBlueId),
                    "Source identity must finish on the direct identity path");
            return new Result(canonical, sourceBlueId, directBlueId);
        }
        // end::source-document-blueid[]
    }

    /**
     * Runs from a shell and prints the Source Document BlueId.
     *
     * @param args ignored command-line arguments
     */
    public static void main(String[] args) {
        System.out.println(run().getSourceBlueId());
    }

    /** Immutable result containing a detached canonical input and both IDs. */
    public static final class Result {
        private final Node canonical;
        private final String sourceBlueId;
        private final String directBlueId;

        private Result(
                Node canonical,
                String sourceBlueId,
                String directBlueId) {
            this.canonical = canonical.clone();
            this.sourceBlueId = sourceBlueId;
            this.directBlueId = directBlueId;
        }

        /**
         * Returns a detached canonical identity input.
         *
         * @return canonical input copy
         */
        public Node getCanonical() {
            return canonical.clone();
        }

        /**
         * Returns the identity calculated from the authored Source Document.
         *
         * @return Source Document BlueId
         */
        public String getSourceBlueId() {
            return sourceBlueId;
        }

        /**
         * Returns the identity calculated from the canonical direct input.
         *
         * @return direct canonical BlueId
         */
        public String getDirectBlueId() {
            return directBlueId;
        }
    }
}
