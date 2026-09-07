package blue.language.processor;

import blue.language.api.BlueCachePolicy;
import blue.language.provider.NodeProvider;
import blue.language.conformance.ConformanceEngine;
import blue.language.mapping.NodeToObjectConverter;
import blue.language.model.Node;
import blue.language.processor.model.Contract;
import blue.language.merge.ResolvedSnapshot;
import blue.language.mapping.TypeClassResolver;
import blue.language.runtime.LanguageRuntimeAccess;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Lifecycle and configuration facade over the Contracts processor kernel.
 *
 * <p>Each processing call captures one read-locked configuration revision.
 * Registration and cache invalidation publish under the write lock, while
 * {@link #close()} rejects new work and releases reloadable caches once active
 * readers leave. Input nodes remain caller-owned and are never mutated.</p>
 */
public class DocumentProcessor implements AutoCloseable {

    private final ContractProcessorRegistry contractRegistry;
    private final TypeClassResolver contractTypeResolver;
    private final NodeToObjectConverter contractConverter;
    private final ContractLoader contractLoader;
    private final NodeProvider configuredNodeProvider;
    private final BlueCachePolicy cachePolicy;
    private final boolean immutableConfiguration;
    private ConformanceEngine conformanceEngine;
    private ConformancePlannerOverride conformancePlannerOverride;
    private ProcessingSnapshotManager snapshotManager;
    private LanguageRuntimeAccess languageRuntimeAccess;
    private ProcessorRuntimeAccess.GenerationGuard
            runtimeGenerationGuard;
    private ContractMatchingService matchingService;
    private volatile ProcessingObserver observer;
    private final GasSchedule gasSchedule;
    private final long gasLimit;
    private final String runtimeRegistryIdentity;
    private ExternalDeliveryPlanDeriver externalDeliveryPlanDeriver;
    private final ExternalDeliveryEvidenceVerifier configuredDeliveryEvidenceVerifier;
    private ExternalDeliveryEvidenceVerifier deliveryEvidenceVerifier;
    private final SubscriptionSurfaceValidator configuredSubscriptionSurfaceValidator;
    private final SubscriptionSurfaceValidator subscriptionSurfaceValidator;
    private final DocumentProcessorLifecycle lifecycle;
    private final DocumentProcessorNodeOperations nodeOperations;
    private final DocumentProcessorSnapshotOperations snapshotOperations;
    private final DocumentProcessorAdministration administration;

    /** Creates a processor with the default immutable Contracts configuration. */
    public DocumentProcessor() {
        this(new DocumentProcessorBuilderState().snapshot());
    }

    private DocumentProcessor(DocumentProcessorComponents components) {
        this.contractRegistry = components.registry;
        this.contractTypeResolver = components.typeResolver;
        this.contractConverter = components.converter;
        this.contractLoader = components.loader;
        this.configuredNodeProvider = components.nodeProvider;
        this.cachePolicy = components.cachePolicy;
        this.immutableConfiguration = components.immutableConfiguration;
        this.conformanceEngine = components.conformanceEngine;
        this.conformancePlannerOverride =
                components.conformancePlannerOverride;
        this.snapshotManager = components.snapshotManager;
        this.languageRuntimeAccess = components.languageRuntimeAccess;
        this.runtimeGenerationGuard =
                components.runtimeGenerationGuard;
        this.matchingService = components.matchingService;
        this.observer = components.observer;
        this.gasSchedule = components.gasSchedule;
        this.gasLimit = components.gasLimit;
        this.runtimeRegistryIdentity = components.runtimeRegistryIdentity;
        this.externalDeliveryPlanDeriver = components.deliveryPlanDeriver;
        this.configuredDeliveryEvidenceVerifier =
                components.configuredEvidenceVerifier;
        this.deliveryEvidenceVerifier = components.evidenceVerifier;
        this.configuredSubscriptionSurfaceValidator =
                components.configuredSurfaceValidator;
        this.subscriptionSurfaceValidator = components.surfaceValidator;
        this.lifecycle = new DocumentProcessorLifecycle(
                new DocumentProcessorLifecycle.Resources() {
                    @Override
                    public void clearCaches() {
                        clearOwnedCaches();
                    }

                    @Override
                    public void detachRuntimeCollaborators() {
                        DocumentProcessor.this
                                .detachRuntimeCollaborators();
                    }
                },
                runtimeGenerationGuard);
        DocumentProcessorProcessingSupport processingSupport =
                new DocumentProcessorProcessingSupport(this);
        this.nodeOperations = new DocumentProcessorNodeOperations(
                this, lifecycle, processingSupport);
        this.snapshotOperations =
                new DocumentProcessorSnapshotOperations(
                        this, lifecycle, processingSupport);
        this.administration = new DocumentProcessorAdministration(
                this, lifecycle);
    }

    DocumentProcessor(DocumentProcessorConfiguration configuration) {
        this(DocumentProcessorComponents.from(configuration));
    }

    /**
     * Initializes a mutable document without mutating caller-owned input.
     *
     * @param document document root to initialize
     * @return initialized document and deterministic processing metadata
     */
    public DocumentProcessingResult initializeDocument(Node document) {
        return nodeOperations.initializeDocument(document);
    }

    /**
     * Initializes a verified snapshot while retaining its canonical root.
     *
     * @param snapshot verified snapshot to initialize
     * @return initialized document and deterministic processing metadata
     */
    public DocumentProcessingResult initializeDocument(
            ResolvedSnapshot snapshot) {
        return snapshotOperations.initializeDocument(snapshot);
    }

    /**
     * Processes mutable inputs using a derived exact delivery plan.
     *
     * @param document current document root
     * @param event event to apply
     * @return deterministic document-processing result
     */
    public DocumentProcessingResult processDocument(
            Node document,
            Node event) {
        return nodeOperations.processDocument(document, event);
    }

    /**
     * Processes mutable inputs with explicit revision-bound evidence.
     *
     * @param document current document root
     * @param event event to apply
     * @param evidence verified evidence bound to the processing revision
     * @return deterministic document-processing result
     */
    public DocumentProcessingResult processDocument(
            Node document,
            Node event,
            VerifiedExecutionEvidence evidence) {
        return nodeOperations.processDocument(
                document, event, evidence);
    }

    /**
     * Processes mutable inputs and returns the atomic host companion.
     *
     * @param document current document root
     * @param event event to apply
     * @param evidence verified evidence bound to the processing revision
     * @return platform result containing the semantic result and commit companion
     */
    public PlatformProcessingResult processDocumentForPlatformCommit(
            Node document,
            Node event,
            VerifiedExecutionEvidence evidence) {
        return nodeOperations.processDocumentForPlatformCommit(
                document, event, evidence);
    }

    /** Runs the strict supplied-plan lane through invocation-local services. */
    PlatformProcessingResult processDocumentForPlatformCommit(
            Node document,
            Node event,
            PlatformProcessInvocation invocation,
            ProcessorInvocationServices services) {
        return nodeOperations.processDocumentForPlatformCommit(
                document, event, invocation, services);
    }

    /**
     * Processes mutable inputs and returns a non-semantic debug trace.
     *
     * @param document current document root
     * @param event event to apply
     * @return processing result paired with its observational debug trace
     */
    public ProcessingDebugResult processDocumentWithTrace(
            Node document,
            Node event) {
        return nodeOperations.processDocumentWithTrace(
                document, event);
    }

    /**
     * Processes mutable inputs with explicit evidence and a debug trace.
     *
     * @param document current document root
     * @param event event to apply
     * @param evidence verified evidence bound to the processing revision
     * @return processing result paired with its observational debug trace
     */
    public ProcessingDebugResult processDocumentWithTrace(
            Node document,
            Node event,
            VerifiedExecutionEvidence evidence) {
        return nodeOperations.processDocumentWithTrace(
                document, event, evidence);
    }

    /**
     * Attempts mutable-input processing and reports exact missing resources.
     *
     * @param document current document root
     * @param event event to apply
     * @return completed result or deterministic proof-unavailability details
     */
    public ProcessAttemptResult processAttempt(
            Node document,
            Node event) {
        return nodeOperations.processAttempt(document, event);
    }

    /**
     * Attempts mutable-input processing with a captured evidence envelope.
     *
     * @param document current document root
     * @param event event to apply
     * @param evidence verified evidence bound to the processing revision
     * @return completed result or deterministic proof-unavailability details
     */
    public ProcessAttemptResult processAttempt(
            Node document,
            Node event,
            VerifiedExecutionEvidence evidence) {
        return nodeOperations.processAttempt(
                document, event, evidence);
    }

    /**
     * Processes a verified snapshot using a derived exact delivery plan.
     *
     * @param snapshot verified current document snapshot
     * @param event event to apply
     * @return deterministic document-processing result
     */
    public DocumentProcessingResult processDocument(
            ResolvedSnapshot snapshot,
            Node event) {
        return snapshotOperations.processDocument(snapshot, event);
    }

    /**
     * Processes a verified snapshot with revision-bound evidence.
     *
     * @param snapshot verified current document snapshot
     * @param event event to apply
     * @param evidence verified evidence bound to the processing revision
     * @return deterministic document-processing result
     */
    public DocumentProcessingResult processDocument(
            ResolvedSnapshot snapshot,
            Node event,
            VerifiedExecutionEvidence evidence) {
        return snapshotOperations.processDocument(
                snapshot, event, evidence);
    }

    /**
     * Processes a snapshot and returns the atomic host companion.
     *
     * @param snapshot verified current document snapshot
     * @param event event to apply
     * @param evidence verified evidence bound to the processing revision
     * @return platform result containing the semantic result and commit companion
     */
    public PlatformProcessingResult processDocumentForPlatformCommit(
            ResolvedSnapshot snapshot,
            Node event,
            VerifiedExecutionEvidence evidence) {
        return snapshotOperations.processDocumentForPlatformCommit(
                snapshot, event, evidence);
    }

    /**
     * Processes a snapshot and returns a non-semantic debug trace.
     *
     * @param snapshot verified current document snapshot
     * @param event event to apply
     * @return processing result paired with its observational debug trace
     */
    public ProcessingDebugResult processDocumentWithTrace(
            ResolvedSnapshot snapshot,
            Node event) {
        return snapshotOperations.processDocumentWithTrace(
                snapshot, event);
    }

    /**
     * Processes a snapshot with explicit evidence and a debug trace.
     *
     * @param snapshot verified current document snapshot
     * @param event event to apply
     * @param evidence verified evidence bound to the processing revision
     * @return processing result paired with its observational debug trace
     */
    public ProcessingDebugResult processDocumentWithTrace(
            ResolvedSnapshot snapshot,
            Node event,
            VerifiedExecutionEvidence evidence) {
        return snapshotOperations.processDocumentWithTrace(
                snapshot, event, evidence);
    }

    /**
     * Returns whether a mutable root has a valid initialization marker.
     *
     * @param document document root to inspect
     * @return {@code true} when the root has a valid initialization marker
     */
    public boolean isInitialized(Node document) {
        return nodeOperations.isInitialized(document);
    }

    /**
     * Returns whether a snapshot root has a valid initialization marker.
     *
     * @param snapshot snapshot whose root is inspected
     * @return {@code true} when the root has a valid initialization marker
     */
    public boolean isInitialized(ResolvedSnapshot snapshot) {
        return snapshotOperations.isInitialized(snapshot);
    }

    /** Registers an annotated processor in an internal mutable test generation. */
    DocumentProcessor registerContractProcessor(
            ContractProcessor<? extends Contract> processor) {
        return administration.registerContractProcessor(processor);
    }

    /** Registers an explicit contract identity in an internal mutable test generation. */
    DocumentProcessor registerContractProcessor(
            String blueId,
            ContractProcessor<? extends Contract> processor) {
        return administration.registerContractProcessor(
                blueId, processor);
    }

    /** Registers exact canonical type content in an internal mutable test generation. */
    DocumentProcessor registerContractProcessor(
            String blueId,
            Node canonicalTypeNode,
            ContractProcessor<? extends Contract> processor) {
        return administration.registerContractProcessor(
                blueId, canonicalTypeNode, processor);
    }

    ContractProcessorRegistry registry() { return contractRegistry; }

    NodeToObjectConverter contractConverter() { return contractConverter; }

    ContractLoader contractLoader() { return contractLoader; }

    ConformanceEngine conformanceEngine() { return conformanceEngine; }

    ConformancePlannerOverride conformancePlannerOverride() { return conformancePlannerOverride; }

    ProcessingSnapshotManager snapshotManager() { return snapshotManager; }

    LanguageRuntimeAccess languageRuntimeAccess() {
        return languageRuntimeAccess;
    }

    ProcessorRuntimeAccess.GenerationGuard runtimeGenerationGuard() {
        return runtimeGenerationGuard;
    }

    ProcessingSnapshotManager scopeIdentitySnapshotManager() {
        return administration.scopeIdentitySnapshotManager();
    }

    ContractMatchingService matchingService() { return matchingService; }

    ProcessingObserver observer() {
        return observer != null ? observer : NoOpProcessingObserver.INSTANCE;
    }

    GasMeter newGasMeter() { return new GasMeter(gasSchedule, gasLimit); }

    String runtimeRegistryIdentity() { return runtimeRegistryIdentity; }

    SubscriptionSurfaceValidator subscriptionSurfaceValidator() { return subscriptionSurfaceValidator; }

    GasSchedule gasSchedule() { return gasSchedule; }

    long gasLimit() { return gasLimit; }

    NodeProvider configuredNodeProvider() { return configuredNodeProvider; }

    BlueCachePolicy cachePolicy() { return cachePolicy; }

    boolean hasImmutableConfiguration() { return immutableConfiguration; }

    /**
     * Returns the typed operational observer.
     *
     * @return observer receiving non-semantic processing notifications
     */
    public ProcessingObserver processingObserver() { return observer(); }

    /**
     * Returns whether snapshot-native entry points are configured.
     *
     * @return {@code true} when a processing snapshot manager is configured
     */
    public boolean supportsSnapshotProcessing() { return snapshotManager != null; }

    /**
     * Returns the focused cache, registry, and fragmentation inspection view.
     *
     * @return processor administration and inspection service
     */
    public DocumentProcessorAdministration administration() {
        return administration;
    }

    /** Clears reloadable caches while preserving lifecycle override hooks. */
    public void clearCaches() {
        administration.clearCaches();
    }

    /** Replaces the delivery-plan deriver in internal mutable test generations. */
    DocumentProcessor externalDeliveryPlanDeriver(
            ExternalDeliveryPlanDeriver deriver) {
        return administration.externalDeliveryPlanDeriver(deriver);
    }

    /**
     * Returns whether terminal shutdown has begun.
     *
     * @return {@code true} once terminal shutdown has begun
     */
    public boolean isClosed() { return administration.isClosed(); }

    /**
     * Executes one composed operation against a single captured processor
     * configuration and runtime generation.
     *
     * <p>This is the package-neutral composition boundary used by higher-level
     * runtimes whose model types cannot be imported into the ordinary
     * processor package without creating a package cycle. The supplied
     * operation must not attempt to mutate this processor's configuration.
     * The captured read scope is released in a {@code finally} equivalent,
     * including when the operation throws.</p>
     *
     * @param operation operation to run while the captured revision is live
     * @param <T> operation result type
     * @return the operation result
     * @throws NullPointerException if {@code operation} is {@code null}
     * @throws IllegalStateException if this processor is closed
     *         or the operation attempts a configuration write upgrade
     */
    public <T> T withCapturedConfiguration(Supplier<T> operation) {
        Objects.requireNonNull(operation, "operation");
        try (DocumentProcessorLifecycle.ReadScope ignored =
                     lifecycle.openRead(registry())) {
            return operation.get();
        }
    }

    /** Rejects new work and releases reloadable collaborators when safe. */
    @Override
    public void close() { administration.close(); }

    TypeClassResolver contractTypeResolverInternal() { return contractTypeResolver; }

    ExternalDeliveryPlanDeriver externalDeliveryPlanDeriver() { return externalDeliveryPlanDeriver; }

    ExternalDeliveryEvidenceVerifier deliveryEvidenceVerifier() { return deliveryEvidenceVerifier; }

    ExternalDeliveryEvidenceVerifier configuredDeliveryEvidenceVerifier() {
        return configuredDeliveryEvidenceVerifier;
    }

    SubscriptionSurfaceValidator configuredSubscriptionSurfaceValidator() {
        return configuredSubscriptionSurfaceValidator;
    }

    void replaceExternalDeliveryPlanDeriver(
            ExternalDeliveryPlanDeriver replacement) {
        externalDeliveryPlanDeriver = replacement;
        deliveryEvidenceVerifier =
                RootExternalDeliveryEvidenceVerifier.configured(
                        contractLoader,
                        snapshotManager,
                        contractRegistry,
                        contractConverter,
                        languageRuntimeAccess,
                        gasSchedule,
                        gasLimit,
                        externalDeliveryPlanDeriver);
    }

    void clearOwnedCaches() {
        contractLoader.clearCaches();
        ContractMatchingService currentMatchingService = matchingService;
        if (currentMatchingService != null) {
            currentMatchingService.clearCaches();
        }
    }

    private void detachRuntimeCollaborators() {
        conformanceEngine = null;
        conformancePlannerOverride = null;
        snapshotManager = null;
        languageRuntimeAccess = null;
        runtimeGenerationGuard = null;
        matchingService = null;
        observer = NoOpProcessingObserver.INSTANCE;
    }

    /**
     * Starts an independent processor configuration builder.
     *
     * @return mutable builder with default Contracts collaborators
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Mutable, single-owner configuration builder.
     *
     * <p>Every build snapshots its registry and type resolver. The builder has
     * one canonical vocabulary; configuration mechanics are package-owned.</p>
     */
    public static final class Builder {

        private final DocumentProcessorBuilderSupport support;

        /** Creates a builder with the default Contracts configuration. */
        public Builder() {
            support = new DocumentProcessorBuilderSupport();
        }

        private Builder(DocumentProcessor processor) {
            support = new DocumentProcessorBuilderSupport(processor);
        }

        /**
         * Starts a detached builder for a successor configuration generation.
         *
         * @param processor existing immutable processor generation
         * @return builder initialized from a point-in-time configuration copy
         */
        public static Builder from(DocumentProcessor processor) {
            return new Builder(Objects.requireNonNull(processor, "processor"));
        }

        /**
         * Replaces the runtime contract registry.
         *
         * @param registry registry to snapshot when building
         * @return this builder
         */
        public Builder runtimeRegistry(
                ContractProcessorRegistry registry) {
            return support.runtimeRegistry(registry, this);
        }

        /**
         * Replaces the contract type resolver.
         *
         * @param resolver resolver to snapshot when building
         * @return this builder
         */
        public Builder contractTypeResolver(
                TypeClassResolver resolver) {
            return support.contractTypeResolver(resolver, this);
        }

        /**
         * Adds annotated contract types discovered in one package.
         *
         * @param packageName package to scan
         * @return this builder
         */
        public Builder scanContractTypes(String packageName) {
            return support.scanContractTypes(packageName, this);
        }

        /**
         * Registers one Java contract model under an exact runtime BlueId.
         *
         * @param blueId exact runtime type identity
         * @param contractType Java contract model
         * @return this builder
         */
        public Builder registerContractType(
                String blueId,
                Class<? extends Contract> contractType) {
            return support.registerContractType(
                    blueId, contractType, this);
        }

        /**
         * Registers a processor that declares its own runtime type.
         *
         * @param processor processor to register
         * @return this builder
         */
        public Builder registerContractProcessor(
                ContractProcessor<? extends Contract> processor) {
            return support.registerContractProcessor(processor, this);
        }

        /**
         * Registers a processor under an exact runtime BlueId.
         *
         * @param blueId exact runtime type identity
         * @param processor processor to register
         * @return this builder
         */
        public Builder registerContractProcessor(
                String blueId,
                ContractProcessor<? extends Contract> processor) {
            return support.registerContractProcessor(
                    blueId, processor, this);
        }

        /**
         * Registers a processor with its canonical runtime type node.
         *
         * @param blueId exact runtime type identity
         * @param canonicalTypeNode canonical direct-identity input
         * @param processor processor to register
         * @return this builder
         */
        public Builder registerContractProcessor(
                String blueId,
                Node canonicalTypeNode,
                ContractProcessor<? extends Contract> processor) {
            return support.registerContractProcessor(
                    blueId, canonicalTypeNode, processor, this);
        }

        /**
         * Replaces the optional conformance engine.
         *
         * @param engine conformance engine, or {@code null}
         * @return this builder
         */
        public Builder conformanceEngine(
                ConformanceEngine engine) {
            return support.conformanceEngine(engine, this);
        }

        /**
         * Replaces the optional conformance planner override.
         *
         * @param override planner override, or {@code null}
         * @return this builder
         */
        public Builder conformancePlannerOverride(
                ConformancePlannerOverride override) {
            return support.conformancePlannerOverride(override, this);
        }

        /**
         * Selects the verified snapshot store used for exact evidence.
         *
         * @param store snapshot manager, or {@code null}
         * @return this builder
         */
        public Builder snapshotStore(
                ProcessingSnapshotManager store) {
            return support.snapshotStore(store, this);
        }

        /**
         * Imports one exact processor runtime and snapshot generation.
         *
         * <p>The access view is consulted while its source processor is live.
         * The resulting processor borrows the same Language runtime and
         * snapshot manager, and configures its provider, cache policy, and
         * matching service from that single generation.</p>
         *
         * @param access live processor runtime access view
         * @return this builder
         * @throws NullPointerException if {@code access} is {@code null}
         * @throws IllegalStateException if the source generation is closed,
         *         incomplete, or no longer current
         */
        public Builder runtimeAccess(
                ProcessorRuntimeAccess access) {
            return support.runtimeAccess(access, this);
        }

        /**
         * Replaces contract matching behavior.
         *
         * @param service matching service
         * @return this builder
         */
        public Builder matchingService(
                ContractMatchingService service) {
            return support.matchingService(service, this);
        }

        /**
         * Selects the exact Contracts gas schedule.
         *
         * @param schedule gas schedule
         * @return this builder
         */
        public Builder gasSchedule(GasSchedule schedule) {
            return support.gasSchedule(schedule, this);
        }

        /**
         * Sets the maximum admitted gas for one invocation.
         *
         * @param limit non-negative gas limit
         * @return this builder
         */
        public Builder gasLimit(long limit) {
            return support.gasLimit(limit, this);
        }

        /**
         * Binds generated evidence to an exact runtime registry identity.
         *
         * @param identity nonblank registry identity
         * @return this builder
         */
        public Builder runtimeRegistryIdentity(String identity) {
            return support.runtimeRegistryIdentity(identity, this);
        }

        /**
         * Selects the complete external-delivery plan deriver.
         *
         * @param deriver plan deriver
         * @return this builder
         */
        public Builder deliveryPlanDeriver(
                ExternalDeliveryPlanDeriver deriver) {
            return support.deliveryPlanDeriver(deriver, this);
        }

        /**
         * Selects the external-delivery evidence verifier.
         *
         * @param verifier evidence verifier
         * @return this builder
         */
        public Builder evidenceVerifier(
                ExternalDeliveryEvidenceVerifier verifier) {
            return support.evidenceVerifier(verifier, this);
        }

        /**
         * Selects final subscription-surface validation behavior.
         *
         * @param validator subscription validator
         * @return this builder
         */
        public Builder subscriptionSurfaceValidator(
                SubscriptionSurfaceValidator validator) {
            return support.subscriptionSurfaceValidator(validator, this);
        }

        /**
         * Wraps one exact node provider as the snapshot evidence source.
         *
         * @param provider exact node provider
         * @return this builder
         */
        public Builder nodeProvider(NodeProvider provider) {
            return support.nodeProvider(provider, this);
        }

        /**
         * Selects the failure-isolated processing observer.
         *
         * @param observer processing observer
         * @return this builder
         */
        public Builder observer(ProcessingObserver observer) {
            return support.observer(observer, this);
        }

        /**
         * Selects bounded cache policy for processor-owned caches.
         *
         * @param policy cache policy
         * @return this builder
         */
        public Builder cachePolicy(BlueCachePolicy policy) {
            return support.cachePolicy(policy, this);
        }

        /**
         * Builds one processor from the current configuration snapshot.
         *
         * @return independent processor generation
         */
        public DocumentProcessor build() {
            return support.build();
        }
    }
}
