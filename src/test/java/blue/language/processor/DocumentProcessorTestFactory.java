package blue.language.processor;

import blue.language.mapping.TypeClassResolver;
import blue.language.processor.registry.RuntimeBlueIds;

/** Creates intentionally mutable processor generations for lock-boundary tests. */
final class DocumentProcessorTestFactory {

    private DocumentProcessorTestFactory() {
    }

    /**
     * Creates one processor that shares the supplied mutable registry and
     * resolver. Production callers use the immutable public builder instead.
     */
    static DocumentProcessor mutableProcessor(
            ContractProcessorRegistry registry,
            TypeClassResolver resolver) {
        return new DocumentProcessor(new DocumentProcessorConfiguration(
                registry,
                resolver,
                null,
                null,
                null,
                null,
                null,
                new ContractMatchingService(),
                NoOpProcessingObserver.INSTANCE,
                null,
                null,
                GasSchedule.contracts10(),
                null,
                RuntimeBlueIds.REGISTRY_PACKAGE_IDENTITY,
                ExternalDeliveryPlanDeriver.unavailable(),
                null,
                null,
                false));
    }
}
