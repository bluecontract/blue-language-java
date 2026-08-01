package blue.language.processor;

import blue.language.api.BlueCachePolicy;
import blue.language.provider.NodeProvider;
import blue.language.conformance.ConformanceEngine;
import blue.language.model.Node;
import blue.language.processor.model.Contract;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.mapping.TypeClassResolver;

import java.util.Objects;

/**
 * Single-owner mutable state behind {@link DocumentProcessor.Builder}.
 *
 * <p>Every build creates an immutable generation: registry and resolver are
 * detached at build time, while intentionally supplied service interfaces are
 * retained as immutable configuration values.</p>
 */
final class DocumentProcessorBuilderState {

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
    private ContractMatchingService matchingService =
            new ContractMatchingService();
    private boolean matchingServiceExplicit;
    private ProcessingObserver observer =
            NoOpProcessingObserver.INSTANCE;
    private NodeProvider nodeProvider;
    private BlueCachePolicy cachePolicy;
    private GasSchedule gasSchedule = GasSchedule.contracts10();
    private Long gasLimit;
    private String runtimeRegistryIdentity =
            RuntimeBlueIds.REGISTRY_PACKAGE_IDENTITY;
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
        matchingService = processor.matchingService();
        matchingServiceExplicit = true;
        observer = processor.observer();
        nodeProvider = processor.configuredNodeProvider();
        cachePolicy = processor.cachePolicy();
        gasSchedule = processor.gasSchedule();
        gasLimit = processor.gasLimit();
        runtimeRegistryIdentity = processor.runtimeRegistryIdentity();
        externalDeliveryPlanDeriver = processor.externalDeliveryPlanDeriver();
        deliveryEvidenceVerifier =
                processor.configuredDeliveryEvidenceVerifier();
        subscriptionSurfaceValidator =
                processor.configuredSubscriptionSurfaceValidator();
    }

    void registry(ContractProcessorRegistry registry, boolean modern) {
        contractRegistry = Objects.requireNonNull(registry, "registry");
    }

    void contractTypeResolver(TypeClassResolver resolver) {
        contractTypeResolver = Objects.requireNonNull(resolver, "resolver");
    }

    void scanContractTypes(String packageName) {
        contractTypeResolver.scanPackage(packageName);
    }

    void registerContractType(
            String blueId,
            Class<? extends Contract> contractType) {
        contractTypeResolver.register(blueId, contractType);
    }

    void registerContractProcessor(
            ContractProcessor<? extends Contract> processor) {
        Objects.requireNonNull(processor, "processor");
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
        contractRegistry.register(blueId, processor);
        contractTypeResolver.register(
                blueId, processor.contractType());
    }

    void registerContractProcessor(
            String blueId,
            Node canonicalTypeNode,
            ContractProcessor<? extends Contract> processor) {
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
        snapshotManager = modern
                ? Objects.requireNonNull(manager, "snapshotStore")
                : manager;
    }

    void matchingService(ContractMatchingService service) {
        matchingService = Objects.requireNonNull(
                service, "matchingService");
        matchingServiceExplicit = true;
    }

    void observer(ProcessingObserver value, boolean modern) {
        observer = modern
                ? Objects.requireNonNull(value, "observer")
                : value != null
                        ? value
                        : NoOpProcessingObserver.INSTANCE;
    }

    void nodeProvider(NodeProvider provider) {
        nodeProvider = Objects.requireNonNull(provider, "provider");
    }

    void cachePolicy(BlueCachePolicy policy) {
        cachePolicy = Objects.requireNonNull(policy, "policy");
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
