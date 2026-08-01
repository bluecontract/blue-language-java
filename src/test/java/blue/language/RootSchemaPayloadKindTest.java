package blue.language;

import blue.language.model.wire.BlueLanguageConstants;

import blue.language.api.BlueCachePolicy;
import blue.language.api.BlueCacheStats;
import blue.language.api.BlueLanguageErrorCategory;
import blue.language.api.BlueLanguageErrorClassifier;
import blue.language.api.BlueLanguageRuntime;
import blue.language.api.BlueOperationLimits;
import blue.language.api.BlueOperationOutcome;
import blue.language.api.BlueOperationResult;
import blue.language.api.BlueViewPath;
import blue.language.api.LanguageRuntimeAccess;
import blue.language.api.WeightedLruCache;
import blue.language.provider.NodeProvider;

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

import static blue.language.processor.FailureCapture.captureFailure;
import static blue.language.model.wire.BlueLanguageConstants.DICTIONARY_TYPE_BLUE_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RootSchemaPayloadKindTest {

    @Test
    void shouldFailEmptyDictionaryRootWhenMinFieldsIsOne() {
        // given
        Blue blue = new Blue();
        Node root = dictionaryRoot(new Schema().minFields(1));

        // when
        Throwable failure = captureFailure(() -> blue.resolve(root));

        // then
        assertSchemaFailure(failure, "/");
        assertTrue(failure.getMessage().contains("Number of fields 0"), failure.getMessage());
    }

    @Test
    void shouldAllowEmptyDictionaryRootWhenMaxFieldsIsZero() {
        // given
        Blue blue = new Blue();
        Node root = dictionaryRoot(new Schema().maxFields(0));

        // when
        Throwable failure = captureFailure(() -> blue.resolve(root));

        // then
        assertNull(failure);
    }

    @Test
    void shouldAllowRequiredEmptyDictionaryRootWhenMaxFieldsIsZero() {
        // given
        Blue blue = new Blue();
        Node root = dictionaryRoot(
                new Schema().required(true).maxFields(0));

        // when
        Throwable failure = captureFailure(() -> blue.resolve(root));

        // then
        assertNull(failure);
    }

    @Test
    void shouldCountOneOrdinaryFieldAtDictionaryRoot() {
        // given
        Node root = dictionaryRoot(new Schema().minFields(1).maxFields(1))
                .properties("field", new Node().value("present"));
        Blue blue = new Blue();

        // when
        Node resolved = blue.resolve(root);

        // then
        assertEquals("present", resolved.getAsText("/field"));
    }

    @Test
    void shouldFailDictionarySubtypeRootWithoutFieldsWhenMinFieldsIsOne() {
        // given
        BasicNodeProvider delegate = new BasicNodeProvider();
        Node subtype = new Node().name("Dictionary Subtype")
                .type(reference(DICTIONARY_TYPE_BLUE_ID));
        delegate.addSingleNodes(subtype);
        String subtypeId = delegate.getBlueIdByName("Dictionary Subtype");
        CountingProvider provider = new CountingProvider(delegate);
        Node root = new Node()
                .type(reference(subtypeId))
                .schema(new Schema().minFields(1));
        Blue blue = new Blue(provider);

        // when
        Throwable failure = captureFailure(() -> blue.resolve(root));

        // then
        assertSchemaFailure(failure, "/");
        assertTrue(failure.getMessage().contains("Number of fields 0"), failure.getMessage());
        assertEquals(1, provider.fetches.get());
    }

    @Test
    void shouldRejectScalarRootWithMinFieldsAsWrongKind() {
        // given
        Node root = new Node()
                .value("scalar")
                .schema(new Schema().minFields(0));
        Blue blue = new Blue();

        // when
        Throwable failure = captureFailure(() -> blue.resolve(root));

        // then
        assertSchemaFailure(failure, "/");
        assertTrue(failure.getMessage().contains("wrong kind"), failure.getMessage());
    }

    @Test
    void shouldRejectMetadataOnlyUntypedRootWithMinFieldsAsWrongKind() {
        // given
        Node root = new Node()
                .description("metadata only")
                .schema(new Schema().minFields(0));
        Blue blue = new Blue();

        // when
        Throwable failure = captureFailure(() -> blue.resolve(root));

        // then
        assertSchemaFailure(failure, "/");
        assertTrue(failure.getMessage().contains("wrong kind"), failure.getMessage());
    }

    @Test
    void shouldSkipMinFieldsForOmittedOptionalDictionaryChild() {
        // given
        BasicNodeProvider provider = new BasicNodeProvider();
        Node type = new Node().name("Optional Dictionary Holder")
                .properties("optional", new Node()
                        .type(reference(DICTIONARY_TYPE_BLUE_ID))
                        .schema(new Schema().minFields(1)));
        provider.addSingleNodes(type);
        String typeId = provider.getBlueIdByName("Optional Dictionary Holder");
        Blue blue = new Blue(provider);
        Node instance = new Node().type(reference(typeId));

        // when
        Throwable failure = captureFailure(() -> blue.resolve(instance));

        // then
        assertNull(failure);
    }

    @Test
    void shouldRejectRequiredEmptyDictionaryChildAsMissing() {
        // given
        BasicNodeProvider provider = new BasicNodeProvider();
        Node type = new Node().name("Required Dictionary Holder")
                .properties("required", new Node()
                        .type(reference(DICTIONARY_TYPE_BLUE_ID))
                        .schema(new Schema().required(true).maxFields(0)));
        provider.addSingleNodes(type);
        String typeId = provider.getBlueIdByName("Required Dictionary Holder");
        Node instance = new Node().type(reference(typeId))
                .properties("required", new Node());
        Blue blue = new Blue(provider);

        // when
        Throwable failure = captureFailure(
                () -> blue.resolve(instance));

        // then
        assertSchemaFailure(failure, "/required");
        assertTrue(failure.getMessage().contains("Required node"), failure.getMessage());
    }

    @Test
    void shouldCompleteRootCandidateValidationExactlyOnce() {
        // given
        CountingSchemaVerifier verifier = new CountingSchemaVerifier();
        CountingProvider provider = new CountingProvider(blueId -> null);
        Blue blue = new Blue(provider, processor(verifier));
        Node root = dictionaryRoot(new Schema().maxFields(0));

        // when
        Throwable failure = captureFailure(() -> blue.resolve(root));

        // then
        assertNull(failure);
        assertEquals(1, verifier.completedValidations.get());
        assertEquals(0, provider.fetches.get());
    }

    private static Node dictionaryRoot(Schema schema) {
        return new Node().type(reference(DICTIONARY_TYPE_BLUE_ID)).schema(schema);
    }

    private static void assertSchemaFailure(Throwable failure, String path) {
        assertInstanceOf(IllegalArgumentException.class, failure);
        assertTrue(failure.getMessage().contains("path " + path + ":"), failure.getMessage());
        assertEquals(BlueLanguageErrorCategory.SchemaViolation,
                BlueLanguageErrorClassifier.classify(failure));
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
