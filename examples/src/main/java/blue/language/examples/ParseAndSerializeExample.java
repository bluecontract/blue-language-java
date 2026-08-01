package blue.language.examples;

import blue.language.codec.BlueFormat;
import blue.language.model.Node;
import blue.language.runtime.BlueLanguage;

import static blue.language.model.wire.BlueLanguageConstants.TEXT_TYPE_BLUE_ID;

/** Parses one Source Document and transports it through JSON and YAML. */
public final class ParseAndSerializeExample {

    private static final String SOURCE_YAML =
            "type:\n"
                    + "  blueId: " + TEXT_TYPE_BLUE_ID + "\n"
                    + "value: hello\n";

    private ParseAndSerializeExample() {
    }

    /**
     * Runs the example and verifies that transport format does not change identity.
     *
     * @return the serialized forms, stable identity, and parsed scalar value
     */
    public static Result run() {
        try (BlueLanguage language = BlueLanguage.builder().build()) {
            Node source = language.codec().parseSource(
                    SOURCE_YAML, BlueFormat.YAML);
            String json = language.codec().write(source, BlueFormat.JSON);
            String yaml = language.codec().write(source, BlueFormat.YAML);
            Node fromJson = language.codec().parseSource(
                    json, BlueFormat.JSON);
            Node fromYaml = language.codec().parseSource(
                    yaml, BlueFormat.YAML);

            String sourceBlueId = language.identity()
                    .sourceDocumentBlueId(source);
            String jsonBlueId = language.identity()
                    .sourceDocumentBlueId(fromJson);
            String yamlBlueId = language.identity()
                    .sourceDocumentBlueId(fromYaml);

            ExampleSupport.require("hello".equals(source.getValue()),
                    "The parsed scalar must remain hello");
            ExampleSupport.require(sourceBlueId.equals(jsonBlueId),
                    "JSON transport must preserve Source Document identity");
            ExampleSupport.require(sourceBlueId.equals(yamlBlueId),
                    "YAML transport must preserve Source Document identity");
            return new Result(json, yaml, sourceBlueId, source.getValue());
        }
    }

    /**
     * Runs from a shell and prints the normalized JSON representation.
     *
     * @param args command-line arguments, which this example ignores
     */
    public static void main(String[] args) {
        System.out.println(run().getJson());
    }

    /** Immutable values produced by the parse-and-serialize example. */
    public static final class Result {
        private final String json;
        private final String yaml;
        private final String blueId;
        private final Object value;

        private Result(String json, String yaml, String blueId, Object value) {
            this.json = json;
            this.yaml = yaml;
            this.blueId = blueId;
            this.value = value;
        }

        /**
         * Returns the normalized JSON representation.
         *
         * @return the serialized JSON
         */
        public String getJson() {
            return json;
        }

        /**
         * Returns the normalized YAML representation.
         *
         * @return the serialized YAML
         */
        public String getYaml() {
            return yaml;
        }

        /**
         * Returns the Source Document identity shared by both transports.
         *
         * @return the Source Document BlueId
         */
        public String getBlueId() {
            return blueId;
        }

        /**
         * Returns the scalar value parsed from the Source Document.
         *
         * @return the parsed scalar value
         */
        public Object getValue() {
            return value;
        }
    }
}
