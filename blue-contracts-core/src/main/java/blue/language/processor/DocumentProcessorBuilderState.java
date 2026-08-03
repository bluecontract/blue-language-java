package blue.language.processor;

import blue.language.api.BlueCachePolicy;
import blue.language.provider.NodeProvider;
import blue.language.conformance.ConformanceEngine;
import blue.language.model.Node;
import blue.language.processor.model.Contract;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.mapping.TypeClassResolver;
import blue.language.runtime.LanguageRuntimeAccess;

import java.util.Objects;

/**
 * Single-owner mutable state behind {@link DocumentProcessor.Builder}.
 *
 * <p>Every build creates an immutable generation: registry and resolver are
 * detached at build time, while intentionally supplied service interfaces are
 * retained as immutable configuration values.</p>
 */
final class DocumentProcessorBuilderState {

    private static final String RUNTIME_ACCESS_OVERRIDE =
            "Processor runtime access configures snapshots, matching, provider, and cache policy atomically";
    private static final String RUNTIME_ACCESS_CONFLICT =
            "Processor runtime access cannot be combined with individually configured snapshots, matching, provider, or cache policy";
    private static final String IMPORTED_RUNTIME_REGISTRY_IDENTITY_REQUIRED =
            "A custom runtime registry combined with imported processor runtime access requires an explicit non-default runtime registry identity";

    private ContractProcessorRegistry contractRegistry =
            ContractProcessorRegistryBuilder.create()
                    .registerDefaults()
                    .build();
    private TypeClassResolver contractTypeResolver =
            DocumentProcessorConfigurationSupport
                    .defaultContractTypeResolver();
    private ConformanceEngine conformanceEngine;
    private ConformancePlannerOverride conformancePlannerOverride;
    private ProcessingSnapshotManager snapshotManager;
    private LanguageRuntimeAccess languageRuntimeAccess;
    private ProcessorRuntimeAccess.GenerationGuard
            runtimeGenerationGuard;
    private ContractMatchingService matchingService =
            new ContractMatchingService();
    private boolean matchingServiceExplicit;
    private boolean runtimeAccessExplicit;
    private boolean snapshotManagerConfigured;
    private boolean matchingServiceConfigured;
    private boolean nodeProviderConfigured;
    private boolean cachePolicyConfigured;
    private ProcessingObserver observer =
            NoOpProcessingObserver.INSTANCE;
    private NodeProvider nodeProvider;
    private BlueCachePolicy cachePolicy;
    private GasSchedule gasSchedule = GasSchedule.contracts10();
    private Long gasLimit;
    private String runtimeRegistryIdentity =
            RuntimeBlueIds.REGISTRY_PACKAGE_IDENTITY;
    private boolean runtimeRegistryConfigured;
    private boolean runtimeRegistryIdentityConfigured;
    private ExternalDeliveryPlanDeriver externalDeliveryPlanDeriver =
            ExternalDeliveryPlanDeriver.unavailable();
    private ExternalDeliveryEvidenceVerifier deliveryEvidenceVerifier;
    private SubscriptionSurfaceValidator subscriptionSurfaceValidator;

    DocumentProcessorBuilderState() {
    }

    DocumentProcessorBuilderState(DocumentProcessor processor) {
        Objects.requireNonNull(processor, "processor");
        contractProcessorConfiguration(processor);
    }

    private void contractProcessorConfiguration(DocumentProcessor processor) {
        contractRegistry = processor.registry().mutableCopy();
        contractTypeResolver = DocumentProcessorConfigurationSupport
                .copyContractTypeResolver(
                        processor.contractTypeResolverInternal());
        conformanceEngine = processor.conformanceEngine();
        conformancePlannerOverride = processor.conformancePlannerOverride();
        snapshotManager = processor.snapshotManager();
        languageRuntimeAccess = processor.languageRuntimeAccess();
        runtimeGenerationGuard =
                processor.runtimeGenerationGuard();
        runtimeAccessExplicit = runtimeGenerationGuard != null;
        matchingService = processor.matchingService();
        matchingServiceExplicit = true;
        observer = processor.observer();
        nodeProvider = processor.configuredNodeProvider();
        cachePolicy = processor.cachePolicy();
        gasSchedule = processor.gasSchedule();
        gasLimit = processor.gasLimit();
        runtimeRegistryIdentity = processor.runtimeRegistryIdentity();
        if (runtimeGenerationGuard != null
                && !RuntimeBlueIds.REGISTRY_PACKAGE_IDENTITY.equals(
                        runtimeRegistryIdentity)) {
            runtimeRegistryConfigured = true;
            runtimeRegistryIdentityConfigured = true;
        }
        externalDeliveryPlanDeriver = processor.externalDeliveryPlanDeriver();
        deliveryEvidenceVerifier =
                processor.configuredDeliveryEvidenceVerifier();
        subscriptionSurfaceValidator =
                processor.configuredSubscriptionSurfaceValidator();
    }

    void registry(ContractProcessorRegistry registry, boolean modern) {
        contractRegistry = Objects.requireNonNull(registry, "registry");
        markRuntimeRegistryChanged();
    }

    void contractTypeResolver(TypeClassResolver resolver) {
        contractTypeResolver = Objects.requireNonNull(resolver, "resolver");
        markRuntimeRegistryChanged();
    }

    void scanContractTypes(String packageName) {
        markRuntimeRegistryChanged();
        contractTypeResolver.scanPackage(packageName);
    }

    void registerContractType(
            String blueId,
            Class<? extends Contract> contractType) {
        markRuntimeRegistryChanged();
        contractTypeResolver.register(blueId, contractType);
    }

    void registerContractProcessor(
            ContractProcessor<? extends Contract> processor) {
        Objects.requireNonNull(processor, "processor");
        markRuntimeRegistryChanged();
        contractRegistry.register(processor);
        DocumentProcessorConfigurationSupport
                .registerAnnotatedContractType(
                        contractTypeResolver,
                        processor.contractType());
    }

    void registerContractProcessor(
            String blueId,
            ContractProcessor<? extends Contract> processor) {
        Objects.requireNonNull(processor, "processor");
        markRuntimeRegistryChanged();
        contractRegistry.register(blueId, processor);
        contractTypeResolver.register(
                blueId, processor.contractType());
    }

    void registerContractProcessor(
            String blueId,
            Node canonicalTypeNode,
            ContractProcessor<? extends Contract> processor) {
        markRuntimeRegistryChanged();
        DocumentProcessorConfigurationSupport
                .registerExactContractProcessor(
                        contractRegistry,
                        contractTypeResolver,
                        blueId,
                        canonicalTypeNode,
                        processor);
    }

    void conformanceEngine(ConformanceEngine engine) {
        conformanceEngine = engine;
    }

    void conformancePlannerOverride(
            ConformancePlannerOverride override) {
        conformancePlannerOverride = override;
    }

    void snapshotManager(
            ProcessingSnapshotManager manager,
            boolean modern) {
        rejectRuntimeAccessOverride();
        snapshotManager = modern
                ? Objects.requireNonNull(manager, "snapshotStore")
                : manager;
        snapshotManagerConfigured = true;
    }

    void matchingService(ContractMatchingService service) {
        rejectRuntimeAccessOverride();
        matchingService = Objects.requireNonNull(
                service, "matchingService");
        languageRuntimeAccess = matchingService.blue();
        matchingServiceExplicit = true;
        matchingServiceConfigured = true;
    }

    void runtimeAccess(ProcessorRuntimeAccess access) {
        rejectIndividualRuntimeConfiguration();
        ProcessorRuntimeAccess.Binding binding = Objects.requireNonNull(
                access, "runtimeAccess").binding();
        LanguageRuntimeAccess runtime = binding.languageRuntime;
        NodeProvider importedProvider;
        BlueCachePolicy importedCachePolicy;
        ContractMatchingService importedMatchingService;
        try (ProcessorRuntimeAccess.GenerationLease ignored =
                     binding.generationGuard.open()) {
            importedProvider = runtime.getNodeProvider();
            importedCachePolicy = runtime.cachePolicy();
            importedMatchingService =
                    new ContractMatchingService(runtime);
        }
        snapshotManager = binding.snapshotManager;
        languageRuntimeAccess = runtime;
        runtimeGenerationGuard = binding.generationGuard;
        nodeProvider = importedProvider;
        cachePolicy = importedCachePolicy;
        matchingService = importedMatchingService;
        matchingServiceExplicit = true;
        runtimeAccessExplicit = true;
    }

    void observer(ProcessingObserver value, boolean modern) {
        observer = modern
                ? Objects.requireNonNull(value, "observer")
                : value != null
                        ? value
                        : NoOpProcessingObserver.INSTANCE;
    }

    void nodeProvider(NodeProvider provider) {
        rejectRuntimeAccessOverride();
        nodeProvider = Objects.requireNonNull(provider, "provider");
        nodeProviderConfigured = true;
    }

    void cachePolicy(BlueCachePolicy policy) {
        rejectRuntimeAccessOverride();
        cachePolicy = Objects.requireNonNull(policy, "policy");
        cachePolicyConfigured = true;
    }

    private void rejectRuntimeAccessOverride() {
        if (runtimeAccessExplicit) {
            throw new IllegalStateException(
                    RUNTIME_ACCESS_OVERRIDE);
        }
    }

    private void rejectIndividualRuntimeConfiguration() {
        if (snapshotManagerConfigured
                || matchingServiceConfigured
                || nodeProviderConfigured
                || cachePolicyConfigured) {
            throw new IllegalStateException(
                    RUNTIME_ACCESS_CONFLICT);
        }
    }

    private void markRuntimeRegistryChanged() {
        runtimeRegistryConfigured = true;
        runtimeRegistryIdentityConfigured = false;
    }

    void gasSchedule(GasSchedule schedule, boolean modern) {
        gasSchedule = Objects.requireNonNull(
                schedule,
                modern ? "schedule" : "gasSchedule");
        if (gasLimit != null
                && gasLimit > gasSchedule.maxProcessGas()) {
            throw new IllegalArgumentException(
                    "Configured gas limit exceeds manifest maxProcessGas");
        }
    }

    void gasLimit(long limit, boolean modern) {
        if (limit < 0L || limit > gasSchedule.maxProcessGas()) {
            throw new IllegalArgumentException(
                    "Gas limit must be between 0 and manifest maxProcessGas "
                            + gasSchedule.maxProcessGas());
        }
        gasLimit = limit;
    }

    void runtimeRegistryIdentity(String identity) {
        if (identity == null || identity.isEmpty()) {
            throw new IllegalArgumentException(
                    "Runtime registry identity must not be empty");
        }
        runtimeRegistryIdentity = identity;
        runtimeRegistryIdentityConfigured = true;
    }

    void deliveryPlanDeriver(
            ExternalDeliveryPlanDeriver deriver,
            boolean modern) {
        externalDeliveryPlanDeriver = Objects.requireNonNull(
                deriver, "deriver");
    }

    void deliveryEvidenceVerifier(
            ExternalDeliveryEvidenceVerifier verifier,
            boolean modern) {
        deliveryEvidenceVerifier = Objects.requireNonNull(
                verifier, "verifier");
    }

    void subscriptionSurfaceValidator(
            SubscriptionSurfaceValidator validator,
            boolean modern) {
        subscriptionSurfaceValidator = Objects.requireNonNull(
                validator, "validator");
    }

    /** Captures the exact ownership policy and collaborators for one build. */
    DocumentProcessorConfiguration snapshot() {
        validateImportedRuntimeRegistryBinding();
        ContractProcessorRegistry effectiveRegistry =
                contractRegistry.immutableSnapshot();
        TypeClassResolver effectiveResolver =
                DocumentProcessorConfigurationSupport
                        .copyContractTypeResolver(contractTypeResolver);
        return new DocumentProcessorConfiguration(
                effectiveRegistry,
                effectiveResolver,
                conformanceEngine,
                conformancePlannerOverride,
                snapshotManager,
                languageRuntimeAccess,
                runtimeGenerationGuard,
                effectiveMatchingService(),
                observer,
                nodeProvider,
                cachePolicy,
                gasSchedule,
                gasLimit,
                runtimeRegistryIdentity,
                externalDeliveryPlanDeriver,
                deliveryEvidenceVerifier,
                subscriptionSurfaceValidator,
                true);
    }

    /** Acquires the imported source generation across one complete build. */
    ProcessorRuntimeAccess.GenerationLease openRuntimeGeneration() {
        validateImportedRuntimeRegistryBinding();
        return runtimeGenerationGuard != null
                ? runtimeGenerationGuard.open()
                : null;
    }

    private void validateImportedRuntimeRegistryBinding() {
        if (runtimeGenerationGuard != null
                && runtimeRegistryConfigured
                && (!runtimeRegistryIdentityConfigured
                || RuntimeBlueIds.REGISTRY_PACKAGE_IDENTITY.equals(
                        runtimeRegistryIdentity))) {
            throw new IllegalStateException(
                    IMPORTED_RUNTIME_REGISTRY_IDENTITY_REQUIRED);
        }
    }

    private ContractMatchingService effectiveMatchingService() {
        if (matchingServiceExplicit
                || (nodeProvider == null && cachePolicy == null)) {
            return matchingService;
        }
        BlueCachePolicy effectivePolicy = cachePolicy != null
                ? cachePolicy
                : BlueCachePolicy.boundedDefaults();
        return new ContractMatchingService(
                nodeProvider, effectivePolicy);
    }
}
