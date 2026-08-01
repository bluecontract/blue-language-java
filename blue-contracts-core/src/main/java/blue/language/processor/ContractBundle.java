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
 * Immutable dispatch view of the effective contracts bound to one scope.
 *
 * <p>Bindings retain exact frozen contract nodes separately from converted
 * Java contract objects. Runtime-marker copies are invocation-local so
 * checkpoint and termination state cannot mutate a cached structural
 * bundle.</p>
 */
public final class ContractBundle {

    private final Map<String, ChannelContract> channels;
    private final Map<String, FrozenNode> channelNodes;
    private final Map<String, List<HandlerBinding>> handlersByChannel;
    private final Map<String, MarkerContract> markers;
    private final Map<String, FrozenNode> contractNodes;
    private final List<EffectiveContractSnapshot> effectiveContractSnapshots;
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
                           List<EffectiveContractSnapshot> effectiveContractSnapshots,
                           List<String> embeddedPaths,
                           boolean checkpointDeclared) {
        this.channels = channels;
        this.channelNodes = channelNodes;
        this.handlersByChannel = handlersByChannel;
        this.markers = markers;
        this.contractNodes = contractNodes;
        this.effectiveContractSnapshots = effectiveContractSnapshots;
        this.embeddedPaths = embeddedPaths;
        this.checkpointDeclared = checkpointDeclared;

        this.channelsView = Collections.unmodifiableMap(this.channels);
        this.markersView = Collections.unmodifiableMap(this.markers);
        this.contractNodesView = Collections.unmodifiableMap(this.contractNodes);
        this.embeddedPathsView = Collections.unmodifiableList(this.embeddedPaths);
    }

    /**
     * Starts an insertion-ordered bundle builder.
     *
     * @return a new empty builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Creates a bundle with no contracts, snapshots, or embedded paths.
     *
     * @return a new empty bundle
     */
    public static ContractBundle empty() {
        return builder().build();
    }

    /**
     * Returns the invocation-local marker bindings.
     *
     * @return an unmodifiable marker map in declaration order
     */
    public Map<String, MarkerContract> markers() {
        return markersView;
    }

    /**
     * Returns the effective channel bindings.
     *
     * @return an unmodifiable channel map in declaration order
     */
    public Map<String, ChannelContract> channels() {
        return channelsView;
    }

    /**
     * Looks up an effective channel by its exact contract key.
     *
     * @param key raw same-scope contract key
     * @return the channel contract, or {@code null} when absent
     */
    public ChannelContract channel(String key) {
        return channels.get(key);
    }

    /**
     * Looks up a channel together with its exact frozen source node.
     *
     * @param key raw same-scope contract key
     * @return a binding view, or {@code null} when the key is not a channel
     */
    public ChannelBinding channelBinding(String key) {
        ChannelContract contract = channels.get(key);
        return contract != null ? new ChannelBinding(key, contract, channelNodes.get(key)) : null;
    }

    /**
     * Looks up an invocation-local marker.
     *
     * @param key exact marker key
     * @return the marker contract, or {@code null} when absent
     */
    public MarkerContract marker(String key) {
        return markers.get(key);
    }

    /**
     * Returns the exact frozen contract node for a binding.
     *
     * @param key exact contract key
     * @return immutable source node, or {@code null} when unavailable
     */
    public FrozenNode contractNode(String key) {
        return contractNodes.get(key);
    }

    /**
     * Returns all retained exact contract nodes.
     *
     * @return an unmodifiable map in contract declaration order
     */
    public Map<String, FrozenNode> contractNodes() {
        return contractNodesView;
    }

    /**
     * Returns the effective contract snapshots in deterministic dispatch order.
     *
     * @return an unmodifiable snapshot list
     */
    public List<EffectiveContractSnapshot> effectiveContractSnapshots() {
        return Collections.unmodifiableList(effectiveContractSnapshots);
    }

    /**
     * Looks up an effective contract snapshot by exact key.
     *
     * @param key exact same-scope contract key
     * @return the snapshot, or {@code null} when absent
     */
    public EffectiveContractSnapshot effectiveContractSnapshot(String key) {
        for (EffectiveContractSnapshot snapshot : effectiveContractSnapshots) {
            if (snapshot.key().equals(key)) {
                return snapshot;
            }
        }
        return null;
    }

    /**
     * Returns a stable snapshot of current marker entries.
     *
     * @return an unmodifiable insertion-ordered entry set
     */
    public Set<Map.Entry<String, MarkerContract>> markerEntries() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(markers.entrySet()));
    }

    /**
     * Returns normalized paths declared by the Process Embedded marker.
     *
     * @return an unmodifiable path list
     */
    public List<String> embeddedPaths() {
        return embeddedPathsView;
    }

    /**
     * Reports whether a checkpoint marker has been declared.
     *
     * @return {@code true} after a static or invocation-local declaration
     */
    public boolean hasCheckpoint() {
        return checkpointDeclared;
    }

    /**
     * Adds the invocation-local checkpoint marker under its reserved key.
     *
     * @param checkpoint checkpoint marker to register
     * @throws IllegalStateException when a checkpoint is already declared
     */
    public void registerCheckpointMarker(ChannelEventCheckpoint checkpoint) {
        if (checkpointDeclared) {
            throw new IllegalStateException("Duplicate Channel Event Checkpoint markers detected in same contracts map");
        }
        markers.put(ProcessorContractConstants.KEY_CHECKPOINT, checkpoint);
        checkpointDeclared = true;
    }

    /**
     * Returns handlers targeting a channel in deterministic dispatch order.
     *
     * @param channelKey exact channel contract key
     * @return a newly allocated sorted list, or an immutable empty list
     */
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

    /**
     * Selects channels assignable to the requested Java contract type.
     *
     * @param type channel contract class used for runtime selection
     * @return a newly allocated list sorted by order and key
     */
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

    ContractBundle copyWithRuntimeMarkers(Map<String, MarkerContract> runtimeMarkers,
                                          Map<String, FrozenNode> runtimeMarkerNodes,
                                          boolean runtimeCheckpointDeclared) {
        Map<String, List<HandlerBinding>> handlersCopy = new LinkedHashMap<>();
        for (Map.Entry<String, List<HandlerBinding>> entry : handlersByChannel.entrySet()) {
            handlersCopy.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        Map<String, FrozenNode> nodesCopy = new LinkedHashMap<>(contractNodes);
        for (String key : markers.keySet()) {
            nodesCopy.remove(key);
        }
        if (runtimeMarkerNodes != null) {
            nodesCopy.putAll(runtimeMarkerNodes);
        }
        return new ContractBundle(new LinkedHashMap<>(channels),
                new LinkedHashMap<>(channelNodes),
                handlersCopy,
                runtimeMarkers != null ? new LinkedHashMap<>(runtimeMarkers) : new LinkedHashMap<>(),
                nodesCopy,
                new ArrayList<>(effectiveContractSnapshots),
                new ArrayList<>(embeddedPaths),
                runtimeCheckpointDeclared);
    }

    boolean hasStaticCheckpointDeclaration() {
        return checkpointDeclared;
    }

    /**
     * Read-only association between a channel key, converted contract, and
     * exact frozen source node.
     */
    public static final class ChannelBinding {
        private final String key;
        private final ChannelContract contract;
        private final FrozenNode node;

        ChannelBinding(String key, ChannelContract contract, FrozenNode node) {
            this.key = key;
            this.contract = contract;
            this.node = node;
        }

        /**
         * Returns the key under which this Channel was recognized.
         *
         * @return the exact contract key
         */
        public String key() {
            return key;
        }

        /**
         * Returns the converted Channel contract.
         *
         * @return the converted channel contract
         */
        public ChannelContract contract() {
            return contract;
        }

        /**
         * Returns the frozen contract contribution retained for execution.
         *
         * @return the exact immutable source node, or {@code null}
         */
        public FrozenNode node() {
            return node;
        }

        /**
         * Resolves the Channel's dispatch order.
         *
         * @return explicit dispatch order, or zero when omitted
         */
        public int order() {
            Integer order = contract.getOrder();
            return order != null ? order : 0;
        }
    }

    /**
     * Read-only association between a handler key, converted contract, exact
     * source node, and executable body field selection.
     */
    public static final class HandlerBinding {
        private final String key;
        private final HandlerContract contract;
        private final FrozenNode node;
        private final List<String> executableBodyFields;

        HandlerBinding(String key, HandlerContract contract, FrozenNode node) {
            this(key, contract, node, Collections.emptyList());
        }

        HandlerBinding(String key,
                       HandlerContract contract,
                       FrozenNode node,
                       List<String> executableBodyFields) {
            this.key = key;
            this.contract = contract;
            this.node = node;
            this.executableBodyFields = Collections.unmodifiableList(
                    new ArrayList<>(executableBodyFields != null
                            ? executableBodyFields
                            : Collections.emptyList()));
        }

        /**
         * Returns the key under which this Handler was recognized.
         *
         * @return the exact handler contract key
         */
        public String key() {
            return key;
        }

        /**
         * Returns the converted Handler contract.
         *
         * @return the converted handler contract
         */
        public HandlerContract contract() {
            return contract;
        }

        /**
         * Returns the frozen contract contribution retained for execution.
         *
         * @return the exact immutable source node, or {@code null}
         */
        public FrozenNode node() {
            return node;
        }

        /**
         * Returns the direct fields whose contents remain deferred as bodies.
         *
         * @return immutable executable-body field names
         */
        public List<String> executableBodyFields() {
            return executableBodyFields;
        }

        /**
         * Resolves the Handler's dispatch order.
         *
         * @return explicit dispatch order, or zero when omitted
         */
        public int order() {
            Integer order = contract.getOrder();
            return order != null ? order : 0;
        }
    }

    /**
     * Mutable, insertion-ordered accumulator for one scope's contract bundle.
     *
     * <p>A builder is intended for a single load operation and is not
     * thread-safe.</p>
     */
    public static final class Builder {
        private final Map<String, ChannelContract> channels = new LinkedHashMap<>();
        private final Map<String, FrozenNode> channelNodes = new LinkedHashMap<>();
        private final Map<String, List<HandlerBinding>> handlersByChannel = new LinkedHashMap<>();
        private final Map<String, MarkerContract> markers = new LinkedHashMap<>();
        private final Map<String, FrozenNode> contractNodes = new LinkedHashMap<>();
        private final List<EffectiveContractSnapshot> effectiveContractSnapshots =
                new ArrayList<>();
        private final List<String> embeddedPaths = new ArrayList<>();
        private boolean embeddedDeclared;
        private boolean checkpointDeclared;

        private Builder() {
        }

        /**
         * Adds a converted channel without retaining a frozen source node.
         *
         * @param key exact contract key
         * @param contract converted channel contract
         * @return this builder
         */
        public Builder addChannel(String key, ChannelContract contract) {
            return addChannel(key, contract, null);
        }

        /**
         * Adds a converted channel and its exact frozen source node.
         *
         * @param key exact contract key
         * @param contract converted channel contract
         * @param node immutable source node, or {@code null}
         * @return this builder
         */
        public Builder addChannel(String key, ChannelContract contract, FrozenNode node) {
            channels.put(key, contract);
            if (node != null) {
                channelNodes.put(key, node);
                contractNodes.put(key, node);
            }
            return this;
        }

        /**
         * Appends an effective contract snapshot.
         *
         * @param snapshot immutable effective snapshot
         * @return this builder
         */
        public Builder addEffectiveContractSnapshot(EffectiveContractSnapshot snapshot) {
            effectiveContractSnapshots.add(snapshot);
            return this;
        }

        /**
         * Adds a handler without retained node or executable-body metadata.
         *
         * @param key exact contract key
         * @param contract converted handler contract
         * @return this builder
         */
        public Builder addHandler(String key, HandlerContract contract) {
            return addHandler(key, contract, null);
        }

        /**
         * Adds a handler and its exact source node.
         *
         * @param key exact contract key
         * @param contract converted handler contract
         * @param node immutable source node, or {@code null}
         * @return this builder
         */
        public Builder addHandler(String key, HandlerContract contract, FrozenNode node) {
            return addHandler(
                    key, contract, node, Collections.emptyList());
        }

        /**
         * Adds a handler with its exact source and executable-body fields.
         *
         * @param key exact contract key
         * @param contract converted handler contract
         * @param node immutable source node, or {@code null}
         * @param executableBodyFields selected executable-body field names
         * @return this builder
         */
        public Builder addHandler(String key,
                                  HandlerContract contract,
                                  FrozenNode node,
                                  List<String> executableBodyFields) {
            handlersByChannel
                    .computeIfAbsent(contract.getChannelKey(), k -> new ArrayList<>())
                    .add(new HandlerBinding(
                            key, contract, node, executableBodyFields));
            if (node != null) {
                contractNodes.put(key, node);
            }
            return this;
        }

        /**
         * Sets the single Process Embedded marker without a retained node.
         *
         * @param embedded converted marker
         * @return this builder
         * @throws MustUnderstandFailureException when already declared
         */
        public Builder setEmbedded(ProcessEmbedded embedded) {
            return setEmbedded(embedded, null);
        }

        /**
         * Sets the single Process Embedded marker and its exact source node.
         *
         * @param embedded converted marker
         * @param node immutable source node, or {@code null}
         * @return this builder
         * @throws MustUnderstandFailureException when already declared
         */
        public Builder setEmbedded(ProcessEmbedded embedded, FrozenNode node) {
            if (embeddedDeclared) {
                throw new MustUnderstandFailureException(
                        "Multiple Process Embedded markers detected in same contracts map",
                        ProcessorErrorCategory.PatchBoundaryViolation);
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

        /**
         * Adds a marker without retaining its frozen source node.
         *
         * @param key exact marker key
         * @param contract converted marker
         * @return this builder
         */
        public Builder addMarker(String key, MarkerContract contract) {
            return addMarker(key, contract, null);
        }

        /**
         * Adds a marker and validates reserved checkpoint-key invariants.
         *
         * @param key exact marker key
         * @param contract converted marker
         * @param node immutable source node, or {@code null}
         * @return this builder
         * @throws IllegalStateException for invalid or duplicate checkpoint use
         */
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

        /**
         * Finishes the scope bundle.
         *
         * <p>The builder must not be reused after this call because the bundle
         * owns its accumulated collections.</p>
         *
         * @return the completed bundle
         */
        public ContractBundle build() {
            return new ContractBundle(channels,
                    channelNodes,
                    handlersByChannel,
                    markers,
                    contractNodes,
                    effectiveContractSnapshots,
                    embeddedPaths,
                    checkpointDeclared);
        }
    }
}
