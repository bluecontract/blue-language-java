package blue.language.processor;

import blue.language.conformance.ConformanceEngine;
import blue.language.model.Node;
import blue.language.runtime.LanguageProcessing;

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
