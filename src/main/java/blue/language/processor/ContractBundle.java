package blue.language.processor;

import blue.language.processor.model.ChannelContract;
import blue.language.processor.model.HandlerContract;
import blue.language.processor.model.MarkerContract;
import blue.language.processor.model.ProcessEmbedded;
import blue.language.processor.model.ChannelEventCheckpoint;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.snapshot.FrozenNode;

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
    private final Map<String, FrozenNode> channelNodes;
    private final Map<String, List<HandlerBinding>> handlersByChannel;
    private final Map<String, MarkerContract> markers;
    private final Map<String, FrozenNode> contractNodes;
    private final List<String> embeddedPaths;
    private boolean checkpointDeclared;

    private final Map<String, ChannelContract> channelsView;
    private final Map<String, MarkerContract> markersView;
    private final Map<String, FrozenNode> contractNodesView;
    private final List<String> embeddedPathsView;

    private ContractBundle(Map<String, ChannelContract> channels,
                           Map<String, FrozenNode> channelNodes,
                           Map<String, List<HandlerBinding>> handlersByChannel,
                           Map<String, MarkerContract> markers,
                           Map<String, FrozenNode> contractNodes,
                           List<String> embeddedPaths,
                           boolean checkpointDeclared) {
        this.channels = channels;
        this.channelNodes = channelNodes;
        this.handlersByChannel = handlersByChannel;
        this.markers = markers;
        this.contractNodes = contractNodes;
        this.embeddedPaths = embeddedPaths;
        this.checkpointDeclared = checkpointDeclared;

        this.channelsView = Collections.unmodifiableMap(this.channels);
        this.markersView = Collections.unmodifiableMap(this.markers);
        this.contractNodesView = Collections.unmodifiableMap(this.contractNodes);
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

    public Map<String, ChannelContract> channels() {
        return channelsView;
    }

    public ChannelContract channel(String key) {
        return channels.get(key);
    }

    public ChannelBinding channelBinding(String key) {
        ChannelContract contract = channels.get(key);
        return contract != null ? new ChannelBinding(key, contract, channelNodes.get(key)) : null;
    }

    public MarkerContract marker(String key) {
        return markers.get(key);
    }

    public FrozenNode contractNode(String key) {
        return contractNodes.get(key);
    }

    public Map<String, FrozenNode> contractNodes() {
        return contractNodesView;
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
                result.add(new ChannelBinding(entry.getKey(), contract, channelNodes.get(entry.getKey())));
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
        private final FrozenNode node;

        ChannelBinding(String key, ChannelContract contract, FrozenNode node) {
            this.key = key;
            this.contract = contract;
            this.node = node;
        }

        public String key() {
            return key;
        }

        public ChannelContract contract() {
            return contract;
        }

        public FrozenNode node() {
            return node;
        }

        public int order() {
            Integer order = contract.getOrder();
            return order != null ? order : 0;
        }
    }

    public static final class HandlerBinding {
        private final String key;
        private final HandlerContract contract;
        private final FrozenNode node;

        HandlerBinding(String key, HandlerContract contract, FrozenNode node) {
            this.key = key;
            this.contract = contract;
            this.node = node;
        }

        public String key() {
            return key;
        }

        public HandlerContract contract() {
            return contract;
        }

        public FrozenNode node() {
            return node;
        }

        public int order() {
            Integer order = contract.getOrder();
            return order != null ? order : 0;
        }
    }

    public static final class Builder {
        private final Map<String, ChannelContract> channels = new LinkedHashMap<>();
        private final Map<String, FrozenNode> channelNodes = new LinkedHashMap<>();
        private final Map<String, List<HandlerBinding>> handlersByChannel = new LinkedHashMap<>();
        private final Map<String, MarkerContract> markers = new LinkedHashMap<>();
        private final Map<String, FrozenNode> contractNodes = new LinkedHashMap<>();
        private final List<String> embeddedPaths = new ArrayList<>();
        private boolean embeddedDeclared;
        private boolean checkpointDeclared;

        private Builder() {
        }

        public Builder addChannel(String key, ChannelContract contract) {
            return addChannel(key, contract, null);
        }

        public Builder addChannel(String key, ChannelContract contract, FrozenNode node) {
            channels.put(key, contract);
            if (node != null) {
                channelNodes.put(key, node);
                contractNodes.put(key, node);
            }
            return this;
        }

        public Builder addHandler(String key, HandlerContract contract) {
            return addHandler(key, contract, null);
        }

        public Builder addHandler(String key, HandlerContract contract, FrozenNode node) {
            handlersByChannel
                    .computeIfAbsent(contract.getChannelKey(), k -> new ArrayList<>())
                    .add(new HandlerBinding(key, contract, node));
            if (node != null) {
                contractNodes.put(key, node);
            }
            return this;
        }

        public Builder setEmbedded(ProcessEmbedded embedded) {
            return setEmbedded(embedded, null);
        }

        public Builder setEmbedded(ProcessEmbedded embedded, FrozenNode node) {
            if (embeddedDeclared) {
                throw new IllegalStateException("Multiple Process Embedded markers detected in same contracts map");
            }
            embeddedDeclared = true;
            if (node != null && embedded.getKey() != null) {
                contractNodes.put(embedded.getKey(), node);
            }
            if (embedded.getPaths() != null) {
                embeddedPaths.clear();
                embeddedPaths.addAll(embedded.getPaths());
            }
            return this;
        }

        public Builder addMarker(String key, MarkerContract contract) {
            return addMarker(key, contract, null);
        }

        public Builder addMarker(String key, MarkerContract contract, FrozenNode node) {
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
            if (node != null) {
                contractNodes.put(key, node);
            }
            return this;
        }

        public ContractBundle build() {
            return new ContractBundle(channels,
                    channelNodes,
                    handlersByChannel,
                    markers,
                    contractNodes,
                    embeddedPaths,
                    checkpointDeclared);
        }
    }
}
