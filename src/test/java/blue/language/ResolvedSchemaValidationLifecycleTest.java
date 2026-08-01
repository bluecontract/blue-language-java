package blue.language;

import blue.language.model.wire.BlueLanguageConstants;

import blue.language.api.BlueCachePolicy;
import blue.language.api.BlueCacheStats;
import blue.language.api.BlueLanguageErrorCategory;
import blue.language.api.BlueLanguageErrorClassifier;
import blue.language.api.BlueOperationLimits;
import blue.language.api.BlueOperationOutcome;
import blue.language.api.BlueOperationResult;
import blue.language.api.BlueViewPath;
import blue.language.api.LanguageRuntimeAccess;
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
import blue.language.utils.limits.PathLimits;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static blue.language.processor.FailureCapture.captureFailure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static blue.language.model.wire.BlueLanguageConstants.LIST_TYPE_BLUE_ID;
import static blue.language.model.wire.BlueLanguageConstants.TEXT_TYPE_BLUE_ID;

class ResolvedSchemaValidationLifecycleTest {

    @Test
    void shouldValidateEffectiveSchemaOnceAcrossDeepTypeChain() {
        // given
        BasicNodeProvider provider = new BasicNodeProvider();
        Node parent = new Node().name("Required Parent")
                .properties("field", new Node().schema(new Schema().required(true)));
        provider.addSingleNodes(parent);
        String currentTypeId = provider.getBlueIdByName("Required Parent");
        for (int level = 1; level <= 8; level++) {
            Node child = new Node().name("Required Level " + level)
                    .type(reference(currentTypeId))
                    .properties("field", new Node().schema(new Schema().required(true)));
            provider.addSingleNodes(child);
            currentTypeId = provider.getBlueIdByName("Required Level " + level);
        }
        CountingSchemaVerifier verifier = new CountingSchemaVerifier();
        Blue blue = new Blue(provider, processor(verifier));
        String deepestTypeId = currentTypeId;

        // when
        blue.resolve(new Node().type(reference(deepestTypeId))
                .properties("field", new Node().value("present")));
        int completedValidations = verifier.completedValidations.get();

        // then
        assertEquals(1, completedValidations);
    }

    @Test
    void shouldUseCompletedValueForStringNumericEnumAndWrongKindChecks() {
        // given
        Node type = new Node().name("Payload Constraints")
                .properties("text", new Node().schema(new Schema().minLength(3)))
                .properties("number", new Node().schema(new Schema().minimum(BigDecimal.TEN)))
                .properties("choice", new Node().schema(new Schema().enumValues(Arrays.asList(
                        new Node().value("red"), new Node().value("blue")))));
        BasicNodeProvider provider = new BasicNodeProvider(type);
        String typeId = provider.getBlueIdByName("Payload Constraints");
        Blue blue = new Blue(provider);

        Node valid = new Node().type(reference(typeId))
                .properties("text", new Node().value("valid"))
                .properties("number", new Node().value(10))
                .properties("choice", new Node().value("red"));

        // when
        blue.resolve(valid);
        IllegalArgumentException textFailure = resolutionFailure(
                blue, valid.clone().properties("text", new Node().value(12)));
        IllegalArgumentException numberFailure = resolutionFailure(
                blue, valid.clone().properties("number", new Node().value(9)));
        IllegalArgumentException choiceFailure = resolutionFailure(
                blue, valid.clone().properties("choice", new Node().value("green")));

        // then
        assertPathFailure(textFailure, "/text", "minLength");
        assertPathFailure(numberFailure, "/number", "minimum");
        assertPathFailure(choiceFailure, "/choice", "enum");
    }

    @Test
    void shouldNotCertifySkippedRequiredPathDuringPartialResolution() {
        // given
        Fixture fixture = new Fixture();
        Node missing = new Node().type(reference(fixture.holderTypeId));
        PathLimits skipRequired = new PathLimits(Collections.singleton("/unrelated"), 8);

        // when
        Node partial = fixture.blue.resolve(missing, skipRequired);
        IllegalArgumentException fullFailure =
                resolutionFailure(fixture.blue, missing);

        // then
        assertNull(partial.getProperties());
        assertTrue(fullFailure instanceof IllegalArgumentException);
    }

    @Test
    void shouldRunRequiredValidationInsideIncludedPath() {
        // given
        Fixture fixture = new Fixture();
        PathLimits includeRequired = new PathLimits(Collections.singleton("/field"), 8);

        // when
        IllegalArgumentException failure = captureFailure(
                () -> fixture.blue.resolve(new Node().type(reference(fixture.holderTypeId)), includeRequired));

        // then
        assertTrue(failure instanceof IllegalArgumentException);
        assertTrue(failure.getMessage().contains("/field"));
    }

    @Test
    void shouldRespectMaterializationDepthLimitAndValidateFullResolution() {
        // given
        Fixture fixture = new Fixture();
        Node content = new Node().name("Deep Content")
                .properties("nested", reference(fixture.holderTypeId));
        fixture.provider.addSingleNodes(content);
        String contentId = fixture.provider.getBlueIdByName("Deep Content");
        Node constrainedType = new Node().name("Deep Holder")
                .properties("field", new Node().schema(new Schema().minFields(1)));
        fixture.provider.addSingleNodes(constrainedType);
        String constrainedTypeId = fixture.provider.getBlueIdByName("Deep Holder");
        Node instance = new Node().type(reference(constrainedTypeId))
                .properties("field", reference(contentId));

        // when
        Node partial = fixture.blue.resolve(
                instance, new PathLimits(Collections.singleton("*"), 2));
        Node complete = fixture.blue.resolve(instance);

        // then
        assertNotNull(partial.getProperties().get("field"));
        assertNotNull(complete);
    }

    @Test
    void shouldResolvePathLimitedMaterializationPerOccurrenceInEitherOrder() {
        // given
        // The two orders exercise the same occurrence-specific invariant.

        // when
        MaterializationObservation narrowFirst =
                observeLimitSpecificMaterializationOrder("narrow", "broad");
        MaterializationObservation broadFirst =
                observeLimitSpecificMaterializationOrder("broad", "narrow");

        // then
        assertMaterializationObservation(narrowFirst);
        assertMaterializationObservation(broadFirst);
    }

    @Test
    void shouldNotContaminateLaterResolutionWithPathLimitedPartialMaterialization() {
        // given
        LimitedReferenceFixture fixture = new LimitedReferenceFixture("narrow", "broad");

        // when
        Node partial = fixture.blue.resolve(fixture.instance(), fixture.limits());
        Node complete = fixture.blue.resolve(fixture.instance());
        int fetchCount = fixture.provider.fetches(fixture.contentId);

        // then
        assertNull(partial.getProperties().get("narrow").getProperties());
        assertEquals("present", complete.getProperties().get("narrow")
                .getProperties().get("nested").getValue());
        assertEquals(1, fetchCount);
    }

    @Test
    void shouldPermitAbsentOptionalFieldForEveryPayloadKeyword() {
        // given
        List<Schema> schemas = Arrays.asList(
                new Schema().minLength(1),
                new Schema().maxLength(1),
                new Schema().minimum(BigDecimal.ZERO),
                new Schema().maximum(BigDecimal.ONE),
                new Schema().exclusiveMinimum(BigDecimal.ZERO),
                new Schema().exclusiveMaximum(BigDecimal.ONE),
                new Schema().multipleOf(BigDecimal.ONE),
                new Schema().minItems(1),
                new Schema().maxItems(1),
                new Schema().uniqueItems(true),
                new Schema().minFields(1),
                new Schema().maxFields(1),
                new Schema().enumValues(Collections.singletonList(new Node().value("allowed"))));

        // when
        int resolvedCount = 0;
        for (Schema schema : schemas) {
            Node declaration = new Node().properties("optional", new Node().schema(schema));
            new Blue(new BasicNodeProvider()).resolve(declaration);
            resolvedCount++;
        }

        // then
        assertEquals(schemas.size(), resolvedCount);
    }

    @Test
    void shouldFailPresentWrongKindForEveryPayloadKeywordFamily() {
        // given
        List<Schema> schemas = Arrays.asList(
                new Schema().minLength(1),
                new Schema().maxLength(1),
                new Schema().minimum(BigDecimal.ZERO),
                new Schema().maximum(BigDecimal.ONE),
                new Schema().exclusiveMinimum(BigDecimal.ZERO),
                new Schema().exclusiveMaximum(BigDecimal.ONE),
                new Schema().multipleOf(BigDecimal.ONE),
                new Schema().minItems(1),
                new Schema().maxItems(1),
                new Schema().uniqueItems(true),
                new Schema().minFields(1),
                new Schema().maxFields(1),
                new Schema().enumValues(Collections.singletonList(new Node().value("allowed"))));
        List<Node> payloads = Arrays.asList(
                new Node().items(new Node().value("x")),
                new Node().items(new Node().value("x")),
                new Node().value("text"),
                new Node().value("text"),
                new Node().value("text"),
                new Node().value("text"),
                new Node().value("text"),
                new Node().value("text"),
                new Node().value("text"),
                new Node().value("text"),
                new Node().items(new Node().value("x")),
                new Node().items(new Node().value("x")),
                new Node().items(new Node().value("allowed")));

        // when
        List<IllegalArgumentException> failures = new java.util.ArrayList<>();
        for (int index = 0; index < schemas.size(); index++) {
            failures.add(wrongKindFailure(schemas.get(index), payloads.get(index)));
        }

        // then
        assertEquals(schemas.size(), failures.size());
        for (IllegalArgumentException failure : failures) {
            assertTrue(failure instanceof IllegalArgumentException);
            assertTrue(failure.getMessage().contains("wrong kind"), failure.getMessage());
        }
    }

    @Test
    void shouldFailPayloadlessReferenceConstraintWhileAcceptingEmptyList() {
        // given
        Node payloadless = new Node().name("Payloadless Content")
                .type(reference(TEXT_TYPE_BLUE_ID));
        BasicNodeProvider provider = new BasicNodeProvider(payloadless);
        String payloadlessId = provider.getBlueIdByName("Payloadless Content");
        Node target = new Node().type(reference(TEXT_TYPE_BLUE_ID))
                .schema(new Schema().minLength(1));

        // when
        IllegalArgumentException failure = captureFailure(
                () -> new blue.language.merge.Merger(processor(new SchemaVerifier()), provider)
                        .merge(target, reference(payloadlessId), blue.language.utils.limits.Limits.NO_LIMITS));
        Node emptyList = new Blue(new BasicNodeProvider()).resolve(new Node()
                .properties("values", new Node().schema(new Schema()
                        .minItems(0).maxItems(0).uniqueItems(true))
                        .items(Collections.emptyList())));

        // then
        assertTrue(failure instanceof IllegalArgumentException);
        assertTrue(messageChain(failure).contains("wrong kind"), messageChain(failure));
        assertNotNull(emptyList);
    }

    @Test
    void shouldTrackPositionalReplacementDuringCandidateRegistration() {
        // given
        Node listType = new Node().name("Replacement Holder")
                .properties("values", new Node().items(
                        new Node().schema(new Schema().minLength(3)).value("old")));
        BasicNodeProvider provider = new BasicNodeProvider(listType);
        String typeId = provider.getBlueIdByName("Replacement Holder");
        Blue blue = new Blue(provider);

        Node invalidReplacement = new Node().type(reference(typeId)).properties("values",
                new Node().type(reference(LIST_TYPE_BLUE_ID)).items(new Node().position(0)
                        .properties("$replace", new Node().schema(new Schema().minLength(3)).value("x"))));
        Node validReplacement = new Node().type(reference(typeId)).properties("values",
                new Node().type(reference(LIST_TYPE_BLUE_ID)).items(new Node().position(0)
                        .properties("$replace", new Node().schema(new Schema().minLength(3)).value("new"))));

        // when
        IllegalArgumentException failure = resolutionFailure(blue, invalidReplacement);
        Node resolved = blue.resolve(validReplacement);

        // then
        assertTrue(failure instanceof IllegalArgumentException);
        assertTrue(failure.getMessage().contains("/values/0"), failure.getMessage());
        assertNotNull(resolved);
    }

    @Test
    void shouldNotSharePresenceStateAcrossParallelResolutions() throws Exception {
        // given
        Fixture fixture = new Fixture();
        ExecutorService executor = Executors.newFixedThreadPool(8);

        // when
        List<Boolean> results = new java.util.ArrayList<>();
        boolean terminated;
        try {
            List<Callable<Boolean>> tasks = new java.util.ArrayList<>();
            for (int index = 0; index < 64; index++) {
                final boolean valid = index % 2 == 0;
                tasks.add(() -> {
                    try {
                        fixture.blue.resolve(valid
                                ? fixture.validInstance()
                                : new Node().type(reference(fixture.holderTypeId)));
                        return valid;
                    } catch (IllegalArgumentException ex) {
                        return !valid;
                    }
                });
            }
            for (Future<Boolean> result : executor.invokeAll(tasks)) {
                results.add(result.get());
            }
        } finally {
            executor.shutdownNow();
            terminated = executor.awaitTermination(5, TimeUnit.SECONDS);
        }

        // then
        assertTrue(terminated);
        assertEquals(64, results.size());
        for (Boolean result : results) {
            assertTrue(result);
        }
    }

    @Test
    void shouldNotContaminateNextResolutionAfterFailure() {
        // given
        Fixture fixture = new Fixture();

        // when
        IllegalArgumentException firstFailure = resolutionFailure(
                fixture.blue, new Node().type(reference(fixture.holderTypeId)));
        Node valid = fixture.blue.resolve(fixture.validInstance());
        IllegalArgumentException secondFailure = resolutionFailure(
                fixture.blue, new Node().type(reference(fixture.holderTypeId)));

        // then
        assertTrue(firstFailure instanceof IllegalArgumentException);
        assertNotNull(valid);
        assertTrue(secondFailure instanceof IllegalArgumentException);
    }

    private static IllegalArgumentException resolutionFailure(Blue blue, Node node) {
        return captureFailure(() -> blue.resolve(node));
    }

    private static void assertPathFailure(
            IllegalArgumentException failure,
            String path,
            String keyword) {
        assertTrue(failure instanceof IllegalArgumentException);
        assertTrue(failure.getMessage().contains(path), failure.getMessage());
        assertTrue(failure.getMessage().contains(keyword), failure.getMessage());
    }

    private static String messageChain(Throwable failure) {
        StringBuilder message = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current.getMessage() != null) {
                message.append(current.getMessage()).append('\n');
            }
        }
        return message.toString();
    }

    private static IllegalArgumentException wrongKindFailure(Schema schema, Node payload) {
        Node document = new Node().properties("field", payload.clone().schema(schema));
        return captureFailure(
                () -> new Blue(new BasicNodeProvider()).resolve(document));
    }

    private static MaterializationObservation observeLimitSpecificMaterializationOrder(
            String first,
            String second) {
        LimitedReferenceFixture fixture = new LimitedReferenceFixture(first, second);
        Node resolved = fixture.blue.resolve(fixture.instance(), fixture.limits());
        return new MaterializationObservation(
                resolved.getProperties().get("broad")
                        .getProperties().get("nested").getValue(),
                resolved.getProperties().get("narrow").getProperties(),
                fixture.provider.fetches(fixture.contentId));
    }

    private static void assertMaterializationObservation(
            MaterializationObservation observation) {
        assertEquals("present", observation.broadNestedValue);
        assertNull(observation.narrowProperties);
        assertEquals(1, observation.fetchCount);
    }

    private static final class MaterializationObservation {
        private final Object broadNestedValue;
        private final java.util.Map<String, Node> narrowProperties;
        private final int fetchCount;

        private MaterializationObservation(
                Object broadNestedValue,
                java.util.Map<String, Node> narrowProperties,
                int fetchCount) {
            this.broadNestedValue = broadNestedValue;
            this.narrowProperties = narrowProperties;
            this.fetchCount = fetchCount;
        }
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

    private static final class LimitedReferenceFixture {
        private final CountingProvider provider;
        private final Blue blue;
        private final String holderId;
        private final String contentId;

        private LimitedReferenceFixture(String first, String second) {
            BasicNodeProvider delegate = new BasicNodeProvider();
            Node content = new Node().name("Limited Content")
                    .properties("nested", new Node().value("present"));
            delegate.addSingleNodes(content);
            contentId = delegate.getBlueIdByName("Limited Content");
            Node holder = new Node().name("Limited Holder");
            holder.properties(first, new Node().schema(new Schema().minFields(1)));
            holder.properties(second, new Node().schema(new Schema().minFields(1)));
            delegate.addSingleNodes(holder);
            holderId = delegate.getBlueIdByName("Limited Holder");
            provider = new CountingProvider(delegate);
            blue = new Blue(provider);
        }

        private Node instance() {
            return new Node().type(reference(holderId))
                    .properties("narrow", reference(contentId))
                    .properties("broad", reference(contentId));
        }

        private PathLimits limits() {
            Set<String> paths = new LinkedHashSet<>();
            paths.add("/narrow");
            paths.add("/broad/nested");
            return new PathLimits(paths, 8);
        }
    }

    private static final class CountingProvider implements blue.language.provider.NodeProvider {
        private final blue.language.provider.NodeProvider delegate;
        private final java.util.concurrent.ConcurrentHashMap<String, AtomicInteger> counts =
                new java.util.concurrent.ConcurrentHashMap<>();

        private CountingProvider(blue.language.provider.NodeProvider delegate) {
            this.delegate = delegate;
        }

        @Override
        public List<Node> fetchByBlueId(String blueId) {
            counts.computeIfAbsent(blueId, ignored -> new AtomicInteger()).incrementAndGet();
            return delegate.fetchByBlueId(blueId);
        }

        private int fetches(String blueId) {
            AtomicInteger count = counts.get(blueId);
            return count == null ? 0 : count.get();
        }
    }

    private static final class Fixture {
        private final BasicNodeProvider provider = new BasicNodeProvider();
        private final String holderTypeId;
        private final Blue blue;

        private Fixture() {
            Node holder = new Node().name("Required Holder")
                    .properties("field", new Node().schema(new Schema().required(true)));
            provider.addSingleNodes(holder);
            holderTypeId = provider.getBlueIdByName("Required Holder");
            blue = new Blue(provider);
        }

        private Node validInstance() {
            return new Node().type(reference(holderTypeId))
                    .properties("field", new Node().value("present"));
        }
    }
}
