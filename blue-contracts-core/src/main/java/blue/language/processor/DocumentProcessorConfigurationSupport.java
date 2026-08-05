package blue.language.processor;

import blue.language.model.Node;
import blue.language.model.TypeBlueId;
import blue.language.processor.model.Contract;
import blue.language.mapping.TypeClassResolver;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.locks.Lock;

/** Shared type-registry mechanics for processor construction and registration. */
final class DocumentProcessorConfigurationSupport {

    private static final String DEFAULT_CONTRACT_MODEL_PACKAGE =
            "blue.language.processor.model";
    private static final Map<String, Class<?>> DEFAULT_CONTRACT_TYPES =
            discoverDefaultContractTypes();

    private DocumentProcessorConfigurationSupport() {
    }

    /** Returns a fresh resolver populated with the closed default model set. */
    static TypeClassResolver defaultContractTypeResolver() {
        TypeClassResolver resolver = new TypeClassResolver();
        for (Map.Entry<String, Class<?>> entry
                : DEFAULT_CONTRACT_TYPES.entrySet()) {
            resolver.register(entry.getKey(), entry.getValue());
        }
        return resolver;
    }

    /** Returns a detached resolver snapshot for an immutable generation. */
    static TypeClassResolver copyContractTypeResolver(
            TypeClassResolver source) {
        TypeClassResolver copy = new TypeClassResolver();
        for (Map.Entry<String, Class<?>> entry
                : source.getBlueIdMap().entrySet()) {
            copy.register(entry.getKey(), entry.getValue());
        }
        return copy;
    }

    /** Adds every exact registry type to the paired conversion resolver. */
    static void registerRegistryContractTypes(
            ContractProcessorRegistry registry,
            TypeClassResolver resolver) {
        synchronized (resolver) {
            for (Map.Entry<String, Class<? extends Contract>> entry
                    : registry.registeredContractTypes().entrySet()) {
                resolver.register(entry.getKey(), entry.getValue());
            }
        }
    }

    /** Registers an annotated contract class when it declares a BlueId. */
    static void registerAnnotatedContractType(
            TypeClassResolver resolver,
            Class<? extends Contract> contractType) {
        if (contractType != null
                && contractType.isAnnotationPresent(TypeBlueId.class)) {
            resolver.registerAnnotatedClass(contractType);
        }
    }

    /** Atomically publishes exact canonical type content and its Java mapping. */
    static void registerExactContractProcessor(
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
                requireCompatibleTypeRegistration(
                        resolver, blueId, contractType);
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
            throw new IllegalArgumentException(
                    "blueId must not be empty");
        }
        if (contractType == null) {
            throw new IllegalArgumentException(
                    "clazz must not be null");
        }
        Class<?> existing = resolver.resolveClass(blueId);
        if (existing != null && !existing.equals(contractType)) {
            throw new IllegalStateException(
                    "Duplicate BlueId value: " + blueId);
        }
    }

    private static Map<String, Class<?>> discoverDefaultContractTypes() {
        TypeClassResolver discovered =
                new TypeClassResolver(
                        DEFAULT_CONTRACT_MODEL_PACKAGE);
        return Collections.unmodifiableMap(
                new TreeMap<>(discovered.getBlueIdMap()));
    }
}
