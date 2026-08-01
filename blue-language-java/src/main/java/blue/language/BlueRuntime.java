package blue.language;

import blue.language.api.BlueCachePolicy;
import blue.language.mapping.BlueMapper;
import blue.language.processor.BlueContracts;
import blue.language.processor.ContractProcessorRegistry;
import blue.language.processor.ContractProcessorRegistryBuilder;
import blue.language.processor.ExternalDeliveryEvidenceVerifier;
import blue.language.processor.ExternalDeliveryPlanDeriver;
import blue.language.processor.GasSchedule;
import blue.language.processor.ProcessingObserver;
import blue.language.processor.SubscriptionSurfaceValidator;
import blue.language.processor.registry.BlueRuntimeTypeRegistry;
import blue.language.processor.registry.RuntimeTypeAliases;
import blue.language.provider.NodeProvider;
import blue.language.provider.SequentialNodeProvider;
import blue.language.runtime.BlueLanguage;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable aggregate composition root for Language and generic Contracts.
 *
 * <p>The builder freezes one Contracts registry generation, composes its exact
 * type content with the verified built-in registry and caller provider, then
 * gives Language and Contracts the same provider and cache environment.
 * Runtime services are thread-safe. Close releases Contracts first and
 * Language second; mapping is immutable and owns no closeable state.</p>
 */
public final class BlueRuntime implements AutoCloseable {

    private static final NodeProvider EMPTY_PROVIDER = blueId -> null;

    private final BlueLanguage language;
    private final BlueContracts contracts;
    private final BlueMapper mapping;

    private volatile boolean closed;
    private volatile Throwable closeFailure;

    private BlueRuntime(Builder builder) {
        ContractProcessorRegistry registryGeneration =
                builder.contractRuntimeRegistry.snapshot();
        NodeProvider provider = new SequentialNodeProvider(
                BlueRuntimeTypeRegistry.getDefault()
                        .asProcessorSnapshotProvider(),
                registryGeneration.exactTypeProvider(),
                builder.nodeProvider);

        BlueLanguage builtLanguage = null;
        BlueContracts builtContracts = null;
        try {
            builtLanguage = BlueLanguage.builder()
                    .nodeProvider(provider)
                    .cachePolicy(builder.cachePolicy)
                    .preprocessingAliases(
                            builder.preprocessingAliases)
                    .environmentImports(
                            RuntimeTypeAliases.NAME_TO_BLUE_ID)
                    .build();
            BlueContracts.Builder contractsBuilder =
                    BlueContracts.builder(
                            builtLanguage.processing())
                            .runtimeRegistry(registryGeneration)
                            .gasSchedule(builder.gasSchedule)
                            .observer(builder.observer);
            if (builder.gasLimit != null) {
                contractsBuilder.gasLimit(builder.gasLimit);
            }
            if (builder.deliveryPlanDeriver != null) {
                contractsBuilder.deliveryPlanDeriver(
                        builder.deliveryPlanDeriver);
            }
            if (builder.evidenceVerifier != null) {
                contractsBuilder.evidenceVerifier(
                        builder.evidenceVerifier);
            }
            if (builder.subscriptionSurfaceValidator != null) {
                contractsBuilder.subscriptionSurfaceValidator(
                        builder.subscriptionSurfaceValidator);
            }
            builtContracts = contractsBuilder.build();
        } catch (Throwable failure) {
            Throwable retained = closeResource(
                    builtContracts, failure);
            closeResource(builtLanguage, retained);
            throw failure;
        }
        this.language = builtLanguage;
        this.contracts = builtContracts;
        this.mapping = builder.mapping;
    }

    /**
     * Starts an independent aggregate runtime builder.
     *
     * @return new single-owner builder with bounded default services
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the focused Language services owned by this runtime.
     *
     * @return thread-safe Language service
     * @throws IllegalStateException if terminal shutdown has begun
     */
    public BlueLanguage language() {
        ensureOpen();
        return language;
    }

    /**
     * Returns the focused generic Contracts service owned by this runtime.
     *
     * @return thread-safe generic Contracts service
     * @throws IllegalStateException if terminal shutdown has begun
     */
    public BlueContracts contracts() {
        ensureOpen();
        return contracts;
    }

    /**
     * Returns the immutable Java mapping service owned by this runtime.
     *
     * @return immutable Java mapping service
     * @throws IllegalStateException if terminal shutdown has begun
     */
    public BlueMapper mapping() {
        ensureOpen();
        return mapping;
    }

    /**
     * Returns whether terminal shutdown has begun.
     *
     * @return {@code true} once this runtime starts terminal shutdown
     */
    public boolean isClosed() {
        return closed;
    }

    /**
     * Releases Contracts-owned state before Language-owned caches.
     *
     * <p>Closing is idempotent after a successful shutdown. If shutdown fails,
     * the retained failure is rethrown by later close calls.</p>
     *
     * @throws RuntimeException if either owned service fails during shutdown
     * @throws Error if either owned service reports a terminal JVM failure
     */
    @Override
    public synchronized void close() {
        if (closed) {
            rethrow(closeFailure);
            return;
        }
        closed = true;
        Throwable failure = null;
        failure = closeResource(contracts, failure);
        failure = closeResource(language, failure);
        closeFailure = failure;
        rethrow(failure);
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException(
                    "Blue runtime is closed");
        }
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
            if (failure == null) {
                return closeFailure;
            }
            if (failure != closeFailure) {
                failure.addSuppressed(closeFailure);
            }
        }
        return failure;
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
                "Blue runtime close failed", failure);
    }

    /** Mutable single-owner builder for one immutable aggregate runtime. */
    public static final class Builder {
        private NodeProvider nodeProvider = EMPTY_PROVIDER;
        private BlueCachePolicy cachePolicy =
                BlueCachePolicy.boundedDefaults();
        private ContractProcessorRegistry contractRuntimeRegistry =
                ContractProcessorRegistryBuilder.create()
                        .registerDefaults()
                        .build();
        private GasSchedule gasSchedule = GasSchedule.contracts10();
        private Long gasLimit;
        private ExternalDeliveryPlanDeriver deliveryPlanDeriver;
        private ExternalDeliveryEvidenceVerifier evidenceVerifier;
        private SubscriptionSurfaceValidator subscriptionSurfaceValidator;
        private ProcessingObserver observer = observation -> {
        };
        private Map<String, String> preprocessingAliases =
                Collections.emptyMap();
        private BlueMapper mapping = BlueMapper.builder().build();

        private Builder() {
        }

        /**
         * Selects the borrowed application content provider.
         *
         * <p>The resulting runtime verifies exact content at its Language
         * provider boundary and does not close the borrowed provider.</p>
         *
         * @param nodeProvider application provider of exact Blue content
         * @return this builder
         * @throws NullPointerException if {@code nodeProvider} is {@code null}
         */
        public Builder nodeProvider(NodeProvider nodeProvider) {
            this.nodeProvider = Objects.requireNonNull(
                    nodeProvider, "nodeProvider");
            return this;
        }

        /**
         * Selects bounds shared by Language and Contracts matching caches.
         *
         * @param cachePolicy immutable cache bounds and weighting policy
         * @return this builder
         * @throws NullPointerException if {@code cachePolicy} is {@code null}
         */
        public Builder cachePolicy(BlueCachePolicy cachePolicy) {
            this.cachePolicy = Objects.requireNonNull(
                    cachePolicy, "cachePolicy");
            return this;
        }

        /**
         * Selects the Contracts registry to freeze once at build time.
         *
         * @param registry registry whose current generation is snapshotted
         * @return this builder
         * @throws NullPointerException if {@code registry} is {@code null}
         */
        public Builder contractRuntimeRegistry(
                ContractProcessorRegistry registry) {
            this.contractRuntimeRegistry = Objects.requireNonNull(
                    registry, "contractRuntimeRegistry");
            return this;
        }

        /**
         * Selects the immutable Contracts gas schedule.
         *
         * @param gasSchedule deterministic schedule applied by the processor
         * @return this builder
         * @throws NullPointerException if {@code gasSchedule} is {@code null}
         */
        public Builder gasSchedule(GasSchedule gasSchedule) {
            this.gasSchedule = Objects.requireNonNull(
                    gasSchedule, "gasSchedule");
            return this;
        }

        /**
         * Selects a process gas budget within the configured schedule maximum.
         *
         * <p>The budget is validated against the final selected schedule when
         * {@link #build()} creates the Contracts service.</p>
         *
         * @param gasLimit maximum gas admitted for one process invocation
         * @return this builder
         */
        public Builder gasLimit(long gasLimit) {
            this.gasLimit = gasLimit;
            return this;
        }

        /**
         * Selects deterministic external-delivery plan derivation.
         *
         * @param deliveryPlanDeriver host delivery-plan derivation boundary
         * @return this builder
         * @throws NullPointerException if {@code deliveryPlanDeriver} is
         *                              {@code null}
         */
        public Builder deliveryPlanDeriver(
                ExternalDeliveryPlanDeriver deliveryPlanDeriver) {
            this.deliveryPlanDeriver = Objects.requireNonNull(
                    deliveryPlanDeriver, "deliveryPlanDeriver");
            return this;
        }

        /**
         * Selects exact execution-evidence verification.
         *
         * @param evidenceVerifier verifier for host-supplied execution evidence
         * @return this builder
         * @throws NullPointerException if {@code evidenceVerifier} is
         *                              {@code null}
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
         * @throws NullPointerException if {@code validator} is {@code null}
         */
        public Builder subscriptionSurfaceValidator(
                SubscriptionSurfaceValidator validator) {
            this.subscriptionSurfaceValidator = Objects.requireNonNull(
                    validator, "subscriptionSurfaceValidator");
            return this;
        }

        /**
         * Selects an operational observer outside semantic execution.
         *
         * @param observer observer receiving non-semantic processing events
         * @return this builder
         * @throws NullPointerException if {@code observer} is {@code null}
         */
        public Builder observer(ProcessingObserver observer) {
            this.observer = Objects.requireNonNull(
                    observer, "observer");
            return this;
        }

        /**
         * Freezes explicit aliases used only by root {@code blue} values.
         *
         * <p>The supplied map is defensively copied when this method returns.</p>
         *
         * @param preprocessingAliases aliases mapped to exact BlueIds
         * @return this builder
         * @throws NullPointerException if {@code preprocessingAliases} is
         *                              {@code null}
         */
        public Builder preprocessingAliases(
                Map<String, String> preprocessingAliases) {
            this.preprocessingAliases = Collections.unmodifiableMap(
                    new LinkedHashMap<>(Objects.requireNonNull(
                            preprocessingAliases,
                            "preprocessingAliases")));
            return this;
        }

        /**
         * Selects the immutable Java mapping service.
         *
         * @param mapping immutable mapper shared by runtime callers
         * @return this builder
         * @throws NullPointerException if {@code mapping} is {@code null}
         */
        public Builder mapping(BlueMapper mapping) {
            this.mapping = Objects.requireNonNull(
                    mapping, "mapping");
            return this;
        }

        /**
         * Builds one independent runtime with no process-global mutation.
         *
         * <p>The selected registry is snapshotted, and each built runtime owns
         * independent Language and Contracts lifecycle state.</p>
         *
         * @return new independently owned aggregate runtime
         * @throws IllegalArgumentException if the selected gas budget or
         *                                  preprocessing aliases are invalid
         * @throws IllegalStateException if a valid component generation cannot
         *                               be constructed
         */
        public BlueRuntime build() {
            return new BlueRuntime(this);
        }
    }
}
