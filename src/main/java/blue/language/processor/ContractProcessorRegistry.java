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

    public Optional<HandlerProcessor<? extends HandlerContract>> lookupHandler(Class<? extends HandlerContract> type) {
        return Optional.ofNullable(handlerProcessors.get(type));
    }

    public Optional<ChannelProcessor<? extends ChannelContract>> lookupChannel(Class<? extends ChannelContract> type) {
        return Optional.ofNullable(channelProcessors.get(type));
    }

    public Optional<ContractProcessor<? extends MarkerContract>> lookupMarker(Class<? extends MarkerContract> type) {
        return Optional.ofNullable(markerProcessors.get(type));
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
            processorsByBlueId.put(blueId, processor);
        }
    }
}
