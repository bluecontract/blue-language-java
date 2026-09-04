package blue.language.processor;

import blue.language.api.BlueCachePolicy;
import blue.language.conformance.ConformanceEngine;
import blue.language.identity.BlueIds;
import blue.language.mapping.NodeToObjectConverter;
import blue.language.mapping.TypeClassResolver;
import blue.language.model.Node;
import blue.language.provider.NodeProvider;
import blue.language.runtime.LanguageRuntimeAccess;

import java.util.Objects;

/**
 * Invocation-owned collaborator set used by exactly one processor invocation.
 *
 * <p>The ordinary path captures the configured processor generation. The
 * platform path replaces every provider-sensitive collaborator with an
 * invocation-local equivalent while retaining the immutable registry, gas,
 * observer, and conformance-policy configuration. This is a lightweight view,
 * not a new {@link DocumentProcessor} generation.</p>
 */
final class ProcessorInvocationServices implements AutoCloseable {

    private final ContractProcessorRegistry registry;
    private final TypeClassResolver contractTypeResolver;
    private final NodeToObjectConverter contractConverter;
    private final ContractLoader contractLoader;
    private final ConformanceEngine conformanceEngine;
    private final ConformancePlannerOverride conformancePlannerOverride;
    private final ProcessingSnapshotManager snapshotManager;
    private final LanguageRuntimeAccess languageRuntimeAccess;
    private final ContractMatchingService matchingService;
    private final ProcessingObserver observer;
    private final GasSchedule gasSchedule;
    private final long gasLimit;
    private final String runtimeRegistryIdentity;
    private final ExternalDeliveryEvidenceVerifier deliveryEvidenceVerifier;
    private final SubscriptionSurfaceValidator subscriptionSurfaceValidator;
    private final CanonicalContributionIdentityMemo
            contributionIdentityMemo =
            new CanonicalContributionIdentityMemo();
    private final boolean ownsProviderDerivedCaches;
    private final boolean strictPlatformInvocation;

    private ProcessorInvocationServices(
            ContractProcessorRegistry registry,
            TypeClassResolver contractTypeResolver,
            NodeToObjectConverter contractConverter,
            ContractLoader contractLoader,
            ConformanceEngine conformanceEngine,
            ConformancePlannerOverride conformancePlannerOverride,
            ProcessingSnapshotManager snapshotManager,
            LanguageRuntimeAccess languageRuntimeAccess,
            ContractMatchingService matchingService,
            ProcessingObserver observer,
            GasSchedule gasSchedule,
            long gasLimit,
            String runtimeRegistryIdentity,
            ExternalDeliveryEvidenceVerifier deliveryEvidenceVerifier,
            SubscriptionSurfaceValidator subscriptionSurfaceValidator,
            boolean ownsProviderDerivedCaches,
            boolean strictPlatformInvocation) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.contractTypeResolver = Objects.requireNonNull(
                contractTypeResolver, "contractTypeResolver");
        this.contractConverter = Objects.requireNonNull(
                contractConverter, "contractConverter");
        this.contractLoader = Objects.requireNonNull(
                contractLoader, "contractLoader");
        this.conformanceEngine = conformanceEngine;
        this.conformancePlannerOverride = conformancePlannerOverride;
        this.snapshotManager = snapshotManager;
        this.languageRuntimeAccess = languageRuntimeAccess;
        this.matchingService = Objects.requireNonNull(
                matchingService, "matchingService");
        this.observer = observer != null
                ? observer : NoOpProcessingObserver.INSTANCE;
        this.gasSchedule = Objects.requireNonNull(
                gasSchedule, "gasSchedule");
        this.gasLimit = gasLimit;
        this.runtimeRegistryIdentity = Objects.requireNonNull(
                runtimeRegistryIdentity, "runtimeRegistryIdentity");
        this.deliveryEvidenceVerifier = Objects.requireNonNull(
                deliveryEvidenceVerifier, "deliveryEvidenceVerifier");
        this.subscriptionSurfaceValidator = Objects.requireNonNull(
                subscriptionSurfaceValidator,
                "subscriptionSurfaceValidator");
        this.ownsProviderDerivedCaches = ownsProviderDerivedCaches;
        this.strictPlatformInvocation = strictPlatformInvocation;
    }

    /** Captures the ordinary immutable processor generation. */
    static ProcessorInvocationServices configured(
            DocumentProcessor processor) {
        Objects.requireNonNull(processor, "processor");
        return new ProcessorInvocationServices(
                processor.registry(),
                processor.contractTypeResolverInternal(),
                processor.contractConverter(),
                processor.contractLoader(),
                processor.conformanceEngine(),
                processor.conformancePlannerOverride(),
                processor.snapshotManager(),
                processor.languageRuntimeAccess(),
                processor.matchingService(),
                processor.observer(),
                processor.gasSchedule(),
                processor.gasLimit(),
                processor.runtimeRegistryIdentity(),
                processor.deliveryEvidenceVerifier(),
                processor.subscriptionSurfaceValidator(),
                false,
                false);
    }

    /**
     * Creates a provider-isolated platform view over the immutable processor
     * generation. The caller owns the supplied snapshot and conformance
     * lifetimes; this view owns only its Contracts caches.
     */
    static ProcessorInvocationServices platform(
            DocumentProcessor processor,
            ProcessingSnapshotManager snapshotManager,
            LanguageRuntimeAccess languageRuntimeAccess,
            ConformanceEngine conformanceEngine) {
        Objects.requireNonNull(processor, "processor");
        ProcessingSnapshotManager manager = Objects.requireNonNull(
                snapshotManager, "snapshotManager");
        LanguageRuntimeAccess runtime = Objects.requireNonNull(
                languageRuntimeAccess, "languageRuntimeAccess");
        NodeProvider provider = Objects.requireNonNull(
                runtime.getNodeProvider(), "invocation nodeProvider");
        ContractLoader loader = new ContractLoader(
                processor.registry(),
                processor.contractConverter(),
                processor.contractTypeResolverInternal(),
                processor.cachePolicy(),
                provider,
                true,
                manager);
        loader.gasSchedule(processor.gasSchedule());
        ContractMatchingService matching =
                new ContractMatchingService(runtime);
        ExternalDeliveryEvidenceVerifier verifier =
                RootExternalDeliveryEvidenceVerifier.configured(
                        loader,
                        manager,
                        processor.registry(),
                        processor.contractConverter(),
                        runtime,
                        processor.gasSchedule(),
                        processor.gasLimit(),
                        ExternalDeliveryPlanDeriver.unavailable());
        SubscriptionSurfaceValidator surfaceValidator =
                DirectSubscriptionSurfaceValidator.configured(
                        loader,
                        manager,
                        processor.registry(),
                        processor.contractConverter());
        return new ProcessorInvocationServices(
                processor.registry(),
                processor.contractTypeResolverInternal(),
                processor.contractConverter(),
                loader,
                conformanceEngine,
                processor.conformancePlannerOverride(),
                manager,
                runtime,
                matching,
                processor.observer(),
                processor.gasSchedule(),
                processor.gasLimit(),
                processor.runtimeRegistryIdentity(),
                verifier,
                surfaceValidator,
                true,
                true);
    }

    ContractProcessorRegistry registry() {
        return registry;
    }

    TypeClassResolver contractTypeResolver() {
        return contractTypeResolver;
    }

    NodeToObjectConverter contractConverter() {
        return contractConverter;
    }

    ContractLoader contractLoader() {
        return contractLoader;
    }

    /** Reuses successful canonical contribution identities in this call only. */
    CanonicalContributionIdentityMemo contributionIdentityMemo() {
        return contributionIdentityMemo;
    }

    ConformanceEngine conformanceEngine() {
        return conformanceEngine;
    }

    ConformancePlannerOverride conformancePlannerOverride() {
        return conformancePlannerOverride;
    }

    ProcessingSnapshotManager snapshotManager() {
        return snapshotManager;
    }

    LanguageRuntimeAccess languageRuntimeAccess() {
        return languageRuntimeAccess;
    }

    ContractMatchingService matchingService() {
        return matchingService;
    }

    ProcessingObserver observer() {
        return observer;
    }

    GasMeter newGasMeter() {
        return new GasMeter(gasSchedule, gasLimit);
    }

    /** Opens the one semantic gas/admission context for a whole invocation. */
    ProcessingGasContext newGasContext() {
        return new ProcessingGasContext(newGasMeter());
    }

    GasSchedule gasSchedule() {
        return gasSchedule;
    }

    String runtimeRegistryIdentity() {
        return runtimeRegistryIdentity;
    }

    /** Returns the exact released gas-manifest byte identity, or fails closed. */
    String gasManifestIdentity() {
        if (gasSchedule != GasSchedule.contracts10()) {
            throw new IllegalStateException(
                    "Custom gas schedules require an explicitly configured "
                            + "exact manifest-byte identity");
        }
        return "sha256:" + GasSchedule.CONTRACTS_1_0_RESOURCE_SHA256;
    }

    ExternalDeliveryEvidenceVerifier deliveryEvidenceVerifier() {
        return deliveryEvidenceVerifier;
    }

    SubscriptionSurfaceValidator subscriptionSurfaceValidator() {
        return subscriptionSurfaceValidator;
    }

    /** Returns whether this call uses the strict request-local provider domain. */
    boolean strictPlatformInvocation() {
        return strictPlatformInvocation;
    }

    /**
     * Opens independently metered admission sessions for supplied-plan replay
     * while retaining this invocation's exact Language/provider boundary.
     */
    ExternalPreselectionVerifier.RuntimeWorkSessionFactory
    externalPlanVerificationSessions(
            Node exactEvent,
            String exactEventBlueId) {
        return externalPlanVerificationSessions(
                exactEvent,
                exactEventBlueId,
                languageRuntimeAccess,
                snapshotManager,
                gasSchedule,
                gasLimit);
    }

    static ExternalPreselectionVerifier.RuntimeWorkSessionFactory
    externalPlanVerificationSessions(
            Node exactEvent,
            String exactEventBlueId,
            LanguageRuntimeAccess languageRuntimeAccess,
            ProcessingSnapshotManager snapshotManager,
            GasSchedule gasSchedule,
            long gasLimit) {
        final Node event = Objects.requireNonNull(
                exactEvent, "exactEvent").clone();
        final String eventBlueId =
                BlueIds.requireBlueIdOrCyclicMember(
                        exactEventBlueId,
                        "external plan verification event");
        final ProcessingGasContext gasContext =
                new ProcessingGasContext(
                        new GasMeter(
                                Objects.requireNonNull(
                                        gasSchedule,
                                        "gasSchedule"),
                                gasLimit));
        return new ExternalPreselectionVerifier
                .RuntimeWorkSessionFactory() {
            @Override
            public RuntimeWorkSession open() {
                if (snapshotManager == null) {
                    throw new IllegalStateException(
                            "External Channel event evaluation requires "
                                    + "processor-backed snapshot admission");
                }
                RuntimeWorkSession session = gasContext
                        .newAdmissionRuntimeWorkSession(
                                languageRuntimeAccess,
                                snapshotManager);
                session.carryExactInput(event, eventBlueId);
                return session;
            }
        };
    }

    /** Releases all invocation-owned memoized and provider-derived state. */
    @Override
    public void close() {
        contributionIdentityMemo.clear();
        if (!ownsProviderDerivedCaches) {
            return;
        }
        contractLoader.clearCaches();
        matchingService.clearCaches();
    }
}
