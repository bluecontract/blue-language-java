package blue.language.processor;

import blue.language.Blue;
import blue.language.conformance.ConformanceEngine;
import blue.language.mapping.NodeToObjectConverter;
import blue.language.model.Node;
import blue.language.model.TypeBlueId;
import blue.language.processor.model.Contract;
import blue.language.processor.model.MarkerContract;
import blue.language.snapshot.FrozenNode;
import blue.language.snapshot.ResolvedSnapshot;
import blue.language.utils.TypeClassResolver;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Facade over the processor engine; retains public API for Document processing.
 */
public class DocumentProcessor implements AutoCloseable {

    private final ContractProcessorRegistry contractRegistry;
    private final TypeClassResolver contractTypeResolver;
    private final NodeToObjectConverter contractConverter;
    private final ContractLoader contractLoader;
    private ConformanceEngine conformanceEngine;
    private ConformancePlannerOverride conformancePlannerOverride;
    private ProcessingSnapshotManager snapshotManager;
    private ContractMatchingService matchingService;
    private volatile ProcessingMetricsSink metricsSink;
    private final ReentrantReadWriteLock lifecycleLock = new ReentrantReadWriteLock();
    private final Lock lifecycleRead = lifecycleLock.readLock();
    private final Lock lifecycleWrite = lifecycleLock.writeLock();
    private volatile boolean closed;
    private volatile boolean cachesCleared;
    private volatile boolean clearRequested;

    public DocumentProcessor() {
        this(ContractProcessorRegistryBuilder.create().registerDefaults().build());
    }

    public DocumentProcessor(ContractProcessorRegistry registry) {
        this(registry, defaultContractTypeResolver(), null, null);
    }

    public DocumentProcessor(ConformanceEngine conformanceEngine) {
        this(ContractProcessorRegistryBuilder.create().registerDefaults().build(), conformanceEngine, null);
    }

    public DocumentProcessor(ConformanceEngine conformanceEngine, ProcessingSnapshotManager snapshotManager) {
        this(ContractProcessorRegistryBuilder.create().registerDefaults().build(), conformanceEngine, snapshotManager);
    }

    public DocumentProcessor(ContractProcessorRegistry registry, ConformanceEngine conformanceEngine) {
        this(registry, conformanceEngine, null);
    }

    public DocumentProcessor(ContractProcessorRegistry registry,
                             ConformanceEngine conformanceEngine,
                             ProcessingSnapshotManager snapshotManager) {
        this(registry, defaultContractTypeResolver(), conformanceEngine, snapshotManager);
    }

    public DocumentProcessor(ContractProcessorRegistry registry,
                             TypeClassResolver contractTypeResolver,
                             ConformanceEngine conformanceEngine,
                             ProcessingSnapshotManager snapshotManager) {
        this(registry, contractTypeResolver, conformanceEngine, snapshotManager, new ContractMatchingService());
    }

    public DocumentProcessor(ContractProcessorRegistry registry,
                             TypeClassResolver contractTypeResolver,
                             ConformanceEngine conformanceEngine,
                             ProcessingSnapshotManager snapshotManager,
                             ContractMatchingService matchingService) {
        this(registry, contractTypeResolver, conformanceEngine, snapshotManager, matchingService, null);
    }

    public DocumentProcessor(ContractProcessorRegistry registry,
                             TypeClassResolver contractTypeResolver,
                             ConformanceEngine conformanceEngine,
                             ProcessingSnapshotManager snapshotManager,
                             ContractMatchingService matchingService,
                             ProcessingMetricsSink metricsSink) {
        this(registry,
                contractTypeResolver,
                conformanceEngine,
                null,
                snapshotManager,
                matchingService,
                metricsSink);
    }

    public DocumentProcessor(ContractProcessorRegistry registry,
                             TypeClassResolver contractTypeResolver,
                             ConformanceEngine conformanceEngine,
                             ConformancePlannerOverride conformancePlannerOverride,
                             ProcessingSnapshotManager snapshotManager,
                             ContractMatchingService matchingService,
                             ProcessingMetricsSink metricsSink) {
        this.contractRegistry = Objects.requireNonNull(registry, "registry");
        this.contractTypeResolver = Objects.requireNonNull(contractTypeResolver, "contractTypeResolver");
        registerRegistryContractTypes(this.contractRegistry, this.contractTypeResolver);
        this.contractConverter = new NodeToObjectConverter(this.contractTypeResolver);
        this.matchingService = Objects.requireNonNull(matchingService, "matchingService");
        this.contractLoader = new ContractLoader(
                contractRegistry,
                contractConverter,
                this.contractTypeResolver,
                this.matchingService.cachePolicy());
        this.conformanceEngine = conformanceEngine;
        this.conformancePlannerOverride = conformancePlannerOverride;
        this.snapshotManager = snapshotManager;
        this.metricsSink = metricsSink != null ? metricsSink : ProcessingMetricsSink.NOOP;
    }

    private DocumentProcessor(Builder builder) {
        this(builder.contractRegistry,
                builder.contractTypeResolver,
                builder.conformanceEngine,
                builder.conformancePlannerOverride,
                builder.snapshotManager,
                builder.matchingService,
                builder.metricsSink);
    }

    public DocumentProcessingResult initializeDocument(Node document) {
        Lock configurationRead = contractRegistry.configurationReadLock();
        configurationRead.lock();
        lifecycleRead.lock();
        try {
            ensureOpen();
            return ProcessorEngine.initializeDocument(this, document);
        } finally {
            releaseLifecycleReadAndConfiguration(configurationRead);
        }
    }

    /**
     * Initializes the snapshot's resolved root as the selected Processing Document.
     * The canonical root remains the immutable identity companion.
     *
     * @param snapshot verified canonical and resolved document views
     * @return the initialization result and its authoritative snapshot
     */
    public DocumentProcessingResult initializeDocument(ResolvedSnapshot snapshot) {
        Lock configurationRead = contractRegistry.configurationReadLock();
        configurationRead.lock();
        lifecycleRead.lock();
        try {
            ensureOpen();
            requireSnapshotManager();
            return ProcessorEngine.initializeDocument(this, snapshot);
        } finally {
            releaseLifecycleReadAndConfiguration(configurationRead);
        }
    }

    public DocumentProcessingResult processDocument(Node document, Node event) {
        Lock configurationRead = contractRegistry.configurationReadLock();
        configurationRead.lock();
        lifecycleRead.lock();
        try {
            ensureOpen();
            return ProcessorEngine.processDocument(this, document, event);
        } finally {
            releaseLifecycleReadAndConfiguration(configurationRead);
        }
    }

    /**
     * Processes the snapshot's resolved root as the selected Processing Document.
     * The canonical root remains the immutable identity companion.
     *
     * @param snapshot verified canonical and resolved document views
     * @param event read-only Processing Event
     * @return the processing result and its authoritative snapshot
     */
    public DocumentProcessingResult processDocument(ResolvedSnapshot snapshot, Node event) {
        Lock configurationRead = contractRegistry.configurationReadLock();
        configurationRead.lock();
        lifecycleRead.lock();
        try {
            ensureOpen();
            requireSnapshotManager();
            return ProcessorEngine.processDocument(this, snapshot, event);
        } finally {
            releaseLifecycleReadAndConfiguration(configurationRead);
        }
    }

    public boolean isInitialized(Node document) {
        Lock configurationRead = contractRegistry.configurationReadLock();
        configurationRead.lock();
        lifecycleRead.lock();
        try {
            ensureOpen();
            return ProcessorEngine.isInitialized(this, document);
        } finally {
            releaseLifecycleReadAndConfiguration(configurationRead);
        }
    }

    public boolean isInitialized(ResolvedSnapshot snapshot) {
        Lock configurationRead = contractRegistry.configurationReadLock();
        configurationRead.lock();
        lifecycleRead.lock();
        try {
            ensureOpen();
            return ProcessorEngine.isInitialized(this, snapshot);
        } finally {
            releaseLifecycleReadAndConfiguration(configurationRead);
        }
    }

    public DocumentProcessor registerContractProcessor(ContractProcessor<? extends Contract> processor) {
        rejectWriteUpgrade();
        Lock configurationWrite = contractRegistry.configurationWriteLock();
        configurationWrite.lock();
        lifecycleWrite.lock();
        try {
            ensureOpen();
            Objects.requireNonNull(processor, "processor");
            contractRegistry.register(processor);
            registerAnnotatedContractType(processor.contractType());
            clearCachesInternal();
            return this;
        } finally {
            lifecycleWrite.unlock();
            configurationWrite.unlock();
        }
    }

    /**
     * Registers a processor for an explicit BlueId without supplying provider
     * content for that BlueId.
     *
     * <p>For standalone initialization, configure a verified provider-backed
     * snapshot manager/Blue runtime or use the exact-canonical-content overload.
     * Otherwise a scope that requires the registered type fails before
     * initiation with {@link ProcessorErrorCategory#ProviderUnavailable}.</p>
     */
    public DocumentProcessor registerContractProcessor(String blueId, ContractProcessor<? extends Contract> processor) {
        rejectWriteUpgrade();
        Lock configurationWrite = contractRegistry.configurationWriteLock();
        configurationWrite.lock();
        lifecycleWrite.lock();
        try {
            ensureOpen();
            Objects.requireNonNull(processor, "processor");
            contractRegistry.register(blueId, processor);
            contractTypeResolver.register(blueId, processor.contractType());
            clearCachesInternal();
            return this;
        } finally {
            lifecycleWrite.unlock();
            configurationWrite.unlock();
        }
    }

    /**
     * Registers an external contract processor together with its exact
     * canonical Blue type content. The content is cloned and verified against
     * {@code blueId} before the registry is mutated.
     */
    public DocumentProcessor registerContractProcessor(
            String blueId,
            Node canonicalTypeNode,
            ContractProcessor<? extends Contract> processor) {
        rejectWriteUpgrade();
        Lock configurationWrite = contractRegistry.configurationWriteLock();
        configurationWrite.lock();
        lifecycleWrite.lock();
        try {
            ensureOpen();
            Objects.requireNonNull(processor, "processor");
            registerExactContractProcessor(
                    contractRegistry,
                    contractTypeResolver,
                    blueId,
                    canonicalTypeNode,
                    processor);
            clearCachesInternal();
            return this;
        } finally {
            lifecycleWrite.unlock();
            configurationWrite.unlock();
        }
    }

    public ContractProcessorRegistry getContractRegistry() {
        return contractRegistry;
    }

    public TypeClassResolver getContractTypeResolver() {
        return contractTypeResolver;
    }

    ContractProcessorRegistry registry() {
        return contractRegistry;
    }

    NodeToObjectConverter contractConverter() {
        return contractConverter;
    }

    ContractLoader contractLoader() {
        return contractLoader;
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

    ProcessingSnapshotManager scopeIdentitySnapshotManager() {
        if (snapshotManager != null) {
            return snapshotManager;
        }
        ContractMatchingService currentMatchingService = matchingService;
        Blue languageRuntime = currentMatchingService != null
                ? currentMatchingService.blue()
                : null;
        if (languageRuntime == null) {
            return new RegisteredContractScopeIdentitySnapshotManager(contractRegistry);
        }
        DocumentProcessor languageProcessor = languageRuntime.getDocumentProcessor();
        ProcessingSnapshotManager languageManager = languageProcessor != this
                ? languageProcessor.snapshotManager()
                : null;
        return languageManager != null ? languageManager.transientSequence() : null;
    }

    ContractMatchingService matchingService() {
        return matchingService;
    }

    ProcessingMetricsSink metricsSink() {
        return metricsSink != null ? metricsSink : ProcessingMetricsSink.NOOP;
    }

    public ProcessingMetricsSink processingMetricsSink() {
        return metricsSink();
    }

    public boolean supportsSnapshotProcessing() {
        return snapshotManager != null;
    }

    public DocumentProcessor processingMetricsSink(ProcessingMetricsSink metricsSink) {
        rejectWriteUpgrade();
        lifecycleWrite.lock();
        try {
            ensureOpen();
            this.metricsSink = metricsSink != null ? metricsSink : ProcessingMetricsSink.NOOP;
            return this;
        } finally {
            lifecycleWrite.unlock();
        }
    }

    /** Releases every reloadable contract-plan and matching cache owned by this processor. */
    public void clearCaches() {
        if (lifecycleLock.getReadHoldCount() > 0) {
            clearRequested = true;
            return;
        }
        lifecycleWrite.lock();
        try {
            clearCachesInternal();
            clearRequested = false;
        } finally {
            lifecycleWrite.unlock();
        }
    }

    /** Returns the number of reloadable processor-plan cache entries. */
    public int cacheEntryCount() {
        int loaderEntries = contractLoader.cacheSize();
        ContractMatchingService currentMatchingService = matchingService;
        int matchingEntries = currentMatchingService != null
                ? currentMatchingService.cacheEntryCount() : 0;
        return Integer.MAX_VALUE - loaderEntries < matchingEntries
                ? Integer.MAX_VALUE
                : loaderEntries + matchingEntries;
    }

    /** Returns the approximate retained weight of reloadable processor-plan caches. */
    public long cacheWeightBytes() {
        long loaderWeight = contractLoader.cacheWeightBytes();
        ContractMatchingService currentMatchingService = matchingService;
        long matchingWeight = currentMatchingService != null
                ? currentMatchingService.cacheWeightBytes() : 0L;
        return Long.MAX_VALUE - loaderWeight < matchingWeight
                ? Long.MAX_VALUE
                : loaderWeight + matchingWeight;
    }

    public Map<String, MarkerContract> markersFor(Node scopeNode, String scopePath) {
        Lock configurationRead = contractRegistry.configurationReadLock();
        configurationRead.lock();
        lifecycleRead.lock();
        try {
            ensureOpen();
            ContractBundle bundle = contractLoader.load(
                    FrozenNode.fromResolvedNode(scopeNode), scopePath);
            return bundle.markers();
        } finally {
            releaseLifecycleReadAndConfiguration(configurationRead);
        }
    }

    /** Returns whether this processor has released its reloadable caches. */
    public boolean isClosed() {
        return closed;
    }

    /** Invalidates processor work and releases every reloadable plan/matching cache. */
    @Override
    public void close() {
        closed = true;
        if (lifecycleLock.getReadHoldCount() > 0) {
            clearRequested = true;
            return;
        }
        lifecycleWrite.lock();
        try {
            clearCachesIfNeeded();
        } finally {
            lifecycleWrite.unlock();
        }
    }

    private void clearCachesInternal() {
        contractLoader.clearCaches();
        ContractMatchingService currentMatchingService = matchingService;
        if (currentMatchingService != null) {
            currentMatchingService.clearCaches();
        }
    }

    private void releaseLifecycleRead() {
        lifecycleRead.unlock();
        if ((closed || clearRequested) && lifecycleLock.getReadHoldCount() == 0) {
            lifecycleWrite.lock();
            try {
                clearCachesIfNeeded();
            } finally {
                lifecycleWrite.unlock();
            }
        }
    }

    private void releaseLifecycleReadAndConfiguration(Lock configurationRead) {
        try {
            releaseLifecycleRead();
        } finally {
            configurationRead.unlock();
        }
    }

    private void clearCachesIfNeeded() {
        if (closed) {
            if (!cachesCleared) {
                clearCachesInternal();
                cachesCleared = true;
            }
            detachRuntimeCollaborators();
            clearRequested = false;
        } else if (clearRequested) {
            clearCachesInternal();
            clearRequested = false;
        }
    }

    private void rejectWriteUpgrade() {
        if (lifecycleLock.getReadHoldCount() > 0
                || contractRegistry.isConfigurationReadHeldByCurrentThread()) {
            throw new IllegalStateException(
                    "Document processor configuration cannot change during active processing");
        }
    }

    private void detachRuntimeCollaborators() {
        conformanceEngine = null;
        conformancePlannerOverride = null;
        snapshotManager = null;
        matchingService = null;
        metricsSink = ProcessingMetricsSink.NOOP;
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Document processor is closed");
        }
    }

    private void requireSnapshotManager() {
        if (snapshotManager == null) {
            throw new IllegalStateException("Snapshot-native processing requires a ProcessingSnapshotManager");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    private static TypeClassResolver defaultContractTypeResolver() {
        return new TypeClassResolver("blue.language.processor.model");
    }

    private static void registerRegistryContractTypes(
            ContractProcessorRegistry registry,
            TypeClassResolver resolver) {
        synchronized (resolver) {
            for (Map.Entry<String, Class<? extends Contract>> entry
                    : registry.registeredContractTypes().entrySet()) {
                resolver.register(entry.getKey(), entry.getValue());
            }
        }
    }

    private static void registerExactContractProcessor(
            ContractProcessorRegistry registry,
            TypeClassResolver resolver,
            String blueId,
            Node canonicalTypeNode,
            ContractProcessor<? extends Contract> processor) {
        Objects.requireNonNull(processor, "processor");
        Class<? extends Contract> contractType = processor.contractType();
        Lock configurationWrite = registry.configurationWriteLock();
        configurationWrite.lock();
        try {
            synchronized (resolver) {
                requireCompatibleTypeRegistration(resolver, blueId, contractType);
                // Registry validation (canonical BlueId and processor shape) is
                // mutation-free on failure. With both configuration locks held,
                // the following resolver registration cannot conflict.
                registry.register(blueId, canonicalTypeNode, processor);
                resolver.register(blueId, contractType);
            }
        } finally {
            configurationWrite.unlock();
        }
    }

    private static void requireCompatibleTypeRegistration(
            TypeClassResolver resolver,
            String blueId,
            Class<? extends Contract> contractType) {
        if (blueId == null || blueId.isEmpty()) {
            throw new IllegalArgumentException("blueId must not be empty");
        }
        if (contractType == null) {
            throw new IllegalArgumentException("clazz must not be null");
        }
        Class<?> existing = resolver.resolveClass(blueId);
        if (existing != null && !existing.equals(contractType)) {
            throw new IllegalStateException("Duplicate BlueId value: " + blueId);
        }
    }

    private void registerAnnotatedContractType(Class<? extends Contract> contractType) {
        if (contractType != null && contractType.isAnnotationPresent(TypeBlueId.class)) {
            contractTypeResolver.registerAnnotatedClass(contractType);
        }
    }

    public static final class Builder {
        private ContractProcessorRegistry contractRegistry = ContractProcessorRegistryBuilder.create().registerDefaults().build();
        private TypeClassResolver contractTypeResolver = defaultContractTypeResolver();
        private ConformanceEngine conformanceEngine;
        private ConformancePlannerOverride conformancePlannerOverride;
        private ProcessingSnapshotManager snapshotManager;
        private ContractMatchingService matchingService = new ContractMatchingService();
        private ProcessingMetricsSink metricsSink = ProcessingMetricsSink.NOOP;

        public Builder withRegistry(ContractProcessorRegistry registry) {
            this.contractRegistry = Objects.requireNonNull(registry, "registry");
            return this;
        }

        public Builder withContractTypeResolver(TypeClassResolver resolver) {
            this.contractTypeResolver = Objects.requireNonNull(resolver, "resolver");
            return this;
        }

        public Builder scanContractTypes(String packageName) {
            this.contractTypeResolver.scanPackage(packageName);
            return this;
        }

        public Builder registerContractType(String blueId, Class<? extends Contract> contractType) {
            this.contractTypeResolver.register(blueId, contractType);
            return this;
        }

        public Builder registerContractProcessor(ContractProcessor<? extends Contract> processor) {
            Objects.requireNonNull(processor, "processor");
            this.contractRegistry.register(processor);
            Class<? extends Contract> contractType = processor.contractType();
            if (contractType != null && contractType.isAnnotationPresent(TypeBlueId.class)) {
                this.contractTypeResolver.registerAnnotatedClass(contractType);
            }
            return this;
        }

        /**
         * Registers a processor mapping without supplying provider content.
         * Standalone initialization that needs this type fails with
         * {@link ProcessorErrorCategory#ProviderUnavailable} unless a verified
         * provider-backed manager/Blue runtime is configured.
         */
        public Builder registerContractProcessor(String blueId, ContractProcessor<? extends Contract> processor) {
            Objects.requireNonNull(processor, "processor");
            this.contractRegistry.register(blueId, processor);
            this.contractTypeResolver.register(blueId, processor.contractType());
            return this;
        }

        public Builder registerContractProcessor(
                String blueId,
                Node canonicalTypeNode,
                ContractProcessor<? extends Contract> processor) {
            Objects.requireNonNull(processor, "processor");
            registerExactContractProcessor(
                    this.contractRegistry,
                    this.contractTypeResolver,
                    blueId,
                    canonicalTypeNode,
                    processor);
            return this;
        }

        public Builder withConformanceEngine(ConformanceEngine conformanceEngine) {
            this.conformanceEngine = conformanceEngine;
            return this;
        }

        public Builder withConformancePlannerOverride(ConformancePlannerOverride conformancePlannerOverride) {
            this.conformancePlannerOverride = conformancePlannerOverride;
            return this;
        }

        public Builder withSnapshotManager(ProcessingSnapshotManager snapshotManager) {
            this.snapshotManager = snapshotManager;
            return this;
        }

        public Builder withMatchingService(ContractMatchingService matchingService) {
            this.matchingService = Objects.requireNonNull(matchingService, "matchingService");
            return this;
        }

        public Builder withProcessingMetricsSink(ProcessingMetricsSink metricsSink) {
            this.metricsSink = metricsSink != null ? metricsSink : ProcessingMetricsSink.NOOP;
            return this;
        }

        public DocumentProcessor build() {
            return new DocumentProcessor(this);
        }
    }
}
