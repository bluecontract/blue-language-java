package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.model.Contract;

import java.util.Objects;

/**
 * Fluent owner of a registry while its initial type set is assembled.
 *
 * <p>Each registration delegates to the registry's atomic validation. Calling
 * {@link #build()} returns that live registry; the builder does not create a
 * detached copy.</p>
 */
public final class ContractProcessorRegistryBuilder {

    private final ContractProcessorRegistry registry;

    private ContractProcessorRegistryBuilder(ContractProcessorRegistry registry) {
        this.registry = registry;
    }

    /**
     * Creates a builder around a new empty registry.
     *
     * @return new registry builder
     */
    public static ContractProcessorRegistryBuilder create() {
        return new ContractProcessorRegistryBuilder(new ContractProcessorRegistry());
    }

    /**
     * Registers the normative processor-managed Contracts runtime types.
     *
     * <p>The processor-managed types require no application processor
     * registration, so this compatibility method currently leaves the
     * builder unchanged.</p>
     *
     * @return this builder
     */
    public ContractProcessorRegistryBuilder registerDefaults() {
        return this;
    }

    /**
     * Registers every exact identity declared by the processor's contract
     * model.
     *
     * @param processor processor to register
     * @return this builder
     */
    public ContractProcessorRegistryBuilder register(ContractProcessor<? extends Contract> processor) {
        Objects.requireNonNull(processor, "processor");
        registry.register(processor);
        return this;
    }

    /**
     * Registers only the Java processor mapping. It does not invent or retain
     * provider content for {@code blueId}; standalone initialization therefore
     * requires a verified provider-backed runtime or exact evidence.
     *
     * @param blueId exact runtime type identity
     * @param processor Java processor mapping
     * @return this builder
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
     *
     * @param blueId exact runtime type identity
     * @param canonicalTypeNode exact canonical content for {@code blueId}
     * @param processor Java processor mapping
     * @return this builder
     */
    public ContractProcessorRegistryBuilder register(
            String blueId,
            Node canonicalTypeNode,
            ContractProcessor<? extends Contract> processor) {
        registry.register(blueId, canonicalTypeNode, processor);
        return this;
    }

    /**
     * Returns the live registry assembled by this builder.
     *
     * @return owned live registry
     */
    public ContractProcessorRegistry build() {
        return registry;
    }
}
