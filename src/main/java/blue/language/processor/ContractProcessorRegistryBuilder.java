package blue.language.processor;

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

    public ContractProcessorRegistry build() {
        return registry;
    }
}
