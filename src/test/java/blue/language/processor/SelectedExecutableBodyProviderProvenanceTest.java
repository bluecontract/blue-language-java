package blue.language.processor;

import blue.language.Blue;
import blue.language.identity.CanonicalTypeIdentityEvidence;
import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.BlueId;
import blue.language.model.Node;
import blue.language.merge.ResolvedSnapshot;
import blue.language.processor.conformance.MockHandler;
import blue.language.processor.conformance.MockTypeBlueIds;
import blue.language.processor.model.HandlerContract;
import blue.language.processor.model.JsonPatch;
import blue.language.provider.NodeProvider;
import blue.language.runtime.BlueLanguage;
import blue.language.runtime.LanguageProcessing;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static blue.language.processor.FailureCapture.captureFailure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelectedExecutableBodyProviderProvenanceTest {

    private static final CanonicalTypeIdentityLookup COMPLETE_EMPTY_EVIDENCE =
            new CanonicalTypeIdentityLookup() {
                @Override
                public boolean hasCompleteCoverage() {
                    return true;
                }

                @Override
                public Optional<String> findCanonicalTypeBlueId(
                        Node completedType) {
                    if (completedType == null) {
                        throw new NullPointerException("completedType");
                    }
                    return completedType.isReferenceOnly()
                            ? Optional.of(completedType.getBlueId())
                            : Optional.<String>empty();
                }

                @Override
                public Optional<CanonicalTypeIdentityEvidence>
                findCanonicalTypeIdentityEvidence(
                        Node completedType) {
                    if (completedType == null) {
                        throw new NullPointerException("completedType");
                    }
                    return completedType.isReferenceOnly()
                            ? Optional.of(
                                    CanonicalTypeIdentityEvidence
                                            .referenceSource(
                                                    completedType
                                                            .getBlueId()))
                            : Optional
                                    .<CanonicalTypeIdentityEvidence>empty();
                }

                @Override
                public String requireCanonicalTypeBlueId(
                        Node completedType) {
                    return findCanonicalTypeBlueId(completedType)
                            .orElseThrow(() -> new IllegalStateException(
                                    "No canonical type identity evidence"));
                }
            };

    @Test
    void shouldUseActiveSnapshotManagerForSelectedBodyInsteadOfMatchingBlueProvider() {
        // given
        Node body = new Node().properties(
                "provenance", new Node().value("active-snapshot-manager"));
        String bodyBlueId =
                DirectBlueIdCalculator.calculateBlueId(body);
        ActiveProviderManager activeManager =
                new ActiveProviderManager(bodyBlueId, body);

        AtomicInteger matchingProviderFetches =
                new AtomicInteger();
        Blue matchingBlue = new Blue(blueId -> {
            if (bodyBlueId.equals(blueId)) {
                matchingProviderFetches.incrementAndGet();
                return Collections.singletonList(
                        new Node().value(
                                "wrong matching-provider content"));
            }
            return null;
        });
        CapturingMockHandlerProcessor handlerProcessor =
                new CapturingMockHandlerProcessor();
        ContractProcessorRegistry registry =
                ContractProcessorRegistryBuilder.create()
                        .register(handlerProcessor)
                        .build();
        DocumentProcessor owner = DocumentProcessor.builder()
                .runtimeRegistry(registry)
                .snapshotStore(activeManager)
                .matchingService(
                        new ContractMatchingService(matchingBlue))
                .build();

        MockHandler selected = new MockHandler();
        selected.setTypeBlueId(
                MockTypeBlueIds.MOCK_HANDLER);
        selected.setChannelKey("events");
        selected.setResult(
                new Node().blueId(bodyBlueId));
        Node selectedNode = new Node()
                .type(new Node().blueId(
                        MockTypeBlueIds.MOCK_HANDLER))
                .properties("channel",
                        new Node().value("events"))
                .properties("result",
                        new Node().blueId(bodyBlueId));
        ContractBundle bundle = ContractBundle.builder()
                .addHandler(
                        "selected",
                        selected,
                        FrozenNode.fromResolvedNode(
                                selectedNode),
                        Collections.singletonList("result"),
                        COMPLETE_EMPTY_EVIDENCE)
                .build();
        ResolvedSnapshot invocationSnapshot =
                activeManager.fromDocument(new Node());
        ProcessorInvocationState execution =
                new ProcessorInvocationState(
                        owner, invocationSnapshot);
        ChannelRunner runner = new ChannelRunner(
                owner,
                execution,
                execution.runtime(),
                new CheckpointManager(execution.runtime()));

        // when
        boolean handled = runner.runHandlers(
                "/", bundle, "events", new Node());

        // then
        assertTrue(handled);
        assertEquals(1, activeManager.materializations);
        assertEquals(0, matchingProviderFetches.get());
        assertNotNull(handlerProcessor.executedResult);
        assertEquals("active-snapshot-manager",
                handlerProcessor.executedResult
                        .getAsText("/provenance"));
    }

    @Test
    void shouldComputeDirectInlineBodyIdentityFromExactSource() {
        // given
        Node inlineType = new Node()
                .name("Direct inline selected-body type")
                .properties(
                        "declaration",
                        new Node().value("must remain authored"));
        Node body = new Node().properties(
                "payload",
                new Node()
                        .type(inlineType)
                        .properties(
                                "actual",
                                new Node().value("stable")));
        try (BlueLanguage language = BlueLanguage.builder().build();
             LanguageProcessing.Scope scope =
                     language.processing().openScope()) {
            Node exactSource = language.preprocessing().preprocess(
                    body.clone());
            ProcessingSnapshotManager manager =
                    new LanguageProcessingSnapshotManager(scope);
            ResolvedSnapshot bodyEvidence = manager
                    .fromDocumentTransientForCanonicalIdentity(
                            exactSource.clone());
            CapturingMockHandlerProcessor handlerProcessor =
                    new CapturingMockHandlerProcessor();
            DocumentProcessor owner = owner(manager, handlerProcessor);
            ContractBundle bundle = selectedHandlerBundle(
                    exactSource,
                    Collections.singletonList("result"),
                    bodyEvidence.canonicalTypeIdentities());
            ProcessorInvocationState execution =
                    new ProcessorInvocationState(
                            owner,
                            manager.fromDocument(new Node()));
            ChannelRunner runner = runner(owner, execution);

            // when
            boolean handled = runner.runHandlers(
                    "/", bundle, "events", new Node());

            // then
            assertTrue(handled);
            assertEquals(
                    bodyEvidence.blueId(),
                    handlerProcessor.selectedBodyBlueId);
            assertFalse(handlerProcessor.selectedBodyWasMaterialized);
            owner.close();
        }
    }

    @Test
    void shouldCarrySameOperationTypeEvidenceIntoSelectedBodyConversion() {
        // given
        BodyEvidenceMode evidenceMode = BodyEvidenceMode.AUTHORITATIVE;

        // when
        TypedBodyOutcome outcome = runTypedBody(evidenceMode);

        // then
        assertNull(outcome.failure);
        assertEquals(1, outcome.executions);
        assertEquals(outcome.expectedSubjectBlueId,
                outcome.executedSubjectBlueId);
        assertEquals(0, outcome.coldReferenceFetches);
    }

    @Test
    void shouldFailSelectedBodyConversionClosedWithoutBodyTypeEvidence() {
        // given
        BodyEvidenceMode evidenceMode =
                BodyEvidenceMode.STRIP_TYPE_EVIDENCE;

        // when
        TypedBodyOutcome outcome = runTypedBody(evidenceMode);

        // then
        assertNotNull(outcome.failure);
        assertEquals(0, outcome.executions);
        assertTrue(outcome.failure.getMessage().contains(
                "No resolver-issued canonical type identity evidence"));
    }

    @Test
    void shouldRejectSelectedBodyResolutionThatChangesExactCanonicalContent() {
        // given
        BodyEvidenceMode evidenceMode =
                BodyEvidenceMode.MISMATCH_CANONICAL_CONTENT;

        // when
        TypedBodyOutcome outcome = runTypedBody(evidenceMode);

        // then
        assertNotNull(outcome.failure);
        assertEquals(0, outcome.executions);
        assertTrue(outcome.failure.getMessage().contains(
                "Selected executable body resolver identity mismatch"));
    }

    @Test
    void shouldRevalidateManagerOwnedExactResultInActiveRuntimeMaterializer() {
        // given
        Node body = new Node().value("owned");
        String bodyBlueId =
                DirectBlueIdCalculator.calculateBlueId(body);
        ActiveProviderManager manager =
                new ActiveProviderManager(bodyBlueId, body);
        DocumentProcessingRuntime runtime =
                new DocumentProcessingRuntime(
                        new Node(), null, manager);
        FrozenNode reference =
                FrozenNode.fromResolvedNode(
                        new Node().blueId(bodyBlueId));

        // when
        FrozenNode materialized =
                runtime.materializeSelectedExecutableReference(
                        reference);

        // then
        assertEquals(1, manager.materializations);
        assertEquals(bodyBlueId,
                materialized.blueId());
        assertTrue(materialized.isStrictCanonical());
    }

    @Test
    void shouldFailRuntimeMaterializationClosedWithoutSnapshotManager() {
        // given
        DocumentProcessingRuntime runtime =
                new DocumentProcessingRuntime(new Node());
        FrozenNode reference =
                FrozenNode.fromResolvedNode(
                        new Node().blueId(
                                DirectBlueIdCalculator.calculateBlueId(
                                        new Node().value("body"))));

        // when
        Throwable failure = captureFailure(
                () -> runtime
                        .materializeSelectedExecutableReference(
                                reference));

        // then
        assertInstanceOf(IllegalStateException.class, failure);
    }

    @Test
    void shouldRejectManagerContentThatDoesNotMatchSelectedBodyReference() {
        // given
        Node exact = new Node().value("exact");
        String bodyBlueId =
                DirectBlueIdCalculator.calculateBlueId(exact);
        ActiveProviderManager manager =
                new ActiveProviderManager(
                        bodyBlueId,
                        new Node().value("expanded-or-wrong"));
        DocumentProcessingRuntime runtime =
                new DocumentProcessingRuntime(
                        new Node(), null, manager);
        FrozenNode reference = FrozenNode.fromNode(
                new Node().blueId(bodyBlueId));

        // when
        Throwable failure = captureFailure(
                () -> runtime
                        .materializeSelectedExecutableReference(
                                reference));

        // then
        assertInstanceOf(
                ProcessorFailureException.class,
                failure);
        assertEquals(
                ProcessorErrorCategory
                        .InvalidProcessingDocument,
                ((ProcessorFailureException) failure)
                        .errorCategory());
    }

    @Test
    void shouldPropagateInvalidEvidenceFromSelectedBodyMaterialization() {
        // given
        Node body =
                new Node().value(
                        "selected body");
        String bodyBlueId =
                DirectBlueIdCalculator.calculateBlueId(
                        body);
        InvalidExecutionEvidenceException invalidEvidence =
                new InvalidExecutionEvidenceException(
                        "forged selected-body evidence");
        ActiveProviderManager manager =
                new ActiveProviderManager(
                        bodyBlueId,
                        body,
                        invalidEvidence);
        CapturingMockHandlerProcessor handlerProcessor =
                new CapturingMockHandlerProcessor();
        DocumentProcessor owner =
                owner(
                        manager,
                        handlerProcessor);
        ContractBundle bundle =
                selectedHandlerBundle(
                        new Node().blueId(
                                bodyBlueId),
                        Collections.singletonList(
                                "result"));
        ProcessorInvocationState execution =
                new ProcessorInvocationState(
                        owner,
                        manager.fromDocument(
                                new Node()));
        ChannelRunner runner =
                runner(
                        owner,
                        execution);

        // when
        Throwable failure =
                captureFailure(
                        () -> runner.runHandlers(
                                "/",
                                bundle,
                                "events",
                                new Node()));

        // then
        assertInstanceOf(
                InvalidExecutionEvidenceException.class,
                failure);
        assertSame(
                invalidEvidence,
                failure);
    }

    @Test
    void shouldPropagateInvalidEvidenceFromHandlerExecution() {
        // given
        Node body =
                new Node().value(
                        "inline body");
        String bodyBlueId =
                DirectBlueIdCalculator.calculateBlueId(
                        body);
        ActiveProviderManager manager =
                new ActiveProviderManager(
                        bodyBlueId,
                        body);
        InvalidExecutionEvidenceException invalidEvidence =
                new InvalidExecutionEvidenceException(
                        "forged handler evidence");
        ThrowingMockHandlerProcessor handlerProcessor =
                new ThrowingMockHandlerProcessor(
                        invalidEvidence);
        DocumentProcessor owner =
                owner(
                        manager,
                        handlerProcessor);
        ContractBundle bundle =
                selectedHandlerBundle(
                        body,
                        Collections.<String>emptyList());
        ProcessorInvocationState execution =
                new ProcessorInvocationState(
                        owner,
                        manager.fromDocument(
                                new Node()));
        ChannelRunner runner =
                runner(
                        owner,
                        execution);

        // when
        Throwable failure =
                captureFailure(
                        () -> runner.runHandlers(
                                "/",
                                bundle,
                                "events",
                                new Node()));

        // then
        assertInstanceOf(
                InvalidExecutionEvidenceException.class,
                failure);
        assertSame(
                invalidEvidence,
                failure);
    }

    private static DocumentProcessor owner(
            ProcessingSnapshotManager manager,
            HandlerProcessor<MockHandler> handlerProcessor) {
        ContractProcessorRegistry registry =
                ContractProcessorRegistryBuilder.create()
                        .register(
                                handlerProcessor)
                        .build();
        return DocumentProcessor.builder()
                .runtimeRegistry(
                        registry)
                .snapshotStore(
                        manager)
                .build();
    }

    private static ContractBundle selectedHandlerBundle(
            Node result,
            List<String> executableBodyFields) {
        return selectedHandlerBundle(
                result,
                executableBodyFields,
                COMPLETE_EMPTY_EVIDENCE);
    }

    private static ContractBundle selectedHandlerBundle(
            Node result,
            List<String> executableBodyFields,
            CanonicalTypeIdentityLookup typeIdentities) {
        MockHandler selected =
                new MockHandler();
        selected.setTypeBlueId(
                MockTypeBlueIds.MOCK_HANDLER);
        selected.setChannelKey(
                "events");
        selected.setResult(
                result);
        Node selectedNode =
                new Node()
                        .type(new Node().blueId(
                                MockTypeBlueIds.MOCK_HANDLER))
                        .properties(
                                "channel",
                                new Node().value(
                                        "events"))
                        .properties(
                                "result",
                                result.clone());
        return ContractBundle.builder()
                .addHandler(
                        "selected",
                        selected,
                        FrozenNode.fromResolvedNode(
                                selectedNode),
                        executableBodyFields,
                        typeIdentities)
                .build();
    }

    private static TypedBodyOutcome runTypedBody(
            BodyEvidenceMode mode) {
        Node handlerType = new Node().name(
                "Selected executable body evidence Handler");
        String handlerTypeBlueId =
                DirectBlueIdCalculator.calculateBlueId(handlerType);
        Node subjectType = new Node().name(
                "Selected executable body Subject");
        String subjectTypeBlueId =
                DirectBlueIdCalculator.calculateBlueId(subjectType);
        Node subject = new Node()
                .type(new Node().blueId(subjectTypeBlueId))
                .properties("payload", new Node().value("stable"));
        Node coldContent = new Node().properties(
                "payload", new Node().value("must stay cold"));
        String coldBlueId =
                DirectBlueIdCalculator.calculateBlueId(coldContent);
        Node body = new Node()
                .properties("subject", subject)
                .properties("cold", new Node().blueId(coldBlueId));
        String bodyBlueId = DirectBlueIdCalculator.calculateBlueId(body);
        Map<String, Node> providerContent = new LinkedHashMap<>();
        providerContent.put(handlerTypeBlueId, handlerType);
        providerContent.put(subjectTypeBlueId, subjectType);
        providerContent.put(bodyBlueId, body);
        providerContent.put(coldBlueId, coldContent);
        AtomicInteger coldReferenceFetches = new AtomicInteger();
        NodeProvider provider = blueId -> {
            if (coldBlueId.equals(blueId)) {
                coldReferenceFetches.incrementAndGet();
            }
            Node provided = providerContent.get(blueId);
            return provided != null
                    ? Collections.singletonList(provided.clone())
                    : null;
        };
        TypedBodyHandlerProcessor handlerProcessor =
                new TypedBodyHandlerProcessor();

        try (BlueLanguage language = BlueLanguage.builder()
                .nodeProvider(provider)
                .build();
             LanguageProcessing.Scope scope =
                     language.processing().openScope()) {
            ProcessingSnapshotManager authoritative =
                    new LanguageProcessingSnapshotManager(scope);
            Node authoredHandler = new Node()
                    .type(new Node().blueId(handlerTypeBlueId))
                    .properties("channel", new Node().value("events"))
                    .properties("result", new Node().blueId(bodyBlueId));
            ResolvedSnapshot preservedHeader = authoritative
                    .fromDocumentTransientPreservingPaths(
                            authoredHandler,
                            Collections.singleton("/result"));
            assertTrue(preservedHeader.canonicalTypeIdentities()
                    .hasCompleteCoverage());
            assertTrue(preservedHeader.frozenResolvedRoot()
                    .getProperties().get("result").isReferenceOnly());

            TypedBodyHandler selected = new TypedBodyHandler();
            selected.setTypeBlueId(handlerTypeBlueId);
            selected.setChannelKey("events");
            ContractBundle bundle = ContractBundle.builder(
                            preservedHeader.canonicalTypeIdentities())
                    .addHandler(
                            "selected",
                            selected,
                            preservedHeader.frozenResolvedRoot(),
                            Collections.singletonList("result"),
                            preservedHeader.canonicalTypeIdentities())
                    .build();
            ContractProcessorRegistry registry =
                    ContractProcessorRegistryBuilder.create()
                            .register(
                                    handlerTypeBlueId,
                                    handlerType,
                                    handlerProcessor)
                            .build();
            ProcessingSnapshotManager runtimeManager =
                    mode == BodyEvidenceMode.AUTHORITATIVE
                            ? authoritative
                            : new BodyEvidenceManager(
                                    authoritative,
                                    mode);
            try (DocumentProcessor owner = DocumentProcessor.builder()
                    .runtimeRegistry(registry)
                    .snapshotStore(runtimeManager)
                    .build()) {
                ProcessorInvocationState execution =
                        new ProcessorInvocationState(
                                owner,
                                authoritative.fromDocument(new Node()));
                ChannelRunner runner = runner(owner, execution);
                Throwable failure = captureFailure(
                        () -> runner.runHandlers(
                                "/",
                                bundle,
                                "events",
                                new Node()));
                return new TypedBodyOutcome(
                        failure,
                        handlerProcessor.executions,
                        handlerProcessor.executedSubjectBlueId,
                        DirectBlueIdCalculator.calculateBlueId(subject),
                        coldReferenceFetches.get());
            }
        }
    }

    private static ChannelRunner runner(
            DocumentProcessor owner,
            ProcessorInvocationState execution) {
        return new ChannelRunner(
                owner,
                execution,
                execution.runtime(),
                new CheckpointManager(
                        execution.runtime()));
    }

    private static Throwable captureFailure(Runnable operation) {
        try {
            operation.run();
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    private static final class CapturingMockHandlerProcessor
            implements HandlerProcessor<MockHandler> {
        private Node executedResult;
        private String selectedBodyBlueId;
        private boolean selectedBodyWasMaterialized;

        @Override
        public Class<MockHandler> contractType() {
            return MockHandler.class;
        }

        @Override
        public List<String> executableBodyFields() {
            return Collections.singletonList("result");
        }

        @Override
        public void execute(
                MockHandler contract,
                ProcessorExecutionContext context) {
            executedResult = contract.getResult();
            SelectedExecutableBody selected =
                    context.selectedExecutableBody("result");
            if (selected != null) {
                selectedBodyBlueId = selected.bodyBlueId();
                selectedBodyWasMaterialized =
                        selected.wasMaterializedFromReference();
            }
        }
    }

    private static final class ThrowingMockHandlerProcessor
            implements HandlerProcessor<MockHandler> {
        private final InvalidExecutionEvidenceException
                invalidEvidence;

        private ThrowingMockHandlerProcessor(
                InvalidExecutionEvidenceException
                        invalidEvidence) {
            this.invalidEvidence =
                    invalidEvidence;
        }

        @Override
        public Class<MockHandler> contractType() {
            return MockHandler.class;
        }

        @Override
        public void execute(
                MockHandler contract,
                ProcessorExecutionContext context) {
            throw invalidEvidence;
        }
    }

    private static final class ActiveProviderManager
            implements ProcessingSnapshotManager {
        private final String bodyBlueId;
        private final FrozenNode materializedBody;
        private final RuntimeException
                materializationFailure;
        private int materializations;

        private ActiveProviderManager(
                String bodyBlueId,
                Node body) {
            this(
                    bodyBlueId,
                    body,
                    null);
        }

        private ActiveProviderManager(
                String bodyBlueId,
                Node body,
                RuntimeException
                        materializationFailure) {
            this.bodyBlueId = bodyBlueId;
            this.materializedBody =
                    FrozenNode.fromResolvedNode(body);
            this.materializationFailure =
                    materializationFailure;
        }

        @Override
        public ResolvedSnapshot fromDocument(
                Node document) {
            FrozenNode canonical = FrozenNode.fromNode(document);
            return ResolvedSnapshot.withCanonicalTypeIdentities(
                    canonical,
                    FrozenNode.fromResolvedNode(document),
                    COMPLETE_EMPTY_EVIDENCE);
        }

        @Override
        public FrozenNode materializeVerifiedReference(
                FrozenNode reference) {
            assertTrue(reference.isReferenceOnly());
            assertEquals(bodyBlueId,
                    reference.getReferenceBlueId());
            materializations++;
            if (materializationFailure != null) {
                throw materializationFailure;
            }
            return materializedBody;
        }

        @Override
        public ResolvedSnapshot applyPatch(
                ResolvedSnapshot snapshot,
                JsonPatch patch) {
            return snapshot;
        }
    }

    private enum BodyEvidenceMode {
        AUTHORITATIVE,
        STRIP_TYPE_EVIDENCE,
        MISMATCH_CANONICAL_CONTENT
    }

    private static final class TypedBodyOutcome {
        private final Throwable failure;
        private final int executions;
        private final String executedSubjectBlueId;
        private final String expectedSubjectBlueId;
        private final int coldReferenceFetches;

        private TypedBodyOutcome(
                Throwable failure,
                int executions,
                String executedSubjectBlueId,
                String expectedSubjectBlueId,
                int coldReferenceFetches) {
            this.failure = failure;
            this.executions = executions;
            this.executedSubjectBlueId = executedSubjectBlueId;
            this.expectedSubjectBlueId = expectedSubjectBlueId;
            this.coldReferenceFetches = coldReferenceFetches;
        }
    }

    public static final class TypedBodyHandler extends HandlerContract {
        private TypedBody result;

        public TypedBody getResult() {
            return result;
        }

        public void setResult(TypedBody result) {
            this.result = result;
        }
    }

    public static final class TypedBody {
        @BlueId
        private String subject;
    }

    private static final class TypedBodyHandlerProcessor
            implements HandlerProcessor<TypedBodyHandler> {
        private int executions;
        private String executedSubjectBlueId;

        @Override
        public Class<TypedBodyHandler> contractType() {
            return TypedBodyHandler.class;
        }

        @Override
        public List<String> executableBodyFields() {
            return Collections.singletonList("result");
        }

        @Override
        public void execute(
                TypedBodyHandler contract,
                ProcessorExecutionContext context) {
            executions++;
            executedSubjectBlueId = contract.getResult().subject;
        }
    }

    private static final class BodyEvidenceManager
            implements ProcessingSnapshotManager {
        private final ProcessingSnapshotManager delegate;
        private final BodyEvidenceMode mode;

        private BodyEvidenceManager(
                ProcessingSnapshotManager delegate,
                BodyEvidenceMode mode) {
            this.delegate = delegate;
            this.mode = mode;
        }

        @Override
        public ResolvedSnapshot fromDocument(Node document) {
            return delegate.fromDocument(document);
        }

        @Override
        public ResolvedSnapshot fromDocumentTransient(Node document) {
            if (mode == BodyEvidenceMode.MISMATCH_CANONICAL_CONTENT) {
                return delegate.fromDocumentTransient(
                        new Node().value("wrong selected body"));
            }
            ResolvedSnapshot established =
                    delegate.fromDocumentTransient(document);
            return new ResolvedSnapshot(
                    established.frozenCanonicalRoot(),
                    established.frozenResolvedRoot(),
                    established.blueId());
        }

        @Override
        public ResolvedSnapshot fromDocumentPreservingPaths(
                Node document,
                Collection<String> preservedPaths) {
            return transform(delegate.fromDocumentPreservingPaths(
                    document,
                    preservedPaths));
        }

        @Override
        public ResolvedSnapshot fromDocumentTransientPreservingPaths(
                Node document,
                Collection<String> preservedPaths) {
            return transform(
                    delegate.fromDocumentTransientPreservingPaths(
                            document,
                            preservedPaths));
        }

        @Override
        public FrozenNode materializeVerifiedExactReference(
                FrozenNode reference) {
            return delegate.materializeVerifiedExactReference(reference);
        }

        @Override
        public ResolvedSnapshot applyPatch(
                ResolvedSnapshot snapshot,
                JsonPatch patch) {
            return delegate.applyPatch(snapshot, patch);
        }

        private ResolvedSnapshot transform(
                ResolvedSnapshot established) {
            if (mode == BodyEvidenceMode.MISMATCH_CANONICAL_CONTENT) {
                return delegate.fromDocumentTransient(
                        new Node().value("wrong selected body"));
            }
            return new ResolvedSnapshot(
                    established.frozenCanonicalRoot(),
                    established.frozenResolvedRoot(),
                    established.blueId());
        }
    }
}
