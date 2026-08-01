package blue.language.processor;

import blue.language.BlueCachePolicy;
import blue.language.model.Node;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.snapshot.FrozenNode;
import blue.language.utils.JsonPointer;
import blue.language.utils.Nodes;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Processor-owned weighted LRU cache for immutable structural contract views.
 *
 * <p>Keys include every selected/effective input that can alter recognition.
 * Invocation-local marker data is intentionally excluded and refreshed after
 * every lookup, so cached snapshots never retain mutable checkpoint state.</p>
 */
final class ContractSnapshotCache {

    private static final String LEGACY_CHANNEL_BINDINGS_PROPERTY = "channelBindings";
    private static final String LEGACY_LAST_EVENTS_PROPERTY = "lastEvents";

    private final int maximumEntries;
    private final long maximumWeightBytes;
    private final long maximumEntryWeightBytes;
    private final LinkedHashMap<Key, Entry> entries =
            new LinkedHashMap<Key, Entry>(16, 0.75f, true);
    private long currentWeightBytes;

    ContractSnapshotCache(BlueCachePolicy policy) {
        BlueCachePolicy required = Objects.requireNonNull(policy, "policy");
        this.maximumEntries = required.conformancePlanMaxEntries();
        this.maximumWeightBytes = required.conformancePlanMaxWeightBytes();
        this.maximumEntryWeightBytes = Math.min(
                required.maximumDerivedEntryWeightBytes(), maximumWeightBytes);
    }

    Key key(
            Node selectedScopeNode,
            FrozenNode effectiveScopeNode,
            String scopePath,
            long registryVersion) {
        FrozenNode contracts = property(
                effectiveScopeNode, ProcessorContractConstants.KEY_CONTRACTS);
        FrozenNode channelBindings = property(
                effectiveScopeNode, LEGACY_CHANNEL_BINDINGS_PROPERTY);
        return new Key(
                scopePath != null ? scopePath : JsonPointer.ROOT,
                registryVersion,
                selectedTypeSignature(selectedScopeNode),
                frozenTypeSignature(effectiveScopeNode),
                selectedContractKeysSignature(selectedScopeNode, contracts),
                contractsSignature(contracts),
                nodeSignature(channelBindings));
    }

    synchronized ContractBundle get(Key key) {
        Entry entry = entries.get(key);
        return entry != null ? entry.bundle : null;
    }

    synchronized void putIfAbsent(Key key, ContractBundle bundle) {
        if (entries.containsKey(key)) {
            entries.get(key);
            return;
        }
        long weight = estimateWeight(key, bundle);
        if (weight > maximumEntryWeightBytes || weight > maximumWeightBytes) {
            return;
        }
        entries.put(key, new Entry(bundle, weight));
        currentWeightBytes = saturatedAdd(currentWeightBytes, weight);
        evictToBounds();
    }

    synchronized void clear() {
        entries.clear();
        currentWeightBytes = 0L;
    }

    synchronized int size() {
        return entries.size();
    }

    synchronized long currentWeightBytes() {
        return currentWeightBytes;
    }

    private String selectedContractKeysSignature(
            Node selectedScopeNode,
            FrozenNode effectiveContractsNode) {
        Node selectedContracts =
                selectedScopeNode != null ? selectedScopeNode.getContracts() : null;
        if (selectedContracts == null) {
            return effectiveContractsNode == null ? "<matches-effective>" : "<missing>";
        }
        Map<String, Node> selected = selectedContracts.getProperties();
        if (selected == null) {
            if (Nodes.isEmptyNode(selectedContracts)
                    && (effectiveContractsNode == null
                    || effectiveContractsNode.isEmptyNode())) {
                return "<matches-effective>";
            }
            return Nodes.isEmptyNode(selectedContracts) ? "<empty>" : "<non-object>";
        }
        Map<String, FrozenNode> effective =
                effectiveContractsNode != null
                        ? effectiveContractsNode.getProperties()
                        : null;
        if (sameOrderedKeys(selected, effective)) {
            return "<matches-effective>";
        }
        StringBuilder signature = new StringBuilder("contracts{");
        for (String key : selected.keySet()) {
            signature.append(key.length()).append(':').append(key).append(';');
        }
        return signature.append('}').toString();
    }

    private String selectedTypeSignature(Node selectedScopeNode) {
        Node type = selectedScopeNode != null ? selectedScopeNode.getType() : null;
        return type != null
                ? FrozenNode.fromNode(type).blueId()
                : "<missing>";
    }

    private String frozenTypeSignature(FrozenNode effectiveScopeNode) {
        FrozenNode type = effectiveScopeNode != null
                ? effectiveScopeNode.getType()
                : null;
        return nodeSignature(type);
    }

    private boolean sameOrderedKeys(
            Map<String, Node> selected,
            Map<String, FrozenNode> effective) {
        if (effective == null || selected.size() != effective.size()) {
            return false;
        }
        Iterator<String> selectedKeys = selected.keySet().iterator();
        Iterator<String> effectiveKeys = effective.keySet().iterator();
        while (selectedKeys.hasNext()) {
            if (!Objects.equals(selectedKeys.next(), effectiveKeys.next())) {
                return false;
            }
        }
        return true;
    }

    private String contractsSignature(FrozenNode contracts) {
        if (contracts == null) {
            return "<missing>";
        }
        Map<String, FrozenNode> properties = contracts.getProperties();
        if (properties == null
                || !properties.containsKey(ProcessorContractConstants.KEY_CHECKPOINT)) {
            return nodeSignature(contracts);
        }
        StringBuilder signature = new StringBuilder("contracts{");
        for (Map.Entry<String, FrozenNode> entry : properties.entrySet()) {
            signature.append(entry.getKey()).append('=');
            signature.append(
                    ProcessorContractConstants.KEY_CHECKPOINT.equals(entry.getKey())
                            ? checkpointStaticSignature(entry.getValue())
                            : nodeSignature(entry.getValue()));
            signature.append(';');
        }
        return signature.append('}').toString();
    }

    private String checkpointStaticSignature(FrozenNode checkpoint) {
        if (checkpoint == null) {
            return "<missing>";
        }
        Node node = checkpoint.toNode();
        if (node.getProperties() != null) {
            node.getProperties().remove(LEGACY_LAST_EVENTS_PROPERTY);
        }
        return FrozenNode.fromResolvedNode(node).blueId();
    }

    private FrozenNode property(FrozenNode node, String key) {
        if (node != null && ProcessorContractConstants.KEY_CONTRACTS.equals(key)) {
            return node.getContracts();
        }
        return node != null && node.getProperties() != null
                ? node.getProperties().get(key)
                : null;
    }

    private String nodeSignature(FrozenNode node) {
        return node != null ? node.blueId() : "<missing>";
    }

    private void evictToBounds() {
        Iterator<Map.Entry<Key, Entry>> iterator = entries.entrySet().iterator();
        while ((entries.size() > maximumEntries
                || currentWeightBytes > maximumWeightBytes)
                && iterator.hasNext()) {
            Entry eldest = iterator.next().getValue();
            currentWeightBytes -= eldest.weightBytes;
            iterator.remove();
        }
    }

    private long estimateWeight(Key key, ContractBundle bundle) {
        long weight = 256L;
        weight = saturatedAdd(weight, retainedString(key.scopePath));
        weight = saturatedAdd(weight, retainedString(key.selectedTypeSignature));
        weight = saturatedAdd(weight, retainedString(key.effectiveTypeSignature));
        weight = saturatedAdd(weight, retainedString(key.selectedContractKeysSignature));
        weight = saturatedAdd(weight, retainedString(key.contractsSignature));
        weight = saturatedAdd(weight, retainedString(key.channelBindingsSignature));
        weight = saturatedAdd(weight, 192L * bundle.channels().size());
        weight = saturatedAdd(weight, 160L * bundle.markers().size());
        weight = saturatedAdd(weight, 64L * bundle.embeddedPaths().size());
        for (String path : bundle.embeddedPaths()) {
            weight = saturatedAdd(weight, retainedString(path));
        }
        for (Map.Entry<String, FrozenNode> entry : bundle.contractNodes().entrySet()) {
            weight = saturatedAdd(weight, 96L + retainedString(entry.getKey()));
            weight = saturatedAdd(
                    weight, entry.getValue().approximateRetainedWeightBytes());
        }
        for (String channelKey : bundle.channels().keySet()) {
            weight = saturatedAdd(weight, retainedString(channelKey));
            weight = saturatedAdd(weight, 160L * bundle.handlersFor(channelKey).size());
        }
        for (String markerKey : bundle.markers().keySet()) {
            weight = saturatedAdd(weight, retainedString(markerKey));
        }
        return weight;
    }

    private long retainedString(String value) {
        return value != null ? 48L + 2L * value.length() : 0L;
    }

    private long saturatedAdd(long left, long right) {
        return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
    }

    static final class Key {
        private final String scopePath;
        private final long registryVersion;
        private final String selectedTypeSignature;
        private final String effectiveTypeSignature;
        private final String selectedContractKeysSignature;
        private final String contractsSignature;
        private final String channelBindingsSignature;

        private Key(
                String scopePath,
                long registryVersion,
                String selectedTypeSignature,
                String effectiveTypeSignature,
                String selectedContractKeysSignature,
                String contractsSignature,
                String channelBindingsSignature) {
            this.scopePath = scopePath;
            this.registryVersion = registryVersion;
            this.selectedTypeSignature = selectedTypeSignature;
            this.effectiveTypeSignature = effectiveTypeSignature;
            this.selectedContractKeysSignature = selectedContractKeysSignature;
            this.contractsSignature = contractsSignature;
            this.channelBindingsSignature = channelBindingsSignature;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key)) {
                return false;
            }
            Key that = (Key) other;
            return registryVersion == that.registryVersion
                    && Objects.equals(scopePath, that.scopePath)
                    && Objects.equals(selectedTypeSignature, that.selectedTypeSignature)
                    && Objects.equals(effectiveTypeSignature, that.effectiveTypeSignature)
                    && Objects.equals(
                            selectedContractKeysSignature,
                            that.selectedContractKeysSignature)
                    && Objects.equals(contractsSignature, that.contractsSignature)
                    && Objects.equals(
                            channelBindingsSignature,
                            that.channelBindingsSignature);
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                    scopePath,
                    registryVersion,
                    selectedTypeSignature,
                    effectiveTypeSignature,
                    selectedContractKeysSignature,
                    contractsSignature,
                    channelBindingsSignature);
        }
    }

    private static final class Entry {
        private final ContractBundle bundle;
        private final long weightBytes;

        private Entry(ContractBundle bundle, long weightBytes) {
            this.bundle = Objects.requireNonNull(bundle, "bundle");
            this.weightBytes = weightBytes;
        }
    }
}
