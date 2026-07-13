package blue.language;

import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.provider.BasicNodeProvider;
import blue.language.provider.CyclicAwareNodeProvider;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.BlueIdReferenceValidator;
import blue.language.utils.BlueIds;
import blue.language.utils.NodeProviderWrapper;
import blue.language.utils.limits.PathLimits;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static blue.language.utils.UncheckedObjectMapper.YAML_MAPPER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class ReferenceBlueIdResolutionValidationTest {

    private static final String MALFORMED_BLUE_ID = "symbolic-type-name";

    @Test
    void unmaterializedMalformedReferenceFailsBeforeOrdinaryProviderLookup() {
        AtomicInteger fetches = new AtomicInteger();
        Blue blue = new Blue(countingMiss(fetches));

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> blue.resolve(nestedMalformedReference()));

        assertFailure(failure, BlueLanguageErrorCategory.InvalidBlueId, "/subject/blueId");
        assertEquals(0, fetches.get());
    }

    @Test
    void unmaterializedMalformedReferenceFailsBeforeTrustedProviderLookup() {
        AtomicInteger fetches = new AtomicInteger();
        Blue blue = new Blue(NodeProviderWrapper.unverified(countingMiss(fetches)));

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> blue.resolve(nestedMalformedReference()));

        assertFailure(failure, BlueLanguageErrorCategory.InvalidBlueId, "/subject/blueId");
        assertEquals(0, fetches.get());
    }

    @Test
    void malformedTypeReferenceIsProviderInvariantDuringDirectResolution() {
        AtomicInteger ordinaryFetches = new AtomicInteger();
        AtomicInteger trustedFetches = new AtomicInteger();
        Blue ordinary = new Blue(countingMiss(ordinaryFetches));
        Blue trusted = new Blue(NodeProviderWrapper.unverified(countingMiss(trustedFetches)));

        RuntimeException ordinaryFailure = assertThrows(RuntimeException.class,
                () -> ordinary.resolve(malformedTypeDocument(false)));
        RuntimeException trustedFailure = assertThrows(RuntimeException.class,
                () -> trusted.resolve(malformedTypeDocument(false)));

        assertFailure(ordinaryFailure, BlueLanguageErrorCategory.InvalidBlueId, "/type/blueId");
        assertFailure(trustedFailure, BlueLanguageErrorCategory.InvalidBlueId, "/type/blueId");
        assertEquals(0, ordinaryFetches.get());
        assertEquals(0, trustedFetches.get());
    }

    @Test
    void malformedTypeReferenceIsProviderInvariantDuringInitialization() {
        AtomicInteger ordinaryFetches = new AtomicInteger();
        AtomicInteger trustedFetches = new AtomicInteger();
        Blue ordinary = new Blue(countingMiss(ordinaryFetches));
        Blue trusted = new Blue(NodeProviderWrapper.unverified(countingMiss(trustedFetches)));

        RuntimeException ordinaryFailure = assertThrows(RuntimeException.class,
                () -> ordinary.initializeDocument(malformedTypeDocument(true)));
        RuntimeException trustedFailure = assertThrows(RuntimeException.class,
                () -> trusted.initializeDocument(malformedTypeDocument(true)));

        assertFailure(ordinaryFailure, BlueLanguageErrorCategory.InvalidBlueId, "/type/blueId");
        assertFailure(trustedFailure, BlueLanguageErrorCategory.InvalidBlueId, "/type/blueId");
        assertEquals(0, ordinaryFetches.get());
        assertEquals(0, trustedFetches.get());
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("malformedReferenceContainers")
    void malformedReferencesAreValidatedInEveryNodeContainer(Node source, String expectedPath) {
        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> new Blue().resolve(source));

        assertFailure(failure, BlueLanguageErrorCategory.InvalidBlueId, expectedPath);
    }

    @Test
    void malformedReferenceUnderExcludedResolutionPathStillFails() {
        AtomicInteger fetches = new AtomicInteger();
        Blue blue = new Blue(countingMiss(fetches));
        Node source = new Node()
                .properties("included", new Node().value("visible"))
                .properties("excluded", malformedReference());

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> blue.resolve(source, PathLimits.withSinglePath("/included")));

        assertFailure(failure, BlueLanguageErrorCategory.InvalidBlueId, "/excluded/blueId");
        assertEquals(0, fetches.get());
    }

    @Test
    void malformedPreviousReferenceRemainsAListControlViolationBeforeProviderLookup() {
        AtomicInteger fetches = new AtomicInteger();
        Blue blue = new Blue(countingMiss(fetches));
        Node source = new Node().items(
                new Node().previousBlueId(MALFORMED_BLUE_ID),
                new Node().value("appended"));

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> blue.resolve(source));

        assertFailure(failure, BlueLanguageErrorCategory.ListControlViolation,
                "/0/$previous/blueId");
        assertEquals(0, fetches.get());
    }

    @Test
    void directBlueIdInputParsersUseTheSharedReferenceValidator() {
        RuntimeException yamlFailure = assertThrows(RuntimeException.class,
                () -> new Blue().parseBlueIdInputYaml(
                        "subject:\n  blueId: " + MALFORMED_BLUE_ID + "\n"));
        RuntimeException jsonFailure = assertThrows(RuntimeException.class,
                () -> new Blue().parseBlueIdInputJson(
                        "{\"subject\":{\"blueId\":\"" + MALFORMED_BLUE_ID + "\"}}"));

        assertFailure(yamlFailure, BlueLanguageErrorCategory.InvalidBlueId,
                "/subject/blueId");
        assertFailure(jsonFailure, BlueLanguageErrorCategory.InvalidBlueId,
                "/subject/blueId");
    }

    @Test
    void validMissingReferenceRemainsProviderUnavailable() {
        String missingBlueId = BlueIdCalculator.calculateBlueId(new Node().name("Missing Type"));
        AtomicInteger fetches = new AtomicInteger();
        Blue blue = new Blue(countingMiss(fetches));

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> blue.resolve(new Node().type(reference(missingBlueId))));

        assertFailure(failure, BlueLanguageErrorCategory.ProviderUnavailable, missingBlueId);
        assertEquals(1, fetches.get());
    }

    @Test
    void validOrdinaryMismatchRemainsProviderBlueIdMismatch() {
        String requestedBlueId = BlueIdCalculator.calculateBlueId(new Node().name("Requested Type"));
        AtomicInteger fetches = new AtomicInteger();
        Blue blue = new Blue(blueId -> {
            fetches.incrementAndGet();
            return requestedBlueId.equals(blueId)
                    ? Collections.singletonList(new Node().name("Different Type"))
                    : null;
        });

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> blue.resolve(new Node().type(reference(requestedBlueId))));

        assertFailure(failure, BlueLanguageErrorCategory.ProviderBlueIdMismatch, requestedBlueId);
        assertEquals(1, fetches.get());
    }

    @Test
    void validTrustedNonDirectContentStillResolves() {
        Node requested = new Node().name("Requested Trusted Type")
                .properties("fixed", new Node().value("requested"));
        Node trusted = new Node().name("Trusted Non-Direct Type")
                .properties("fixed", new Node().value("trusted"));
        String requestedBlueId = BlueIdCalculator.calculateBlueId(requested);
        AtomicInteger fetches = new AtomicInteger();
        Blue blue = new Blue(NodeProviderWrapper.unverified(blueId -> {
            fetches.incrementAndGet();
            return requestedBlueId.equals(blueId)
                    ? Collections.singletonList(trusted.clone())
                    : null;
        }));

        Node resolved = blue.resolve(new Node().type(reference(requestedBlueId)));

        assertEquals("trusted", resolved.getAsText("/fixed"));
        assertEquals(1, fetches.get());
        assertEquals(0, blue.resolvedReferenceCacheSize());
    }

    @Test
    void validCyclicMemberStillReachesCyclicAwareProvider() {
        BasicNodeProvider cyclicProvider = new BasicNodeProvider(YAML_MAPPER.readValue(
                "- name: Cyclic A\n"
                        + "  fixed: cyclic\n"
                        + "- name: Cyclic B\n"
                        + "  fixed: companion\n",
                Node.class));
        String memberBlueId = cyclicProvider.getBlueIdByName("Cyclic A");
        CountingCyclicProvider countingProvider = new CountingCyclicProvider(cyclicProvider);
        Blue blue = new Blue(countingProvider);

        Node resolved = blue.resolve(new Node().type(reference(memberBlueId)));

        assertEquals("Cyclic A", resolved.getType().getName());
        assertEquals("cyclic", resolved.getAsText("/fixed"));
        assertEquals(1, countingProvider.fetches.get());

        AtomicInteger malformedFetches = new AtomicInteger();
        CountingCyclicProvider malformedProvider = new CountingCyclicProvider(
                cyclicProvider, malformedFetches);
        String malformedMember = memberBlueId.substring(0, memberBlueId.indexOf('#')) + "#01";
        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> new Blue(malformedProvider).resolve(
                        new Node().type(reference(malformedMember))));

        assertFailure(failure, BlueLanguageErrorCategory.InvalidBlueId, "/type/blueId");
        assertEquals(0, malformedFetches.get());
    }

    @Test
    void declaredTypeAliasStillPreprocessesBeforeResolution() {
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
        Node resolved = blue.resolve(preprocessed);

        assertEquals(typeBlueId, preprocessed.getType().getBlueId());
        assertEquals("from-alias", resolved.getAsText("/provided"));
    }

    @Test
    void malformedBlueIdClassifierMappingsPreserveExistingDiagnosticControls() {
        RuntimeException malformedPlain = assertThrows(RuntimeException.class,
                () -> BlueIds.requirePlainBlueId(MALFORMED_BLUE_ID, "/subject/blueId"));
        RuntimeException malformedCyclic = assertThrows(RuntimeException.class,
                () -> BlueIds.requireBlueIdOrCyclicMember("abc#01", "/subject/blueId"));
        RuntimeException malformedPrevious = assertThrows(RuntimeException.class,
                () -> BlueIds.requirePlainBlueId(MALFORMED_BLUE_ID, "/$previous/blueId"));
        RuntimeException invalidDirectInput = assertThrows(RuntimeException.class,
                () -> new Blue().parseBlueIdInputYaml("type: Integer\nvalue: 1\n"));

        assertEquals(BlueLanguageErrorCategory.InvalidBlueId,
                BlueLanguageErrorClassifier.classify(malformedPlain));
        assertEquals(BlueLanguageErrorCategory.InvalidBlueId,
                BlueLanguageErrorClassifier.classify(malformedCyclic));
        assertEquals(BlueLanguageErrorCategory.ListControlViolation,
                BlueLanguageErrorClassifier.classify(malformedPrevious));
        assertEquals(BlueLanguageErrorCategory.InvalidBlueIdInput,
                BlueLanguageErrorClassifier.classify(invalidDirectInput));

        String missingBlueId = BlueIdCalculator.calculateBlueId(new Node().name("Classifier Missing"));
        RuntimeException missing = assertThrows(RuntimeException.class,
                () -> new Blue(blueId -> null).resolve(new Node().type(reference(missingBlueId))));
        RuntimeException mismatch = assertThrows(RuntimeException.class,
                () -> new Blue(blueId -> Collections.singletonList(new Node().name("Mismatch")))
                        .resolve(new Node().type(reference(missingBlueId))));

        assertEquals(BlueLanguageErrorCategory.ProviderUnavailable,
                BlueLanguageErrorClassifier.classify(missing));
        assertEquals(BlueLanguageErrorCategory.ProviderBlueIdMismatch,
                BlueLanguageErrorClassifier.classify(mismatch));
    }

    @Test
    void validatorHandlesSharedNodesAndAccidentalObjectCyclesWithoutMutation() {
        String validBlueId = BlueIdCalculator.calculateBlueId(new Node().name("Shared Reference"));
        Node shared = reference(validBlueId);
        Node root = new Node().type(shared).properties("shared", shared);
        root.properties("self", root);

        BlueIdReferenceValidator.validate(root);

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
        return contracts ? document.contracts(new Node()) : document;
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
        public boolean hasVerifiedContentForBlueId(String blueId) {
            return delegate.hasVerifiedContentForBlueId(blueId);
        }
    }
}
