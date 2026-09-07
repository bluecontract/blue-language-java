package blue.language.processor;

import blue.language.conformance.ConformanceEngine;
import blue.language.model.Node;
import blue.language.runtime.LanguageProcessing;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

/**
 * Focused immutable composition root for generic Contracts processing.
 *
 * <p>The service borrows one configured {@link LanguageProcessing} bridge and
 * owns its processor, root processing scope, conformance handle, and bounded
 * processor caches. It is thread-safe. {@link #close()} waits for admitted
 * calls, releases Contracts-owned resources, and never closes the borrowed
 * Language runtime.</p>
 */
public final class BlueContracts implements AutoCloseable {

    private final DocumentProcessor processor;
    private final LanguageProcessing languageProcessing;
    private final LanguageProcessingSnapshotManager snapshotManager;
    private final ConformanceEngine conformanceEngine;
    private final ReentrantReadWriteLock lifecycle =
            new ReentrantReadWriteLock(true);
    private final ThreadLocal<Integer> operationDepth =
            new ThreadLocal<>();

    private volatile boolean closed;
    private volatile Throwable closeFailure;

    private BlueContracts(Builder builder) {
        ContractProcessorRegistry registryGeneration =
                builder.runtimeRegistry.snapshot();
        LanguageProcessing processing = builder.languageProcessing;
        LanguageProcessing.Scope rootScope = processing.openScope(
                LanguageProcessingSnapshotManager.observer(
                        builder.observer));
        LanguageProcessingSnapshotManager manager =
                new LanguageProcessingSnapshotManager(rootScope);
        ConformanceEngine engine = null;
        DocumentProcessor builtProcessor = null;
        try {
            engine = processing.newConformanceEngine();
            DocumentProcessor.Builder processorBuilder =
                    DocumentProcessor.builder()
                            .nodeProvider(processing.runtimeAccess()
                                    .getNodeProvider())
                            .runtimeRegistry(registryGeneration)
                            .runtimeRegistryIdentity(
                                    registryGeneration
                                            .generationIdentity())
                            .gasSchedule(builder.gasSchedule)
                            .snapshotStore(manager)
                            .observer(builder.observer)
                            .cachePolicy(processing.runtimeAccess()
                                    .cachePolicy())
                            .conformanceEngine(engine)
                            .matchingService(
                                    new ContractMatchingService(
                                            processing.runtimeAccess()));
            if (builder.gasLimit != null) {
                processorBuilder.gasLimit(builder.gasLimit);
            }
            if (builder.deliveryPlanDeriver != null) {
                processorBuilder.deliveryPlanDeriver(
                        builder.deliveryPlanDeriver);
            }
            if (builder.evidenceVerifier != null) {
                processorBuilder.evidenceVerifier(
                        builder.evidenceVerifier);
            }
            if (builder.subscriptionSurfaceValidator != null) {
                processorBuilder.subscriptionSurfaceValidator(
                        builder.subscriptionSurfaceValidator);
            }
            builtProcessor = processorBuilder.build();
        } catch (Throwable failure) {
            closeAfterConstructionFailure(
                    builtProcessor, manager, engine, failure);
            throw failure;
        }
        this.processor = builtProcessor;
        this.languageProcessing = processing;
        this.snapshotManager = manager;
        this.conformanceEngine = engine;
    }

    /**
     * Starts a builder borrowing one immutable Language processing bridge.
     *
     * @param languageProcessing bridge borrowed by the resulting service
     * @return a new single-owner Contracts service builder
     * @throws NullPointerException when {@code languageProcessing} is
     *         {@code null}
     */
    public static Builder builder(
            LanguageProcessing languageProcessing) {
        return new Builder(languageProcessing);
    }

    /**
     * Processes one Root and event using a derived exact delivery plan.
     *
     * @param root exact Root document supplied to the processor
     * @param event exact event supplied to the processor
     * @return the complete deterministic processing result
     * @throws IllegalStateException when this service is closed
     */
    public DocumentProcessingResult process(
            Node root,
            Node event) {
        return call(() -> processor.processDocument(root, event));
    }

    /**
     * Attempts processing and returns exact retry resources as data.
     *
     * @param root exact Root document supplied to the processor
     * @param event exact event supplied to the processor
     * @return the processing attempt and any exact retry requirements
     * @throws IllegalStateException when this service is closed
     */
    public ProcessAttemptResult processAttempt(
            Node root,
            Node event) {
        return call(() -> processor.processAttempt(root, event));
    }

    /**
     * Processes one Root for an atomic host commit using verified execution
     * evidence.
     *
     * @param root exact Root document supplied to the processor
     * @param event exact event supplied to the processor
     * @param evidence immutable host execution evidence bound to the inputs
     * @return the prepared platform-commit result
     * @throws IllegalStateException when this service is closed
     */
    public PlatformProcessingResult processForPlatformCommit(
            Node root,
            Node event,
            VerifiedExecutionEvidence evidence) {
        return call(() -> processor.processDocumentForPlatformCommit(
                root, event, evidence));
    }

    /**
     * Processes one Root/event pair for an atomic host commit using an already
     * evaluated exact plan and one strict request-local provider.
     *
     * <p>Root and event remain the only Blue semantic inputs. The invocation
     * value is verified execution environment: its hidden indexed-evaluator
     * binding is checked against both inputs and this immutable registry
     * generation, while its plan is replayed through the authoritative core
     * verifier without consulting the construction-time plan deriver. Every
     * provider-backed operation in admission, classification, execution,
     * patching, and final validation shares one isolated provider domain.</p>
     *
     * @param root exact Root document supplied to the processor
     * @param event exact event supplied to the processor
     * @param invocation exact plan and borrowed request-local provider
     * @return the prepared platform-commit result
     * @throws NullPointerException if an argument is {@code null}
     * @throws InvalidExecutionEvidenceException when the plan or its binding
     *         is forged, stale, incomplete, or belongs to another generation
     * @throws ExecutionEvidenceUnavailableException when required exact
     *         provider evidence is temporarily unavailable
     * @throws UnsupportedOperationException when a custom Language bridge
     *         does not implement strict invocation-provider scopes
     * @throws IllegalStateException when this service is closed
     */
    public PlatformProcessingResult processForPlatformCommit(
            Node root,
            Node event,
            PlatformProcessInvocation invocation) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(invocation, "invocation");
        final Node exactRoot = root.clone();
        final Node exactEvent = event.clone();
        return call(() -> processInvocation(
                exactRoot, exactEvent, invocation));
    }

    /**
     * Inspects effective fragmentation without semantic execution.
     *
     * @param root exact Root document to inspect
     * @return the deterministic effective fragmentation catalog
     * @throws IllegalStateException when this service is closed
     */
    public EffectiveFragmentationCatalog effectiveFragmentationCatalog(
            Node root) {
        return call(() -> processor.administration().effectiveFragmentationCatalog(root));
    }

    /**
     * Returns the borrowed Language runtime capability owned by this service.
     *
     * <p>The access remains valid only while this service is open. A custom
     * processor can import it atomically through
     * {@link DocumentProcessor.Builder#runtimeAccess(ProcessorRuntimeAccess)}.
     * </p>
     *
     * @return lifecycle-bound processor runtime access
     * @throws IllegalStateException when this service is closed
     */
    public ProcessorRuntimeAccess runtimeAccess() {
        return call(() -> processor.administration().runtimeAccess());
    }

    /**
     * Prepares preprocessed processing Source under this runtime's registered
     * exact-field semantics without initializing or executing the document.
     *
     * @param source preprocessed Source, including a verified ordinary Root reference
     * @return strict canonical processing content
     * @throws IllegalStateException when this service is closed or evidence is unavailable
     * @throws IllegalArgumentException when Source or a cyclic-member Root is invalid
     */
    public Node canonicalizeProcessingSource(Node source) {
        return call(() -> processor.administration().canonicalizeProcessingSource(source));
    }

    /**
     * Resolves preprocessed processing Source for read-only host projections.
     * Registered declarations and executable bodies keep their exact field roles;
     * ordinary document values retain completed-instance validation.
     *
     * @param source preprocessed processing Source
     * @return resolved document with phase-owned fields preserved
     * @throws IllegalArgumentException when Source is invalid
     * @throws IllegalStateException when closed or exact evidence is unavailable
     */
    public Node resolveProcessingSource(Node source) {
        return call(() -> processor.administration().resolveProcessingSource(source));
    }

    /**
     * Returns one verified immutable snapshot of processing Source without execution.
     *
     * @param source preprocessed processing Source or an exact top-level reference
     * @return canonical identity and resolved view with registered exact fields preserved
     * @throws IllegalArgumentException when Source is invalid
     * @throws IllegalStateException when this service is closed or evidence is unavailable
     */
    public blue.language.merge.ResolvedSnapshot processingSourceSnapshot(Node source) {
        return call(() -> processor.administration().processingSourceSnapshot(source));
    }

    /**
     * Returns the configured subscription-surface projection service.
     *
     * @return lifecycle-bound read-only projection service
     * @throws IllegalStateException when this service is closed
     */
    public SubscriptionSurfaceProjection subscriptionSurfaceProjection() {
        return call(() -> processor.administration()
                .subscriptionSurfaceProjection());
    }

    /**
     * Returns the authoritative indexed-delivery evaluator.
     *
     * @return lifecycle-bound indexed-delivery evaluator
     * @throws IllegalStateException when this service is closed
     */
    public IndexedDeliveryEvaluator indexedDeliveryEvaluator() {
        return call(() -> processor.administration()
                .indexedDeliveryEvaluator());
    }

    /**
     * Creates a compatibility deriver backed by authoritative evaluation of
     * every retained active occurrence.
     *
     * @param rootRevision non-negative managed and indexed Root revision
     * @param eventOrderKey exact order of the event supplied to the deriver
     * @param completeActiveIntervals complete retained subscription surface
     * @return immutable plan deriver borrowing this service
     * @throws IllegalStateException when this service is closed
     */
    public ExternalDeliveryPlanDeriver currentRootDeliveryPlanDeriver(
            long rootRevision,
            ExternalOrderKey eventOrderKey,
            List<SubscriptionDelta.Entry> completeActiveIntervals) {
        return call(() -> processor.administration()
                .indexedDeliveryEvaluator()
                .currentRootDeriver(
                        rootRevision,
                        eventOrderKey,
                        completeActiveIntervals));
    }

    /**
     * Returns whether terminal shutdown has begun.
     *
     * @return {@code true} after terminal shutdown begins
     */
    public boolean isClosed() {
        return closed;
    }

    /**
     * Waits for admitted processing calls and releases Contracts-owned state.
     * Closing from inside an admitted call is rejected.
     *
     * @throws IllegalStateException when invoked from an admitted processing
     *         call or when a checked resource-close failure occurs
     */
    @Override
    public void close() {
        Integer depth = operationDepth.get();
        if (depth != null && depth > 0) {
            throw new IllegalStateException(
                    "Blue Contracts cannot close from active processing");
        }
        lifecycle.writeLock().lock();
        try {
            if (closed) {
                rethrow(closeFailure);
                return;
            }
            closed = true;
            Throwable failure = null;
            failure = closeResource(processor, failure);
            failure = closeSnapshotManager(
                    snapshotManager, failure);
            failure = closeResource(
                    conformanceEngine, failure);
            closeFailure = failure;
            rethrow(failure);
        } finally {
            lifecycle.writeLock().unlock();
        }
    }

    private <T> T call(Supplier<T> work) {
        lifecycle.readLock().lock();
        Integer previous = operationDepth.get();
        try {
            if (closed) {
                throw new IllegalStateException(
                        "Blue Contracts is closed");
            }
            operationDepth.set(
                    previous == null ? 1 : previous + 1);
            return work.get();
        } finally {
            if (previous == null) {
                operationDepth.remove();
            } else {
                operationDepth.set(previous);
            }
            lifecycle.readLock().unlock();
        }
    }

    private PlatformProcessingResult processInvocation(
            Node root,
            Node event,
            PlatformProcessInvocation invocation) {
        try (LanguageProcessing.Scope scope =
                     languageProcessing.openScope(
                             invocation.nodeProvider(),
                             LanguageProcessingSnapshotManager.observer(
                                     processor.observer()))) {
            LanguageProcessingSnapshotManager manager =
                    new LanguageProcessingSnapshotManager(scope);
            try (ConformanceEngine invocationConformance =
                         scope.newConformanceEngine();
                 ProcessorInvocationServices services =
                         ProcessorInvocationServices.platform(
                                 processor,
                                 manager,
                                 scope.runtimeAccess(),
                                 invocationConformance)) {
                return processor.processDocumentForPlatformCommit(
                        root,
                        event,
                        invocation,
                        services);
            }
        }
    }

    private static void closeAfterConstructionFailure(
            DocumentProcessor processor,
            LanguageProcessingSnapshotManager snapshotManager,
            ConformanceEngine conformanceEngine,
            Throwable constructionFailure) {
        Throwable failure = constructionFailure;
        failure = closeResource(processor, failure);
        failure = closeSnapshotManager(snapshotManager, failure);
        closeResource(conformanceEngine, failure);
    }

    private static Throwable closeSnapshotManager(
            LanguageProcessingSnapshotManager manager,
            Throwable failure) {
        if (manager == null) {
            return failure;
        }
        try {
            manager.releaseTransientState();
        } catch (Throwable closeFailure) {
            return retainFailure(failure, closeFailure);
        }
        return failure;
    }

    private static Throwable closeResource(
            AutoCloseable resource,
            Throwable failure) {
        if (resource == null) {
            return failure;
        }
        try {
            resource.close();
        } catch (Throwable closeFailure) {
            return retainFailure(failure, closeFailure);
        }
        return failure;
    }

    private static Throwable retainFailure(
            Throwable primary,
            Throwable additional) {
        if (primary == null) {
            return additional;
        }
        if (additional != primary) {
            primary.addSuppressed(additional);
        }
        return primary;
    }

    private static void rethrow(Throwable failure) {
        if (failure == null) {
            return;
        }
        if (failure instanceof RuntimeException) {
            throw (RuntimeException) failure;
        }
        if (failure instanceof Error) {
            throw (Error) failure;
        }
        throw new IllegalStateException(
                "Blue Contracts close failed", failure);
    }

    /** Mutable single-owner builder for one immutable Contracts generation. */
    public static final class Builder {
        private final LanguageProcessing languageProcessing;
        private ContractProcessorRegistry runtimeRegistry =
                ContractProcessorRegistryBuilder.create()
                        .registerDefaults()
                        .build();
        private GasSchedule gasSchedule = GasSchedule.contracts10();
        private Long gasLimit;
        private ExternalDeliveryPlanDeriver deliveryPlanDeriver;
        private ExternalDeliveryEvidenceVerifier evidenceVerifier;
        private SubscriptionSurfaceValidator subscriptionSurfaceValidator;
        private ProcessingObserver observer =
                NoOpProcessingObserver.INSTANCE;

        private Builder(LanguageProcessing languageProcessing) {
            this.languageProcessing = Objects.requireNonNull(
                    languageProcessing, "languageProcessing");
        }

        /**
         * Selects the registry generation to freeze at build time.
         *
         * @param runtimeRegistry registry whose current generation is frozen
         * @return this builder
         * @throws NullPointerException when {@code runtimeRegistry} is
         *         {@code null}
         */
        public Builder runtimeRegistry(
                ContractProcessorRegistry runtimeRegistry) {
            this.runtimeRegistry = Objects.requireNonNull(
                    runtimeRegistry, "runtimeRegistry");
            return this;
        }

        /**
         * Selects the immutable Contracts 1.0 gas schedule.
         *
         * @param gasSchedule schedule applied by the processor
         * @return this builder
         * @throws NullPointerException when {@code gasSchedule} is
         *         {@code null}
         */
        public Builder gasSchedule(GasSchedule gasSchedule) {
            this.gasSchedule = Objects.requireNonNull(
                    gasSchedule, "gasSchedule");
            return this;
        }

        /**
         * Selects a process budget within the configured schedule maximum.
         *
         * @param gasLimit maximum gas admitted for one process operation
         * @return this builder
         */
        public Builder gasLimit(long gasLimit) {
            this.gasLimit = gasLimit;
            return this;
        }

        /**
         * Selects the host's deterministic delivery-plan derivation.
         *
         * @param deliveryPlanDeriver host delivery-plan derivation boundary
         * @return this builder
         * @throws NullPointerException when {@code deliveryPlanDeriver} is
         *         {@code null}
         */
        public Builder deliveryPlanDeriver(
                ExternalDeliveryPlanDeriver deliveryPlanDeriver) {
            this.deliveryPlanDeriver = Objects.requireNonNull(
                    deliveryPlanDeriver, "deliveryPlanDeriver");
            return this;
        }

        /**
         * Selects the host's exact execution-evidence verifier.
         *
         * @param evidenceVerifier verifier for host-supplied execution evidence
         * @return this builder
         * @throws NullPointerException when {@code evidenceVerifier} is
         *         {@code null}
         */
        public Builder evidenceVerifier(
                ExternalDeliveryEvidenceVerifier evidenceVerifier) {
            this.evidenceVerifier = Objects.requireNonNull(
                    evidenceVerifier, "evidenceVerifier");
            return this;
        }

        /**
         * Selects the pre-commit subscription surface validator.
         *
         * @param validator validator applied before subscription-state commit
         * @return this builder
         * @throws NullPointerException when {@code validator} is {@code null}
         */
        public Builder subscriptionSurfaceValidator(
                SubscriptionSurfaceValidator validator) {
            this.subscriptionSurfaceValidator = Objects.requireNonNull(
                    validator, "subscriptionSurfaceValidator");
            return this;
        }

        /**
         * Selects an operational observer outside the semantic model.
         *
         * @param observer operational processing observer
         * @return this builder
         * @throws NullPointerException when {@code observer} is {@code null}
         */
        public Builder observer(ProcessingObserver observer) {
            this.observer = Objects.requireNonNull(
                    observer, "observer");
            return this;
        }

        /**
         * Builds one independent Contracts service generation.
         *
         * @return a new independently owned Contracts service
         * @throws IllegalStateException when the selected configuration cannot
         *         construct a valid processor generation
         */
        public BlueContracts build() {
            return new BlueContracts(this);
        }
    }
}
