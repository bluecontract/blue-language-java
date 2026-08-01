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
                            .withConformanceEngine(engine)
                            .withMatchingService(
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

    /** Starts a builder borrowing one immutable Language processing bridge. */
    public static Builder builder(
            LanguageProcessing languageProcessing) {
        return new Builder(languageProcessing);
    }

    /** Processes one Root and event using a derived exact delivery plan. */
    public DocumentProcessingResult process(
            Node root,
            Node event) {
        return call(() -> processor.processDocument(root, event));
    }

    /** Attempts processing and returns exact retry resources as data. */
    public ProcessAttemptResult processAttempt(
            Node root,
            Node event) {
        return call(() -> processor.processAttempt(root, event));
    }

    /**
     * Processes one Root for an atomic host commit using verified execution
     * evidence.
     */
    public PlatformProcessingResult processForPlatformCommit(
            Node root,
            Node event,
            VerifiedExecutionEvidence evidence) {
        return call(() -> processor.processDocumentForPlatformCommit(
                root, event, evidence));
    }

    /** Inspects effective fragmentation without semantic execution. */
    public EffectiveFragmentationCatalog effectiveFragmentationCatalog(
            Node root) {
        return call(() -> processor.effectiveFragmentationCatalog(root));
    }

    /** Returns whether terminal shutdown has begun. */
    public boolean isClosed() {
        return closed;
    }

    /**
     * Waits for admitted processing calls and releases Contracts-owned state.
     * Closing from inside an admitted call is rejected.
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

        /** Selects the registry generation to freeze at build time. */
        public Builder runtimeRegistry(
                ContractProcessorRegistry runtimeRegistry) {
            this.runtimeRegistry = Objects.requireNonNull(
                    runtimeRegistry, "runtimeRegistry");
            return this;
        }

        /** Selects the immutable Contracts 1.0 gas schedule. */
        public Builder gasSchedule(GasSchedule gasSchedule) {
            this.gasSchedule = Objects.requireNonNull(
                    gasSchedule, "gasSchedule");
            return this;
        }

        /** Selects a process budget within the configured schedule maximum. */
        public Builder gasLimit(long gasLimit) {
            this.gasLimit = gasLimit;
            return this;
        }

        /** Selects the host's deterministic delivery-plan derivation. */
        public Builder deliveryPlanDeriver(
                ExternalDeliveryPlanDeriver deliveryPlanDeriver) {
            this.deliveryPlanDeriver = Objects.requireNonNull(
                    deliveryPlanDeriver, "deliveryPlanDeriver");
            return this;
        }

        /** Selects the host's exact execution-evidence verifier. */
        public Builder evidenceVerifier(
                ExternalDeliveryEvidenceVerifier evidenceVerifier) {
            this.evidenceVerifier = Objects.requireNonNull(
                    evidenceVerifier, "evidenceVerifier");
            return this;
        }

        /** Selects the pre-commit subscription surface validator. */
        public Builder subscriptionSurfaceValidator(
                SubscriptionSurfaceValidator validator) {
            this.subscriptionSurfaceValidator = Objects.requireNonNull(
                    validator, "subscriptionSurfaceValidator");
            return this;
        }

        /** Selects an operational observer outside the semantic model. */
        public Builder observer(ProcessingObserver observer) {
            this.observer = Objects.requireNonNull(
                    observer, "observer");
            return this;
        }

        /** Builds one independent Contracts service generation. */
        public BlueContracts build() {
            return new BlueContracts(this);
        }
    }
}
