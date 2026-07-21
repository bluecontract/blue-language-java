package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.model.Contract;

import java.util.Objects;

/**
 * Builder utility concentrated around contract processor registration.
 */
public final class ContractProcessorRegistryBuilder {

    private final ContractProcessorRegistry registry;

    private ContractProcessorRegistryBuilder(ContractProcessorRegistry registry) {
        this.registry = registry;
    }

    public static ContractProcessorRegistryBuilder create() {
        return new ContractProcessorRegistryBuilder(new ContractProcessorRegistry());
    }

    public ContractProcessorRegistryBuilder registerDefaults() {
        return this;
    }

    public ContractProcessorRegistryBuilder register(ContractProcessor<? extends Contract> processor) {
        Objects.requireNonNull(processor, "processor");
        registry.register(processor);
        return this;
    }

    /**
     * Registers only the Java processor mapping. It does not invent or retain
     * provider content for {@code blueId}; standalone initialization therefore
     * requires a verified provider-backed runtime or exact evidence.
     */
    public ContractProcessorRegistryBuilder register(String blueId, ContractProcessor<? extends Contract> processor) {
        registry.register(blueId, processor);
        return this;
    }

    /**
     * Registers exact, verified canonical provider evidence together with the
     * Java processor mapping. A {@link DocumentProcessor} constructed from the
     * resulting registry imports the registered BlueId-to-contract-class
     * mappings into its resolver.
     */
    public ContractProcessorRegistryBuilder register(
            String blueId,
            Node canonicalTypeNode,
            ContractProcessor<? extends Contract> processor) {
        registry.register(blueId, canonicalTypeNode, processor);
        return this;
    }

    public ContractProcessorRegistry build() {
        return registry;
    }
}
