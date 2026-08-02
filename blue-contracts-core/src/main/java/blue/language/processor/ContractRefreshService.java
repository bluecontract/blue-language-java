package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.model.ChannelEventCheckpoint;
import blue.language.processor.model.CheckpointEntry;
import blue.language.processor.model.MarkerContract;
import blue.language.processor.model.ProcessEmbedded;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.snapshot.FrozenNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Refreshes structural contract recognition and invocation-local state.
 *
 * <p>Metered recognition always rebuilds so physical cache warmth cannot
 * change gas or trace. Unmetered loads may reuse an immutable structural
 * bundle, but direct markers are reconstructed on every invocation. This also
 * keeps delivery snapshots frozen when initialization mutates processor-owned
 * state after classification.</p>
 */
final class ContractRefreshService {

    private final ContractProcessorRegistry registry;
    private final EffectiveContractResolver effectiveContracts;
    private final ContractSnapshotCache cache;

    ContractRefreshService(
            ContractProcessorRegistry registry,
            EffectiveContractResolver effectiveContracts,
            ContractSnapshotCache cache) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.effectiveContracts =
                Objects.requireNonNull(effectiveContracts, "effectiveContracts");
        this.cache = Objects.requireNonNull(cache, "cache");
    }

    ContractBundle load(
            Node selectedScopeNode,
            FrozenNode effectiveScopeNode,
            String scopePath,
            ProcessingObserver observer,
            ContractRecognitionMeter recognitionMeter,
            String recognitionReason,
            StructuralBundleLoader structuralLoader) {
        ProcessingObserver metrics = observer != null
                ? observer
                : NoOpProcessingObserver.INSTANCE;
        effectiveContracts.requireRegisteredProviderEvidence(effectiveScopeNode);
        if (recognitionMeter != null) {
            ContractBundle built = timedBuild(
                    selectedScopeNode,
                    effectiveScopeNode,
                    scopePath,
                    metrics,
                    recognitionMeter,
                    recognitionReason,
                    structuralLoader);
            ProcessingObservations.record(
                    metrics, ProcessingMetricId.BUNDLES_BUILT, 1L);
            return withCurrentMarkers(
                    built, selectedScopeNode, effectiveScopeNode);
        }

        long keyStart = System.nanoTime();
        ContractSnapshotCache.Key key;
        try {
            key = cache.key(
                    selectedScopeNode,
                    effectiveScopeNode,
                    scopePath,
                    registry.version());
        } finally {
            ProcessingObservations.record(
                    metrics,
                    ProcessingMetricId.BUNDLE_LOAD_CACHE_KEY_BUILD_NANOS,
                    System.nanoTime() - keyStart);
        }
        ContractBundle cached = cache.get(key);
        if (cached != null) {
            ProcessingObservations.record(
                    metrics, ProcessingMetricId.BUNDLE_LOAD_CACHE_HITS, 1L);
            long reuseStart = System.nanoTime();
            try {
                ProcessingObservations.record(
                        metrics, ProcessingMetricId.BUNDLES_REUSED, 1L);
                return withCurrentMarkers(
                        cached, selectedScopeNode, effectiveScopeNode);
            } finally {
                ProcessingObservations.record(
                        metrics,
                        ProcessingMetricId.BUNDLE_LOAD_REUSE_NANOS,
                        System.nanoTime() - reuseStart);
            }
        }

        ProcessingObservations.record(
                metrics, ProcessingMetricId.BUNDLE_LOAD_CACHE_MISSES, 1L);
        ContractBundle built = timedBuild(
                selectedScopeNode,
                effectiveScopeNode,
                scopePath,
                metrics,
                null,
                null,
                structuralLoader);
        cache.putIfAbsent(key, built);
        ProcessingObservations.record(
                metrics, ProcessingMetricId.BUNDLES_BUILT, 1L);
        return withCurrentMarkers(
                built, selectedScopeNode, effectiveScopeNode);
    }

    void clear() {
        cache.clear();
    }

    int cacheSize() {
        return cache.size();
    }

    long cacheWeightBytes() {
        return cache.currentWeightBytes();
    }

    private ContractBundle timedBuild(
            Node selectedScopeNode,
            FrozenNode effectiveScopeNode,
            String scopePath,
            ProcessingObserver metrics,
            ContractRecognitionMeter recognitionMeter,
            String recognitionReason,
            StructuralBundleLoader structuralLoader) {
        long buildStart = System.nanoTime();
        try {
            return structuralLoader.load(
                    selectedScopeNode,
                    effectiveScopeNode,
                    scopePath,
                    recognitionMeter,
                    recognitionReason);
        } finally {
            ProcessingObservations.record(
                    metrics,
                    ProcessingMetricId.BUNDLE_LOAD_ACTUAL_BUILD_NANOS,
                    System.nanoTime() - buildStart);
        }
    }

    private ContractBundle withCurrentMarkers(
            ContractBundle structural,
            Node selectedScopeNode,
            FrozenNode effectiveScopeNode) {
        RuntimeMarkers markers = runtimeMarkers(selectedScopeNode, effectiveScopeNode);
        return structural.copyWithRuntimeMarkers(
                markers.markers,
                markers.nodes,
                markers.checkpointDeclared,
                null);
    }

    private RuntimeMarkers runtimeMarkers(
            Node selectedScopeNode,
            FrozenNode effectiveScopeNode) {
        Map<String, MarkerContract> markers = new LinkedHashMap<>();
        Map<String, FrozenNode> markerNodes = new LinkedHashMap<>();
        boolean checkpointDeclared = false;
        Node exactSelectedScope =
                effectiveContracts.materializeSelectedContractsMap(selectedScopeNode);
        Node selectedContracts =
                exactSelectedScope != null ? exactSelectedScope.getContracts() : null;
        FrozenNode effectiveContractMap = effectiveContracts.property(
                effectiveScopeNode, ProcessorContractConstants.KEY_CONTRACTS);
        if (selectedContracts == null
                || selectedContracts.getProperties() == null
                || effectiveContractMap == null
                || effectiveContractMap.getProperties() == null) {
            return new RuntimeMarkers(markers, markerNodes, false);
        }
        for (Map.Entry<String, Node> selectedEntry
                : selectedContracts.getProperties().entrySet()) {
            String key = selectedEntry.getKey();
            if (!EffectiveContractResolver.isDirectProcessorStateKey(key)) {
                continue;
            }
            Node selectedNode = selectedEntry.getValue();
            FrozenNode effectiveNode = effectiveContractMap.getProperties().get(key);
            EffectiveContractResolver.MarkerValue markerValue =
                    effectiveContracts.directMarker(key, selectedNode, effectiveNode);
            if (markerValue == null
                    || markerValue.marker() instanceof ProcessEmbedded) {
                continue;
            }
            MarkerContract marker = markerValue.marker();
            marker.setKey(key);
            marker.setTypeBlueId(markerValue.typeBlueId());
            if (ProcessorContractConstants.KEY_CHECKPOINT.equals(key)
                    && !(marker instanceof ChannelEventCheckpoint)) {
                throw new IllegalStateException(
                        "Reserved key 'checkpoint' must contain a Channel Event Checkpoint");
            }
            if (marker instanceof ChannelEventCheckpoint) {
                if (!ProcessorContractConstants.KEY_CHECKPOINT.equals(key)) {
                    throw new IllegalStateException(
                            "Channel Event Checkpoint must use reserved key 'checkpoint' at key '"
                                    + key
                                    + "'");
                }
                if (checkpointDeclared) {
                    throw new IllegalStateException(
                            "Duplicate Channel Event Checkpoint markers detected in same contracts map");
                }
                checkpointDeclared = true;
                restoreExactCheckpointSubjects(
                        (ChannelEventCheckpoint) marker,
                        selectedNode);
            }
            markers.put(key, marker);
            markerNodes.put(key, effectiveNode);
        }
        return new RuntimeMarkers(markers, markerNodes, checkpointDeclared);
    }

    private void restoreExactCheckpointSubjects(
            ChannelEventCheckpoint checkpoint,
            Node selectedCheckpoint) {
        Node selectedEntries = selectedCheckpoint != null
                && selectedCheckpoint.getProperties() != null
                ? selectedCheckpoint.getProperties().get(
                        ProcessorContractConstants.KEY_ENTRIES)
                : null;
        if (selectedEntries == null || selectedEntries.getProperties() == null) {
            return;
        }
        for (Map.Entry<String, Node> selectedEntry
                : selectedEntries.getProperties().entrySet()) {
            CheckpointEntry checkpointEntry = checkpoint.entry(selectedEntry.getKey());
            Node entryNode = selectedEntry.getValue();
            Node exactSubject = entryNode != null
                    && entryNode.getProperties() != null
                    ? entryNode.getProperties().get(
                            ProcessorContractConstants.KEY_SUBJECT)
                    : null;
            if (checkpointEntry != null && exactSubject != null) {
                checkpointEntry.subject(exactSubject);
            }
        }
    }

    interface StructuralBundleLoader {
        ContractBundle load(
                Node selectedScopeNode,
                FrozenNode effectiveScopeNode,
                String scopePath,
                ContractRecognitionMeter recognitionMeter,
                String recognitionReason);
    }

    private static final class RuntimeMarkers {
        private final Map<String, MarkerContract> markers;
        private final Map<String, FrozenNode> nodes;
        private final boolean checkpointDeclared;

        private RuntimeMarkers(
                Map<String, MarkerContract> markers,
                Map<String, FrozenNode> nodes,
                boolean checkpointDeclared) {
            this.markers = markers;
            this.nodes = nodes;
            this.checkpointDeclared = checkpointDeclared;
        }
    }
}
