package blue.language.processor;

import blue.language.model.Node;
import blue.language.model.TypeBlueId;
import blue.language.processor.model.ChannelContract;
import blue.language.processor.model.Contract;
import blue.language.processor.model.HandlerContract;
import blue.language.processor.model.MarkerContract;
import blue.language.utils.BlueIdCalculator;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Maintains the mapping between contract BlueIds and their processors.
 */
public class ContractProcessorRegistry {

    private final Map<String, ContractProcessor<? extends Contract>> processorsByBlueId = new LinkedHashMap<>();
    private final Map<String, Node> canonicalTypeNodesByBlueId = new LinkedHashMap<>();
    private final Set<String> providerEvidenceRequiredBlueIds =
            new LinkedHashSet<>();
    private final Map<Class<? extends HandlerContract>, HandlerProcessor<? extends HandlerContract>> handlerProcessors = new LinkedHashMap<>();
    private final Map<Class<? extends ChannelContract>, ChannelProcessor<? extends ChannelContract>> channelProcessors = new LinkedHashMap<>();
    private final Map<Class<? extends MarkerContract>, ContractProcessor<? extends MarkerContract>> markerProcessors = new LinkedHashMap<>();
    private final Map<String, HandlerProcessor<? extends HandlerContract>> handlerProcessorsByBlueId = new LinkedHashMap<>();
    private final Map<String, List<String>> handlerExecutableBodyFieldsByBlueId =
            new LinkedHashMap<>();
    private final Map<String, ChannelProcessor<? extends ChannelContract>> channelProcessorsByBlueId = new LinkedHashMap<>();
    private final Map<String, ContractProcessor<? extends MarkerContract>> markerProcessorsByBlueId = new LinkedHashMap<>();
    private final Map<String, ContractProcessor<? extends Contract>> processorsView =
            Collections.unmodifiableMap(
                    new AbstractMap<String, ContractProcessor<? extends Contract>>() {
                        private final Set<Entry<String, ContractProcessor<? extends Contract>>> entries =
                                new AbstractSet<Entry<String, ContractProcessor<? extends Contract>>>() {
                                    @Override
                                    public Iterator<Entry<String, ContractProcessor<? extends Contract>>> iterator() {
                                        synchronized (ContractProcessorRegistry.this) {
                                            return Collections.unmodifiableMap(
                                                    new LinkedHashMap<>(processorsByBlueId))
                                                    .entrySet()
                                                    .iterator();
                                        }
                                    }

                                    @Override
                                    public int size() {
                                        synchronized (ContractProcessorRegistry.this) {
                                            return processorsByBlueId.size();
                                        }
                                    }

                                    @Override
                                    public boolean contains(Object entry) {
                                        synchronized (ContractProcessorRegistry.this) {
                                            return processorsByBlueId.entrySet().contains(entry);
                                        }
                                    }
                                };

                        @Override
                        public ContractProcessor<? extends Contract> get(Object key) {
                            synchronized (ContractProcessorRegistry.this) {
                                return processorsByBlueId.get(key);
                            }
                        }

                        @Override
                        public boolean containsKey(Object key) {
                            synchronized (ContractProcessorRegistry.this) {
                                return processorsByBlueId.containsKey(key);
                            }
                        }

                        @Override
                        public int size() {
                            synchronized (ContractProcessorRegistry.this) {
                                return processorsByBlueId.size();
                            }
                        }

                        @Override
                        public Set<Entry<String, ContractProcessor<? extends Contract>>> entrySet() {
                            return entries;
                        }
                    });
    private final ReentrantReadWriteLock configurationLock = new ReentrantReadWriteLock();
    private long version;

    Lock configurationReadLock() {
        return configurationLock.readLock();
    }

    Lock configurationWriteLock() {
        return configurationLock.writeLock();
    }

    boolean isConfigurationReadHeldByCurrentThread() {
        return configurationLock.getReadHoldCount() > 0;
    }

    public <T extends HandlerContract> void registerHandler(HandlerProcessor<T> processor) {
        mutateConfiguration(() -> registerHandlerInternal(processor));
    }

    public <T extends ChannelContract> void registerChannel(ChannelProcessor<T> processor) {
        mutateConfiguration(() -> registerChannelInternal(processor));
    }

    public <T extends MarkerContract> void registerMarker(ContractProcessor<T> processor) {
        mutateConfiguration(() -> registerMarkerInternal(processor));
    }

    public void register(ContractProcessor<? extends Contract> processor) {
        mutateConfiguration(() -> registerInternal(processor));
    }

    /**
     * Registers a processor mapping for an explicit BlueId without supplying
     * provider content for that BlueId.
     *
     * <p>A standalone processor cannot establish the registered type or the
     * exact selected-scope identity from this registration alone. It must also
     * have a verified provider-backed snapshot manager/Blue runtime or exact
     * canonical registration evidence; otherwise recognition fails explicitly
     * with {@code ProviderUnavailable}.</p>
     */
    public void register(String blueId, ContractProcessor<? extends Contract> processor) {
        mutateConfiguration(() -> {
            Objects.requireNonNull(processor, "processor");
            if (blueId == null || blueId.isEmpty()) {
                throw new IllegalArgumentException("blueId must not be empty");
            }
            registerBlueId(blueId, processor);
            registerClassLookup(processor);
            if (!declaresBlueId(processor.contractType(), blueId)
                    && !canonicalTypeNodesByBlueId.containsKey(blueId)) {
                providerEvidenceRequiredBlueIds.add(blueId);
            }
        });
    }

    /**
     * Registers both the Java processor mapping and the exact canonical Blue
     * type content needed to resolve that mapping outside a configured
     * Language runtime.
     *
     * <p>The legacy {@link #register(String, ContractProcessor)} overload does
     * not imply any type content. In particular, a Java class name is never
     * interpreted as the canonical node for the supplied BlueId.</p>
     */
    public void register(String blueId,
                         Node canonicalTypeNode,
                         ContractProcessor<? extends Contract> processor) {
        mutateConfiguration(() -> {
            Objects.requireNonNull(processor, "processor");
            Node canonical = validatedCanonicalTypeNode(blueId, canonicalTypeNode);
            registerBlueId(blueId, processor);
            registerClassLookup(processor);
            canonicalTypeNodesByBlueId.put(blueId, canonical);
            providerEvidenceRequiredBlueIds.remove(blueId);
        });
    }

    private void registerInternal(ContractProcessor<? extends Contract> processor) {
        Objects.requireNonNull(processor, "processor");
        if (processor instanceof HandlerProcessor) {
            @SuppressWarnings("unchecked")
            HandlerProcessor<? extends HandlerContract> handler = (HandlerProcessor<? extends HandlerContract>) processor;
            registerHandlerInternal(handler);
        } else if (processor instanceof ChannelProcessor) {
            @SuppressWarnings("unchecked")
            ChannelProcessor<? extends ChannelContract> channel = (ChannelProcessor<? extends ChannelContract>) processor;
            registerChannelInternal(channel);
        } else if (processor.contractType() != null && MarkerContract.class.isAssignableFrom(processor.contractType())) {
            @SuppressWarnings("unchecked")
            ContractProcessor<? extends MarkerContract> marker = (ContractProcessor<? extends MarkerContract>) processor;
            registerMarkerInternal(marker);
        } else {
            throw new IllegalArgumentException("Unsupported processor type: " + processor.getClass().getName());
        }
    }

    private <T extends HandlerContract> void registerHandlerInternal(HandlerProcessor<T> processor) {
        Objects.requireNonNull(processor, "processor");
        registerBlueIds(processor.contractType(), processor);
        handlerProcessors.put(processor.contractType(), processor);
    }

    private <T extends ChannelContract> void registerChannelInternal(ChannelProcessor<T> processor) {
        Objects.requireNonNull(processor, "processor");
        registerBlueIds(processor.contractType(), processor);
        channelProcessors.put(processor.contractType(), processor);
    }

    private <T extends MarkerContract> void registerMarkerInternal(ContractProcessor<T> processor) {
        Objects.requireNonNull(processor, "processor");
        registerBlueIds(processor.contractType(), processor);
        markerProcessors.put(processor.contractType(), processor);
    }

    private void mutateConfiguration(Runnable mutation) {
        if (configurationLock.getReadHoldCount() > 0
                && !configurationLock.isWriteLockedByCurrentThread()) {
            throw new IllegalStateException(
                    "Contract processor configuration cannot change during active processing");
        }
        Lock write = configurationWriteLock();
        write.lock();
        try {
            synchronized (this) {
                mutation.run();
            }
        } finally {
            write.unlock();
        }
    }

    public synchronized Optional<HandlerProcessor<? extends HandlerContract>> lookupHandler(Class<? extends HandlerContract> type) {
        return Optional.ofNullable(handlerProcessors.get(type));
    }

    public synchronized Optional<HandlerProcessor<? extends HandlerContract>> lookupHandler(String blueId) {
        return Optional.ofNullable(handlerProcessorsByBlueId.get(blueId));
    }

    /**
     * Returns the immutable ordered executable-body fields captured when the
     * exact Handler runtime type was registered.
     */
    public synchronized List<String> executableBodyFields(String blueId) {
        List<String> fields = handlerExecutableBodyFieldsByBlueId.get(blueId);
        return fields != null ? fields : Collections.emptyList();
    }

    synchronized Map<String, List<String>> executableBodyFieldsByType() {
        Map<String, List<String>> snapshot = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry
                : handlerExecutableBodyFieldsByBlueId.entrySet()) {
            snapshot.put(entry.getKey(), entry.getValue());
        }
        return Collections.unmodifiableMap(snapshot);
    }

    public synchronized Optional<HandlerProcessor<? extends HandlerContract>> lookupHandler(HandlerContract contract) {
        if (contract == null) {
            return Optional.empty();
        }
        Optional<HandlerProcessor<? extends HandlerContract>> byBlueId = lookupHandler(contract.getTypeBlueId());
        return byBlueId.isPresent()
                ? byBlueId
                : lookupHandler(contract.getClass().asSubclass(HandlerContract.class));
    }

    public synchronized Optional<ChannelProcessor<? extends ChannelContract>> lookupChannel(Class<? extends ChannelContract> type) {
        return Optional.ofNullable(channelProcessors.get(type));
    }

    public synchronized Optional<ChannelProcessor<? extends ChannelContract>> lookupChannel(String blueId) {
        return Optional.ofNullable(channelProcessorsByBlueId.get(blueId));
    }

    public synchronized Optional<ChannelProcessor<? extends ChannelContract>> lookupChannel(ChannelContract contract) {
        if (contract == null) {
            return Optional.empty();
        }
        Optional<ChannelProcessor<? extends ChannelContract>> byBlueId = lookupChannel(contract.getTypeBlueId());
        return byBlueId.isPresent()
                ? byBlueId
                : lookupChannel(contract.getClass().asSubclass(ChannelContract.class));
    }

    public synchronized Optional<ContractProcessor<? extends MarkerContract>> lookupMarker(Class<? extends MarkerContract> type) {
        return Optional.ofNullable(markerProcessors.get(type));
    }

    public synchronized Optional<ContractProcessor<? extends MarkerContract>> lookupMarker(String blueId) {
        return Optional.ofNullable(markerProcessorsByBlueId.get(blueId));
    }

    public synchronized Optional<ContractProcessor<? extends MarkerContract>> lookupMarker(MarkerContract contract) {
        if (contract == null) {
            return Optional.empty();
        }
        Optional<ContractProcessor<? extends MarkerContract>> byBlueId = lookupMarker(contract.getTypeBlueId());
        return byBlueId.isPresent()
                ? byBlueId
                : lookupMarker(contract.getClass().asSubclass(MarkerContract.class));
    }

    public synchronized Map<String, ContractProcessor<? extends Contract>> processors() {
        return processorsView;
    }

    synchronized Node canonicalTypeNode(String blueId) {
        Node canonical = canonicalTypeNodesByBlueId.get(blueId);
        return canonical != null ? canonical.clone() : null;
    }

    synchronized boolean requiresProviderEvidence(String blueId) {
        return providerEvidenceRequiredBlueIds.contains(blueId);
    }

    synchronized Map<String, Class<? extends Contract>> registeredContractTypes() {
        Map<String, Class<? extends Contract>> registered = new LinkedHashMap<>();
        for (Map.Entry<String, ContractProcessor<? extends Contract>> entry
                : processorsByBlueId.entrySet()) {
            Class<? extends Contract> contractType = entry.getValue().contractType();
            if (contractType != null) {
                registered.put(entry.getKey(), contractType);
            }
        }
        return Collections.unmodifiableMap(registered);
    }

    synchronized long version() {
        return version;
    }

    private <T extends Contract> void registerBlueIds(Class<T> contractType, ContractProcessor<T> processor) {
        Objects.requireNonNull(contractType, "contractType");

        TypeBlueId typeBlueId = contractType.getAnnotation(TypeBlueId.class);
        if (typeBlueId == null) {
            throw new IllegalArgumentException("Contract type lacks @TypeBlueId: " + contractType.getName());
        }

        String[] declared = typeBlueId.value();
        if (declared.length == 0 && !typeBlueId.defaultValue().isEmpty()) {
            declared = new String[]{typeBlueId.defaultValue()};
        }
        if (declared.length == 0) {
            throw new IllegalArgumentException("Contract type " + contractType.getName() + " does not declare any BlueId values");
        }

        for (String blueId : declared) {
            registerBlueId(blueId, processor);
        }
    }

    private boolean declaresBlueId(
            Class<? extends Contract> contractType,
            String blueId) {
        if (contractType == null) {
            return false;
        }
        TypeBlueId typeBlueId =
                contractType.getAnnotation(TypeBlueId.class);
        if (typeBlueId == null) {
            return false;
        }
        for (String declared : typeBlueId.value()) {
            if (blueId.equals(declared)) {
                return true;
            }
        }
        return typeBlueId.value().length == 0
                && blueId.equals(typeBlueId.defaultValue());
    }

    private Node validatedCanonicalTypeNode(String blueId, Node canonicalTypeNode) {
        if (blueId == null || blueId.isEmpty()) {
            throw new IllegalArgumentException("blueId must not be empty");
        }
        Objects.requireNonNull(canonicalTypeNode, "canonicalTypeNode");
        Node canonical = canonicalTypeNode.clone();
        String suppliedRootBlueId = canonical.getBlueId();
        if (canonical.isReferenceOnly()) {
            throw new IllegalArgumentException(
                    "Missing provider content for registered contract BlueId " + blueId);
        }
        if (suppliedRootBlueId != null) {
            if (!blueId.equals(suppliedRootBlueId)) {
                throw providerBlueIdMismatch(blueId, suppliedRootBlueId);
            }
            canonical.blueId(null);
        }
        String calculatedBlueId = BlueIdCalculator.calculateBlueId(canonical);
        if (!blueId.equals(calculatedBlueId)) {
            throw providerBlueIdMismatch(blueId, calculatedBlueId);
        }
        return canonical;
    }

    private IllegalArgumentException providerBlueIdMismatch(String requestedBlueId,
                                                            String actualBlueId) {
        return new IllegalArgumentException("Provider returned content with BlueId " + actualBlueId
                + " for requested BlueId " + requestedBlueId + ".");
    }

    private void registerBlueId(String blueId, ContractProcessor<? extends Contract> processor) {
        if (blueId == null || blueId.isEmpty()) {
            throw new IllegalArgumentException("blueId must not be empty");
        }
        ProcessorKind kind = requireSupportedProcessor(processor);
        List<String> executableBodyFields =
                kind == ProcessorKind.HANDLER
                        ? validatedExecutableBodyFields(
                        (HandlerProcessor<?>) processor)
                        : Collections.emptyList();
        ContractProcessor<? extends Contract> existing = processorsByBlueId.get(blueId);
        if (existing != null
                && !Objects.equals(existing.contractType(), processor.contractType())) {
            throw new IllegalStateException("Duplicate BlueId value: " + blueId);
        }
        processorsByBlueId.put(blueId, processor);
        version++;
        if (kind == ProcessorKind.HANDLER) {
            @SuppressWarnings("unchecked")
            HandlerProcessor<? extends HandlerContract> handler = (HandlerProcessor<? extends HandlerContract>) processor;
            handlerProcessorsByBlueId.put(blueId, handler);
            handlerExecutableBodyFieldsByBlueId.put(
                    blueId, executableBodyFields);
        } else if (kind == ProcessorKind.CHANNEL) {
            @SuppressWarnings("unchecked")
            ChannelProcessor<? extends ChannelContract> channel = (ChannelProcessor<? extends ChannelContract>) processor;
            channelProcessorsByBlueId.put(blueId, channel);
        } else {
            @SuppressWarnings("unchecked")
            ContractProcessor<? extends MarkerContract> marker = (ContractProcessor<? extends MarkerContract>) processor;
            markerProcessorsByBlueId.put(blueId, marker);
        }
    }

    private List<String> validatedExecutableBodyFields(
            HandlerProcessor<?> processor) {
        List<String> declared = processor.executableBodyFields();
        if (declared == null) {
            throw new IllegalArgumentException(
                    "Handler executableBodyFields must not be null: "
                            + processor.getClass().getName());
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String field : declared) {
            if (field == null || field.isEmpty()) {
                throw new IllegalArgumentException(
                        "Handler executable-body field names must not be empty: "
                                + processor.getClass().getName());
            }
            if (!unique.add(field)) {
                throw new IllegalArgumentException(
                        "Duplicate Handler executable-body field '" + field
                                + "': " + processor.getClass().getName());
            }
        }
        return Collections.unmodifiableList(
                new ArrayList<>(unique));
    }

    private ProcessorKind requireSupportedProcessor(
            ContractProcessor<? extends Contract> processor) {
        Objects.requireNonNull(processor, "processor");
        Class<? extends Contract> contractType = processor.contractType();
        if (processor instanceof HandlerProcessor) {
            if (contractType != null
                    && HandlerContract.class.isAssignableFrom(contractType)) {
                return ProcessorKind.HANDLER;
            }
            throw unsupportedProcessor(processor);
        }
        if (processor instanceof ChannelProcessor) {
            if (contractType != null
                    && ChannelContract.class.isAssignableFrom(contractType)) {
                return ProcessorKind.CHANNEL;
            }
            throw unsupportedProcessor(processor);
        }
        if (contractType != null && MarkerContract.class.isAssignableFrom(contractType)) {
            return ProcessorKind.MARKER;
        }
        throw unsupportedProcessor(processor);
    }

    private IllegalArgumentException unsupportedProcessor(
            ContractProcessor<? extends Contract> processor) {
        return new IllegalArgumentException(
                "Unsupported processor type: " + processor.getClass().getName());
    }

    private enum ProcessorKind {
        HANDLER,
        CHANNEL,
        MARKER
    }

    private void registerClassLookup(ContractProcessor<? extends Contract> processor) {
        Class<? extends Contract> type = processor.contractType();
        if (type == null) {
            return;
        }
        if (processor instanceof HandlerProcessor && HandlerContract.class.isAssignableFrom(type)) {
            @SuppressWarnings("unchecked")
            Class<? extends HandlerContract> handlerType = (Class<? extends HandlerContract>) type;
            @SuppressWarnings("unchecked")
            HandlerProcessor<? extends HandlerContract> handler = (HandlerProcessor<? extends HandlerContract>) processor;
            handlerProcessors.put(handlerType, handler);
        } else if (processor instanceof ChannelProcessor && ChannelContract.class.isAssignableFrom(type)) {
            @SuppressWarnings("unchecked")
            Class<? extends ChannelContract> channelType = (Class<? extends ChannelContract>) type;
            @SuppressWarnings("unchecked")
            ChannelProcessor<? extends ChannelContract> channel = (ChannelProcessor<? extends ChannelContract>) processor;
            channelProcessors.put(channelType, channel);
        } else if (MarkerContract.class.isAssignableFrom(type)) {
            @SuppressWarnings("unchecked")
            Class<? extends MarkerContract> markerType = (Class<? extends MarkerContract>) type;
            @SuppressWarnings("unchecked")
            ContractProcessor<? extends MarkerContract> marker = (ContractProcessor<? extends MarkerContract>) processor;
            markerProcessors.put(markerType, marker);
        }
    }
}
