package blue.language.processor;

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

/**
 * Facade over the processor engine; retains public API for Document processing.
 */
public class DocumentProcessor {

    private final ContractProcessorRegistry contractRegistry;
    private final TypeClassResolver contractTypeResolver;
    private final NodeToObjectConverter contractConverter;
    private final ContractLoader contractLoader;
    private final ConformanceEngine conformanceEngine;
    private final ProcessingSnapshotManager snapshotManager;

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
        this.contractRegistry = Objects.requireNonNull(registry, "registry");
        this.contractTypeResolver = Objects.requireNonNull(contractTypeResolver, "contractTypeResolver");
        this.contractConverter = new NodeToObjectConverter(this.contractTypeResolver);
        this.contractLoader = new ContractLoader(contractRegistry, contractConverter, this.contractTypeResolver);
        this.conformanceEngine = conformanceEngine;
        this.snapshotManager = snapshotManager;
    }

    private DocumentProcessor(Builder builder) {
        this(builder.contractRegistry, builder.contractTypeResolver, builder.conformanceEngine, builder.snapshotManager);
    }

    public DocumentProcessingResult initializeDocument(Node document) {
        return ProcessorEngine.initializeDocument(this, document);
    }

    public DocumentProcessingResult initializeDocument(ResolvedSnapshot snapshot) {
        requireSnapshotManager();
        return ProcessorEngine.initializeDocument(this, snapshot);
    }

    public DocumentProcessingResult processDocument(Node document, Node event) {
        return ProcessorEngine.processDocument(this, document, event);
    }

    public DocumentProcessingResult processDocument(ResolvedSnapshot snapshot, Node event) {
        requireSnapshotManager();
        return ProcessorEngine.processDocument(this, snapshot, event);
    }

    public boolean isInitialized(Node document) {
        return ProcessorEngine.isInitialized(this, document);
    }

    public boolean isInitialized(ResolvedSnapshot snapshot) {
        return ProcessorEngine.isInitialized(this, snapshot);
    }

    public DocumentProcessor registerContractProcessor(ContractProcessor<? extends Contract> processor) {
        Objects.requireNonNull(processor, "processor");
        contractRegistry.register(processor);
        registerAnnotatedContractType(processor.contractType());
        return this;
    }

    public DocumentProcessor registerContractProcessor(String blueId, ContractProcessor<? extends Contract> processor) {
        Objects.requireNonNull(processor, "processor");
        contractRegistry.register(blueId, processor);
        contractTypeResolver.register(blueId, processor.contractType());
        return this;
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

    ProcessingSnapshotManager snapshotManager() {
        return snapshotManager;
    }

    public Map<String, MarkerContract> markersFor(Node scopeNode, String scopePath) {
        ContractBundle bundle = contractLoader.load(FrozenNode.fromResolvedNode(scopeNode), scopePath);
        return bundle.markers();
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

    private void registerAnnotatedContractType(Class<? extends Contract> contractType) {
        if (contractType != null && contractType.isAnnotationPresent(TypeBlueId.class)) {
            contractTypeResolver.registerAnnotatedClass(contractType);
        }
    }

    public static final class Builder {
        private ContractProcessorRegistry contractRegistry = ContractProcessorRegistryBuilder.create().registerDefaults().build();
        private TypeClassResolver contractTypeResolver = defaultContractTypeResolver();
        private ConformanceEngine conformanceEngine;
        private ProcessingSnapshotManager snapshotManager;

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

        public Builder registerContractProcessor(String blueId, ContractProcessor<? extends Contract> processor) {
            Objects.requireNonNull(processor, "processor");
            this.contractRegistry.register(blueId, processor);
            this.contractTypeResolver.register(blueId, processor.contractType());
            return this;
        }

        public Builder withConformanceEngine(ConformanceEngine conformanceEngine) {
            this.conformanceEngine = conformanceEngine;
            return this;
        }

        public Builder withSnapshotManager(ProcessingSnapshotManager snapshotManager) {
            this.snapshotManager = snapshotManager;
            return this;
        }

        public DocumentProcessor build() {
            return new DocumentProcessor(this);
        }
    }
}
