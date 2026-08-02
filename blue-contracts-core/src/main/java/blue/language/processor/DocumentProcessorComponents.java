package blue.language.processor;

import blue.language.api.BlueCachePolicy;
import blue.language.conformance.ConformanceEngine;
import blue.language.mapping.NodeToObjectConverter;
import blue.language.mapping.TypeClassResolver;
import blue.language.provider.NodeProvider;

import java.util.Objects;

/**
 * Normalizes one immutable builder snapshot into processor-owned components.
 *
 * <p>The value performs construction only. It owns no lifecycle and is never
 * retained after {@link DocumentProcessor} has copied its fields.</p>
 */
final class DocumentProcessorComponents {

    final ContractProcessorRegistry registry;
    final TypeClassResolver typeResolver;
    final NodeToObjectConverter converter;
    final ContractLoader loader;
    final NodeProvider nodeProvider;
    final BlueCachePolicy cachePolicy;
    final boolean immutableConfiguration;
    final ConformanceEngine conformanceEngine;
    final ConformancePlannerOverride conformancePlannerOverride;
    final ProcessingSnapshotManager snapshotManager;
    final ContractMatchingService matchingService;
    final ProcessingObserver observer;
    final GasSchedule gasSchedule;
    final long gasLimit;
    final String runtimeRegistryIdentity;
    final ExternalDeliveryPlanDeriver deliveryPlanDeriver;
    final ExternalDeliveryEvidenceVerifier configuredEvidenceVerifier;
    final ExternalDeliveryEvidenceVerifier evidenceVerifier;
    final SubscriptionSurfaceValidator configuredSurfaceValidator;
    final SubscriptionSurfaceValidator surfaceValidator;

    private DocumentProcessorComponents(
            DocumentProcessorConfiguration configuration) {
        registry = Objects.requireNonNull(
                configuration.contractRegistry, "registry");
        typeResolver = Objects.requireNonNull(
                configuration.contractTypeResolver,
                "contractTypeResolver");
        DocumentProcessorConfigurationSupport.registerRegistryContractTypes(
                registry, typeResolver);
        converter = new NodeToObjectConverter(typeResolver);
        matchingService = Objects.requireNonNull(
                configuration.matchingService, "matchingService");
        cachePolicy = configuration.cachePolicy != null
                ? configuration.cachePolicy
                : matchingService.cachePolicy();
        nodeProvider = configuration.nodeProvider != null
                ? configuration.nodeProvider
                : matchingService.blue() != null
                        ? matchingService.blue().getNodeProvider()
                        : null;
        loader = new ContractLoader(
                registry,
                converter,
                typeResolver,
                cachePolicy,
                nodeProvider);
        conformanceEngine = configuration.conformanceEngine;
        conformancePlannerOverride =
                configuration.conformancePlannerOverride;
        snapshotManager = configuration.snapshotManager;
        observer = configuration.observer != null
                ? configuration.observer
                : NoOpProcessingObserver.INSTANCE;
        gasSchedule = Objects.requireNonNull(
                configuration.gasSchedule, "gasSchedule");
        loader.gasSchedule(gasSchedule);
        gasLimit = configuration.gasLimit != null
                ? configuration.gasLimit
                : gasSchedule.maxProcessGas();
        runtimeRegistryIdentity = Objects.requireNonNull(
                configuration.runtimeRegistryIdentity,
                "runtimeRegistryIdentity");
        deliveryPlanDeriver = Objects.requireNonNull(
                configuration.externalDeliveryPlanDeriver,
                "externalDeliveryPlanDeriver");
        configuredEvidenceVerifier =
                configuration.deliveryEvidenceVerifier;
        evidenceVerifier = configuredEvidenceVerifier != null
                ? configuredEvidenceVerifier
                : RootExternalDeliveryEvidenceVerifier.configured(
                        loader,
                        snapshotManager,
                        registry,
                        converter,
                        deliveryPlanDeriver);
        configuredSurfaceValidator =
                configuration.subscriptionSurfaceValidator;
        surfaceValidator = configuredSurfaceValidator != null
                ? configuredSurfaceValidator
                : DirectSubscriptionSurfaceValidator.configured(
                        loader,
                        snapshotManager,
                        registry,
                        converter);
        immutableConfiguration = configuration.immutableConfiguration;
    }

    /** Creates fully normalized components from one builder snapshot. */
    static DocumentProcessorComponents from(
            DocumentProcessorConfiguration configuration) {
        return new DocumentProcessorComponents(
                Objects.requireNonNull(configuration, "configuration"));
    }
}
