package blue.language.examples;

import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.preprocess.provider.BasicNodeProvider;
import blue.language.runtime.BlueLanguage;

import static blue.language.model.wire.BlueLanguageConstants.DICTIONARY_TYPE_BLUE_ID;

/** Contrasts an unconstrained field, Dictionary, and a required unconstrained field. */
public final class UnconstrainedFieldExample {

    private static final String PAYLOAD_FIELD = "payload";
    private static final String MEMBER_FIELD = "member";
    private static final String OPTIONAL_TYPE_NAME = "Optional value holder";
    private static final String DICTIONARY_TYPE_NAME = "Dictionary value holder";
    private static final String REQUIRED_TYPE_NAME = "Required value holder";
    private static final String SCALAR_VALUE = "any scalar";
    private static final String MEMBER_VALUE = "dictionary member";

    private UnconstrainedFieldExample() {
    }

    /** Resolves accepted shapes and captures deterministic validation failures. */
    public static Result run() {
        Node optionalType = holderType(
                OPTIONAL_TYPE_NAME,
                new Node().description("Any optional Blue value"));
        Node dictionaryType = holderType(
                DICTIONARY_TYPE_NAME,
                new Node().type(ExampleSupport.reference(
                        DICTIONARY_TYPE_BLUE_ID)));
        Node requiredType = holderType(
                REQUIRED_TYPE_NAME,
                new Node()
                        .description("Any required Blue value")
                        .schema(new Schema().required(true)));
        BasicNodeProvider provider = new BasicNodeProvider(
                optionalType, dictionaryType, requiredType);

        try (BlueLanguage language = BlueLanguage.builder()
                .nodeProvider(provider)
                .build()) {
            Node unconstrainedScalar = language.resolution().resolve(
                    instance(provider, OPTIONAL_TYPE_NAME,
                            new Node().value(SCALAR_VALUE)));
            Node dictionaryObject = language.resolution().resolve(
                    instance(provider, DICTIONARY_TYPE_NAME,
                            new Node().properties(
                                    MEMBER_FIELD,
                                    new Node().value(MEMBER_VALUE))));
            Throwable dictionaryScalarFailure = ExampleSupport.captureFailure(
                    () -> language.resolution().resolve(
                            instance(provider, DICTIONARY_TYPE_NAME,
                                    new Node().value(SCALAR_VALUE))));
            Throwable missingRequiredFailure = ExampleSupport.captureFailure(
                    () -> language.resolution().resolve(
                            instance(provider, REQUIRED_TYPE_NAME, null)));

            Object resolvedScalar = unconstrainedScalar.getProperties()
                    .get(PAYLOAD_FIELD).getValue();
            Object resolvedMember = dictionaryObject.getProperties()
                    .get(PAYLOAD_FIELD).getProperties()
                    .get(MEMBER_FIELD).getValue();
            ExampleSupport.require(SCALAR_VALUE.equals(resolvedScalar),
                    "A field without a type must accept a scalar");
            ExampleSupport.require(MEMBER_VALUE.equals(resolvedMember),
                    "A Dictionary field must accept an object");
            ExampleSupport.require(
                    dictionaryScalarFailure instanceof IllegalArgumentException,
                    "Dictionary must reject scalar payloads deterministically");
            ExampleSupport.require(
                    missingRequiredFailure instanceof IllegalArgumentException,
                    "Required unconstrained fields must reject absence");
            return new Result(
                    resolvedScalar,
                    resolvedMember,
                    dictionaryScalarFailure,
                    missingRequiredFailure);
        }
    }

    private static Node holderType(String name, Node declaration) {
        return new Node().name(name).properties(PAYLOAD_FIELD, declaration);
    }

    private static Node instance(
            BasicNodeProvider provider,
            String typeName,
            Node payload) {
        Node instance = new Node().type(ExampleSupport.reference(
                provider.getBlueIdByName(typeName)));
        if (payload != null) {
            instance.properties(PAYLOAD_FIELD, payload);
        }
        return instance;
    }

    /** Runs from a shell and prints the accepted unconstrained scalar. */
    public static void main(String[] args) {
        System.out.println(run().getResolvedScalar());
    }

    /** Immutable accepted values and captured deterministic failures. */
    public static final class Result {
        private final Object resolvedScalar;
        private final Object resolvedMember;
        private final Throwable dictionaryScalarFailure;
        private final Throwable missingRequiredFailure;

        private Result(
                Object resolvedScalar,
                Object resolvedMember,
                Throwable dictionaryScalarFailure,
                Throwable missingRequiredFailure) {
            this.resolvedScalar = resolvedScalar;
            this.resolvedMember = resolvedMember;
            this.dictionaryScalarFailure = dictionaryScalarFailure;
            this.missingRequiredFailure = missingRequiredFailure;
        }

        public Object getResolvedScalar() {
            return resolvedScalar;
        }

        public Object getResolvedMember() {
            return resolvedMember;
        }

        public Throwable getDictionaryScalarFailure() {
            return dictionaryScalarFailure;
        }

        public Throwable getMissingRequiredFailure() {
            return missingRequiredFailure;
        }
    }
}
