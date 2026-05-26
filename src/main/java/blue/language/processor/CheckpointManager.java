package blue.language.processor;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.processor.model.ChannelEventCheckpoint;
import blue.language.processor.model.MarkerContract;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.processor.util.PointerUtils;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.processor.util.ProcessorPointerConstants;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Handles per-scope checkpoint lifecycle: lazy creation, gating, and persistence.
 */
final class CheckpointManager {

    private final DocumentProcessingRuntime runtime;
    private final CheckpointIdentityCache identityCache;

    CheckpointManager(DocumentProcessingRuntime runtime) {
        this(runtime, (Blue) null, ProcessingMetricsSink.NOOP);
    }

    CheckpointManager(DocumentProcessingRuntime runtime, Blue blue) {
        this(runtime, blue, ProcessingMetricsSink.NOOP);
    }

    CheckpointManager(DocumentProcessingRuntime runtime, Blue blue, ProcessingMetricsSink metrics) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.identityCache = new CheckpointIdentityCache(blue, metrics);
    }

    CheckpointManager(DocumentProcessingRuntime runtime, Function<Node, String> ignoredSignatureFn) {
        this(runtime, (Blue) null, ProcessingMetricsSink.NOOP);
    }

    void ensureCheckpointMarker(String scopePath, ContractBundle bundle) {
        MarkerContract marker = bundle.marker(ProcessorContractConstants.KEY_CHECKPOINT);
        String pointer = PointerUtils.resolvePointer(scopePath, ProcessorPointerConstants.RELATIVE_CHECKPOINT);
        if (marker == null) {
            Node markerNode = new Node()
                    .type(new Node().blueId(RuntimeBlueIds.CHANNEL_EVENT_CHECKPOINT))
                    .properties("lastEvents", new Node().properties(new LinkedHashMap<>()));
            runtime.directWrite(pointer, markerNode);
            bundle.registerCheckpointMarker(new ChannelEventCheckpoint());
            return;
        }
        if (!(marker instanceof ChannelEventCheckpoint)) {
            throw new IllegalStateException(
                    "Reserved key 'checkpoint' must contain a Channel Event Checkpoint at " + pointer);
        }
    }

    CheckpointRecord findCheckpoint(ContractBundle bundle, String channelKey) {
        for (Map.Entry<String, MarkerContract> entry : bundle.markerEntries()) {
            if (entry.getValue() instanceof ChannelEventCheckpoint) {
                ChannelEventCheckpoint checkpoint = (ChannelEventCheckpoint) entry.getValue();
                Node stored = checkpoint.lastEvent(channelKey);
                CheckpointRecord record = new CheckpointRecord(entry.getKey(), checkpoint, channelKey, stored);
                return record;
            }
        }
        return null;
    }

    boolean isDuplicate(CheckpointRecord record, String signature) {
        if (record == null || signature == null || record.lastEventNode == null) {
            return false;
        }
        if (record.lastEventSignature == null) {
            record.lastEventSignature = identityCache.storedIdentity(record.checkpoint,
                    record.channelKey,
                    record.lastEventNode);
        }
        return record.matches(signature);
    }

    void persist(String scopePath,
                 ContractBundle bundle,
                 CheckpointRecord record,
                 String eventSignature,
                 Node eventNode) {
        if (record == null) {
            return;
        }
        String pointer = PointerUtils.resolvePointer(scopePath,
                ProcessorPointerConstants.relativeCheckpointLastEvent(record.markerKey, record.channelKey));
        Node stored = eventNode != null ? eventNode.clone() : null;
        runtime.chargeCheckpointUpdate();
        runtime.directWrite(pointer, stored);
        record.checkpoint.updateEvent(record.channelKey, stored);
        record.lastEventNode = stored != null ? stored.clone() : null;
        record.lastEventSignature = eventSignature;
        identityCache.updateStoredIdentity(record.checkpoint, record.channelKey, eventSignature);
    }

    String eventIdentity(Node event) {
        return identityCache.identity(event);
    }

    static final class CheckpointRecord {
        final String markerKey;
        final ChannelEventCheckpoint checkpoint;
        final String channelKey;
        Node lastEventNode;
        String lastEventSignature;

        CheckpointRecord(String markerKey,
                         ChannelEventCheckpoint checkpoint,
                         String channelKey,
                         Node lastEventNode) {
            this.markerKey = markerKey;
            this.checkpoint = checkpoint;
            this.channelKey = channelKey;
            this.lastEventNode = lastEventNode != null ? lastEventNode.clone() : null;
        }

        boolean matches(String signature) {
            return signature != null && signature.equals(lastEventSignature);
        }
    }
}
