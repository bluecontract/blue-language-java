package blue.language.processor;

import blue.language.api.BlueCachePolicy;
import blue.language.provider.NodeProvider;
import blue.language.conformance.ConformanceEngine;
import blue.language.mapping.NodeToObjectConverter;
import blue.language.model.Node;
import blue.language.processor.model.Contract;
import blue.language.processor.model.MarkerContract;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.merge.ResolvedSnapshot;
import blue.language.mapping.TypeClassResolver;

import java.util.Map;
import java.util.Objects;

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
        this(new Builder());
    }

    /** Package-private compatibility constructor for kernel tests. */
    DocumentProcessor(ContractProcessorRegistry registry) {
        this(registry,
                DocumentProcessorConfigurationSupport
                        .defaultContractTypeResolver(),
                null,
                null);
    }

    /** Package-private compatibility constructor for kernel tests. */
    DocumentProcessor(ConformanceEngine conformanceEngine) {
        this(ContractProcessorRegistryBuilder.create()
                        .registerDefaults()
                        .build(),
                conformanceEngine,
                null);
    }

    /** Package-private compatibility constructor for kernel tests. */
    DocumentProcessor(
            ConformanceEngine conformanceEngine,
            ProcessingSnapshotManager snapshotManager) {
        this(ContractProcessorRegistryBuilder.create()
                        .registerDefaults()
                        .build(),
                conformanceEngine,
                snapshotManager);
    }

    /** Package-private compatibility constructor for kernel tests. */
    DocumentProcessor(
            ContractProcessorRegistry registry,
            ConformanceEngine conformanceEngine) {
        this(registry, conformanceEngine, null);
    }

    /** Package-private compatibility constructor for kernel tests. */
    DocumentProcessor(
            ContractProcessorRegistry registry,
            ConformanceEngine conformanceEngine,
            ProcessingSnapshotManager snapshotManager) {
        this(registry,
                DocumentProcessorConfigurationSupport
                        .defaultContractTypeResolver(),
                conformanceEngine,
                snapshotManager);
    }

    /** Package-private compatibility constructor for kernel tests. */
    DocumentProcessor(
            ContractProcessorRegistry registry,
            TypeClassResolver contractTypeResolver,
            ConformanceEngine conformanceEngine,
            ProcessingSnapshotManager snapshotManager) {
        this(registry,
                contractTypeResolver,
                conformanceEngine,
                snapshotManager,
                new ContractMatchingService());
    }

    /** Package-private compatibility constructor for kernel tests. */
    DocumentProcessor(
            ContractProcessorRegistry registry,
            TypeClassResolver contractTypeResolver,
            ConformanceEngine conformanceEngine,
            ProcessingSnapshotManager snapshotManager,
            ContractMatchingService matchingService) {
        this(registry,
                contractTypeResolver,
                conformanceEngine,
                snapshotManager,
                matchingService,
                null);
    }

    /** Package-private compatibility constructor for kernel tests. */
    DocumentProcessor(
            ContractProcessorRegistry registry,
            TypeClassResolver contractTypeResolver,
            ConformanceEngine conformanceEngine,
            ProcessingSnapshotManager snapshotManager,
            ContractMatchingService matchingService,
            ProcessingObserver observer) {
        this(registry,
                contractTypeResolver,
                conformanceEngine,
                null,
                snapshotManager,
                matchingService,
                observer);
    }

    /** Package-private compatibility constructor for kernel tests. */
    DocumentProcessor(
            ContractProcessorRegistry registry,
            TypeClassResolver contractTypeResolver,
            ConformanceEngine conformanceEngine,
            ConformancePlannerOverride conformancePlannerOverride,
            ProcessingSnapshotManager snapshotManager,
            ContractMatchingService matchingService,
            ProcessingObserver observer) {
        this(registry,
                contractTypeResolver,
                conformanceEngine,
                conformancePlannerOverride,
                snapshotManager,
                matchingService,
                observer,
                null,
                null,
                GasSchedule.contracts10(),
                null,
                RuntimeBlueIds.REGISTRY_PACKAGE_IDENTITY,
                ExternalDeliveryPlanDeriver.unavailable(),
                null,
                null,
                false);
    }

    private DocumentProcessor(
            ContractProcessorRegistry registry,
            TypeClassResolver contractTypeResolver,
            ConformanceEngine conformanceEngine,
            ConformancePlannerOverride conformancePlannerOverride,
            ProcessingSnapshotManager snapshotManager,
            ContractMatchingService matchingService,
            ProcessingObserver observer,
            NodeProvider nodeProvider,
            BlueCachePolicy cachePolicy,
            GasSchedule gasSchedule,
            Long gasLimit,
            String runtimeRegistryIdentity,
            ExternalDeliveryPlanDeriver externalDeliveryPlanDeriver,
            ExternalDeliveryEvidenceVerifier deliveryEvidenceVerifier,
            SubscriptionSurfaceValidator subscriptionSurfaceValidator,
            boolean immutableConfiguration) {
        this.contractRegistry = Objects.requireNonNull(registry, "registry");
        this.contractTypeResolver = Objects.requireNonNull(contractTypeResolver, "contractTypeResolver");
        DocumentProcessorConfigurationSupport.registerRegistryContractTypes(
                this.contractRegistry, this.contractTypeResolver);
        this.contractConverter = new NodeToObjectConverter(this.contractTypeResolver);
        this.matchingService = Objects.requireNonNull(matchingService, "matchingService");
        this.cachePolicy = cachePolicy != null
                ? cachePolicy
                : this.matchingService.cachePolicy();
        this.configuredNodeProvider = nodeProvider != null
                ? nodeProvider
                : this.matchingService.blue() != null
                        ? this.matchingService.blue().getNodeProvider()
                        : null;
        this.contractLoader = new ContractLoader(
                contractRegistry,
                contractConverter,
                this.contractTypeResolver,
                this.cachePolicy,
                this.configuredNodeProvider);
        this.conformanceEngine = conformanceEngine;
        this.conformancePlannerOverride = conformancePlannerOverride;
        this.snapshotManager = snapshotManager;
        this.observer = observer != null
                ? observer
                : NoOpProcessingObserver.INSTANCE;
        this.gasSchedule = Objects.requireNonNull(gasSchedule, "gasSchedule");
        this.contractLoader.gasSchedule(this.gasSchedule);
        this.gasLimit = gasLimit != null
                ? gasLimit
                : this.gasSchedule.maxProcessGas();
        this.runtimeRegistryIdentity = Objects.requireNonNull(
                runtimeRegistryIdentity, "runtimeRegistryIdentity");
        this.externalDeliveryPlanDeriver = Objects.requireNonNull(
                externalDeliveryPlanDeriver, "externalDeliveryPlanDeriver");
        this.configuredDeliveryEvidenceVerifier =
                deliveryEvidenceVerifier;
        this.deliveryEvidenceVerifier = deliveryEvidenceVerifier != null
                ? deliveryEvidenceVerifier
                : RootExternalDeliveryEvidenceVerifier.configured(
                        contractLoader,
                        snapshotManager,
                        contractRegistry,
                        contractConverter,
                        this.externalDeliveryPlanDeriver);
        this.configuredSubscriptionSurfaceValidator =
                subscriptionSurfaceValidator;
        this.subscriptionSurfaceValidator = subscriptionSurfaceValidator != null
                ? subscriptionSurfaceValidator
                : DirectSubscriptionSurfaceValidator.configured(
                        contractLoader,
                        snapshotManager,
                        contractRegistry,
                        contractConverter);
        this.immutableConfiguration = immutableConfiguration;
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
                });
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

    private DocumentProcessor(Builder builder) {
        this(builder.configuration.snapshot());
    }

    private DocumentProcessor(DocumentProcessorConfiguration configuration) {
        this(configuration.contractRegistry,
                configuration.contractTypeResolver,
                configuration.conformanceEngine,
                configuration.conformancePlannerOverride,
                configuration.snapshotManager,
                configuration.matchingService,
                configuration.observer,
                configuration.nodeProvider,
                configuration.cachePolicy,
                configuration.gasSchedule,
                configuration.gasLimit,
                configuration.runtimeRegistryIdentity,
                configuration.externalDeliveryPlanDeriver,
                configuration.deliveryEvidenceVerifier,
                configuration.subscriptionSurfaceValidator,
                configuration.immutableConfiguration);
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

    /**
     * Returns the frozen contract registry used by subsequent invocations.
     *
     * @return runtime contract registry
     */
    public ContractProcessorRegistry getContractRegistry() {
        return contractRegistry;
    }

    /**
     * Returns a detached view of the contract type resolver so caller
     * registration cannot mutate the running processor.
     *
     * @return contract type resolver view
     */
    public TypeClassResolver getContractTypeResolver() {
        return DocumentProcessorConfigurationSupport
                .copyContractTypeResolver(contractTypeResolver);
    }

    ContractProcessorRegistry registry() { return contractRegistry; }

    NodeToObjectConverter contractConverter() { return contractConverter; }

    ContractLoader contractLoader() { return contractLoader; }

    ConformanceEngine conformanceEngine() { return conformanceEngine; }

    ConformancePlannerOverride conformancePlannerOverride() { return conformancePlannerOverride; }

    ProcessingSnapshotManager snapshotManager() { return snapshotManager; }

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

    /** Replaces the delivery-plan deriver in internal mutable test generations. */
    DocumentProcessor externalDeliveryPlanDeriver(
            ExternalDeliveryPlanDeriver deriver) {
        return administration.externalDeliveryPlanDeriver(deriver);
    }

    /** Releases every reloadable processor-owned cache. */
    public void clearCaches() { administration.clearCaches(); }

    /**
     * Returns a saturated count of reloadable cache entries.
     *
     * @return current cache-entry count, saturated at {@link Integer#MAX_VALUE}
     */
    public int cacheEntryCount() { return administration.cacheEntryCount(); }

    /**
     * Returns a saturated approximation of reloadable cache weight.
     *
     * @return approximate cache weight in bytes, saturated at {@link Long#MAX_VALUE}
     */
    public long cacheWeightBytes() { return administration.cacheWeightBytes(); }

    /**
     * Returns the immutable marker view for one exact scope.
     *
     * @param scopeNode node containing the marker scope
     * @param scopePath canonical path identifying the exact scope
     * @return immutable map of marker key to marker contract
     */
    public Map<String, MarkerContract> markersFor(
            Node scopeNode,
            String scopePath) {
        return administration.markersFor(scopeNode, scopePath);
    }

    /**
     * Inspects effective fragmentation without semantic execution.
     *
     * @param document document whose effective fragmentation is inspected
     * @return deterministic effective fragmentation catalog
     */
    public EffectiveFragmentationCatalog effectiveFragmentationCatalog(
            Node document) {
        return administration.effectiveFragmentationCatalog(document);
    }

    /**
     * Returns whether terminal shutdown has begun.
     *
     * @return {@code true} once terminal shutdown has begun
     */
    public boolean isClosed() { return administration.isClosed(); }

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
     * <p>Every build snapshots its registry and type resolver. Builder aliases
     * retained for source migration have the same immutable ownership policy.</p>
     */
    public static final class Builder {
        private final DocumentProcessorBuilderState configuration;

        /** Creates a builder with the default Contracts configuration. */
        public Builder() {
            this.configuration = new DocumentProcessorBuilderState();
        }

        private Builder(DocumentProcessor processor) {
            this.configuration = new DocumentProcessorBuilderState(processor);
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
         * Selects the registry to snapshot at build time.
         *
         * @param registry mutable registry to snapshot
         * @return this builder
         */
        public Builder withRegistry(ContractProcessorRegistry registry) {
            configuration.registry(registry, false); return this;
        }

        /**
         * Selects the type resolver to snapshot at build time.
         *
         * @param resolver type resolver to snapshot
         * @return this builder
         */
        public Builder withContractTypeResolver(TypeClassResolver resolver) {
            configuration.contractTypeResolver(resolver); return this;
        }

        /**
         * Scans one package into the builder resolver.
         *
         * @param packageName Java package containing contract types
         * @return this builder
         */
        public Builder scanContractTypes(String packageName) {
            configuration.scanContractTypes(packageName); return this;
        }

        /**
         * Registers one explicit contract type.
         *
         * @param blueId exact BlueId identifying the contract type
         * @param contractType Java class representing the contract type
         * @return this builder
         */
        public Builder registerContractType(
                String blueId, Class<? extends Contract> contractType) {
            configuration.registerContractType(blueId, contractType); return this;
        }

        /**
         * Registers one annotated contract processor.
         *
         * @param processor processor whose annotated contract type is registered
         * @return this builder
         */
        public Builder registerContractProcessor(
                ContractProcessor<? extends Contract> processor) {
            configuration.registerContractProcessor(processor); return this;
        }

        /**
         * Registers a processor for an explicit BlueId.
         *
         * @param blueId exact BlueId identifying the contract type
         * @param processor processor registered for that identity
         * @return this builder
         */
        public Builder registerContractProcessor(
                String blueId, ContractProcessor<? extends Contract> processor) {
            configuration.registerContractProcessor(blueId, processor); return this;
        }

        /**
         * Registers a processor with exact canonical type content.
         *
         * @param blueId exact BlueId identifying the contract type
         * @param canonicalTypeNode canonical content whose identity must match {@code blueId}
         * @param processor processor registered for that exact type
         * @return this builder
         */
        public Builder registerContractProcessor(
                String blueId, Node canonicalTypeNode,
                ContractProcessor<? extends Contract> processor) {
            configuration.registerContractProcessor(
                    blueId, canonicalTypeNode, processor);
            return this;
        }

        /**
         * Selects optional conformance.
         *
         * @param engine conformance engine, or {@code null} to disable conformance
         * @return this builder
         */
        public Builder withConformanceEngine(ConformanceEngine engine) {
            configuration.conformanceEngine(engine); return this;
        }

        /**
         * Selects an optional planner override.
         *
         * @param override planner override, or {@code null} to use the configured engine
         * @return this builder
         */
        public Builder withConformancePlannerOverride(
                ConformancePlannerOverride override) {
            configuration.conformancePlannerOverride(override); return this;
        }

        /**
         * Selects the snapshot manager.
         *
         * @param manager processing snapshot manager
         * @return this builder
         */
        public Builder withSnapshotManager(ProcessingSnapshotManager manager) {
            configuration.snapshotManager(manager, false); return this;
        }

        /**
         * Selects the matching service.
         *
         * @param service contract matching service
         * @return this builder
         */
        public Builder withMatchingService(ContractMatchingService service) {
            configuration.matchingService(service); return this;
        }

        /**
         * Selects the gas schedule.
         *
         * @param schedule deterministic gas schedule
         * @return this builder
         */
        public Builder withGasSchedule(GasSchedule schedule) {
            configuration.gasSchedule(schedule, false); return this;
        }

        /**
         * Selects the gas limit.
         *
         * @param limit maximum gas available to one processing invocation
         * @return this builder
         */
        public Builder withGasLimit(long limit) {
            configuration.gasLimit(limit, false); return this;
        }

        /**
         * Selects the registry identity bound into evidence.
         *
         * @param identity canonical runtime-registry identity
         * @return this builder
         */
        public Builder withRuntimeRegistryIdentity(String identity) {
            configuration.runtimeRegistryIdentity(identity); return this;
        }

        /**
         * Selects the evidence verifier.
         *
         * @param verifier external-delivery evidence verifier
         * @return this builder
         */
        public Builder withExternalDeliveryEvidenceVerifier(
                ExternalDeliveryEvidenceVerifier verifier) {
            configuration.deliveryEvidenceVerifier(verifier, false); return this;
        }

        /**
         * Selects the delivery-plan deriver.
         *
         * @param deriver external-delivery plan deriver
         * @return this builder
         */
        public Builder withExternalDeliveryPlanDeriver(
                ExternalDeliveryPlanDeriver deriver) {
            configuration.deliveryPlanDeriver(deriver, false); return this;
        }

        /**
         * Selects the subscription validator.
         *
         * @param validator subscription-surface validator
         * @return this builder
         */
        public Builder withSubscriptionSurfaceValidator(
                SubscriptionSurfaceValidator validator) {
            configuration.subscriptionSurfaceValidator(validator, false); return this;
        }

        /**
         * Selects the verified provider for an immutable generation.
         *
         * @param provider provider of verified canonical nodes
         * @return this builder
         */
        public Builder nodeProvider(NodeProvider provider) {
            configuration.nodeProvider(provider); return this;
        }

        /**
         * Selects the registry for an immutable generation.
         *
         * @param registry contract registry to snapshot
         * @return this builder
         */
        public Builder runtimeRegistry(ContractProcessorRegistry registry) {
            configuration.registry(registry, true); return this;
        }

        /**
         * Selects the gas schedule for an immutable generation.
         *
         * @param schedule deterministic gas schedule
         * @return this builder
         */
        public Builder gasSchedule(GasSchedule schedule) {
            configuration.gasSchedule(schedule, true); return this;
        }

        /**
         * Selects the gas budget for an immutable generation.
         *
         * @param limit maximum gas available to one processing invocation
         * @return this builder
         */
        public Builder gasLimit(long limit) {
            configuration.gasLimit(limit, true); return this;
        }

        /**
         * Selects the delivery-plan deriver for an immutable generation.
         *
         * @param deriver external-delivery plan deriver
         * @return this builder
         */
        public Builder deliveryPlanDeriver(ExternalDeliveryPlanDeriver deriver) {
            configuration.deliveryPlanDeriver(deriver, true); return this;
        }

        /**
         * Selects the evidence verifier for an immutable generation.
         *
         * @param verifier external-delivery evidence verifier
         * @return this builder
         */
        public Builder evidenceVerifier(ExternalDeliveryEvidenceVerifier verifier) {
            configuration.deliveryEvidenceVerifier(verifier, true); return this;
        }

        /**
         * Selects the subscription validator for an immutable generation.
         *
         * @param validator subscription-surface validator
         * @return this builder
         */
        public Builder subscriptionSurfaceValidator(
                SubscriptionSurfaceValidator validator) {
            configuration.subscriptionSurfaceValidator(validator, true); return this;
        }

        /**
         * Selects the snapshot store for an immutable generation.
         *
         * @param snapshotStore processing snapshot store
         * @return this builder
         */
        public Builder snapshotStore(ProcessingSnapshotManager snapshotStore) {
            configuration.snapshotManager(snapshotStore, true); return this;
        }

        /**
         * Selects the observer for an immutable generation.
         *
         * @param processingObserver observer receiving operational notifications
         * @return this builder
         */
        public Builder observer(ProcessingObserver processingObserver) {
            configuration.observer(processingObserver, true); return this;
        }

        /**
         * Selects cache bounds for an immutable generation.
         *
         * @param policy cache bounds and weighting policy
         * @return this builder
         */
        public Builder cachePolicy(BlueCachePolicy policy) {
            configuration.cachePolicy(policy); return this;
        }

        /**
         * Builds one processor from the current configuration snapshot.
         *
         * @return independent processor generation
         */
        public DocumentProcessor build() {
            return new DocumentProcessor(this);
        }
    }
}
