package blue.language.processor;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.NodeWireForm;
import blue.language.model.Nodes;
import blue.language.processor.model.HandlerContract;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.model.ProcessEmbedded;
import blue.language.processor.registry.BlueRuntimeTypeRegistry;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.provider.NodeProvider;
import blue.language.provider.NodeProviderResult;
import blue.language.provider.SequentialNodeProvider;
import blue.language.runtime.BlueLanguage;
import blue.language.runtime.LanguageProcessing;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static blue.language.processor.FailureCapture.captureFailure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndependentT12RegressionTest {

    private static final Node HANDLER_TYPE = new Node().name("Reference parent patch handler");
    private static final String HANDLER_ID = id(HANDLER_TYPE);
    private static final String COLD_ID = id(new Node().name("Unrelated exact value"));

    enum Entry { DIRECT, PREPARED }

    @ParameterizedTest
    @CsvSource({"DIRECT, object", "PREPARED, object", "DIRECT, list", "PREPARED, list",
            "DIRECT, empty", "PREPARED, empty", "DIRECT, reference", "PREPARED, reference"})
    void shouldPatchDeepReferenceWithInlineIdentityAndGas(Entry entry, String shape) {
        // given
        Node parent = "list".equals(shape) ? new Node().items(new Node().value(1))
                : "empty".equals(shape) ? Nodes.emptyObject()
                : new Node().properties("x", new Node().value(1));
        Node value = new Node().properties("deep", "reference".equals(shape) ? ref(id(parent)) : parent)
                .properties("cold", ref(COLD_ID));
        String path = "list".equals(shape) ? "/obj/deep/0" : "/obj/deep/x";
        JsonPatch patch = JsonPatch.replace(path, new Node().value(2));
        try (Fixture referenced = new Fixture(value, true, Collections.singletonList(patch));
             Fixture inline = new Fixture(value, false, Collections.singletonList(patch))) {
            referenced.provider.found(parent);
            inline.provider.found(parent);
            FrozenNode original = referenced.runtime.selectedRootWithoutResolution();
            int readsBeforePatch = referenced.provider.reads(id(value));

            // when
            referenced.apply(entry, patch);
            inline.apply(entry, patch);

            // then
            assertEquals(0, readsBeforePatch);
            assertEquals(2, referenced.runtime.resolvedNodeAt(path).getAsInteger("/"));
            assertEquals(inline.runtime.selectedRootWithoutResolution().blueId(),
                    referenced.runtime.selectedRootWithoutResolution().blueId());
            assertEquals(inline.runtime.totalGas(), referenced.runtime.totalGas());
            assertTrue(original.property("obj").isReferenceOnly());
            assertEquals(id(value), referenced.runtime.selectedRootWithoutResolution()
                    .property("unchanged").getReferenceBlueId());
            assertEquals(COLD_ID, referenced.runtime.selectedRootWithoutResolution()
                    .property("obj").property("cold").getReferenceBlueId());
            assertEquals(0, referenced.provider.reads(COLD_ID));
            assertEquals(0, inline.provider.reads(COLD_ID));
            assertEquals(1, referenced.provider.reads(id(value)));
        }
    }

    @ParameterizedTest
    @CsvSource({"DIRECT, false", "DIRECT, true", "PREPARED, false", "PREPARED, true"})
    void shouldRejectActuallyMissingParentAfterOpeningRequiredReference(Entry entry, boolean reference) {
        // given
        Node value = deepValue();
        JsonPatch patch = JsonPatch.replace("/obj/missing/x", new Node().value(2));
        try (Fixture fixture = new Fixture(value, reference, Collections.singletonList(patch))) {
            String before = fixture.runtime.selectedRootWithoutResolution().blueId();

            // when
            RuntimeException failure = captureFailure(() -> fixture.apply(entry, patch));

            // then
            assertNotNull(failure);
            assertEquals("Final parent does not exist for patch path: /obj/missing/x", failure.getMessage());
            assertEquals(before, fixture.runtime.selectedRootWithoutResolution().blueId());
            assertEquals(reference ? 1 : 0, fixture.provider.reads(id(value)));
        }
    }

    @ParameterizedTest
    @CsvSource({"DIRECT, false", "DIRECT, true", "PREPARED, false", "PREPARED, true"})
    void shouldKeepMissingEvidenceDistinctFromMissingParent(Entry entry, boolean invalid) {
        // given
        Node value = deepValue();
        JsonPatch patch = JsonPatch.replace("/obj/deep/x", new Node().value(2));
        try (Fixture fixture = new Fixture(value, true, Collections.singletonList(patch))) {
            fixture.provider.values.put(id(value), invalid
                    ? NodeProviderResult.invalidEvidence("invalid parent evidence")
                    : NodeProviderResult.unavailable("parent evidence unavailable"));
            String before = fixture.runtime.selectedRootWithoutResolution().blueId();

            // when
            RuntimeException failure = captureFailure(() -> fixture.apply(entry, patch));

            // then
            if (invalid) {
                assertInstanceOf(InvalidExecutionEvidenceException.class, failure);
            } else {
                ExecutionEvidenceUnavailableException unavailable =
                        assertInstanceOf(ExecutionEvidenceUnavailableException.class, failure);
                assertEquals(Collections.singletonList(id(value)), unavailable.requiredExactBlueIds());
            }
            assertEquals(before, fixture.runtime.selectedRootWithoutResolution().blueId());
            assertEquals(0L, fixture.runtime.totalGas());
            assertEquals(1, fixture.provider.reads(id(value)));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldAllowEarlierPatchToCreateParent(boolean reference) {
        // given
        List<JsonPatch> patches = Arrays.asList(JsonPatch.add("/obj/deep", Nodes.emptyObject()),
                JsonPatch.replace("/obj/deep/x", new Node().value(2)));
        try (Fixture fixture = new Fixture(new Node().name("Initially no deep parent"), reference, patches)) {
            // when
            fixture.runtime.applyPatches("/", patches);

            // then
            assertEquals(2, fixture.runtime.document().getAsInteger("/obj/deep/x"));
        }
    }

    @Test
    void shouldRollBackBatchWhenLaterParentIsMissing() {
        // given
        List<JsonPatch> patches = Arrays.asList(JsonPatch.replace("/obj/deep/x", new Node().value(2)),
                JsonPatch.replace("/obj/missing/x", new Node().value(3)));
        try (Fixture fixture = new Fixture(deepValue(), true, patches)) {
            String before = fixture.runtime.selectedRootWithoutResolution().blueId();

            // when
            RuntimeException failure = captureFailure(
                    () -> fixture.runtime.applyPatches("/", patches));

            // then
            assertNotNull(failure);
            assertEquals("Final parent does not exist for patch path: /obj/missing/x", failure.getMessage());
            assertEquals(before, fixture.runtime.selectedRootWithoutResolution().blueId());
            assertEquals(0L, fixture.runtime.totalGas());
        }
    }

    @ParameterizedTest
    @EnumSource(Entry.class)
    void shouldRejectCyclicAncestorBeforeOpeningIt(Entry entry) {
        // given
        String cyclicId = "GX7CFU287wrZ7qw3LQG7gQi6UUoy1FFpM3tzupQJKi3N#0";
        JsonPatch patch = JsonPatch.replace("/obj/deep/x", new Node().value(2));
        try (Fixture fixture = new Fixture(deepValue(), true, Collections.singletonList(patch))) {
            fixture.root.properties("obj", ref(cyclicId));
            DocumentProcessingRuntime runtime = fixture.newRuntime();

            // when
            Throwable captured = captureFailure(() -> apply(runtime, entry, patch));

            // then
            ProcessorFailureException failure = assertInstanceOf(ProcessorFailureException.class, captured);
            assertEquals(ProcessorErrorCategory.CyclicSetMutationUnsupported, failure.errorCategory());
            assertEquals(0, fixture.provider.reads(cyclicId));
        }
    }

    @Test
    void shouldRejectManagedChildMutationBeforeOpeningItsReference() {
        // given
        Node value = deepValue();
        List<JsonPatch> patches = Collections.singletonList(
                JsonPatch.replace("/obj/deep/x", new Node().value(2)));
        try (Fixture fixture = new Fixture(value, true, patches)) {
            ProcessorInvocationState execution = new ProcessorInvocationState(fixture.processor, fixture.root);
            ContractBundle bundle = ContractBundle.builder()
                    .setEmbedded(new ProcessEmbedded().addPath("/obj")).build();
            Object before = NodeWireForm.get(fixture.root);

            // when
            Throwable failure = captureFailure(() -> execution.handlePatches("/", bundle, patches, false));
            DocumentProcessingResult result = execution.result();

            // then
            assertInstanceOf(RunTerminationException.class, failure);
            assertEquals(ProcessorErrorCategory.PatchBoundaryViolation, result.diagnostic().category());
            assertEquals(before, NodeWireForm.get(result.document()));
            assertTrue(result.events().isEmpty());
            assertEquals(0, fixture.provider.reads(id(value)));
        }
    }

    @Test
    void shouldPatchDeepReferenceThroughPublicHandlerWithInlineIdentityAndGas() {
        // given
        List<JsonPatch> patches = Collections.singletonList(JsonPatch.replace("/obj/deep/x", new Node().value(2)));
        try (Fixture referenced = new Fixture(deepValue(), true, patches);
             Fixture inline = new Fixture(deepValue(), false, patches)) {
            Object before = NodeWireForm.get(referenced.root);

            // when
            DocumentProcessingResult result = referenced.processor.initializeDocument(referenced.root);
            DocumentProcessingResult control = inline.processor.initializeDocument(inline.root);

            // then
            assertEquals(ProcessorStatus.SUCCESS, result.status(),
                    result.diagnostic() == null ? null : result.diagnostic().message());
            assertEquals(ProcessorStatus.SUCCESS, control.status());
            assertEquals(2, result.document().getAsInteger("/obj/deep/x"));
            assertEquals(id(control.document()), id(result.document()));
            assertEquals(control.totalGas(), result.totalGas());
            assertEquals(before, NodeWireForm.get(referenced.root));
            assertEquals(1, referenced.handlerCalls);
            assertEquals(1, referenced.provider.reads(id(deepValue())));
        }
    }

    @Test
    void shouldRejectMissingParentAtomicallyThroughPublicHandler() {
        // given
        List<JsonPatch> patches = Arrays.asList(JsonPatch.replace("/obj/deep/x", new Node().value(2)),
                JsonPatch.replace("/obj/missing/x", new Node().value(3)));
        try (Fixture fixture = new Fixture(deepValue(), true, patches)) {
            Object before = NodeWireForm.get(fixture.root);

            // when
            DocumentProcessingResult result = fixture.processor.initializeDocument(fixture.root);

            // then
            assertEquals(ProcessorStatus.RUNTIME_FATAL, result.status());
            assertEquals(ProcessorErrorCategory.InvalidPatch, result.diagnostic().category());
            assertEquals("Final parent does not exist for patch path: /obj/missing/x", result.diagnostic().message());
            assertEquals(before, NodeWireForm.get(result.document()));
            assertEquals(before, NodeWireForm.get(fixture.root));
            assertTrue(result.events().isEmpty());
            assertEquals(1, fixture.handlerCalls);
        }
    }

    private static Node deepValue() {
        return new Node().properties("deep", new Node().properties("x", new Node().value(1)));
    }

    private static String id(Node node) { return DirectBlueIdCalculator.calculateBlueId(node); }
    private static Node ref(String blueId) { return new Node().blueId(blueId); }

    private static void apply(DocumentProcessingRuntime runtime, Entry entry, JsonPatch patch) {
        if (entry == Entry.DIRECT) {
            runtime.applyPatch("/", patch);
        } else {
            try (PreparedPatchTransaction sequence = runtime.preparePatchSequence(
                    "/", Collections.singletonList(patch), null)) {
                sequence.applyNext(0);
            }
        }
    }

    public static final class PatchHandler extends HandlerContract { }

    private static final class Fixture implements AutoCloseable {
        private final CountingProvider provider = new CountingProvider();
        private final BlueLanguage language;
        private final LanguageProcessing.Scope scope;
        private final DocumentProcessor processor;
        private final DocumentProcessingRuntime runtime;
        private final Node root;
        private int handlerCalls;

        private Fixture(Node value, boolean reference, List<JsonPatch> patches) {
            provider.found(value);
            provider.found(HANDLER_TYPE);
            NodeProvider combined = new SequentialNodeProvider(provider, BlueRuntimeTypeRegistry.getDefault().asProvider());
            language = BlueLanguage.builder().nodeProvider(combined).build();
            scope = language.processing().openScope();
            ContractProcessorRegistry registry = ContractProcessorRegistryBuilder.create()
                    .register(HANDLER_ID, HANDLER_TYPE, new HandlerProcessor<PatchHandler>() {
                        @Override public Class<PatchHandler> contractType() { return PatchHandler.class; }
                        @Override public void execute(PatchHandler contract, ProcessorExecutionContext context) {
                            handlerCalls++;
                            context.applyPatches(patches);
                        }
                    }).build();
            processor = DocumentProcessor.builder().runtimeRegistry(registry).nodeProvider(combined)
                    .snapshotStore(new LanguageProcessingSnapshotManager(scope)).build();
            root = new Node().properties("obj", reference ? ref(id(value)) : value.clone())
                    .properties("unchanged", ref(id(value)))
                    .contracts(new Node()
                            .properties("lifecycle", new Node().type(ref(RuntimeBlueIds.LIFECYCLE_EVENT_CHANNEL)))
                            .properties("patch", new Node().type(ref(HANDLER_ID))
                                    .properties("channel", new Node().value("lifecycle"))));
            runtime = newRuntime();
        }

        private DocumentProcessingRuntime newRuntime() {
            return new DocumentProcessingRuntime(root, processor.conformanceEngine(), null,
                    processor.snapshotManager(), null, new GasMeter(), Collections.emptyMap(), true);
        }

        private void apply(Entry entry, JsonPatch patch) { IndependentT12RegressionTest.apply(runtime, entry, patch); }

        @Override public void close() {
            processor.close();
            scope.close();
            language.close();
        }
    }

    private static final class CountingProvider implements NodeProvider {
        private final Map<String, NodeProviderResult> values = new LinkedHashMap<>();
        private final Map<String, Integer> reads = new LinkedHashMap<>();

        private void found(Node value) {
            values.put(id(value), NodeProviderResult.found(Collections.singletonList(value)));
        }

        private int reads(String blueId) { return reads.getOrDefault(blueId, 0); }

        @Override public List<Node> fetchByBlueId(String blueId) {
            return fetchResultByBlueId(blueId).nodes();
        }

        @Override public NodeProviderResult fetchResultByBlueId(String blueId) {
            reads.put(blueId, reads(blueId) + 1);
            return values.getOrDefault(blueId, NodeProviderResult.notFound());
        }
    }
}
