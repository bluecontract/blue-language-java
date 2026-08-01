package blue.language.processor;

import blue.language.api.BlueCachePolicy;
import blue.language.provider.NodeProvider;
import blue.language.conformance.ConformanceEngine;
import blue.language.utils.TypeClassResolver;

/**
 * Immutable construction snapshot consumed by one {@link DocumentProcessor}
 * generation.
 *
 * <p>The builder owns mutation. This value only transports the already
 * validated collaborators and the legacy-versus-modern ownership decision to
 * the processor constructor.</p>
 */
final class DocumentProcessorConfiguration {

    final ContractProcessorRegistry contractRegistry;
    final TypeClassResolver contractTypeResolver;
    final ConformanceEngine conformanceEngine;
    final ConformancePlannerOverride conformancePlannerOverride;
    final ProcessingSnapshotManager snapshotManager;
    final ContractMatchingService matchingService;
    final ProcessingObserver observer;
    final NodeProvider nodeProvider;
    final BlueCachePolicy cachePolicy;
    final GasSchedule gasSchedule;
    final Long gasLimit;
    final String runtimeRegistryIdentity;
    final ExternalDeliveryPlanDeriver externalDeliveryPlanDeriver;
    final ExternalDeliveryEvidenceVerifier deliveryEvidenceVerifier;
    final SubscriptionSurfaceValidator subscriptionSurfaceValidator;
    final boolean immutableConfiguration;

    DocumentProcessorConfiguration(
            ContractProcessorRegistry contractRegistry,
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
        this.contractRegistry = contractRegistry;
        this.contractTypeResolver = contractTypeResolver;
        this.conformanceEngine = conformanceEngine;
        this.conformancePlannerOverride = conformancePlannerOverride;
        this.snapshotManager = snapshotManager;
        this.matchingService = matchingService;
        this.observer = observer;
        this.nodeProvider = nodeProvider;
        this.cachePolicy = cachePolicy;
        this.gasSchedule = gasSchedule;
        this.gasLimit = gasLimit;
        this.runtimeRegistryIdentity = runtimeRegistryIdentity;
        this.externalDeliveryPlanDeriver = externalDeliveryPlanDeriver;
        this.deliveryEvidenceVerifier = deliveryEvidenceVerifier;
        this.subscriptionSurfaceValidator = subscriptionSurfaceValidator;
        this.immutableConfiguration = immutableConfiguration;
    }
}
