package blue.language.processor;

import blue.language.model.TypeBlueId;
import blue.language.processor.model.ChannelContract;
import blue.language.processor.model.Contract;
import blue.language.processor.model.HandlerContract;
import blue.language.processor.model.MarkerContract;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Maintains the mapping between contract BlueIds and their processors.
 */
public class ContractProcessorRegistry {

    private final Map<String, ContractProcessor<? extends Contract>> processorsByBlueId = new LinkedHashMap<>();
    private final Map<Class<? extends HandlerContract>, HandlerProcessor<? extends HandlerContract>> handlerProcessors = new LinkedHashMap<>();
    private final Map<Class<? extends ChannelContract>, ChannelProcessor<? extends ChannelContract>> channelProcessors = new LinkedHashMap<>();
    private final Map<Class<? extends MarkerContract>, ContractProcessor<? extends MarkerContract>> markerProcessors = new LinkedHashMap<>();
    private final Map<String, HandlerProcessor<? extends HandlerContract>> handlerProcessorsByBlueId = new LinkedHashMap<>();
    private final Map<String, ChannelProcessor<? extends ChannelContract>> channelProcessorsByBlueId = new LinkedHashMap<>();
    private final Map<String, ContractProcessor<? extends MarkerContract>> markerProcessorsByBlueId = new LinkedHashMap<>();

    public <T extends HandlerContract> void registerHandler(HandlerProcessor<T> processor) {
        Objects.requireNonNull(processor, "processor");
        registerBlueIds(processor.contractType(), processor);
        handlerProcessors.put(processor.contractType(), processor);
    }

    public <T extends ChannelContract> void registerChannel(ChannelProcessor<T> processor) {
        Objects.requireNonNull(processor, "processor");
        registerBlueIds(processor.contractType(), processor);
        channelProcessors.put(processor.contractType(), processor);
    }

    public <T extends MarkerContract> void registerMarker(ContractProcessor<T> processor) {
        Objects.requireNonNull(processor, "processor");
        registerBlueIds(processor.contractType(), processor);
        markerProcessors.put(processor.contractType(), processor);
    }

    public void register(ContractProcessor<? extends Contract> processor) {
        Objects.requireNonNull(processor, "processor");
        if (processor instanceof HandlerProcessor) {
            @SuppressWarnings("unchecked")
            HandlerProcessor<? extends HandlerContract> handler = (HandlerProcessor<? extends HandlerContract>) processor;
            registerHandler(handler);
        } else if (processor instanceof ChannelProcessor) {
            @SuppressWarnings("unchecked")
            ChannelProcessor<? extends ChannelContract> channel = (ChannelProcessor<? extends ChannelContract>) processor;
            registerChannel(channel);
        } else if (processor.contractType() != null && MarkerContract.class.isAssignableFrom(processor.contractType())) {
            @SuppressWarnings("unchecked")
            ContractProcessor<? extends MarkerContract> marker = (ContractProcessor<? extends MarkerContract>) processor;
            registerMarker(marker);
        } else {
            throw new IllegalArgumentException("Unsupported processor type: " + processor.getClass().getName());
        }
    }

    public void register(String blueId, ContractProcessor<? extends Contract> processor) {
        Objects.requireNonNull(processor, "processor");
        if (blueId == null || blueId.isEmpty()) {
            throw new IllegalArgumentException("blueId must not be empty");
        }
        registerBlueId(blueId, processor);
        registerClassLookup(processor);
    }

    public Optional<HandlerProcessor<? extends HandlerContract>> lookupHandler(Class<? extends HandlerContract> type) {
        return Optional.ofNullable(handlerProcessors.get(type));
    }

    public Optional<HandlerProcessor<? extends HandlerContract>> lookupHandler(String blueId) {
        return Optional.ofNullable(handlerProcessorsByBlueId.get(blueId));
    }

    public Optional<HandlerProcessor<? extends HandlerContract>> lookupHandler(HandlerContract contract) {
        if (contract == null) {
            return Optional.empty();
        }
        Optional<HandlerProcessor<? extends HandlerContract>> byBlueId = lookupHandler(contract.getTypeBlueId());
        return byBlueId.isPresent()
                ? byBlueId
                : lookupHandler(contract.getClass().asSubclass(HandlerContract.class));
    }

    public Optional<ChannelProcessor<? extends ChannelContract>> lookupChannel(Class<? extends ChannelContract> type) {
        return Optional.ofNullable(channelProcessors.get(type));
    }

    public Optional<ChannelProcessor<? extends ChannelContract>> lookupChannel(String blueId) {
        return Optional.ofNullable(channelProcessorsByBlueId.get(blueId));
    }

    public Optional<ChannelProcessor<? extends ChannelContract>> lookupChannel(ChannelContract contract) {
        if (contract == null) {
            return Optional.empty();
        }
        Optional<ChannelProcessor<? extends ChannelContract>> byBlueId = lookupChannel(contract.getTypeBlueId());
        return byBlueId.isPresent()
                ? byBlueId
                : lookupChannel(contract.getClass().asSubclass(ChannelContract.class));
    }

    public Optional<ContractProcessor<? extends MarkerContract>> lookupMarker(Class<? extends MarkerContract> type) {
        return Optional.ofNullable(markerProcessors.get(type));
    }

    public Optional<ContractProcessor<? extends MarkerContract>> lookupMarker(String blueId) {
        return Optional.ofNullable(markerProcessorsByBlueId.get(blueId));
    }

    public Optional<ContractProcessor<? extends MarkerContract>> lookupMarker(MarkerContract contract) {
        if (contract == null) {
            return Optional.empty();
        }
        Optional<ContractProcessor<? extends MarkerContract>> byBlueId = lookupMarker(contract.getTypeBlueId());
        return byBlueId.isPresent()
                ? byBlueId
                : lookupMarker(contract.getClass().asSubclass(MarkerContract.class));
    }

    public Map<String, ContractProcessor<? extends Contract>> processors() {
        return Collections.unmodifiableMap(processorsByBlueId);
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

    private void registerBlueId(String blueId, ContractProcessor<? extends Contract> processor) {
        processorsByBlueId.put(blueId, processor);
        if (processor instanceof HandlerProcessor) {
            @SuppressWarnings("unchecked")
            HandlerProcessor<? extends HandlerContract> handler = (HandlerProcessor<? extends HandlerContract>) processor;
            handlerProcessorsByBlueId.put(blueId, handler);
        } else if (processor instanceof ChannelProcessor) {
            @SuppressWarnings("unchecked")
            ChannelProcessor<? extends ChannelContract> channel = (ChannelProcessor<? extends ChannelContract>) processor;
            channelProcessorsByBlueId.put(blueId, channel);
        } else if (processor.contractType() != null && MarkerContract.class.isAssignableFrom(processor.contractType())) {
            @SuppressWarnings("unchecked")
            ContractProcessor<? extends MarkerContract> marker = (ContractProcessor<? extends MarkerContract>) processor;
            markerProcessorsByBlueId.put(blueId, marker);
        } else {
            throw new IllegalArgumentException("Unsupported processor type: " + processor.getClass().getName());
        }
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
