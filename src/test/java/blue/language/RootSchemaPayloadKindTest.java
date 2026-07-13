package blue.language;

import blue.language.merge.MergingProcessor;
import blue.language.merge.processor.BasicTypesVerifier;
import blue.language.merge.processor.DictionaryProcessor;
import blue.language.merge.processor.ListProcessor;
import blue.language.merge.processor.SchemaPropagator;
import blue.language.merge.processor.SchemaVerifier;
import blue.language.merge.processor.SequentialMergingProcessor;
import blue.language.merge.processor.TypeAssigner;
import blue.language.merge.processor.ValuePropagator;
import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.provider.BasicNodeProvider;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static blue.language.utils.Properties.DICTIONARY_TYPE_BLUE_ID;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RootSchemaPayloadKindTest {

    @Test
    void emptyDictionaryRootFailsMinFieldsOne() {
        IllegalArgumentException failure = assertSchemaFailure(() -> new Blue().resolve(
                dictionaryRoot(new Schema().minFields(1))), "/");

        assertTrue(failure.getMessage().contains("Number of fields 0"), failure.getMessage());
    }

    @Test
    void emptyDictionaryRootPassesMaxFieldsZero() {
        assertDoesNotThrow(() -> new Blue().resolve(
                dictionaryRoot(new Schema().maxFields(0))));
    }

    @Test
    void emptyDictionaryRootPassesRequiredAndMaxFieldsZero() {
        assertDoesNotThrow(() -> new Blue().resolve(
                dictionaryRoot(new Schema().required(true).maxFields(0))));
    }

    @Test
    void dictionaryRootCountsOneOrdinaryField() {
        Node root = dictionaryRoot(new Schema().minFields(1).maxFields(1))
                .properties("field", new Node().value("present"));

        Node resolved = assertDoesNotThrow(() -> new Blue().resolve(root));

        assertEquals("present", resolved.getAsText("/field"));
    }

    @Test
    void dictionarySubtypeRootWithNoFieldsFailsMinFieldsOne() {
        BasicNodeProvider delegate = new BasicNodeProvider();
        Node subtype = new Node().name("Dictionary Subtype")
                .type(reference(DICTIONARY_TYPE_BLUE_ID));
        delegate.addSingleNodes(subtype);
        String subtypeId = delegate.getBlueIdByName("Dictionary Subtype");
        CountingProvider provider = new CountingProvider(delegate);

        IllegalArgumentException failure = assertSchemaFailure(() -> new Blue(provider).resolve(
                new Node().type(reference(subtypeId)).schema(new Schema().minFields(1))), "/");

        assertTrue(failure.getMessage().contains("Number of fields 0"), failure.getMessage());
        assertEquals(1, provider.fetches.get());
    }

    @Test
    void scalarRootWithMinFieldsFailsWrongKind() {
        IllegalArgumentException failure = assertSchemaFailure(() -> new Blue().resolve(
                new Node().value("scalar").schema(new Schema().minFields(0))), "/");

        assertTrue(failure.getMessage().contains("wrong kind"), failure.getMessage());
    }

    @Test
    void metadataOnlyUntypedRootWithMinFieldsFailsWrongKind() {
        IllegalArgumentException failure = assertSchemaFailure(() -> new Blue().resolve(
                new Node().description("metadata only").schema(new Schema().minFields(0))), "/");

        assertTrue(failure.getMessage().contains("wrong kind"), failure.getMessage());
    }

    @Test
    void omittedOptionalDictionaryChildStillSkipsMinFields() {
        BasicNodeProvider provider = new BasicNodeProvider();
        Node type = new Node().name("Optional Dictionary Holder")
                .properties("optional", new Node()
                        .type(reference(DICTIONARY_TYPE_BLUE_ID))
                        .schema(new Schema().minFields(1)));
        provider.addSingleNodes(type);
        String typeId = provider.getBlueIdByName("Optional Dictionary Holder");

        assertDoesNotThrow(() -> new Blue(provider).resolve(
                new Node().type(reference(typeId))));
    }

    @Test
    void requiredEmptyDictionaryChildStillFailsPresence() {
        BasicNodeProvider provider = new BasicNodeProvider();
        Node type = new Node().name("Required Dictionary Holder")
                .properties("required", new Node()
                        .type(reference(DICTIONARY_TYPE_BLUE_ID))
                        .schema(new Schema().required(true).maxFields(0)));
        provider.addSingleNodes(type);
        String typeId = provider.getBlueIdByName("Required Dictionary Holder");
        Node instance = new Node().type(reference(typeId))
                .properties("required", new Node());

        IllegalArgumentException failure = assertSchemaFailure(
                () -> new Blue(provider).resolve(instance), "/required");

        assertTrue(failure.getMessage().contains("Required node"), failure.getMessage());
    }

    @Test
    void rootCandidateReceivesCompletedValidationExactlyOnce() {
        CountingSchemaVerifier verifier = new CountingSchemaVerifier();
        CountingProvider provider = new CountingProvider(blueId -> null);
        Blue blue = new Blue(provider, processor(verifier));

        assertDoesNotThrow(() -> blue.resolve(dictionaryRoot(new Schema().maxFields(0))));

        assertEquals(1, verifier.completedValidations.get());
        assertEquals(0, provider.fetches.get());
    }

    private static Node dictionaryRoot(Schema schema) {
        return new Node().type(reference(DICTIONARY_TYPE_BLUE_ID)).schema(schema);
    }

    private static IllegalArgumentException assertSchemaFailure(ThrowingAction action, String path) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, action::run);
        assertTrue(failure.getMessage().contains("path " + path + ":"), failure.getMessage());
        assertEquals(BlueLanguageErrorCategory.SchemaViolation,
                BlueLanguageErrorClassifier.classify(failure));
        return failure;
    }

    private static MergingProcessor processor(SchemaVerifier verifier) {
        return new SequentialMergingProcessor(Arrays.asList(
                new ValuePropagator(),
                new TypeAssigner(),
                new ListProcessor(),
                new DictionaryProcessor(),
                new SchemaPropagator(),
                verifier,
                new BasicTypesVerifier()));
    }

    private static Node reference(String blueId) {
        return new Node().blueId(blueId);
    }

    private interface ThrowingAction {
        void run();
    }

    private static final class CountingSchemaVerifier extends SchemaVerifier {
        private final AtomicInteger completedValidations = new AtomicInteger();

        @Override
        protected void onCompletedValidation(Node node, String path) {
            completedValidations.incrementAndGet();
        }
    }

    private static final class CountingProvider implements NodeProvider {
        private final NodeProvider delegate;
        private final AtomicInteger fetches = new AtomicInteger();

        private CountingProvider(NodeProvider delegate) {
            this.delegate = delegate;
        }

        @Override
        public List<Node> fetchByBlueId(String blueId) {
            fetches.incrementAndGet();
            return delegate.fetchByBlueId(blueId);
        }
    }
}
