package blue.language.processor;

import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.processor.model.ChannelContract;
import blue.language.processor.model.HandlerContract;
import blue.language.processor.model.MarkerContract;
import blue.language.processor.model.ProcessEmbedded;
import blue.language.processor.model.ChannelEventCheckpoint;
import blue.language.processor.util.PointerUtils;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.snapshot.FrozenNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    private final CanonicalTypeIdentityLookup canonicalTypeIdentities;
    private final EmbeddedScopeDeclaration embeddedScopeDeclaration;
    private final EmbeddedScopePlan embeddedScopePlan;
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
                           CanonicalTypeIdentityLookup canonicalTypeIdentities,
                           EmbeddedScopeDeclaration embeddedScopeDeclaration,
                           EmbeddedScopePlan embeddedScopePlan,
                           boolean checkpointDeclared) {
        this.channels = channels;
        this.channelNodes = channelNodes;
        this.handlersByChannel = handlersByChannel;
        this.markers = markers;
        this.contractNodes = contractNodes;
        this.effectiveContractSnapshots = effectiveContractSnapshots;
        this.canonicalTypeIdentities = Objects.requireNonNull(
                canonicalTypeIdentities, "canonicalTypeIdentities");
        this.embeddedScopeDeclaration = embeddedScopeDeclaration;
        this.embeddedScopePlan = embeddedScopePlan;
        this.embeddedPaths = effectiveEmbeddedPaths(
                embeddedScopeDeclaration, embeddedScopePlan);
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
        return new Builder(CanonicalTypeIdentityLookup.incomplete());
    }

    /**
     * Starts a bundle builder bound to the producing resolution's canonical
     * type identity evidence.
     *
     * @param canonicalTypeIdentities authoritative invocation-local evidence
     * @return a new empty builder
     */
    static Builder builder(
            CanonicalTypeIdentityLookup canonicalTypeIdentities) {
        return new Builder(canonicalTypeIdentities);
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

    /** Returns canonical type identity evidence for this invocation view. */
    CanonicalTypeIdentityLookup canonicalTypeIdentities() {
        return canonicalTypeIdentities;
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
     * Returns the effective embedded paths available in this bundle view.
     *
     * <p>An invocation-planned bundle returns the frozen combined concrete
     * child paths. A structural cache-only bundle, which has no document from
     * which to enumerate collection members, returns its normalized explicit
     * declarations only. Runtime consumers must use planned bundles whenever
     * concrete collection membership is semantic.</p>
     *
     * @return an unmodifiable effective path list
     */
    public List<String> embeddedPaths() {
        return embeddedPathsView;
    }

    /** Returns the immutable structural Process Embedded declaration. */
    EmbeddedScopeDeclaration embeddedScopeDeclaration() {
        return embeddedScopeDeclaration;
    }

    /**
     * Returns the invocation-local concrete embedded-scope plan.
     *
     * @return immutable plan, or {@code null} on a cache-only structural view
     */
    EmbeddedScopePlan embeddedScopePlan() {
        return embeddedScopePlan;
    }

    /** Reports whether this bundle contains an effective Process Embedded marker. */
    boolean hasProcessEmbedded() {
        for (EffectiveContractSnapshot snapshot : effectiveContractSnapshots) {
            if (EffectiveContractSnapshotConstants.Role.PROCESS_EMBEDDED
                    .equals(snapshot.role())) {
                return true;
            }
        }
        return false;
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
                                          boolean runtimeCheckpointDeclared,
                                          EmbeddedScopePlan runtimeEmbeddedScopePlan,
                                          CanonicalTypeIdentityLookup typeIdentities) {
        CanonicalTypeIdentityLookup currentTypeIdentities =
                Objects.requireNonNull(typeIdentities, "typeIdentities");
        Map<String, List<HandlerBinding>> handlersCopy = new LinkedHashMap<>();
        for (Map.Entry<String, List<HandlerBinding>> entry : handlersByChannel.entrySet()) {
            List<HandlerBinding> rebound = new ArrayList<>();
            for (HandlerBinding binding : entry.getValue()) {
                rebound.add(new HandlerBinding(
                        binding.key,
                        binding.contract,
                        binding.node,
                        binding.executableBodyFields,
                        currentTypeIdentities));
            }
            handlersCopy.put(entry.getKey(), rebound);
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
                currentTypeIdentities,
                embeddedScopeDeclaration,
                runtimeEmbeddedScopePlan,
                runtimeCheckpointDeclared);
    }

    /** Returns structural cache state without invocation-local evidence. */
    ContractBundle copyForStructuralCache() {
        return copyWithRuntimeMarkers(
                Collections.<String, MarkerContract>emptyMap(),
                Collections.<String, FrozenNode>emptyMap(),
                false,
                null,
                CanonicalTypeIdentityLookup.incomplete());
    }

    /** Returns an invocation-local copy carrying the frozen entry plan. */
    ContractBundle withEmbeddedScopePlan(EmbeddedScopePlan plan) {
        Map<String, List<HandlerBinding>> handlersCopy =
                new LinkedHashMap<>();
        for (Map.Entry<String, List<HandlerBinding>> entry
                : handlersByChannel.entrySet()) {
            handlersCopy.put(
                    entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        return new ContractBundle(
                new LinkedHashMap<>(channels),
                new LinkedHashMap<>(channelNodes),
                handlersCopy,
                new LinkedHashMap<>(markers),
                new LinkedHashMap<>(contractNodes),
                new ArrayList<>(effectiveContractSnapshots),
                canonicalTypeIdentities,
                embeddedScopeDeclaration,
                plan,
                checkpointDeclared);
    }

    private static List<String> effectiveEmbeddedPaths(
            EmbeddedScopeDeclaration declaration,
            EmbeddedScopePlan plan) {
        if (plan == null) {
            return new ArrayList<>(declaration.explicitPaths());
        }
        List<String> concrete = new ArrayList<>(
                plan.concretePaths().size());
        for (EmbeddedConcretePath path : plan.concretePaths()) {
            concrete.add(path.origin() == EmbeddedPathOrigin.EXPLICIT
                    ? path.declarationPath()
                    : PointerUtils.appendPointer(
                            path.declarationPath(), path.memberKey()));
        }
        return concrete;
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
        private final CanonicalTypeIdentityLookup typeIdentities;

        HandlerBinding(String key,
                       HandlerContract contract,
                       FrozenNode node,
                       List<String> executableBodyFields,
                       CanonicalTypeIdentityLookup typeIdentities) {
            this.key = key;
            this.contract = contract;
            this.node = node;
            this.executableBodyFields = Collections.unmodifiableList(
                    new ArrayList<>(executableBodyFields != null
                            ? executableBodyFields
                            : Collections.emptyList()));
            this.typeIdentities = Objects.requireNonNull(
                    typeIdentities, "typeIdentities");
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

        CanonicalTypeIdentityLookup typeIdentities() {
            return typeIdentities;
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
        private final CanonicalTypeIdentityLookup canonicalTypeIdentities;
        private EmbeddedScopeDeclaration embeddedScopeDeclaration =
                EmbeddedScopeDeclaration.empty();
        private boolean embeddedDeclared;
        private boolean checkpointDeclared;

        private Builder(
                CanonicalTypeIdentityLookup canonicalTypeIdentities) {
            this.canonicalTypeIdentities = Objects.requireNonNull(
                    canonicalTypeIdentities, "canonicalTypeIdentities");
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
                    key,
                    contract,
                    node,
                    Collections.emptyList(),
                    canonicalTypeIdentities);
        }

        /**
         * Adds a handler with its exact source and executable-body fields.
         *
         * @param key exact contract key
         * @param contract converted handler contract
         * @param node immutable source node, or {@code null}
         * @param executableBodyFields selected executable-body field names
         * @param typeIdentities invocation-local canonical type identity evidence
         * @return this builder
         */
        public Builder addHandler(String key,
                                  HandlerContract contract,
                                  FrozenNode node,
                                  List<String> executableBodyFields,
                                  CanonicalTypeIdentityLookup typeIdentities) {
            handlersByChannel
                    .computeIfAbsent(contract.getChannelKey(), k -> new ArrayList<>())
                    .add(new HandlerBinding(
                            key,
                            contract,
                            node,
                            executableBodyFields,
                            typeIdentities));
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
            embeddedScopeDeclaration = EmbeddedScopeDeclaration.of(
                    embedded.getPaths(), embedded.getCollectionPaths());
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
                    canonicalTypeIdentities,
                    embeddedScopeDeclaration,
                    null,
                    checkpointDeclared);
        }
    }
}
