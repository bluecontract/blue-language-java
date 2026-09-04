package blue.language;

import blue.language.api.BlueCachePolicy;
import blue.language.api.BlueCacheStats;
import blue.language.api.BlueLanguageErrorCategory;
import blue.language.api.BlueLanguageErrorClassifier;
import blue.language.api.BlueOperationLimits;
import blue.language.api.BlueOperationOutcome;
import blue.language.api.BlueOperationResult;
import blue.language.api.BlueViewPath;
import blue.language.runtime.LanguageRuntimeAccess;
import blue.language.provider.NodeProvider;

import static blue.language.processor.DocumentProcessingResultTestSupport.*;

import blue.language.model.Node;
import blue.language.model.Nodes;
import blue.language.model.Schema;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.ProcessorErrorCategory;
import blue.language.processor.ProcessorStatus;
import blue.language.preprocess.provider.BasicNodeProvider;
import blue.language.provider.CyclicAwareNodeProvider;
import blue.language.provider.CyclicSetProofResult;
import blue.language.provider.VerifyingNodeProvider;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.identity.BlueIdReferenceValidator;
import blue.language.identity.BlueIds;
import blue.language.model.wire.JsonPointer;
import blue.language.registry.NodeProviderWrapper;
import blue.language.resolve.ResolutionLimits;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static blue.language.processor.FailureCapture.captureFailure;
import static blue.language.codec.jackson.UncheckedObjectMapper.YAML_MAPPER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class ReferenceBlueIdResolutionValidationTest {

    private static final String MALFORMED_BLUE_ID = "symbolic-type-name";

    @Test
    void shouldFailUnmaterializedMalformedReferenceBeforeOrdinaryProviderLookup() {
        // given
        AtomicInteger fetches = new AtomicInteger();
        Blue blue = new Blue(countingMiss(fetches));

        // when
        RuntimeException failure = captureFailure(
                () -> blue.resolve(nestedMalformedReference()));
        int fetchCount = fetches.get();

        // then
        assertTrue(failure instanceof RuntimeException);
        assertFailure(failure, BlueLanguageErrorCategory.InvalidBlueId, "/subject/blueId");
        assertEquals(0, fetchCount);
    }

    @Test
    void shouldFailUnmaterializedMalformedReferenceBeforeTrustedProviderLookup() {
        // given
        AtomicInteger fetches = new AtomicInteger();
        Blue blue = new Blue(new VerifyingNodeProvider(countingMiss(fetches)));

        // when
        RuntimeException failure = captureFailure(
                () -> blue.resolve(nestedMalformedReference()));
        int fetchCount = fetches.get();

        // then
        assertTrue(failure instanceof RuntimeException);
        assertFailure(failure, BlueLanguageErrorCategory.InvalidBlueId, "/subject/blueId");
        assertEquals(0, fetchCount);
    }

    @Test
    void shouldKeepMalformedTypeFailureProviderInvariantDuringDirectResolution() {
        // given
        AtomicInteger ordinaryFetches = new AtomicInteger();
        AtomicInteger trustedFetches = new AtomicInteger();
        Blue ordinary = new Blue(countingMiss(ordinaryFetches));
        Blue trusted = new Blue(
                new VerifyingNodeProvider(countingMiss(trustedFetches)));

        // when
        RuntimeException ordinaryFailure = captureFailure(
                () -> ordinary.resolve(malformedTypeDocument(false)));
        RuntimeException trustedFailure = captureFailure(
                () -> trusted.resolve(malformedTypeDocument(false)));
        int ordinaryFetchCount = ordinaryFetches.get();
        int trustedFetchCount = trustedFetches.get();

        // then
        assertTrue(ordinaryFailure instanceof RuntimeException);
        assertTrue(trustedFailure instanceof RuntimeException);
        assertFailure(ordinaryFailure, BlueLanguageErrorCategory.InvalidBlueId, "/type/blueId");
        assertFailure(trustedFailure, BlueLanguageErrorCategory.InvalidBlueId, "/type/blueId");
        assertEquals(0, ordinaryFetchCount);
        assertEquals(0, trustedFetchCount);
    }

    @Test
    void shouldKeepMalformedTypeFailureProviderInvariantDuringInitialization() {
        // given
        AtomicInteger ordinaryFetches = new AtomicInteger();
        AtomicInteger trustedFetches = new AtomicInteger();
        Blue ordinary = new Blue(countingMiss(ordinaryFetches));
        Blue trusted = new Blue(
                new VerifyingNodeProvider(countingMiss(trustedFetches)));

        // when
        DocumentProcessingResult ordinaryResult =
                ordinary.initializeDocument(malformedTypeDocument(true));
        DocumentProcessingResult trustedResult =
                trusted.initializeDocument(malformedTypeDocument(true));
        int ordinaryFetchCount = ordinaryFetches.get();
        int trustedFetchCount = trustedFetches.get();

        // then
        assertEquals(ProcessorStatus.INVALID_PROCESSING_DOCUMENT,
                ordinaryResult.status(), diagnosticMessage(ordinaryResult));
        assertEquals(ProcessorErrorCategory.InvalidProcessingDocument,
                diagnosticCategory(ordinaryResult), diagnosticMessage(ordinaryResult));
        assertTrue(diagnosticMessage(ordinaryResult).contains("/type/blueId"),
                diagnosticMessage(ordinaryResult));
        assertEquals(ProcessorStatus.INVALID_PROCESSING_DOCUMENT,
                trustedResult.status(), diagnosticMessage(trustedResult));
        assertEquals(ProcessorErrorCategory.InvalidProcessingDocument,
                diagnosticCategory(trustedResult), diagnosticMessage(trustedResult));
        assertTrue(diagnosticMessage(trustedResult).contains("/type/blueId"),
                diagnosticMessage(trustedResult));
        assertEquals(0, ordinaryFetchCount);
        assertEquals(0, trustedFetchCount);
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("malformedReferenceContainers")
    void shouldValidateMalformedReferencesInEveryNodeContainer(
            Node source,
            String expectedPath) {
        // given
        Blue blue = new Blue();

        // when
        RuntimeException failure = captureFailure(() -> blue.resolve(source));

        // then
        assertTrue(failure instanceof RuntimeException);
        assertFailure(failure, BlueLanguageErrorCategory.InvalidBlueId, expectedPath);
    }

    @Test
    void shouldFailMalformedReferenceUnderExcludedResolutionPath() {
        // given
        AtomicInteger fetches = new AtomicInteger();
        Blue blue = new Blue(countingMiss(fetches));
        Node source = new Node()
                .properties("included", new Node().value("visible"))
                .properties("excluded", malformedReference());

        // when
        RuntimeException failure = captureFailure(
                () -> blue.resolve(source, ResolutionLimits.withSinglePath("/included")));
        int fetchCount = fetches.get();

        // then
        assertTrue(failure instanceof RuntimeException);
        assertFailure(failure, BlueLanguageErrorCategory.InvalidBlueId, "/excluded/blueId");
        assertEquals(0, fetchCount);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "requested",
            "provider returned no content",
            "wrong blueId",
            "field$previous"
    })
    void shouldKeepMalformedReferenceCategoryIndependentOfFieldName(String fieldName) {
        // given
        AtomicInteger fetches = new AtomicInteger();
        Blue blue = new Blue(countingMiss(fetches));

        // when
        RuntimeException failure = captureFailure(
                () -> blue.resolve(new Node().properties(fieldName, malformedReference())));
        int fetchCount = fetches.get();

        // then
        assertTrue(failure instanceof RuntimeException);
        assertFailure(failure, BlueLanguageErrorCategory.InvalidBlueId,
                "/" + JsonPointer.escape(fieldName) + "/blueId");
        assertEquals(0, fetchCount);
    }

    @Test
    void shouldClassifyOnlyActualPreviousPathAsListControlViolation() {
        // given
        AtomicInteger fetches = new AtomicInteger();
        Blue blue = new Blue(countingMiss(fetches));
        Node previousSource = new Node().items(
                new Node().previousBlueId(MALFORMED_BLUE_ID),
                new Node().value("appended"));
        Node ordinarySource = new Node().properties("field$previous", malformedReference());

        // when
        RuntimeException previousFailure = captureFailure(
                () -> blue.resolve(previousSource));
        RuntimeException ordinaryFailure = captureFailure(
                () -> blue.resolve(ordinarySource));
        int fetchCount = fetches.get();

        // then
        assertTrue(previousFailure instanceof RuntimeException);
        assertTrue(ordinaryFailure instanceof RuntimeException);
        assertFailure(previousFailure, BlueLanguageErrorCategory.ListControlViolation,
                "/0/$previous/blueId");
        assertFailure(ordinaryFailure, BlueLanguageErrorCategory.InvalidBlueId,
                "/field$previous/blueId");
        assertEquals(0, fetchCount);
    }

    @Test
    @ResourceLock(Resources.LOCALE)
    void shouldKeepMalformedReferenceClassificationLocaleIndependent() {
        // given
        Locale original = Locale.getDefault();
        AtomicInteger fetches = new AtomicInteger();

        // when
        RuntimeException failure;
        int fetchCount;
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            failure = captureFailure(
                    () -> new Blue(countingMiss(fetches)).resolve(nestedMalformedReference()));
            fetchCount = fetches.get();
        } finally {
            Locale.setDefault(original);
        }

        // then
        assertTrue(failure instanceof RuntimeException);
        assertFailure(failure, BlueLanguageErrorCategory.InvalidBlueId,
                "/subject/blueId");
        assertEquals(0, fetchCount);
    }

    @Test
    void shouldDirectBlueIdInputParsersUseTheSharedReferenceValidator() {
        // given
        Blue blue = new Blue();

        // when
        RuntimeException yamlFailure = captureFailure(
                () -> new Blue().parseBlueIdInputYaml(
                        "subject:\n  blueId: " + MALFORMED_BLUE_ID + "\n"));
        RuntimeException jsonFailure = captureFailure(
                () -> blue.parseBlueIdInputJson(
                        "{\"subject\":{\"blueId\":\"" + MALFORMED_BLUE_ID + "\"}}"));

        // then
        assertTrue(yamlFailure instanceof RuntimeException);
        assertTrue(jsonFailure instanceof RuntimeException);
        assertFailure(yamlFailure, BlueLanguageErrorCategory.InvalidBlueId,
                "/subject/blueId");
        assertFailure(jsonFailure, BlueLanguageErrorCategory.InvalidBlueId,
                "/subject/blueId");
    }

    @Test
    void shouldKeepValidMissingReferenceClassifiedAsProviderUnavailable() {
        // given
        String missingBlueId = DirectBlueIdCalculator.calculateBlueId(new Node().name("Missing Type"));
        AtomicInteger fetches = new AtomicInteger();
        Blue blue = new Blue(countingMiss(fetches));

        // when
        RuntimeException failure = captureFailure(
                () -> blue.resolve(new Node().type(reference(missingBlueId))));
        int fetchCount = fetches.get();

        // then
        assertTrue(failure instanceof RuntimeException);
        assertFailure(failure, BlueLanguageErrorCategory.ProviderUnavailable, missingBlueId);
        assertEquals(1, fetchCount);
    }

    @Test
    void shouldKeepValidOrdinaryMismatchClassifiedAsProviderBlueIdMismatch() {
        // given
        String requestedBlueId = DirectBlueIdCalculator.calculateBlueId(new Node().name("Requested Type"));
        AtomicInteger fetches = new AtomicInteger();
        Blue blue = new Blue(blueId -> {
            fetches.incrementAndGet();
            return requestedBlueId.equals(blueId)
                    ? Collections.singletonList(new Node().name("Different Type"))
                    : null;
        });

        // when
        RuntimeException failure = captureFailure(
                () -> blue.resolve(new Node().type(reference(requestedBlueId))));
        int fetchCount = fetches.get();

        // then
        assertTrue(failure instanceof RuntimeException);
        assertFailure(failure, BlueLanguageErrorCategory.ProviderBlueIdMismatch, requestedBlueId);
        assertEquals(1, fetchCount);
    }

    @Test
    void shouldPreventDeprecatedUnverifiedWrapperFromBypassingDirectBlueIdVerification() {
        // given
        Node requested = new Node().name("Requested Trusted Type")
                .properties("fixed", new Node().value("requested"));
        Node trusted = new Node().name("Trusted Non-Direct Type")
                .properties("fixed", new Node().value("trusted"));
        String requestedBlueId = DirectBlueIdCalculator.calculateBlueId(requested);
        AtomicInteger fetches = new AtomicInteger();
        Blue blue = new Blue(NodeProviderWrapper.wrap(blueId -> {
            fetches.incrementAndGet();
            return requestedBlueId.equals(blueId)
                    ? Collections.singletonList(trusted.clone())
                    : null;
        }));

        // when
        RuntimeException failure = captureFailure(
                () -> blue.resolve(new Node().type(reference(requestedBlueId))));
        int fetchCount = fetches.get();
        int referenceCacheSize = blue.resolvedReferenceCacheSize();

        // then
        assertTrue(failure instanceof RuntimeException);
        assertFailure(failure, BlueLanguageErrorCategory.ProviderBlueIdMismatch,
                requestedBlueId);
        assertEquals(1, fetchCount);
        assertEquals(0, referenceCacheSize);
    }

    @Test
    void shouldReachCyclicAwareProviderForValidCyclicMember() {
        // given
        BasicNodeProvider cyclicProvider = new BasicNodeProvider(YAML_MAPPER.readValue(
                "- name: Cyclic A\n"
                        + "  fixed: cyclic\n"
                        + "  peer:\n"
                        + "    blueId: this#1\n"
                        + "- name: Cyclic B\n"
                        + "  fixed: companion\n"
                        + "  peer:\n"
                        + "    blueId: this#0\n",
                Node.class));
        String memberBlueId = cyclicProvider.getBlueIdByName("Cyclic A");
        CountingCyclicProvider countingProvider = new CountingCyclicProvider(cyclicProvider);
        Blue blue = new Blue(countingProvider);

        // when
        Node resolved = blue.resolve(new Node().type(reference(memberBlueId)));
        int fetchCount = countingProvider.fetches.get();

        // then
        assertEquals("Cyclic A", resolved.getType().getName());
        assertEquals("cyclic", resolved.getAsText("/fixed"));
        assertEquals(1, fetchCount);
    }

    @Test
    void shouldRejectMalformedCyclicMemberBeforeProviderLookup() {
        // given
        BasicNodeProvider cyclicProvider = new BasicNodeProvider(YAML_MAPPER.readValue(
                "- name: Cyclic A\n"
                        + "  fixed: cyclic\n"
                        + "  peer:\n"
                        + "    blueId: this#1\n"
                        + "- name: Cyclic B\n"
                        + "  fixed: companion\n"
                        + "  peer:\n"
                        + "    blueId: this#0\n",
                Node.class));
        String memberBlueId = cyclicProvider.getBlueIdByName("Cyclic A");
        AtomicInteger malformedFetches = new AtomicInteger();
        CountingCyclicProvider malformedProvider = new CountingCyclicProvider(
                cyclicProvider, malformedFetches);
        String malformedMember = memberBlueId.substring(0, memberBlueId.indexOf('#')) + "#01";

        // when
        RuntimeException failure = captureFailure(
                () -> new Blue(malformedProvider).resolve(
                        new Node().type(reference(malformedMember))));
        int malformedFetchCount = malformedFetches.get();

        // then
        assertTrue(failure instanceof RuntimeException);
        assertFailure(failure, BlueLanguageErrorCategory.InvalidBlueId, "/type/blueId");
        assertEquals(0, malformedFetchCount);
    }

    @Test
    void shouldPreprocessDeclaredTypeAliasBeforeResolution() {
        // given
        BasicNodeProvider provider = new BasicNodeProvider();
        provider.addSingleNodes(new Node().name("Aliased Subject Type")
                .properties("provided", new Node().value("from-alias")));
        String typeBlueId = provider.getBlueIdByName("Aliased Subject Type");
        Blue blue = new Blue(provider);
        String yaml = "blue:\n"
                + "  imports:\n"
                + "    Subject:\n"
                + "      blueId: " + typeBlueId + "\n"
                + "type: Subject\n";

        Node preprocessed = blue.yamlToNode(yaml);
        // when
        Node resolved = blue.resolve(preprocessed);

        // then
        assertEquals(typeBlueId, preprocessed.getType().getBlueId());
        assertEquals("from-alias", resolved.getAsText("/provided"));
    }

    @Test
    void shouldPreserveDiagnosticControlsInMalformedBlueIdClassifierMappings() {
        // given
        Blue blue = new Blue();

        // when
        RuntimeException malformedPlain = captureFailure(
                () -> BlueIds.requirePlainBlueId(MALFORMED_BLUE_ID, "/subject/blueId"));
        RuntimeException malformedCyclic = captureFailure(
                () -> BlueIds.requireBlueIdOrCyclicMember("abc#01", "/subject/blueId"));
        RuntimeException malformedPrevious = captureFailure(
                () -> BlueIds.requirePlainBlueId(MALFORMED_BLUE_ID, "/$previous/blueId"));
        RuntimeException invalidDirectInput = captureFailure(
                () -> blue.parseBlueIdInputYaml("type: Integer\nvalue: 1\n"));

        // then
        assertTrue(malformedPlain instanceof RuntimeException);
        assertTrue(malformedCyclic instanceof RuntimeException);
        assertTrue(malformedPrevious instanceof RuntimeException);
        assertTrue(invalidDirectInput instanceof RuntimeException);
        assertEquals(BlueLanguageErrorCategory.InvalidBlueId,
                BlueLanguageErrorClassifier.classify(malformedPlain));
        assertEquals(BlueLanguageErrorCategory.InvalidBlueId,
                BlueLanguageErrorClassifier.classify(malformedCyclic));
        assertEquals(BlueLanguageErrorCategory.ListControlViolation,
                BlueLanguageErrorClassifier.classify(malformedPrevious));
        assertEquals(BlueLanguageErrorCategory.InvalidBlueIdInput,
                BlueLanguageErrorClassifier.classify(invalidDirectInput));
    }

    @Test
    void shouldPreserveProviderFailureClassifierMappings() {
        // given
        String missingBlueId = DirectBlueIdCalculator.calculateBlueId(new Node().name("Classifier Missing"));

        // when
        RuntimeException missing = captureFailure(
                () -> new Blue(blueId -> null).resolve(new Node().type(reference(missingBlueId))));
        RuntimeException mismatch = captureFailure(
                () -> new Blue(blueId -> Collections.singletonList(new Node().name("Mismatch")))
                        .resolve(new Node().type(reference(missingBlueId))));

        // then
        assertTrue(missing instanceof RuntimeException);
        assertTrue(mismatch instanceof RuntimeException);
        assertEquals(BlueLanguageErrorCategory.ProviderUnavailable,
                BlueLanguageErrorClassifier.classify(missing));
        assertEquals(BlueLanguageErrorCategory.ProviderBlueIdMismatch,
                BlueLanguageErrorClassifier.classify(mismatch));
    }

    @Test
    void shouldHandleSharedNodesAndAccidentalObjectCyclesWithoutMutation() {
        // given
        String validBlueId = DirectBlueIdCalculator.calculateBlueId(new Node().name("Shared Reference"));
        Node shared = reference(validBlueId);
        Node root = new Node().type(shared).properties("shared", shared);
        root.properties("self", root);

        // when
        BlueIdReferenceValidator.validate(root);

        // then
        assertSame(shared, root.getType());
        assertSame(shared, root.getProperties().get("shared"));
        assertSame(root, root.getProperties().get("self"));
        assertEquals(validBlueId, shared.getBlueId());
    }

    private static Stream<Arguments> malformedReferenceContainers() {
        return Stream.of(
                arguments(malformedReference(), "/blueId"),
                arguments(new Node().type(malformedReference()), "/type/blueId"),
                arguments(new Node().itemType(malformedReference()), "/itemType/blueId"),
                arguments(new Node().keyType(malformedReference()), "/keyType/blueId"),
                arguments(new Node().valueType(malformedReference()), "/valueType/blueId"),
                arguments(new Node().blue(malformedReference()), "/blue/blueId"),
                arguments(new Node().properties("ordinary/field~", malformedReference()),
                        "/ordinary~1field~0/blueId"),
                arguments(new Node().items(Collections.singletonList(malformedReference())), "/0/blueId"),
                arguments(new Node().contracts(malformedReference()), "/contracts/blueId"),
                arguments(schema(new Schema().required(malformedReference())), "/schema/required/blueId"),
                arguments(schema(new Schema().minLength(malformedReference())), "/schema/minLength/blueId"),
                arguments(schema(new Schema().maxLength(malformedReference())), "/schema/maxLength/blueId"),
                arguments(schema(new Schema().minimum(malformedReference())), "/schema/minimum/blueId"),
                arguments(schema(new Schema().maximum(malformedReference())), "/schema/maximum/blueId"),
                arguments(schema(new Schema().exclusiveMinimum(malformedReference())),
                        "/schema/exclusiveMinimum/blueId"),
                arguments(schema(new Schema().exclusiveMaximum(malformedReference())),
                        "/schema/exclusiveMaximum/blueId"),
                arguments(schema(new Schema().multipleOf(malformedReference())), "/schema/multipleOf/blueId"),
                arguments(schema(new Schema().minItems(malformedReference())), "/schema/minItems/blueId"),
                arguments(schema(new Schema().maxItems(malformedReference())), "/schema/maxItems/blueId"),
                arguments(schema(new Schema().uniqueItems(malformedReference())), "/schema/uniqueItems/blueId"),
                arguments(schema(new Schema().minFields(malformedReference())), "/schema/minFields/blueId"),
                arguments(schema(new Schema().maxFields(malformedReference())), "/schema/maxFields/blueId"),
                arguments(schema(new Schema().enumValues(Collections.singletonList(malformedReference()))),
                        "/schema/enum/0/blueId")
        );
    }

    private static Node schema(Schema schema) {
        return new Node().schema(schema);
    }

    private static Node nestedMalformedReference() {
        return new Node().properties("subject", malformedReference());
    }

    private static Node malformedTypeDocument(boolean contracts) {
        Node document = new Node().type(malformedReference());
        return contracts ? document.contracts(Nodes.emptyObject()) : document;
    }

    private static Node malformedReference() {
        return reference(MALFORMED_BLUE_ID);
    }

    private static Node reference(String blueId) {
        return new Node().blueId(blueId);
    }

    private static NodeProvider countingMiss(AtomicInteger fetches) {
        return blueId -> {
            fetches.incrementAndGet();
            return null;
        };
    }

    private static void assertFailure(Throwable failure,
                                      BlueLanguageErrorCategory category,
                                      String messagePart) {
        String messages = messageChain(failure);
        assertEquals(category, BlueLanguageErrorClassifier.classify(failure), messages);
        assertTrue(messages.contains(messagePart), messages);
    }

    private static String messageChain(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        Throwable current = failure;
        while (current != null) {
            if (current.getMessage() != null) {
                messages.append(current.getMessage()).append('\n');
            }
            current = current.getCause();
        }
        return messages.toString();
    }

    private static final class CountingCyclicProvider
            implements NodeProvider, CyclicAwareNodeProvider {
        private final BasicNodeProvider delegate;
        private final AtomicInteger fetches;

        private CountingCyclicProvider(BasicNodeProvider delegate) {
            this(delegate, new AtomicInteger());
        }

        private CountingCyclicProvider(BasicNodeProvider delegate, AtomicInteger fetches) {
            this.delegate = delegate;
            this.fetches = fetches;
        }

        @Override
        public List<Node> fetchByBlueId(String blueId) {
            fetches.incrementAndGet();
            return delegate.fetchByBlueId(blueId);
        }

        @Override
        public CyclicSetProofResult cyclicSetProofFor(String blueId) {
            return delegate.cyclicSetProofFor(blueId);
        }
    }
}
