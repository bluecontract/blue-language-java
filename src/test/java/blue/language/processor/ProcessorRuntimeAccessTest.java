package blue.language.processor;

import blue.language.api.BlueCachePolicy;
import blue.language.api.BlueOperationOutcome;
import blue.language.api.BlueOperationResult;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.merge.ResolvedSnapshot;
import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.model.MarkerContract;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.provider.NodeProvider;
import blue.language.runtime.BlueLanguageRuntime;
import blue.language.runtime.LanguageRuntimeAccess;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static blue.language.model.wire.BlueLanguageConstants.TEXT_TYPE_BLUE_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Focused coverage for borrowed processor runtime access. */
final class ProcessorRuntimeAccessTest {

    private static final String REQUIRED_BLUE_ID =
            TEXT_TYPE_BLUE_ID;
    private static final String CUSTOM_RUNTIME_REGISTRY_IDENTITY =
            "processor-runtime-access-test-registry:v1";
    private static final long ASYNC_TIMEOUT_SECONDS = 5L;
    private static final Node CUSTOM_CONTRACT_TYPE =
            new Node().name("Processor Runtime Access Test Contract");
    private static final String CUSTOM_CONTRACT_TYPE_BLUE_ID =
            DirectBlueIdCalculator.calculateBlueId(
                    CUSTOM_CONTRACT_TYPE);
    private static final ContractProcessor<CustomContract>
            CUSTOM_CONTRACT_PROCESSOR =
            () -> CustomContract.class;

    @Test
    void shouldResolveTransientFromDetachedDocumentInput() {
        // given
        TestRuntime fixture = new TestRuntime();
        Node document = new Node().properties(
                "value", new Node().value("caller-owned"));

        // when
        ResolvedSnapshot snapshot =
                fixture.access.resolveTransient(document);

        // then
        try {
            assertNotSame(document, fixture.snapshots.lastDocument);
            assertNull(document.getProperties().get(
                    "managerMutation"));
            assertEquals(
                    "recorded",
                    snapshot.resolvedRoot()
                            .getAsText("/managerMutation"));
        } finally {
            fixture.close();
        }
    }

    @Test
    void shouldResolvePreservedPathsFromDetachedInputs() {
        // given
        TestRuntime fixture = new TestRuntime();
        Node document = new Node().properties(
                "body", new Node().value("authored"));
        List<String> paths = Arrays.asList("/body");

        // when
        fixture.access.resolveTransientPreservingPaths(
                document, paths);

        // then
        try {
            assertNotSame(document, fixture.snapshots.lastDocument);
            assertNull(document.getProperties().get(
                    "managerMutation"));
            assertEquals(
                    Collections.singletonList("/body"),
                    fixture.snapshots.lastPreservedPaths);
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> fixture.snapshots.lastPreservedPaths
                            .add("/other"));
        } finally {
            fixture.close();
        }
    }

    @Test
    void shouldReturnTypedUnavailableExactReferenceOutcome() {
        // given
        TestRuntime fixture = new TestRuntime();
        fixture.snapshots.materializationFailure =
                new ExecutionEvidenceUnavailableException(
                        "Exact evidence is unavailable",
                        Collections.singleton(REQUIRED_BLUE_ID));
        FrozenNode reference = FrozenNode.fromNode(
                new Node().blueId(REQUIRED_BLUE_ID));

        // when
        BlueOperationResult<FrozenNode> result = fixture.access
                .materializeVerifiedExactReference(reference);

        // then
        try {
            assertEquals(
                    BlueOperationOutcome.INCOMPLETE,
                    result.outcome());
            assertEquals(
                    Collections.singleton(REQUIRED_BLUE_ID),
                    result.outstandingBlueIds());
            assertEquals(
                    "Exact evidence is unavailable",
                    result.reason().orElse(null));
        } finally {
            fixture.close();
        }
    }

    @Test
    void shouldEstablishIndependentlyVerifiedOrdinaryReferenceContent() {
        // given
        TestRuntime fixture = new TestRuntime();
        FrozenNode content = FrozenNode.fromNode(
                new Node().value("verified content"));
        String contentBlueId =
                DirectBlueIdCalculator.calculateBlueId(
                        content.toNode());
        fixture.snapshots.materialized = content;
        FrozenNode reference = FrozenNode.fromNode(
                new Node().blueId(contentBlueId));

        // when
        BlueOperationResult<FrozenNode> result = fixture.access
                .materializeVerifiedExactReference(reference);

        // then
        try {
            assertEquals(
                    BlueOperationOutcome.ESTABLISHED,
                    result.outcome());
            assertSame(content, result.requireEstablished());
        } finally {
            fixture.close();
        }
    }

    @Test
    void shouldRejectOrdinaryReferenceContentWithMismatchedIdentity() {
        // given
        TestRuntime fixture = new TestRuntime();
        FrozenNode content = FrozenNode.fromNode(
                new Node().value("different content"));
        String requestedBlueId =
                DirectBlueIdCalculator.calculateBlueId(
                        new Node().value("requested content"));
        fixture.snapshots.materialized = content;
        FrozenNode reference = FrozenNode.fromNode(
                new Node().blueId(requestedBlueId));

        // when
        BlueOperationResult<FrozenNode> result = fixture.access
                .materializeVerifiedExactReference(reference);

        // then
        try {
            assertEquals(
                    BlueOperationOutcome.INVALID,
                    result.outcome());
            assertTrue(result.reason().orElse("").contains(
                    "BlueId mismatch"));
            assertTrue(result.reason().orElse("").contains(
                    requestedBlueId));
        } finally {
            fixture.close();
        }
    }

    @Test
    void shouldRejectOrdinaryContentWithContradictoryDeclaredBlueId() {
        // given
        TestRuntime fixture = new TestRuntime();
        Node canonicalContent =
                new Node().value("verified content");
        String requestedBlueId =
                DirectBlueIdCalculator.calculateBlueId(
                        canonicalContent);
        String declaredBlueId =
                DirectBlueIdCalculator.calculateBlueId(
                        new Node().value("different content"));
        fixture.snapshots.materialized =
                FrozenNode.fromResolvedNode(
                        canonicalContent.blueId(declaredBlueId));
        FrozenNode reference = FrozenNode.fromNode(
                new Node().blueId(requestedBlueId));

        // when
        BlueOperationResult<FrozenNode> result = fixture.access
                .materializeVerifiedExactReference(reference);

        // then
        try {
            assertEquals(
                    BlueOperationOutcome.INVALID,
                    result.outcome());
            assertTrue(result.reason().orElse("").contains(
                    requestedBlueId));
            assertTrue(result.reason().orElse("").contains(
                    declaredBlueId));
        } finally {
            fixture.close();
        }
    }

    @Test
    void shouldReturnInvalidWhenOrdinaryContentIdentityCannotBeCalculated() {
        // given
        TestRuntime fixture = new TestRuntime();
        FrozenNode invalidContent = FrozenNode.fromResolvedNode(
                new Node().properties(
                        "nested",
                        new Node()
                                .blueId(REQUIRED_BLUE_ID)
                                .name("expanded reference")));
        fixture.snapshots.materialized = invalidContent;
        FrozenNode reference = FrozenNode.fromNode(
                new Node().blueId(REQUIRED_BLUE_ID));

        // when
        BlueOperationResult<FrozenNode> result = fixture.access
                .materializeVerifiedExactReference(reference);

        // then
        try {
            assertEquals(
                    BlueOperationOutcome.INVALID,
                    result.outcome());
            assertTrue(result.reason().orElse("").contains(
                    "identity could not be calculated"));
        } finally {
            fixture.close();
        }
    }

    @Test
    void shouldPreserveVerifiedCyclicMemberMaterialization() {
        // given
        TestRuntime fixture = new TestRuntime();
        FrozenNode content = FrozenNode.fromNode(
                new Node().value("cyclic member content"));
        String masterBlueId =
                DirectBlueIdCalculator.calculateBlueId(
                        new Node().value("complete cyclic set"));
        fixture.snapshots.materialized = content;
        FrozenNode reference = FrozenNode.fromNode(
                new Node().blueId(masterBlueId + "#0"));

        // when
        BlueOperationResult<FrozenNode> result = fixture.access
                .materializeVerifiedExactReference(reference);

        // then
        try {
            assertEquals(
                    BlueOperationOutcome.ESTABLISHED,
                    result.outcome());
            assertSame(content, result.requireEstablished());
        } finally {
            fixture.close();
        }
    }

    @Test
    void shouldRejectCyclicContentWithContradictoryDeclaredBlueId() {
        // given
        TestRuntime fixture = new TestRuntime();
        String masterBlueId =
                DirectBlueIdCalculator.calculateBlueId(
                        new Node().value("complete cyclic set"));
        String requestedBlueId = masterBlueId + "#0";
        String declaredBlueId =
                DirectBlueIdCalculator.calculateBlueId(
                        new Node().value("different cyclic set"));
        fixture.snapshots.materialized =
                FrozenNode.fromResolvedNode(
                        new Node()
                                .blueId(declaredBlueId)
                                .value("cyclic member content"));
        FrozenNode reference = FrozenNode.fromNode(
                new Node().blueId(requestedBlueId));

        // when
        BlueOperationResult<FrozenNode> result = fixture.access
                .materializeVerifiedExactReference(reference);

        // then
        try {
            assertEquals(
                    BlueOperationOutcome.INVALID,
                    result.outcome());
            assertTrue(result.reason().orElse("").contains(
                    requestedBlueId));
            assertTrue(result.reason().orElse("").contains(
                    declaredBlueId));
        } finally {
            fixture.close();
        }
    }

    @Test
    void shouldInvalidateBorrowedViewWhenSourceLifecycleCloses() {
        // given
        TestRuntime fixture = new TestRuntime();

        // when
        fixture.source.close();

        // then
        try {
            assertFalse(fixture.access.isCurrent());
            assertThrows(
                    IllegalStateException.class,
                    fixture.access::languageRuntime);
            assertThrows(
                    IllegalStateException.class,
                    () -> fixture.access.resolveTransient(new Node()));
        } finally {
            fixture.close();
        }
    }

    @Test
    void shouldInvalidateRetainedRuntimeAndProviderWhenSourceCloses() {
        // given
        TestRuntime fixture = new TestRuntime();
        LanguageRuntimeAccess runtime =
                fixture.access.languageRuntime();
        NodeProvider provider = runtime.getNodeProvider();

        // when
        fixture.source.close();
        Throwable runtimeFailure = FailureCapture.captureFailure(
                () -> runtime.canonicalize(new Node()));
        Throwable providerFailure = FailureCapture.captureFailure(
                () -> provider.fetchByBlueId(REQUIRED_BLUE_ID));

        // then
        try {
            assertNotSame(fixture.runtime, runtime);
            assertTrue(runtimeFailure instanceof IllegalStateException);
            assertTrue(providerFailure instanceof IllegalStateException);
            assertTrue(runtimeFailure.getMessage().contains(
                    "Document processor is closed"));
            assertEquals(
                    runtimeFailure.getMessage(),
                    providerFailure.getMessage());
        } finally {
            fixture.close();
        }
    }

    @Test
    void shouldRejectExpiredSnapshotGeneration() {
        // given
        TestRuntime fixture = new TestRuntime();

        // when
        fixture.snapshots.current = false;

        // then
        try {
            assertFalse(fixture.access.isCurrent());
            assertThrows(
                    IllegalStateException.class,
                    () -> fixture.access.resolveTransient(new Node()));
            assertThrows(
                    IllegalStateException.class,
                    () -> DocumentProcessor.builder()
                            .runtimeAccess(fixture.access));
        } finally {
            fixture.close();
        }
    }

    @Test
    void shouldImportOneAtomicRuntimeGenerationIntoSuccessor() {
        // given
        TestRuntime fixture = new TestRuntime();
        DocumentProcessor.Builder builder = DocumentProcessor.builder();

        // when
        DocumentProcessor successor = builder
                .runtimeAccess(fixture.access)
                .build();

        // then
        try {
            assertSame(
                    fixture.runtime,
                    successor.languageRuntimeAccess());
            assertSame(
                    fixture.snapshots,
                    successor.snapshotManager());
            assertSame(
                    fixture.runtime.getNodeProvider(),
                    successor.configuredNodeProvider());
            assertSame(
                    fixture.runtime.cachePolicy(),
                    successor.cachePolicy());
            assertSame(
                    fixture.runtime,
                    successor.matchingService().blue());
            assertNotSame(
                    fixture.source.matchingService(),
                    successor.matchingService());
        } finally {
            successor.close();
            fixture.close();
        }
    }

    @Test
    void shouldRejectBuildWhenImportedSourceClosesAfterConfiguration() {
        // given
        TestRuntime fixture = new TestRuntime();
        DocumentProcessor.Builder builder = DocumentProcessor.builder()
                .runtimeAccess(fixture.access);

        // when
        fixture.source.close();
        Throwable failure = FailureCapture.captureFailure(
                builder::build);

        // then
        try {
            assertTrue(failure instanceof IllegalStateException);
            assertTrue(failure.getMessage().contains(
                    "Document processor is closed"));
        } finally {
            fixture.close();
        }
    }

    @Test
    void shouldRejectBuildWhenImportedSnapshotExpiresAfterConfiguration() {
        // given
        TestRuntime fixture = new TestRuntime();
        DocumentProcessor.Builder builder = DocumentProcessor.builder()
                .runtimeAccess(fixture.access);

        // when
        fixture.snapshots.current = false;
        Throwable failure = FailureCapture.captureFailure(
                builder::build);

        // then
        try {
            assertTrue(failure instanceof IllegalStateException);
            assertTrue(failure.getMessage().contains(
                    "snapshot generation is no longer current"));
        } finally {
            fixture.close();
        }
    }

    @Test
    void shouldRejectSuccessorWorkAfterImportedSourceCloses() {
        // given
        TestRuntime fixture = new TestRuntime();
        DocumentProcessor successor = DocumentProcessor.builder()
                .runtimeAccess(fixture.access)
                .build();

        // when
        fixture.source.close();
        Throwable failure = FailureCapture.captureFailure(
                () -> successor.initializeDocument(new Node()));

        // then
        try {
            assertTrue(failure instanceof IllegalStateException);
            assertTrue(failure.getMessage().contains(
                    "Document processor is closed"));
        } finally {
            successor.close();
            fixture.close();
        }
    }

    @Test
    void shouldRetainSourceGenerationUntilAdmittedSuccessorWorkFinishes()
            throws Exception {
        // given
        TestRuntime fixture = new TestRuntime();
        DocumentProcessor successor = DocumentProcessor.builder()
                .runtimeAccess(fixture.access)
                .build();
        fixture.snapshots.pauseNextTransientResolution();
        ExecutorService executor = daemonExecutor(2);
        Future<ResolvedSnapshot> resolution = executor.submit(
                () -> successor.administration()
                        .runtimeAccess()
                        .resolveTransient(new Node()));

        try {
            // when
            boolean resolutionEntered =
                    fixture.snapshots.awaitTransientResolution();
            Future<?> closing = executor.submit(
                    fixture.source::close);
            boolean sourceCloseStarted = awaitCondition(
                    fixture.source::isClosed);
            boolean closeFinishedWhileResolutionActive =
                    closing.isDone();
            ProcessingSnapshotManager managerWhileResolutionActive =
                    fixture.source.snapshotManager();
            fixture.snapshots.releaseTransientResolution();
            ResolvedSnapshot resolved = resolution.get(
                    ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            closing.get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            ProcessingSnapshotManager managerAfterClose =
                    fixture.source.snapshotManager();
            Throwable laterFailure = FailureCapture.captureFailure(
                    () -> successor.administration()
                            .runtimeAccess());

            // then
            assertTrue(resolutionEntered);
            assertTrue(sourceCloseStarted);
            assertFalse(closeFinishedWhileResolutionActive);
            assertSame(
                    fixture.snapshots,
                    managerWhileResolutionActive);
            assertEquals(
                    "recorded",
                    resolved.resolvedRoot()
                            .getAsText("/managerMutation"));
            assertNull(managerAfterClose);
            assertTrue(laterFailure instanceof IllegalStateException);
        } finally {
            fixture.snapshots.releaseTransientResolution();
            executor.shutdownNow();
            successor.close();
            fixture.close();
        }
    }

    @Test
    void shouldRequireExplicitIdentityForCustomRegistryWithImportedRuntime() {
        // given
        TestRuntime fixture = new TestRuntime();
        DocumentProcessor.Builder builder = DocumentProcessor.builder()
                .runtimeAccess(fixture.access)
                .runtimeRegistry(
                        ContractProcessorRegistryBuilder.create()
                                .registerDefaults()
                                .build());

        // when
        Throwable failure = FailureCapture.captureFailure(
                builder::build);

        // then
        try {
            assertTrue(failure instanceof IllegalStateException);
            assertTrue(failure.getMessage().contains(
                    "explicit non-default runtime registry identity"));
        } finally {
            fixture.close();
        }
    }

    @Test
    void shouldRejectDefaultIdentityForCustomRegistryWithImportedRuntime() {
        // given
        TestRuntime fixture = new TestRuntime();
        DocumentProcessor.Builder builder = DocumentProcessor.builder()
                .runtimeAccess(fixture.access)
                .runtimeRegistry(
                        ContractProcessorRegistryBuilder.create()
                                .registerDefaults()
                                .build())
                .runtimeRegistryIdentity(
                        RuntimeBlueIds.REGISTRY_PACKAGE_IDENTITY);

        // when
        Throwable failure = FailureCapture.captureFailure(
                builder::build);

        // then
        try {
            assertTrue(failure instanceof IllegalStateException);
            assertTrue(failure.getMessage().contains(
                    "explicit non-default runtime registry identity"));
        } finally {
            fixture.close();
        }
    }

    @Test
    void shouldBuildCustomRegistryWithExplicitImportedRuntimeIdentity() {
        // given
        TestRuntime fixture = new TestRuntime();
        DocumentProcessor.Builder builder = DocumentProcessor.builder()
                .runtimeAccess(fixture.access)
                .runtimeRegistry(
                        ContractProcessorRegistryBuilder.create()
                                .registerDefaults()
                                .build())
                .runtimeRegistryIdentity(
                        CUSTOM_RUNTIME_REGISTRY_IDENTITY);

        // when
        DocumentProcessor successor = builder.build();

        // then
        try {
            assertEquals(
                    CUSTOM_RUNTIME_REGISTRY_IDENTITY,
                    successor.runtimeRegistryIdentity());
            assertSame(
                    fixture.snapshots,
                    successor.snapshotManager());
        } finally {
            successor.close();
            fixture.close();
        }
    }

    @Test
    void shouldRequireIdentityWhenProcessorIsRegisteredAfterRuntimeImport() {
        // given
        TestRuntime fixture = new TestRuntime();
        DocumentProcessor.Builder builder = DocumentProcessor.builder()
                .runtimeAccess(fixture.access)
                .registerContractProcessor(
                        CUSTOM_CONTRACT_TYPE_BLUE_ID,
                        CUSTOM_CONTRACT_TYPE,
                        CUSTOM_CONTRACT_PROCESSOR);

        // when
        Throwable failure = FailureCapture.captureFailure(
                builder::build);

        // then
        try {
            assertTrue(failure instanceof IllegalStateException);
            assertTrue(failure.getMessage().contains(
                    "explicit non-default runtime registry identity"));
        } finally {
            fixture.close();
        }
    }

    @Test
    void shouldRequireIdentityAfterFailedRuntimeRegistryMutation() {
        // given
        TestRuntime fixture = new TestRuntime();
        DocumentProcessor.Builder builder = DocumentProcessor.builder()
                .runtimeAccess(fixture.access);

        // when
        Throwable registrationFailure = FailureCapture.captureFailure(
                () -> builder.registerContractProcessor(
                        CUSTOM_CONTRACT_PROCESSOR));
        Throwable buildFailure = FailureCapture.captureFailure(
                builder::build);

        // then
        try {
            assertTrue(registrationFailure
                    instanceof IllegalArgumentException);
            assertTrue(buildFailure instanceof IllegalStateException);
            assertTrue(buildFailure.getMessage().contains(
                    "explicit non-default runtime registry identity"));
        } finally {
            fixture.close();
        }
    }

    @Test
    void shouldRequireIdentityWhenProcessorIsRegisteredBeforeRuntimeImport() {
        // given
        TestRuntime fixture = new TestRuntime();
        DocumentProcessor.Builder builder = DocumentProcessor.builder()
                .registerContractProcessor(
                        CUSTOM_CONTRACT_TYPE_BLUE_ID,
                        CUSTOM_CONTRACT_TYPE,
                        CUSTOM_CONTRACT_PROCESSOR)
                .runtimeAccess(fixture.access);

        // when
        Throwable failure = FailureCapture.captureFailure(
                builder::build);

        // then
        try {
            assertTrue(failure instanceof IllegalStateException);
            assertTrue(failure.getMessage().contains(
                    "explicit non-default runtime registry identity"));
        } finally {
            fixture.close();
        }
    }

    @Test
    void shouldBuildRegisteredProcessorWithExplicitRuntimeIdentity() {
        // given
        TestRuntime fixture = new TestRuntime();
        DocumentProcessor.Builder builder = DocumentProcessor.builder()
                .runtimeAccess(fixture.access)
                .registerContractProcessor(
                        CUSTOM_CONTRACT_TYPE_BLUE_ID,
                        CUSTOM_CONTRACT_TYPE,
                        CUSTOM_CONTRACT_PROCESSOR)
                .runtimeRegistryIdentity(
                        CUSTOM_RUNTIME_REGISTRY_IDENTITY);

        // when
        DocumentProcessor successor = builder.build();

        // then
        try {
            assertEquals(
                    CUSTOM_RUNTIME_REGISTRY_IDENTITY,
                    successor.runtimeRegistryIdentity());
            assertTrue(successor.registry().lookupMarker(
                    CUSTOM_CONTRACT_TYPE_BLUE_ID).isPresent());
        } finally {
            successor.close();
            fixture.close();
        }
    }

    @Test
    void shouldPreserveAtomicRuntimeBoundaryWhenCopyingImportedProcessor() {
        // given
        TestRuntime fixture = new TestRuntime();
        DocumentProcessor imported = DocumentProcessor.builder()
                .runtimeAccess(fixture.access)
                .build();
        DocumentProcessor.Builder copy =
                DocumentProcessor.Builder.from(imported);

        // when
        Throwable failure = FailureCapture.captureFailure(
                () -> copy.snapshotStore(
                        new TrackingSnapshotManager()));

        // then
        try {
            assertTrue(failure instanceof IllegalStateException);
            assertTrue(failure.getMessage().contains(
                    "configures snapshots, matching, provider, and cache policy atomically"));
        } finally {
            imported.close();
            fixture.close();
        }
    }

    @Test
    void shouldReleaseImportedGenerationGuardWhenSuccessorCloses() {
        // given
        TestRuntime fixture = new TestRuntime();
        DocumentProcessor successor = DocumentProcessor.builder()
                .runtimeAccess(fixture.access)
                .build();

        // when
        successor.close();

        // then
        try {
            assertNull(successor.runtimeGenerationGuard());
        } finally {
            fixture.close();
        }
    }

    @Test
    void shouldAttachSemanticOutputBoundaryFromImportedRuntime() {
        // given
        TestRuntime fixture = new TestRuntime();
        DocumentProcessor successor = DocumentProcessor.builder()
                .runtimeAccess(fixture.access)
                .build();
        ProcessorInvocationState execution =
                new ProcessorInvocationState(
                        successor, new Node());
        execution.preflightScope("/");
        ProcessorExecutionContext context = execution.createContext(
                "/",
                execution.bundleForScope("/"),
                new Node(),
                false);

        // when
        ExactBlueValue admitted = context
                .semanticOutputBoundary()
                .admit(new Node().value("hosted output"));

        // then
        try {
            assertEquals(
                    "hosted output",
                    admitted.toNode().getValue());
            assertTrue(admitted.blueId() != null
                    && !admitted.blueId().isEmpty());
        } finally {
            successor.close();
            fixture.close();
        }
    }

    @Test
    void shouldRejectSnapshotOverrideAfterRuntimeImport() {
        // given
        TestRuntime fixture = new TestRuntime();
        DocumentProcessor.Builder builder = DocumentProcessor.builder()
                .runtimeAccess(fixture.access);

        // when
        Throwable failure = FailureCapture.captureFailure(
                () -> builder.snapshotStore(
                        new TrackingSnapshotManager()));

        // then
        try {
            assertTrue(failure instanceof IllegalStateException);
            assertTrue(failure.getMessage().contains(
                    "configures snapshots, matching, provider, and cache policy atomically"));
        } finally {
            fixture.close();
        }
    }

    @Test
    void shouldRejectProviderOverrideAfterRuntimeImport() {
        // given
        TestRuntime fixture = new TestRuntime();
        DocumentProcessor.Builder builder = DocumentProcessor.builder()
                .runtimeAccess(fixture.access);

        // when
        Throwable failure = FailureCapture.captureFailure(
                () -> builder.nodeProvider(blueId -> null));

        // then
        try {
            assertTrue(failure instanceof IllegalStateException);
            assertTrue(failure.getMessage().contains(
                    "configures snapshots, matching, provider, and cache policy atomically"));
        } finally {
            fixture.close();
        }
    }

    @Test
    void shouldRejectRuntimeImportAfterIndividualCollaboratorConfiguration() {
        // given
        TestRuntime fixture = new TestRuntime();
        DocumentProcessor.Builder snapshots = DocumentProcessor.builder()
                .snapshotStore(new TrackingSnapshotManager());
        DocumentProcessor.Builder matching = DocumentProcessor.builder()
                .matchingService(new ContractMatchingService());
        DocumentProcessor.Builder provider = DocumentProcessor.builder()
                .nodeProvider(blueId -> null);
        DocumentProcessor.Builder cache = DocumentProcessor.builder()
                .cachePolicy(BlueCachePolicy.boundedDefaults());

        // when
        Throwable snapshotFailure = FailureCapture.captureFailure(
                () -> snapshots.runtimeAccess(fixture.access));
        Throwable matchingFailure = FailureCapture.captureFailure(
                () -> matching.runtimeAccess(fixture.access));
        Throwable providerFailure = FailureCapture.captureFailure(
                () -> provider.runtimeAccess(fixture.access));
        Throwable cacheFailure = FailureCapture.captureFailure(
                () -> cache.runtimeAccess(fixture.access));

        // then
        try {
            assertTrue(snapshotFailure instanceof IllegalStateException);
            assertTrue(matchingFailure instanceof IllegalStateException);
            assertTrue(providerFailure instanceof IllegalStateException);
            assertTrue(cacheFailure instanceof IllegalStateException);
            assertTrue(snapshotFailure.getMessage().contains(
                    "cannot be combined with individually configured"));
            assertEquals(
                    snapshotFailure.getMessage(),
                    matchingFailure.getMessage());
            assertEquals(
                    snapshotFailure.getMessage(),
                    providerFailure.getMessage());
            assertEquals(
                    snapshotFailure.getMessage(),
                    cacheFailure.getMessage());
        } finally {
            fixture.close();
        }
    }

    private static boolean awaitCondition(
            BooleanSupplier condition) {
        long deadline = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(
                        ASYNC_TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.yield();
        }
        return condition.getAsBoolean();
    }

    private static ExecutorService daemonExecutor(
            int threadCount) {
        return Executors.newFixedThreadPool(
                threadCount,
                task -> {
                    Thread thread = new Thread(
                            task,
                            "processor-runtime-access-test");
                    thread.setDaemon(true);
                    return thread;
                });
    }

    private static final class TestRuntime implements AutoCloseable {
        private final TrackingSnapshotManager snapshots =
                new TrackingSnapshotManager();
        private final BlueLanguageRuntime runtime =
                BlueLanguageRuntime.create(
                        blueId -> null,
                        BlueCachePolicy.disabled(),
                        Collections.<String, String>emptyMap());
        private final DocumentProcessor source =
                DocumentProcessor.builder()
                        .snapshotStore(snapshots)
                        .matchingService(
                                new ContractMatchingService(runtime))
                        .build();
        private final ProcessorRuntimeAccess access =
                source.administration().runtimeAccess();

        @Override
        public void close() {
            source.close();
            runtime.close();
        }
    }

    private static final class CustomContract extends MarkerContract {
    }

    private static final class TrackingSnapshotManager
            implements ProcessingSnapshotManager {

        private Node lastDocument;
        private List<String> lastPreservedPaths =
                Collections.emptyList();
        private RuntimeException materializationFailure;
        private FrozenNode materialized;
        private boolean current = true;
        private volatile CountDownLatch transientResolutionEntered;
        private volatile CountDownLatch transientResolutionRelease;

        @Override
        public ResolvedSnapshot fromDocument(Node document) {
            return record(document);
        }

        @Override
        public ResolvedSnapshot fromDocumentTransient(Node document) {
            return record(document);
        }

        @Override
        public ResolvedSnapshot fromDocumentTransientPreservingPaths(
                Node document,
                Collection<String> preservedPaths) {
            lastPreservedPaths = Collections.unmodifiableList(
                    new java.util.ArrayList<>(preservedPaths));
            return record(document);
        }

        @Override
        public FrozenNode materializeVerifiedExactReference(
                FrozenNode reference) {
            if (materializationFailure != null) {
                throw materializationFailure;
            }
            return materialized != null
                    ? materialized
                    : reference;
        }

        @Override
        public boolean isTransientStateCurrent() {
            return current;
        }

        @Override
        public ResolvedSnapshot applyPatch(
                ResolvedSnapshot snapshot,
                JsonPatch patch) {
            throw new UnsupportedOperationException(
                    "Patch application is not used by this test");
        }

        private ResolvedSnapshot record(Node document) {
            CountDownLatch entered = transientResolutionEntered;
            CountDownLatch release = transientResolutionRelease;
            if (entered != null && release != null) {
                entered.countDown();
                awaitRelease(release);
                transientResolutionEntered = null;
                transientResolutionRelease = null;
            }
            lastDocument = document;
            document.properties(
                    "managerMutation",
                    new Node().value("recorded"));
            return new ResolvedSnapshot(
                    FrozenNode.fromNode(document),
                    FrozenNode.fromResolvedNode(document));
        }

        private void pauseNextTransientResolution() {
            transientResolutionEntered = new CountDownLatch(1);
            transientResolutionRelease = new CountDownLatch(1);
        }

        private boolean awaitTransientResolution()
                throws InterruptedException {
            CountDownLatch entered = transientResolutionEntered;
            return entered != null
                    && entered.await(
                            ASYNC_TIMEOUT_SECONDS,
                            TimeUnit.SECONDS);
        }

        private void releaseTransientResolution() {
            CountDownLatch release = transientResolutionRelease;
            if (release != null) {
                release.countDown();
            }
        }

        private static void awaitRelease(
                CountDownLatch release) {
            try {
                if (!release.await(
                        ASYNC_TIMEOUT_SECONDS,
                        TimeUnit.SECONDS)) {
                    throw new IllegalStateException(
                            "Timed out waiting to release transient resolution");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "Interrupted while waiting to release transient resolution",
                        interrupted);
            }
        }
    }
}
