package blue.language.processor;

import static blue.language.processor.DocumentProcessingResultTestSupport.*;

import blue.language.Blue;
import blue.language.NodeProvider;
import blue.language.model.Node;
import blue.language.processor.contracts.SetPropertyContractProcessor;
import blue.language.processor.model.HandlerContract;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.snapshot.FrozenNode;
import blue.language.snapshot.ResolvedSnapshot;
import blue.language.utils.BlueIdCalculator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static blue.language.processor.FailureCapture.captureFailure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutableBodyFieldMetadataTest {

    @Test
    void shouldVerifyHandlerEventMatcherIsPreservedAsAuthoredPartialData() {
        // given
        Node document = new Node()
                .contracts(new Node()
                        .properties(
                                "h",
                                new Node()
                                        .type(new Node().blueId(
                                                RuntimeBlueIds.HANDLER))
                                        .properties(
                                                "event",
                                                new Node()
                                                        .properties(
                                                                "documentId",
                                                                new Node()
                                                                        .value(
                                                                                "expected")))));
        Map<String, List<String>> handlerMetadata =
                Collections.singletonMap(
                        RuntimeBlueIds.HANDLER,
                        Collections.emptyList());

        // when
        Set<String> mutablePaths =
                DocumentProcessingRuntime.executableBodyPaths(
                        document,
                        Collections.singleton("/"),
                        handlerMetadata);
        Set<String> frozenPaths =
                DocumentProcessingRuntime.executableBodyPaths(
                        FrozenNode.fromUncheckedCanonicalNode(
                                document),
                        Collections.singleton("/"),
                        handlerMetadata);

        // then
        assertEquals(
                Collections.singleton(
                        "/contracts/h/event"),
                mutablePaths);
        assertEquals(mutablePaths, frozenPaths);
    }

    @Test
    void shouldVerifyTypedPartialEventMatcherRemainsExactThroughMatchAndBodyMaterialization() {
        // given
        Fixture fixture =
                new Fixture(
                        true,
                        BodyForm.DIRECT_REFERENCE,
                        false,
                        true);

        // when
        DocumentProcessingResult result =
                fixture.initialize();

        // then
        assertEquals(
                ProcessorStatus.SUCCESS,
                result.status(),
                diagnosticMessage(result));
        assertExactTypeOnlyInitiatedMatcher(
                fixture.processor.eventMatcherDuringMatch);
        assertExactTypeOnlyInitiatedMatcher(
                fixture.processor.eventMatcherDuringExecution);
    }

    private void assertExactTypeOnlyInitiatedMatcher(Node matcher) {
        assertNotNull(matcher);
        assertNotNull(matcher.getType());
        assertEquals(
                RuntimeBlueIds.DOCUMENT_PROCESSING_INITIATED,
                matcher.getType().getBlueId());
        assertNull(
                matcher.getProperties(),
                "a type-only event pattern must not acquire required event fields");
    }

    @Test
    void shouldVerifyRegistryCapturesExactRuntimeMetadataAndPreservesInheritedProgramPath() {
        // given
        Fixture fixture = new Fixture(false);
        ContractProcessorRegistry registry =
                ContractProcessorRegistryBuilder.create()
                        .registerDefaults()
                        .register(
                                fixture.handlerTypeBlueId,
                                fixture.handlerType,
                                fixture.processor)
                        .build();
        // when
        fixture.processor.declaredExecutableFields.add(
                "body");
        Throwable mutationFailure = captureFailure(
                () -> registry.executableBodyFields(
                        fixture.handlerTypeBlueId).add("body"));
        Set<String> mutablePaths =
                DocumentProcessingRuntime.executableBodyPaths(
                        fixture.document(),
                        Collections.singleton("/"),
                        registry.executableBodyFieldsByType());
        Set<String> frozenPaths =
                DocumentProcessingRuntime.executableBodyPaths(
                        FrozenNode.fromUncheckedCanonicalNode(
                                fixture.document()),
                        Collections.singleton("/"),
                        registry.executableBodyFieldsByType());

        // then
        assertEquals(
                Collections.singletonList("program"),
                registry.executableBodyFields(
                        fixture.handlerTypeBlueId));
        assertTrue(mutationFailure instanceof UnsupportedOperationException);
        assertEquals(
                Collections.singleton(
                        "/contracts/run/program"),
                mutablePaths);
        assertEquals(mutablePaths, frozenPaths);
        assertFalse(
                mutablePaths.contains(
                        "/contracts/run/body"),
                "ordinary data named body is not executable metadata");
    }

    @Test
    void shouldVerifyNonMatchingHandlerDoesNotDemandAnyCollapsedHandlerData() {
        // given
        Fixture fixture = new Fixture(false);

        // when
        DocumentProcessingResult result =
                fixture.initialize();

        // then
        assertEquals(
                ProcessorStatus.SUCCESS,
                result.status(),
                diagnosticMessage(result));
        assertFalse(fixture.processor.executed);
        assertFalse(
                fixture.providerRequests.contains(
                        fixture.programBlueId),
                "a nonmatching Handler must not demand its inherited executable program");
        assertFalse(
                fixture.providerRequests.contains(
                        fixture.ordinaryBodyBlueId),
                "ordinary reference data may remain collapsed but is not an executable-body demand");
    }

    @Test
    void shouldVerifyNonMatchingHandlerBehindReferencedContractsMapDoesNotDemandBodyReference() {
        // given
        Fixture fixture =
                new Fixture(
                        false,
                        BodyForm
                                .WHOLE_CONTRACTS_MAP_REFERENCE);

        // when
        DocumentProcessingResult result =
                fixture.initialize();

        // then
        assertEquals(
                ProcessorStatus.SUCCESS,
                result.status(),
                diagnosticMessage(result));
        assertFalse(fixture.processor.executed);
        assertFalse(
                fixture.providerRequests.contains(
                        fixture.programBlueId),
                "recognizing a referenced contracts map must stop at the declared body path");
    }

    @Test
    void shouldVerifyUnrelatedLifecyclePatchBeforeMatchingDoesNotDemandBodyBehindReferencedContractRepresentations() {
        // given
        for (BodyForm form : new BodyForm[]{
                BodyForm.WHOLE_CONTRACT_REFERENCE,
                BodyForm.WHOLE_CONTRACTS_MAP_REFERENCE}) {
            Fixture fixture =
                    new Fixture(
                            false,
                            form,
                            true);

            // when
            DocumentProcessingResult result =
                    fixture.initialize();

            // then
            assertEquals(
                    ProcessorStatus.SUCCESS,
                    result.status(),
                    form + ": " + diagnosticMessage(result));
            assertEquals(
                    1,
                    result.document()
                            .getAsInteger("/unrelated"),
                    form + " did not execute the unrelated patch");
            assertTrue(
                    fixture.processor.matchAttempts > 0,
                    form + " never reached Handler matching");
            assertFalse(
                    fixture.processor
                            .programWasRequestedBeforeMatch,
                    form + " demanded the nested executable body before matching");
            assertFalse(
                    fixture.processor.executed,
                    form + " unexpectedly executed the nonmatching Handler");
            assertFalse(
                    fixture.providerRequests.contains(
                            fixture.programBlueId),
                    form + " demanded the nested executable body");
        }
    }

    @Test
    void shouldVerifyTypedPatchConformancePreservesBodyBehindReferencedContractRepresentations() {
        // given
        for (BodyForm form : new BodyForm[]{
                BodyForm.WHOLE_CONTRACT_REFERENCE,
                BodyForm.WHOLE_CONTRACTS_MAP_REFERENCE}) {
            Fixture fixture = new Fixture(false, form);

            // when
            ProcessingMetricsSnapshot metrics =
                    fixture.applyUnrelatedTypedPatchDirectly();

            // then
            assertTrue(
                    metrics.counter("conformancePlans") > 0,
                    form + " did not exercise conformance planning");
            assertFalse(
                    fixture.providerRequests.contains(
                            fixture.programBlueId),
                    form + " conformance demanded the nested executable body");
        }
    }

    @Test
    void shouldVerifyMatchingHandlerDemandsAndMaterializesOnlyItsDeclaredProgramField() {
        // given
        Fixture fixture = new Fixture(true);

        // when
        DocumentProcessingResult result =
                fixture.initialize();

        // then
        assertEquals(
                ProcessorStatus.SUCCESS,
                result.status(),
                diagnosticMessage(result));
        assertTrue(fixture.processor.executed);
        assertEquals("ran", result.document().getAsText("/ran"));
        assertTrue(fixture.processor.programWasMaterialized);
        assertFalse(fixture.processor.ordinaryBodyWasMaterialized,
                "undeclared body data must not be opened by executable-body selection");
        assertTrue(
                fixture.providerRequests.contains(
                        fixture.programBlueId));
        assertFalse(
                fixture.providerRequests.contains(
                        fixture.ordinaryBodyBlueId));
    }

    @Test
    void shouldVerifyMatcherSeesOnlyHeaderWhileExecutionReceivesExactBodyFromEagerSnapshotAcrossRepresentations() {
        // given
        for (BodyForm form : new BodyForm[]{
                BodyForm.INHERITED_INLINE,
                BodyForm.INHERITED_REFERENCE,
                BodyForm.DIRECT_INLINE,
                BodyForm.DIRECT_REFERENCE,
                BodyForm.WHOLE_CONTRACT_REFERENCE,
                BodyForm.WHOLE_CONTRACTS_MAP_REFERENCE}) {
            Fixture fixture =
                    new Fixture(true, form);

            // when
            DocumentProcessingResult result =
                    fixture.initialize();

            // then
            assertEquals(
                    ProcessorStatus.SUCCESS,
                    result.status(),
                    form + ": " + diagnosticMessage(result));
            assertFalse(
                    fixture.processor
                            .programWasVisibleDuringMatch,
                    form + " leaked executable content to matcher");
            assertTrue(
                    fixture.processor
                            .programWasMaterialized,
                    form + " did not deliver executable content after match");
            assertTrue(
                    fixture.processor
                            .programPatchEntryStayedExact,
                    form + " exposed resolved/injected body structure");
        }
    }

    @Test
    void shouldVerifySelectedExactReferenceAcceptsScalarProviderContent() {
        // given
        Node logicalBody = new Node().value("scalar");
        List<Node> providerResult =
                Collections.singletonList(
                        new Node().value("scalar"));

        // when
        ExactBodyObservation observation =
                executeExactReferencedBody(
                        logicalBody,
                        providerResult);

        // then
        assertExactReferencedBody(observation);
    }

    @Test
    void shouldVerifySelectedExactReferenceAcceptsMultiNodeListProviderContent() {
        // given
        Node first = new Node().value("first");
        Node second = new Node().value("second");
        Node logicalBody = new Node().items(
                first.clone(),
                second.clone());
        List<Node> providerResult =
                java.util.Arrays.asList(first, second);

        // when
        ExactBodyObservation observation =
                executeExactReferencedBody(
                        logicalBody,
                        providerResult);

        // then
        assertExactReferencedBody(observation);
    }

    private ExactBodyObservation executeExactReferencedBody(
            Node logicalBody,
            List<Node> providerResult) {
        String bodyBlueId =
                BlueIdCalculator.calculateBlueId(
                        logicalBody);
        Node handlerType =
                new Node()
                        .name("Opaque Body Handler")
                        .type(new Node().blueId(
                                RuntimeBlueIds.HANDLER));
        String handlerTypeBlueId =
                BlueIdCalculator.calculateBlueId(
                        handlerType);
        OpaqueBodyHandlerProcessor processor =
                new OpaqueBodyHandlerProcessor();
        NodeProvider provider = blueId -> {
            if (handlerTypeBlueId.equals(blueId)) {
                return Collections.singletonList(
                        handlerType.clone());
            }
            if (!bodyBlueId.equals(blueId)) {
                return null;
            }
            List<Node> copy =
                    new ArrayList<>(
                            providerResult.size());
            for (Node node : providerResult) {
                copy.add(node.clone());
            }
            return copy;
        };
        Node document = new Node()
                .name("Exact body provider representation")
                .contracts(new Node()
                        .properties(
                                "lifecycle",
                                new Node().type(
                                        new Node().blueId(
                                                RuntimeBlueIds
                                                        .LIFECYCLE_EVENT_CHANNEL)))
                        .properties(
                                "run",
                                new Node()
                                        .type(new Node()
                                                .blueId(
                                                        handlerTypeBlueId))
                                        .properties(
                                                "channel",
                                                new Node()
                                                        .value(
                                                                "lifecycle"))
                                        .properties(
                                                "program",
                                                new Node()
                                                        .blueId(
                                                                bodyBlueId))));

        DocumentProcessingResult result;
        try (Blue blue =
                     ProcessorTestSupport.blue(provider)) {
            blue.registerContractProcessor(
                    handlerTypeBlueId,
                    processor);
            result = blue.initializeDocument(
                    document);
        }
        return new ExactBodyObservation(
                bodyBlueId,
                processor,
                result);
    }

    private void assertExactReferencedBody(
            ExactBodyObservation observation) {
        assertEquals(
                ProcessorStatus.SUCCESS,
                observation.result.status(),
                diagnosticMessage(observation.result));
        assertFalse(
                observation.processor
                        .programWasVisibleDuringMatch);
        assertEquals(
                observation.bodyBlueId,
                BlueIdCalculator.calculateBlueId(
                        observation.processor.executedProgram));
    }

    private static final class ExactBodyObservation {
        private final String bodyBlueId;
        private final OpaqueBodyHandlerProcessor processor;
        private final DocumentProcessingResult result;

        private ExactBodyObservation(
                String bodyBlueId,
                OpaqueBodyHandlerProcessor processor,
                DocumentProcessingResult result) {
            this.bodyBlueId = bodyBlueId;
            this.processor = processor;
            this.result = result;
        }
    }

    private enum BodyForm {
        INHERITED_INLINE,
        INHERITED_REFERENCE,
        DIRECT_INLINE,
        DIRECT_REFERENCE,
        WHOLE_CONTRACT_REFERENCE,
        WHOLE_CONTRACTS_MAP_REFERENCE
    }

    private static final class Fixture {
        private static final String SET_PROPERTY_TYPE_BLUE_ID =
                "8Vii45Ph3HBUX2ZMEarxXXUBDPrXemrvqJergPr3BNts";
        private final Node programType =
                new Node()
                        .name("Program body")
                        .properties(
                                "value",
                                new Node().type(
                                        new Node().blueId(
                                                blue.language.utils
                                                        .Properties
                                                        .TEXT_TYPE_BLUE_ID)));
        private final String programTypeBlueId =
                BlueIdCalculator.calculateBlueId(
                        programType);
        private final Node program =
                new Node()
                        .type(new Node().blueId(
                                programTypeBlueId))
                        .properties(
                        "value", new Node().value("ran"))
                        .properties(
                                "patches",
                                new Node().items(
                                        new Node()
                                                .properties(
                                                        "op",
                                                        new Node().value(
                                                                "replace"))
                                                .properties(
                                                        "path",
                                                        new Node().value(
                                                                "/ran"))
                                                .properties(
                                                        "val",
                                                        new Node().value(
                                                                true))));
        private final String programBlueId =
                BlueIdCalculator.calculateBlueId(program);
        private final Node ordinaryBody =
                new Node().properties(
                        "ordinary", new Node().value("data"));
        private final String ordinaryBodyBlueId =
                BlueIdCalculator.calculateBlueId(
                        ordinaryBody);
        private final Node handlerType =
                new Node()
                        .name("Program Handler")
                        .type(new Node().blueId(
                                RuntimeBlueIds.HANDLER))
                        .properties(
                                "program",
                                new Node().type(
                                        new Node().blueId(
                                                programTypeBlueId)));
        private final String handlerTypeBlueId =
                BlueIdCalculator.calculateBlueId(
                        handlerType);
        private final Node scopeType;
        private final String scopeTypeBlueId;
        private final List<String> providerRequests =
                new ArrayList<>();
        private final ProgramHandlerProcessor processor;
        private final BodyForm bodyForm;
        private final boolean patchBeforeProgramMatch;
        private final boolean typedPartialEventMatcher;

        private Fixture(boolean matches) {
            this(matches,
                    BodyForm.INHERITED_REFERENCE,
                    false);
        }

        private Fixture(boolean matches,
                        BodyForm bodyForm) {
            this(matches, bodyForm, false);
        }

        private Fixture(boolean matches,
                        BodyForm bodyForm,
                        boolean patchBeforeProgramMatch) {
            this(matches,
                    bodyForm,
                    patchBeforeProgramMatch,
                    false);
        }

        private Fixture(boolean matches,
                        BodyForm bodyForm,
                        boolean patchBeforeProgramMatch,
                        boolean typedPartialEventMatcher) {
            this.processor =
                    new ProgramHandlerProcessor(
                            matches,
                            () -> providerRequests.contains(
                                    programBlueId));
            this.bodyForm = bodyForm;
            this.patchBeforeProgramMatch =
                    patchBeforeProgramMatch;
            this.typedPartialEventMatcher =
                    typedPartialEventMatcher;
            Node inheritedProgram =
                    bodyForm
                            == BodyForm
                            .INHERITED_INLINE
                            ? program.clone()
                            : new Node().blueId(
                                    programBlueId);
            this.scopeType =
                    new Node()
                            .name("Program Scope")
                            .contracts(
                                    new Node().properties(
                                            "run",
                                            new Node().properties(
                                                    "program",
                                                    inheritedProgram)));
            this.scopeTypeBlueId =
                    BlueIdCalculator.calculateBlueId(
                            scopeType);
        }

        private Node document() {
            Node handler = handlerContribution();
            Node selectedHandler =
                    bodyForm
                            == BodyForm
                            .WHOLE_CONTRACT_REFERENCE
                            ? new Node().blueId(
                            BlueIdCalculator.calculateBlueId(
                                    handler))
                            : handler;
            Node selectedContracts =
                    contracts(selectedHandler);
            if (bodyForm
                    == BodyForm
                    .WHOLE_CONTRACTS_MAP_REFERENCE) {
                selectedContracts =
                        new Node().blueId(
                                BlueIdCalculator.calculateBlueId(
                                        selectedContracts));
            }
            return new Node()
                    .name("Executable metadata document")
                    .type(new Node().blueId(
                            scopeTypeBlueId))
                    .contracts(selectedContracts);
        }

        private Node contracts(Node handler) {
            Node result = new Node()
                    .properties(
                            "lifecycle",
                            new Node().type(
                                    new Node().blueId(
                                            RuntimeBlueIds
                                                    .LIFECYCLE_EVENT_CHANNEL)));
            if (patchBeforeProgramMatch) {
                result.properties(
                        "mutate",
                        new Node()
                                .type(new Node().blueId(
                                        SET_PROPERTY_TYPE_BLUE_ID))
                                .properties(
                                        "channel",
                                        new Node().value(
                                                "lifecycle"))
                                .properties(
                                        "order",
                                        new Node().value(-1))
                                .properties(
                                        "propertyKey",
                                        new Node().value(
                                                "unrelated"))
                                .properties(
                                        "propertyValue",
                                        new Node().value(1)));
            }
            return result.properties("run", handler);
        }

        private Node handlerContribution() {
            Node handler = new Node()
                    .type(new Node().blueId(
                            handlerTypeBlueId))
                    .properties(
                            "channel",
                            new Node().value("lifecycle"))
                    .properties(
                            "body",
                            new Node().blueId(
                                    ordinaryBodyBlueId));
            if (typedPartialEventMatcher) {
                handler.properties(
                        "event",
                        new Node().type(
                                new Node().blueId(
                                        RuntimeBlueIds
                                                .DOCUMENT_PROCESSING_INITIATED)));
            }
            if (bodyForm == BodyForm.DIRECT_INLINE) {
                handler.properties(
                        "program", program.clone());
            } else if (bodyForm
                    == BodyForm.DIRECT_REFERENCE
                    || bodyForm
                    == BodyForm.WHOLE_CONTRACT_REFERENCE
                    || bodyForm
                    == BodyForm
                    .WHOLE_CONTRACTS_MAP_REFERENCE) {
                handler.properties(
                        "program",
                        new Node().blueId(
                                programBlueId));
            }
            return handler;
        }

        private DocumentProcessingResult initialize() {
            Map<String, Node> content =
                    new LinkedHashMap<>();
            content.put(
                    programBlueId, program);
            content.put(
                    programTypeBlueId, programType);
            content.put(
                    ordinaryBodyBlueId, ordinaryBody);
            content.put(
                    handlerTypeBlueId, handlerType);
            content.put(
                    scopeTypeBlueId, scopeType);
            if (bodyForm
                    == BodyForm.WHOLE_CONTRACT_REFERENCE) {
                Node handler =
                        handlerContribution();
                content.put(
                        BlueIdCalculator.calculateBlueId(
                                handler),
                        handler);
            } else if (bodyForm
                    == BodyForm
                    .WHOLE_CONTRACTS_MAP_REFERENCE) {
                Node exactContracts =
                        contracts(
                                handlerContribution());
                content.put(
                        BlueIdCalculator.calculateBlueId(
                                exactContracts),
                        exactContracts);
            }
            NodeProvider provider = blueId -> {
                providerRequests.add(blueId);
                Node found = content.get(blueId);
                return found != null
                        ? Collections.singletonList(
                        found.clone())
                        : null;
            };
            try (Blue blue =
                         ProcessorTestSupport.blue(provider)) {
                blue.registerContractProcessor(
                        handlerTypeBlueId,
                        processor);
                if (patchBeforeProgramMatch) {
                    blue.registerContractProcessor(
                            new SetPropertyContractProcessor());
                }
                return blue.initializeDocument(
                        document());
            }
        }

        private ProcessingMetricsSnapshot applyUnrelatedTypedPatchDirectly() {
            Map<String, Node> content =
                    new LinkedHashMap<>();
            Node generalScopeType =
                    new Node()
                            .name("General program scope")
                            .properties(
                                    "unrelated",
                                    new Node().type(
                                            new Node().blueId(
                                                    blue.language.utils
                                                            .Properties
                                                            .TEXT_TYPE_BLUE_ID)));
            String generalScopeTypeBlueId =
                    BlueIdCalculator.calculateBlueId(
                            generalScopeType);
            Node specificScopeType =
                    new Node()
                            .name("Specific program scope")
                            .type(new Node().blueId(
                                    generalScopeTypeBlueId))
                            .properties(
                                    "unrelated",
                                    new Node().value(
                                            "before"));
            String specificScopeTypeBlueId =
                    BlueIdCalculator.calculateBlueId(
                            specificScopeType);
            content.put(
                    programBlueId, program);
            content.put(
                    programTypeBlueId, programType);
            content.put(
                    ordinaryBodyBlueId, ordinaryBody);
            content.put(
                    handlerTypeBlueId, handlerType);
            content.put(
                    scopeTypeBlueId, scopeType);
            content.put(
                    generalScopeTypeBlueId,
                    generalScopeType);
            content.put(
                    specificScopeTypeBlueId,
                    specificScopeType);
            if (bodyForm
                    == BodyForm.WHOLE_CONTRACT_REFERENCE) {
                Node handler =
                        handlerContribution();
                content.put(
                        BlueIdCalculator.calculateBlueId(
                                handler),
                        handler);
            } else if (bodyForm
                    == BodyForm
                    .WHOLE_CONTRACTS_MAP_REFERENCE) {
                Node exactContracts =
                        contracts(
                                handlerContribution());
                content.put(
                        BlueIdCalculator.calculateBlueId(
                                exactContracts),
                        exactContracts);
            }
            NodeProvider provider = blueId -> {
                providerRequests.add(blueId);
                Node found = content.get(blueId);
                return found != null
                        ? Collections.singletonList(
                        found.clone())
                        : null;
            };
            try (Blue blue =
                         ProcessorTestSupport.blue(provider);
                 Blue freshConformanceBlue =
                         ProcessorTestSupport.blue(provider)) {
                blue.registerContractProcessor(
                        handlerTypeBlueId,
                        processor);
                ContractProcessorRegistry registry =
                        blue.getDocumentProcessor()
                                .registry();
                ProcessingSnapshotManager manager =
                        blue.getDocumentProcessor()
                                .snapshotManager();
                Node selected =
                        document()
                                .type(new Node().blueId(
                                        specificScopeTypeBlueId))
                                .properties(
                                        "unrelated",
                                        new Node().value(
                                                "before"));
                ResolvedSnapshot snapshot =
                        manager.fromDocumentPreservingPaths(
                                selected,
                                Collections.singleton(
                                        "/contracts/run/program"));
                FrozenNode canonicalContracts =
                        snapshot.frozenCanonicalRoot()
                                .getContracts();
                assertTrue(
                        bodyForm
                                == BodyForm
                                .WHOLE_CONTRACTS_MAP_REFERENCE
                                ? canonicalContracts
                                .isReferenceOnly()
                                : canonicalContracts
                                .property("run")
                                .isReferenceOnly(),
                        bodyForm
                                + " snapshot setup lost the outer reference");
                assertFalse(
                        providerRequests.contains(
                                programBlueId),
                        bodyForm
                                + " snapshot setup eagerly requested the program");
                providerRequests.clear();
                RecordingProcessingMetricsSink metrics =
                        new RecordingProcessingMetricsSink();
                DocumentProcessingRuntime runtime =
                        new DocumentProcessingRuntime(
                                snapshot,
                                freshConformanceBlue
                                        .conformanceEngine(),
                                null,
                                manager,
                                metrics,
                                new GasMeter(),
                                registry
                                        .executableBodyFieldsByType());

                runtime.applyPatch(
                        "/",
                        JsonPatch.replace(
                                "/unrelated",
                                new Node().value(
                                        "after")));

                assertEquals(
                        "after",
                        runtime.document()
                                .getAsText(
                                        "/unrelated"));
                assertEquals(
                        generalScopeTypeBlueId,
                        runtime.document()
                                .getType()
                                .getBlueId());
                return metrics.snapshot();
            }
        }
    }

    public static final class ProgramHandler
            extends HandlerContract {
        private Node program;
        private Node body;

        public Node getProgram() {
            return program;
        }

        public void setProgram(Node program) {
            this.program = program;
        }

        public Node getBody() {
            return body;
        }

        public void setBody(Node body) {
            this.body = body;
        }
    }

    private static final class ProgramHandlerProcessor
            implements HandlerProcessor<ProgramHandler> {
        private final boolean matches;
        private boolean executed;
        private boolean programWasVisibleDuringMatch;
        private boolean programWasMaterialized;
        private boolean programPatchEntryStayedExact;
        private boolean ordinaryBodyWasMaterialized;
        private Node eventMatcherDuringMatch;
        private Node eventMatcherDuringExecution;
        private int matchAttempts;
        private boolean programWasRequestedBeforeMatch;
        private final BooleanSupplier
                programRequested;
        private final List<String> declaredExecutableFields =
                new ArrayList<>(
                        Collections.singletonList(
                                "program"));

        private ProgramHandlerProcessor(boolean matches) {
            this(matches, () -> false);
        }

        private ProgramHandlerProcessor(
                boolean matches,
                BooleanSupplier programRequested) {
            this.matches = matches;
            this.programRequested =
                    programRequested;
        }

        @Override
        public Class<ProgramHandler> contractType() {
            return ProgramHandler.class;
        }

        @Override
        public List<String> executableBodyFields() {
            return declaredExecutableFields;
        }

        @Override
        public boolean matches(
                ProgramHandler contract,
                HandlerMatchContext context) {
            matchAttempts++;
            programWasRequestedBeforeMatch =
                    programWasRequestedBeforeMatch
                            || programRequested
                            .getAsBoolean();
            programWasVisibleDuringMatch =
                    contract.getProgram() != null;
            eventMatcherDuringMatch =
                    contract.getEvent() != null
                            ? contract.getEvent().clone()
                            : null;
            return matches;
        }

        @Override
        public void execute(
                ProgramHandler contract,
                ProcessorExecutionContext context) {
            executed = true;
            programWasMaterialized =
                    contract.getProgram() != null
                            && !contract.getProgram()
                            .isReferenceOnly();
            Node patches =
                    contract.getProgram() != null
                            && contract.getProgram()
                            .getProperties() != null
                            ? contract.getProgram()
                            .getProperties().get(
                                    "patches")
                            : null;
            programPatchEntryStayedExact =
                    patches != null
                            && patches.getItems() != null
                            && !patches.getItems().isEmpty()
                            && patches.getItems().get(0)
                            .getType() == null;
            ordinaryBodyWasMaterialized =
                    contract.getBody() != null
                            && !contract.getBody()
                            .isReferenceOnly();
            eventMatcherDuringExecution =
                    contract.getEvent() != null
                            ? contract.getEvent().clone()
                            : null;
            Node value =
                    contract.getProgram()
                            .getProperties()
                            .get("value");
            context.applyPatch(
                    JsonPatch.add(
                            "/ran", value.clone()));
        }
    }

    private static final class OpaqueBodyHandlerProcessor
            implements HandlerProcessor<ProgramHandler> {
        private boolean programWasVisibleDuringMatch;
        private Node executedProgram;

        @Override
        public Class<ProgramHandler> contractType() {
            return ProgramHandler.class;
        }

        @Override
        public List<String> executableBodyFields() {
            return Collections.singletonList(
                    "program");
        }

        @Override
        public boolean matches(
                ProgramHandler contract,
                HandlerMatchContext context) {
            programWasVisibleDuringMatch =
                    contract.getProgram() != null;
            return true;
        }

        @Override
        public void execute(
                ProgramHandler contract,
                ProcessorExecutionContext context) {
            executedProgram =
                    contract.getProgram();
        }
    }
}
