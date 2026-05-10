package blue.language.processor;

import blue.language.conformance.ConformanceEngine;
import blue.language.mapping.NodeToObjectConverter;
import blue.language.model.Node;
import blue.language.processor.model.Contract;
import blue.language.processor.model.MarkerContract;
import blue.language.utils.TypeClassResolver;
import java.util.Map;
import java.util.Objects;

/**
 * Facade over the processor engine; retains public API for Document processing.
 */
public class DocumentProcessor {

    private static final TypeClassResolver CONTRACT_TYPE_RESOLVER =
            new TypeClassResolver("blue.language.processor.model");

    private final ContractProcessorRegistry contractRegistry;
    private final NodeToObjectConverter contractConverter;
    private final ContractLoader contractLoader;
    private final ConformanceEngine conformanceEngine;
    private final ProcessingSnapshotManager snapshotManager;

    public DocumentProcessor() {
        this(ContractProcessorRegistryBuilder.create().registerDefaults().build());
    }

    public DocumentProcessor(ContractProcessorRegistry registry) {
        this.contractRegistry = Objects.requireNonNull(registry, "registry");
        this.contractConverter = new NodeToObjectConverter(CONTRACT_TYPE_RESOLVER);
        this.contractLoader = new ContractLoader(contractRegistry, contractConverter);
        this.conformanceEngine = null;
        this.snapshotManager = null;
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
        this.contractRegistry = Objects.requireNonNull(registry, "registry");
        this.contractConverter = new NodeToObjectConverter(CONTRACT_TYPE_RESOLVER);
        this.contractLoader = new ContractLoader(contractRegistry, contractConverter);
        this.conformanceEngine = conformanceEngine;
        this.snapshotManager = snapshotManager;
    }

    private DocumentProcessor(Builder builder) {
        this(builder.contractRegistry, builder.conformanceEngine, builder.snapshotManager);
    }

    public DocumentProcessingResult initializeDocument(Node document) {
        return ProcessorEngine.initializeDocument(this, document);
    }

    public DocumentProcessingResult processDocument(Node document, Node event) {
        return ProcessorEngine.processDocument(this, document, event);
    }

    public boolean isInitialized(Node document) {
        return ProcessorEngine.isInitialized(this, document);
    }

    public DocumentProcessor registerContractProcessor(ContractProcessor<? extends Contract> processor) {
        contractRegistry.register(processor);
        return this;
    }

    public ContractProcessorRegistry getContractRegistry() {
        return contractRegistry;
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
        ContractBundle bundle = contractLoader.load(scopeNode, scopePath);
        return bundle.markers();
    }

    public static Builder builder() {
        return new Builder();
    }

    static final class Builder {
        private ContractProcessorRegistry contractRegistry = ContractProcessorRegistryBuilder.create().registerDefaults().build();
        private ConformanceEngine conformanceEngine;
        private ProcessingSnapshotManager snapshotManager;

        public Builder withRegistry(ContractProcessorRegistry registry) {
            this.contractRegistry = Objects.requireNonNull(registry, "registry");
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
