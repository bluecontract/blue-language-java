package blue.language.processor;

import blue.language.processor.model.ChannelContract;
import blue.language.processor.model.HandlerContract;
import blue.language.processor.model.MarkerContract;
import blue.language.processor.model.ProcessEmbedded;
import blue.language.processor.model.ChannelEventCheckpoint;
import blue.language.processor.util.ProcessorContractConstants;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Collection of contracts bound to a scope, along with helper accessors.
 */
public final class ContractBundle {

    private final Map<String, ChannelContract> channels;
    private final Map<String, List<HandlerBinding>> handlersByChannel;
    private final Map<String, MarkerContract> markers;
    private final List<String> embeddedPaths;
    private boolean checkpointDeclared;

    private final Map<String, MarkerContract> markersView;
    private final List<String> embeddedPathsView;

    private ContractBundle(Map<String, ChannelContract> channels,
                           Map<String, List<HandlerBinding>> handlersByChannel,
                           Map<String, MarkerContract> markers,
                           List<String> embeddedPaths,
                           boolean checkpointDeclared) {
        this.channels = channels;
        this.handlersByChannel = handlersByChannel;
        this.markers = markers;
        this.embeddedPaths = embeddedPaths;
        this.checkpointDeclared = checkpointDeclared;

        this.markersView = Collections.unmodifiableMap(this.markers);
        this.embeddedPathsView = Collections.unmodifiableList(this.embeddedPaths);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static ContractBundle empty() {
        return builder().build();
    }

    public Map<String, MarkerContract> markers() {
        return markersView;
    }

    public MarkerContract marker(String key) {
        return markers.get(key);
    }

    public Set<Map.Entry<String, MarkerContract>> markerEntries() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(markers.entrySet()));
    }

    public List<String> embeddedPaths() {
        return embeddedPathsView;
    }

    public boolean hasCheckpoint() {
        return checkpointDeclared;
    }

    public void registerCheckpointMarker(ChannelEventCheckpoint checkpoint) {
        if (checkpointDeclared) {
            throw new IllegalStateException("Duplicate Channel Event Checkpoint markers detected in same contracts map");
        }
        markers.put(ProcessorContractConstants.KEY_CHECKPOINT, checkpoint);
        checkpointDeclared = true;
    }

    public List<HandlerBinding> handlersFor(String channelKey) {
        List<HandlerBinding> handlers = handlersByChannel.get(channelKey);
        if (handlers == null || handlers.isEmpty()) {
            return Collections.emptyList();
        }
        List<HandlerBinding> sorted = new ArrayList<>(handlers);
        sorted.sort(Comparator
                .comparingInt(HandlerBinding::order)
                .thenComparing(HandlerBinding::key));
        return sorted;
    }

    public List<ChannelBinding> channelsOfType(Class<? extends ChannelContract> type) {
        List<ChannelBinding> result = new ArrayList<>();
        for (Map.Entry<String, ChannelContract> entry : channels.entrySet()) {
            ChannelContract contract = entry.getValue();
            if (type.isInstance(contract)) {
                result.add(new ChannelBinding(entry.getKey(), contract));
            }
        }
        result.sort(Comparator
                .comparingInt(ChannelBinding::order)
                .thenComparing(ChannelBinding::key));
        return result;
    }

    public static final class ChannelBinding {
        private final String key;
        private final ChannelContract contract;

        ChannelBinding(String key, ChannelContract contract) {
            this.key = key;
            this.contract = contract;
        }

        public String key() {
            return key;
        }

        public ChannelContract contract() {
            return contract;
        }

        public int order() {
            Integer order = contract.getOrder();
            return order != null ? order : 0;
        }
    }

    public static final class HandlerBinding {
        private final String key;
        private final HandlerContract contract;

        HandlerBinding(String key, HandlerContract contract) {
            this.key = key;
            this.contract = contract;
        }

        public String key() {
            return key;
        }

        public HandlerContract contract() {
            return contract;
        }

        public int order() {
            Integer order = contract.getOrder();
            return order != null ? order : 0;
        }
    }

    public static final class Builder {
        private final Map<String, ChannelContract> channels = new LinkedHashMap<>();
        private final Map<String, List<HandlerBinding>> handlersByChannel = new LinkedHashMap<>();
        private final Map<String, MarkerContract> markers = new LinkedHashMap<>();
        private final List<String> embeddedPaths = new ArrayList<>();
        private boolean embeddedDeclared;
        private boolean checkpointDeclared;

        private Builder() {
        }

        public Builder addChannel(String key, ChannelContract contract) {
            channels.put(key, contract);
            return this;
        }

        public Builder addHandler(String key, HandlerContract contract) {
            handlersByChannel
                    .computeIfAbsent(contract.getChannelKey(), k -> new ArrayList<>())
                    .add(new HandlerBinding(key, contract));
            return this;
        }

        public Builder setEmbedded(ProcessEmbedded embedded) {
            if (embeddedDeclared) {
                throw new IllegalStateException("Multiple Process Embedded markers detected in same contracts map");
            }
            embeddedDeclared = true;
            if (embedded.getPaths() != null) {
                embeddedPaths.clear();
                embeddedPaths.addAll(embedded.getPaths());
            }
            return this;
        }

        public Builder addMarker(String key, MarkerContract contract) {
            if (ProcessorContractConstants.KEY_CHECKPOINT.equals(key) && !(contract instanceof ChannelEventCheckpoint)) {
                throw new IllegalStateException(
                        "Reserved key 'checkpoint' must contain a Channel Event Checkpoint");
            }
            if (contract instanceof ChannelEventCheckpoint) {
                if (!ProcessorContractConstants.KEY_CHECKPOINT.equals(key)) {
                    throw new IllegalStateException(
                            "Channel Event Checkpoint must use reserved key 'checkpoint' at key '" + key + "'");
                }
                if (checkpointDeclared) {
                    throw new IllegalStateException("Duplicate Channel Event Checkpoint markers detected in same contracts map");
                }
                checkpointDeclared = true;
            }
            markers.put(key, contract);
            return this;
        }

        public ContractBundle build() {
            return new ContractBundle(channels, handlersByChannel, markers, embeddedPaths, checkpointDeclared);
        }
    }
}
