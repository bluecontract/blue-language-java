package blue.language.processor;

import blue.language.api.BlueCachePolicy;
import blue.language.conformance.ConformanceEngine;
import blue.language.mapping.TypeClassResolver;
import blue.language.model.Node;
import blue.language.processor.model.Contract;
import blue.language.provider.NodeProvider;

/**
 * Package-owned implementation for the public processor builder.
 *
 * <p>{@link DocumentProcessor.Builder} redeclares the supported fluent API so
 * its binary surface remains explicit. This support class owns only mutable
 * construction state and keeps configuration mechanics out of the processing
 * facade.</p>
 *
 */
final class DocumentProcessorBuilderSupport {

    private final DocumentProcessorBuilderState configuration;

    /** Creates support with the default Contracts configuration. */
    DocumentProcessorBuilderSupport() {
        configuration = new DocumentProcessorBuilderState();
    }

    /** Creates support from a detached processor configuration snapshot. */
    DocumentProcessorBuilderSupport(DocumentProcessor processor) {
        configuration = new DocumentProcessorBuilderState(processor);
    }

    /** Selects the registry to snapshot at build time. */
    <B> B runtimeRegistry(ContractProcessorRegistry registry, B builder) {
        configuration.registry(registry, true);
        return builder;
    }

    /** Selects the type resolver to snapshot at build time. */
    <B> B contractTypeResolver(TypeClassResolver resolver, B builder) {
        configuration.contractTypeResolver(resolver);
        return builder;
    }

    /** Scans one package into the builder-owned resolver. */
    <B> B scanContractTypes(String packageName, B builder) {
        configuration.scanContractTypes(packageName);
        return builder;
    }

    /** Registers one explicit contract Java type. */
    <B> B registerContractType(
            String blueId,
            Class<? extends Contract> contractType,
            B builder) {
        configuration.registerContractType(blueId, contractType);
        return builder;
    }

    /** Registers one annotated contract processor. */
    <B> B registerContractProcessor(
            ContractProcessor<? extends Contract> processor,
            B builder) {
        configuration.registerContractProcessor(processor);
        return builder;
    }

    /** Registers one processor under an explicit BlueId. */
    <B> B registerContractProcessor(
            String blueId,
            ContractProcessor<? extends Contract> processor,
            B builder) {
        configuration.registerContractProcessor(blueId, processor);
        return builder;
    }

    /** Registers one processor with its exact canonical type content. */
    <B> B registerContractProcessor(
            String blueId,
            Node canonicalTypeNode,
            ContractProcessor<? extends Contract> processor,
            B builder) {
        configuration.registerContractProcessor(
                blueId, canonicalTypeNode, processor);
        return builder;
    }

    /** Selects optional conformance evaluation. */
    <B> B conformanceEngine(ConformanceEngine engine, B builder) {
        configuration.conformanceEngine(engine);
        return builder;
    }

    /** Selects an optional conformance planner override. */
    <B> B conformancePlannerOverride(
            ConformancePlannerOverride override,
            B builder) {
        configuration.conformancePlannerOverride(override);
        return builder;
    }

    /** Selects the verified processing snapshot store. */
    <B> B snapshotStore(ProcessingSnapshotManager store, B builder) {
        configuration.snapshotManager(store, true);
        return builder;
    }

    /** Selects the contract matching service. */
    <B> B matchingService(ContractMatchingService service, B builder) {
        configuration.matchingService(service);
        return builder;
    }

    /** Selects the deterministic Contracts gas schedule. */
    <B> B gasSchedule(GasSchedule schedule, B builder) {
        configuration.gasSchedule(schedule, true);
        return builder;
    }

    /** Selects the invocation gas limit. */
    <B> B gasLimit(long limit, B builder) {
        configuration.gasLimit(limit, true);
        return builder;
    }

    /** Selects the registry identity bound into execution evidence. */
    <B> B runtimeRegistryIdentity(String identity, B builder) {
        configuration.runtimeRegistryIdentity(identity);
        return builder;
    }

    /** Selects the external-delivery evidence verifier. */
    <B> B evidenceVerifier(
            ExternalDeliveryEvidenceVerifier verifier,
            B builder) {
        configuration.deliveryEvidenceVerifier(verifier, true);
        return builder;
    }

    /** Selects the deterministic external-delivery plan derivation service. */
    <B> B deliveryPlanDeriver(
            ExternalDeliveryPlanDeriver deriver,
            B builder) {
        configuration.deliveryPlanDeriver(deriver, true);
        return builder;
    }

    /** Selects the post-change subscription-surface validator. */
    <B> B subscriptionSurfaceValidator(
            SubscriptionSurfaceValidator validator,
            B builder) {
        configuration.subscriptionSurfaceValidator(validator, true);
        return builder;
    }

    /** Selects the verified exact-node provider. */
    <B> B nodeProvider(NodeProvider provider, B builder) {
        configuration.nodeProvider(provider);
        return builder;
    }

    /** Selects the operational processing observer. */
    <B> B observer(ProcessingObserver observer, B builder) {
        configuration.observer(observer, true);
        return builder;
    }

    /** Selects bounded processor cache policy. */
    <B> B cachePolicy(BlueCachePolicy policy, B builder) {
        configuration.cachePolicy(policy);
        return builder;
    }

    /** Freezes the current builder state for one processor generation. */
    DocumentProcessorConfiguration configurationSnapshot() {
        return configuration.snapshot();
    }
}
