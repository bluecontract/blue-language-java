package blue.language.examples;

import blue.language.model.Node;
import blue.language.preprocess.provider.BasicNodeProvider;
import blue.language.runtime.BlueLanguage;

/** Compares resolved, canonical, and minimized forms of one typed Source. */
public final class SemanticFormsExample {

    private static final String TYPE_NAME = "Message type";
    private static final String INHERITED_FIELD = "inherited";
    private static final String LOCAL_FIELD = "local";
    private static final String INHERITED_VALUE = "from type";
    private static final String LOCAL_VALUE = "from source";

    private SemanticFormsExample() {
    }

    /**
     * Resolves meaning, calculates canonical identity input, and minimizes the
     * authoring form.
     *
     * @return detached resolved, canonical, and minimized semantic forms
     */
    public static Result run() {
        Node type = new Node()
                .name(TYPE_NAME)
                .properties(INHERITED_FIELD,
                        new Node().value(INHERITED_VALUE));
        BasicNodeProvider provider = new BasicNodeProvider(type);
        String typeBlueId = provider.getBlueIdByName(TYPE_NAME);
        Node source = new Node()
                .type(ExampleSupport.reference(typeBlueId))
                .properties(
                        INHERITED_FIELD, new Node().value(INHERITED_VALUE),
                        LOCAL_FIELD, new Node().value(LOCAL_VALUE));

        try (BlueLanguage language = BlueLanguage.builder()
                .nodeProvider(provider)
                .build()) {
            Node resolved = language.resolution().resolve(source);
            Node canonical = language.identity()
                    .canonicalIdentityInput(source);
            Node minimized = language.resolution().minimize(source);
            String sourceBlueId = language.identity()
                    .sourceDocumentBlueId(source);
            String canonicalBlueId = language.identity()
                    .directBlueId(canonical);
            String minimizedBlueId = language.identity()
                    .sourceDocumentBlueId(minimized);

            ExampleSupport.require(INHERITED_VALUE.equals(
                            resolved.getProperties().get(
                                    INHERITED_FIELD).getValue()),
                    "Resolution must expose type-provided content");
            ExampleSupport.require(LOCAL_VALUE.equals(
                            resolved.getProperties().get(LOCAL_FIELD).getValue()),
                    "Resolution must retain Source-provided content");
            ExampleSupport.require(!canonical.getProperties()
                            .containsKey(INHERITED_FIELD),
                    "Canonical identity input must omit redundant inheritance");
            ExampleSupport.require(sourceBlueId.equals(canonicalBlueId),
                    "Source and canonical paths must reach the same identity");
            ExampleSupport.require(sourceBlueId.equals(minimizedBlueId),
                    "The smaller authored form must preserve Source identity");
            return new Result(
                    resolved, canonical, minimized, sourceBlueId);
        }
    }

    /**
     * Runs from a shell and prints the common Source Document BlueId.
     *
     * @param args ignored command-line arguments
     */
    public static void main(String[] args) {
        System.out.println(run().getBlueId());
    }

    /** Immutable detached views of the three semantic forms. */
    public static final class Result {
        private final Node resolved;
        private final Node canonical;
        private final Node minimized;
        private final String blueId;

        private Result(
                Node resolved,
                Node canonical,
                Node minimized,
                String blueId) {
            this.resolved = resolved.clone();
            this.canonical = canonical.clone();
            this.minimized = minimized.clone();
            this.blueId = blueId;
        }

        /**
         * Returns a detached resolved view.
         *
         * @return resolved document copy
         */
        public Node getResolved() {
            return resolved.clone();
        }

        /**
         * Returns a detached canonical identity input.
         *
         * @return canonical document copy
         */
        public Node getCanonical() {
            return canonical.clone();
        }

        /**
         * Returns a detached minimized authoring form.
         *
         * @return minimized document copy
         */
        public Node getMinimized() {
            return minimized.clone();
        }

        /**
         * Returns the identity shared by all three semantic forms.
         *
         * @return Source Document BlueId
         */
        public String getBlueId() {
            return blueId;
        }
    }
}
