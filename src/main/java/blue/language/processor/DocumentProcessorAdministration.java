package blue.language.processor;

import blue.language.LanguageRuntimeAccess;
import blue.language.model.Node;
import blue.language.processor.model.Contract;
import blue.language.processor.model.MarkerContract;
import blue.language.snapshot.FrozenNode;

import java.util.Map;
import java.util.Objects;

/**
 * Implements internal configuration support, cache lifecycle, and read-only
 * processor inspection behind the public facade.
 */
final class DocumentProcessorAdministration {

    private static final String FRAGMENTATION_MANAGER_REQUIRED =
            "Effective fragmentation catalog requires a verified ProcessingSnapshotManager";

    private final DocumentProcessor processor;
    private final DocumentProcessorLifecycle lifecycle;

    DocumentProcessorAdministration(
            DocumentProcessor processor,
            DocumentProcessorLifecycle lifecycle) {
        this.processor = processor;
        this.lifecycle = lifecycle;
    }

    /** Registers an annotated processor under one atomic revision. */
    DocumentProcessor registerContractProcessor(
            ContractProcessor<? extends Contract> contractProcessor) {
        try (DocumentProcessorLifecycle.WriteScope ignored =
                     lifecycle.openConfigurationWrite(
                             processor.registry(),
                             processor.hasImmutableConfiguration())) {
            Objects.requireNonNull(contractProcessor, "processor");
            processor.registry().register(contractProcessor);
            DocumentProcessorConfigurationSupport
                    .registerAnnotatedContractType(
                            processor.contractTypeResolverInternal(),
                            contractProcessor.contractType());
            processor.clearOwnedCaches();
            return processor;
        }
    }

    /** Registers an explicit type identity under one atomic revision. */
    DocumentProcessor registerContractProcessor(
            String blueId,
            ContractProcessor<? extends Contract> contractProcessor) {
        try (DocumentProcessorLifecycle.WriteScope ignored =
                     lifecycle.openConfigurationWrite(
                             processor.registry(),
                             processor.hasImmutableConfiguration())) {
            Objects.requireNonNull(contractProcessor, "processor");
            processor.registry().register(blueId, contractProcessor);
            processor.contractTypeResolverInternal().register(
                    blueId, contractProcessor.contractType());
            processor.clearOwnedCaches();
            return processor;
        }
    }

    /** Registers exact canonical type content under one atomic revision. */
    DocumentProcessor registerContractProcessor(
            String blueId,
            Node canonicalTypeNode,
            ContractProcessor<? extends Contract> contractProcessor) {
        try (DocumentProcessorLifecycle.WriteScope ignored =
                     lifecycle.openConfigurationWrite(
                             processor.registry(),
                             processor.hasImmutableConfiguration())) {
            Objects.requireNonNull(contractProcessor, "processor");
            DocumentProcessorConfigurationSupport
                    .registerExactContractProcessor(
                            processor.registry(),
                            processor.contractTypeResolverInternal(),
                            blueId,
                            canonicalTypeNode,
                            contractProcessor);
            processor.clearOwnedCaches();
            return processor;
        }
    }

    /** Replaces the delivery-plan deriver for package-private test generations. */
    DocumentProcessor externalDeliveryPlanDeriver(
            ExternalDeliveryPlanDeriver deriver) {
        try (DocumentProcessorLifecycle.WriteScope ignored =
                     lifecycle.openMutation(
                             processor.registry(),
                             processor.hasImmutableConfiguration())) {
            processor.replaceExternalDeliveryPlanDeriver(
                    Objects.requireNonNull(deriver, "deriver"));
            return processor;
        }
    }

    /** Clears all reloadable processor-owned acceleration caches. */
    void clearCaches() {
        lifecycle.clearCaches();
    }

    /** Returns a saturated count of reloadable cache entries. */
    int cacheEntryCount() {
        int loaderEntries = processor.contractLoader().cacheSize();
        ContractMatchingService matchingService =
                processor.matchingService();
        int matchingEntries = matchingService != null
                ? matchingService.cacheEntryCount()
                : 0;
        return Integer.MAX_VALUE - loaderEntries < matchingEntries
                ? Integer.MAX_VALUE
                : loaderEntries + matchingEntries;
    }

    /** Returns a saturated approximation of reloadable cache weight. */
    long cacheWeightBytes() {
        long loaderWeight =
                processor.contractLoader().cacheWeightBytes();
        ContractMatchingService matchingService =
                processor.matchingService();
        long matchingWeight = matchingService != null
                ? matchingService.cacheWeightBytes()
                : 0L;
        return Long.MAX_VALUE - loaderWeight < matchingWeight
                ? Long.MAX_VALUE
                : loaderWeight + matchingWeight;
    }

    /** Resolves the snapshot manager used for exact scope identity. */
    ProcessingSnapshotManager scopeIdentitySnapshotManager() {
        ProcessingSnapshotManager configured =
                processor.snapshotManager();
        if (configured != null) {
            return configured;
        }
        ContractMatchingService matchingService =
                processor.matchingService();
        LanguageRuntimeAccess languageRuntime = matchingService != null
                ? matchingService.blue()
                : null;
        if (languageRuntime == null) {
            return new RegisteredContractScopeIdentitySnapshotManager(
                    processor.registry());
        }
        return new RegisteredContractScopeIdentitySnapshotManager(
                processor.registry(), languageRuntime);
    }

    /** Loads an immutable marker view for one exact resolved scope. */
    Map<String, MarkerContract> markersFor(
            Node scopeNode,
            String scopePath) {
        try (DocumentProcessorLifecycle.ReadScope ignored =
                     lifecycle.openRead(processor.registry())) {
            ContractBundle bundle = processor.contractLoader().load(
                    FrozenNode.fromResolvedNode(scopeNode), scopePath);
            return bundle.markers();
        }
    }

    /** Builds the effective fragmentation catalog without semantic execution. */
    EffectiveFragmentationCatalog effectiveFragmentationCatalog(
            Node document) {
        Objects.requireNonNull(document, "document");
        try (DocumentProcessorLifecycle.ReadScope ignored =
                     lifecycle.openRead(processor.registry())) {
            ProcessingSnapshotManager manager =
                    processor.scopeIdentitySnapshotManager();
            if (manager == null) {
                throw new IllegalStateException(
                        FRAGMENTATION_MANAGER_REQUIRED);
            }
            return new EffectiveFragmentationCatalogBuilder(
                    processor.contractLoader(),
                    processor.registry(),
                    processor.contractTypeResolverInternal(),
                    manager,
                    processor.gasSchedule())
                    .build(document);
        }
    }

    boolean isClosed() {
        return lifecycle.isClosed();
    }

    /** Begins terminal shutdown. */
    void close() {
        lifecycle.close();
    }
}
