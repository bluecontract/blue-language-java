package blue.language.processor;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.processor.model.ChannelEventCheckpoint;
import blue.language.processor.model.CheckpointEntry;
import blue.language.processor.model.MarkerContract;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.processor.util.PointerUtils;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.processor.util.ProcessorPointerConstants;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Direct, domain-bound checkpoint state for one atomic invocation.
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

    CheckpointManager(DocumentProcessingRuntime runtime,
                      Blue blue,
                      ProcessingMetricsSink metrics) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.identityCache = new CheckpointIdentityCache(blue, metrics);
    }

    CheckpointManager(DocumentProcessingRuntime runtime,
                      Function<Node, String> ignoredSignatureFn) {
        this(runtime, (Blue) null, ProcessingMetricsSink.NOOP);
    }

    void ensureCheckpointMarker(String scopePath, ContractBundle bundle) {
        MarkerContract marker = bundle.marker(ProcessorContractConstants.KEY_CHECKPOINT);
        String pointer = PointerUtils.resolvePointer(
                scopePath, ProcessorPointerConstants.RELATIVE_CHECKPOINT);
        if (marker == null) {
            Node markerNode = new Node()
                    .type(new Node().blueId(RuntimeBlueIds.CHANNEL_EVENT_CHECKPOINT))
                    .properties("entries", new Node().properties(new LinkedHashMap<>()));
            runtime.chargeProcessorMarkerWritten("checkpoint-marker-create");
            runtime.directWrite(pointer, markerNode);
            runtime.recordTrace(ProcessingTraceRecord.Kind.MARKER_WRITE,
                    scopePath,
                    ProcessorContractConstants.KEY_CHECKPOINT,
                    pointer);
            bundle.registerCheckpointMarker(new ChannelEventCheckpoint());
            return;
        }
        if (!(marker instanceof ChannelEventCheckpoint)) {
            throw new IllegalStateException(
                    "Reserved key 'checkpoint' must contain a Channel Event Checkpoint at "
                            + pointer);
        }
    }

    CheckpointRecord findCheckpoint(ContractBundle bundle,
                                    String rawChannelKey,
                                    String checkpointDomainBlueId) {
        for (Map.Entry<String, MarkerContract> entry : bundle.markerEntries()) {
            if (!(entry.getValue() instanceof ChannelEventCheckpoint)) {
                continue;
            }
            ChannelEventCheckpoint checkpoint = (ChannelEventCheckpoint) entry.getValue();
            CheckpointEntry storedEntry = checkpoint.entry(rawChannelKey);
            boolean domainMatches = storedEntry != null
                    && Objects.equals(checkpointDomainBlueId, storedEntry.domainBlueId());
            Node storedSubject = domainMatches ? storedEntry.getSubject() : null;
            return new CheckpointRecord(entry.getKey(),
                    checkpoint,
                    rawChannelKey,
                    checkpointDomainBlueId,
                    storedSubject,
                    domainMatches);
        }
        return new CheckpointRecord(ProcessorContractConstants.KEY_CHECKPOINT,
                null,
                rawChannelKey,
                checkpointDomainBlueId,
                null,
                false);
    }

    @Deprecated
    CheckpointRecord findCheckpoint(ContractBundle bundle, String channelKey) {
        return findCheckpoint(bundle, channelKey, null);
    }

    boolean isDuplicate(CheckpointRecord record, String subjectBlueId) {
        if (record == null || subjectBlueId == null || record.lastEventNode == null) {
            return false;
        }
        if (record.lastEventSignature == null) {
            record.lastEventSignature = identityCache.storedIdentity(
                    record.checkpoint, record.channelKey, record.lastEventNode);
        }
        return record.matches(subjectBlueId);
    }

    void recordComparison(String scopePath,
                          CheckpointRecord record,
                          String subjectBlueId) {
        runtime.chargeCheckpointCompared();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("domain", record != null ? record.checkpointDomainBlueId : null);
        details.put("subject", subjectBlueId);
        details.put("domainMatches", record != null && record.domainMatches);
        runtime.recordTrace(ProcessingTraceRecord.Kind.CHECKPOINT_COMPARE,
                scopePath,
                record != null ? record.channelKey : null,
                null,
                details,
                null);
    }

    void persist(String scopePath,
                 ContractBundle bundle,
                 CheckpointRecord record,
                 String subjectBlueId,
                 Node ignoredEventNode) {
        if (record == null || subjectBlueId == null) {
            return;
        }
        ensureCheckpointMarker(scopePath, bundle);
        CheckpointRecord active = record.checkpoint != null
                ? record
                : findCheckpoint(bundle, record.channelKey, record.checkpointDomainBlueId);
        String pointer = PointerUtils.resolvePointer(scopePath,
                ProcessorPointerConstants.relativeCheckpointEntry(
                        active.markerKey, active.channelKey));
        String domainBlueId = active.checkpointDomainBlueId != null
                ? active.checkpointDomainBlueId
                : subjectBlueId;
        Node entryNode = new Node()
                .properties("domain",
                        new Node().blueId(domainBlueId))
                .properties("subject", new Node().blueId(subjectBlueId));
        runtime.chargeCheckpointUpdate();
        runtime.directWrite(pointer, entryNode);
        active.checkpoint.putEntry(
                active.channelKey, domainBlueId, subjectBlueId);
        active.lastEventNode = new Node().blueId(subjectBlueId);
        active.lastEventSignature = subjectBlueId;

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("domain", domainBlueId);
        details.put("subject", subjectBlueId);
        runtime.recordTrace(ProcessingTraceRecord.Kind.CHECKPOINT_WRITE,
                scopePath,
                active.channelKey,
                pointer,
                details,
                entryNode);
    }

    /**
     * Direct-writes deterministic cleanup for disappeared channels and
     * inactive checkpoint domains. Cleanup is processor state and emits no
     * Document Update.
     */
    void cleanupInactiveEntries(String scopePath,
                                ContractBundle bundle,
                                Map<String, String> activeDomains) {
        MarkerContract marker =
                bundle.marker(ProcessorContractConstants.KEY_CHECKPOINT);
        if (!(marker instanceof ChannelEventCheckpoint)) {
            return;
        }
        ChannelEventCheckpoint checkpoint = (ChannelEventCheckpoint) marker;
        List<String> rawKeys = new ArrayList<>(checkpoint.getEntries().keySet());
        Collections.sort(rawKeys, ExternalOrderKey::compareTextCodePoints);
        for (String rawKey : rawKeys) {
            CheckpointEntry entry = checkpoint.entry(rawKey);
            String activeDomain = activeDomains != null
                    ? activeDomains.get(rawKey)
                    : null;
            if (entry != null
                    && activeDomain != null
                    && Objects.equals(activeDomain, entry.domainBlueId())) {
                continue;
            }
            String pointer = PointerUtils.resolvePointer(
                    scopePath,
                    ProcessorPointerConstants.relativeCheckpointEntry(
                            ProcessorContractConstants.KEY_CHECKPOINT,
                            rawKey));
            runtime.chargeCheckpointUpdate();
            runtime.directWrite(pointer, null);
            checkpoint.removeEntry(rawKey);
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("action", "cleanup");
            if (entry != null) {
                details.put("oldDomain", entry.domainBlueId());
            }
            if (activeDomain != null) {
                details.put("activeDomain", activeDomain);
            }
            runtime.recordTrace(
                    ProcessingTraceRecord.Kind.CHECKPOINT_WRITE,
                    scopePath,
                    rawKey,
                    pointer,
                    details,
                    null);
        }
    }

    String eventIdentity(Node event) {
        return identityCache.identity(event);
    }

    static final class CheckpointRecord {
        final String markerKey;
        final ChannelEventCheckpoint checkpoint;
        final String channelKey;
        final String checkpointDomainBlueId;
        final boolean domainMatches;
        Node lastEventNode;
        String lastEventSignature;

        CheckpointRecord(String markerKey,
                         ChannelEventCheckpoint checkpoint,
                         String channelKey,
                         String checkpointDomainBlueId,
                         Node lastEventNode,
                         boolean domainMatches) {
            this.markerKey = markerKey;
            this.checkpoint = checkpoint;
            this.channelKey = channelKey;
            this.checkpointDomainBlueId = checkpointDomainBlueId;
            this.lastEventNode = lastEventNode != null ? lastEventNode.clone() : null;
            this.domainMatches = domainMatches;
        }

        boolean matches(String signature) {
            return signature != null && signature.equals(lastEventSignature);
        }
    }
}
