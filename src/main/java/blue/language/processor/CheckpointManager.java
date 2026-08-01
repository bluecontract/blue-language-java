package blue.language.processor;

import blue.language.api.LanguageRuntimeAccess;
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
 * Owns domain-bound checkpoint comparison and persistence for one invocation.
 *
 * <p>Checkpoint state is processor-managed and therefore uses direct writes
 * that emit no application Document Update. Subject identity is verified
 * before the metered write, and the bundle mirror is updated only with the
 * same exact subject.</p>
 */
final class CheckpointManager {

    private final DocumentProcessingRuntime runtime;
    private final CheckpointIdentityCache identityCache;

    CheckpointManager(DocumentProcessingRuntime runtime) {
        this(runtime, (LanguageRuntimeAccess) null,
                NoOpProcessingObserver.INSTANCE);
    }

    CheckpointManager(
            DocumentProcessingRuntime runtime,
            LanguageRuntimeAccess languageRuntime) {
        this(runtime, languageRuntime,
                NoOpProcessingObserver.INSTANCE);
    }

    CheckpointManager(DocumentProcessingRuntime runtime,
                      LanguageRuntimeAccess languageRuntime,
                      ProcessingObserver metrics) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.identityCache = new CheckpointIdentityCache(
                languageRuntime, metrics);
    }

    CheckpointManager(DocumentProcessingRuntime runtime,
                      Function<Node, String> ignoredSignatureFn) {
        this(runtime, (LanguageRuntimeAccess) null,
                NoOpProcessingObserver.INSTANCE);
    }

    void ensureCheckpointMarker(String scopePath, ContractBundle bundle) {
        MarkerContract marker = bundle.marker(ProcessorContractConstants.KEY_CHECKPOINT);
        String pointer = PointerUtils.resolvePointer(
                scopePath, ProcessorPointerConstants.RELATIVE_CHECKPOINT);
        if (marker == null) {
            Node markerNode = new Node()
                    .type(new Node().blueId(RuntimeBlueIds.CHANNEL_EVENT_CHECKPOINT))
                    .properties(
                            ProcessorContractConstants.KEY_ENTRIES,
                            new Node().properties(new LinkedHashMap<>()));
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
            CheckpointRecord record = new CheckpointRecord(entry.getKey(),
                    checkpoint,
                    rawChannelKey,
                    checkpointDomainBlueId,
                    storedSubject,
                    domainMatches);
            if (storedSubject != null) {
                record.lastEventSignature =
                        identityCache.storedIdentity(
                                checkpoint,
                                rawChannelKey,
                                storedSubject);
            }
            return record;
        }
        return new CheckpointRecord(ProcessorContractConstants.KEY_CHECKPOINT,
                null,
                rawChannelKey,
                checkpointDomainBlueId,
                null,
                false);
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
        details.put(
                ProcessingTraceConstants.FIELD_DOMAIN,
                record != null ? record.checkpointDomainBlueId : null);
        details.put(
                ProcessingTraceConstants.FIELD_SUBJECT,
                subjectBlueId);
        details.put(
                ProcessingTraceConstants.FIELD_DOMAIN_MATCHES,
                record != null && record.domainMatches);
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
                 Node exactSubject) {
        if (record == null || subjectBlueId == null) {
            return;
        }
        Node storedSubject =
                exactSubject != null
                        ? exactSubject.clone()
                        : new Node().blueId(
                                subjectBlueId);
        String calculatedSubjectBlueId =
                identityCache.identity(
                        storedSubject);
        if (!subjectBlueId.equals(
                calculatedSubjectBlueId)) {
            throw new ProcessorFailureException(
                    ProcessorErrorCategory
                            .CheckpointPolicyError,
                    "Frozen checkpoint subject identity mismatch: expected "
                            + subjectBlueId
                            + " but calculated "
                            + calculatedSubjectBlueId);
        }
        ensureCheckpointMarker(scopePath, bundle);
        /*
         * Every pending update is merged through the invocation's active
         * mutation bundle. A classification-time record may point at a stale
         * bundle mirror shared by only one logical delivery group; using that
         * mirror here could recreate an empty marker and erase an earlier
         * source checkpoint.
         */
        CheckpointRecord active = findCheckpoint(
                bundle,
                record.channelKey,
                record.checkpointDomainBlueId);
        String pointer = PointerUtils.resolvePointer(scopePath,
                ProcessorPointerConstants.relativeCheckpointEntry(
                        active.markerKey, active.channelKey));
        String domainBlueId = active.checkpointDomainBlueId != null
                ? active.checkpointDomainBlueId
                : subjectBlueId;
        Node entryNode = new Node()
                .properties(
                        ProcessorContractConstants.KEY_DOMAIN,
                        new Node().blueId(domainBlueId))
                .properties(
                        ProcessorContractConstants.KEY_SUBJECT,
                        storedSubject.clone());
        runtime.chargeCheckpointUpdate();
        runtime.directWrite(pointer, entryNode);
        active.checkpoint.putEntry(
                active.channelKey, domainBlueId, subjectBlueId);
        active.checkpoint.entry(
                active.channelKey)
                .subject(storedSubject);
        active.lastEventNode =
                storedSubject.clone();
        active.lastEventSignature = subjectBlueId;
        record.lastEventNode =
                storedSubject.clone();
        record.lastEventSignature = subjectBlueId;
        identityCache.updateStoredIdentity(
                active.checkpoint,
                active.channelKey,
                subjectBlueId);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put(
                ProcessingTraceConstants.FIELD_DOMAIN,
                domainBlueId);
        details.put(
                ProcessingTraceConstants.FIELD_SUBJECT,
                subjectBlueId);
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
            details.put(
                    ProcessingTraceConstants.FIELD_ACTION,
                    ProcessingTraceConstants.ACTION_CLEANUP);
            if (entry != null) {
                details.put(
                        ProcessingTraceConstants.FIELD_OLD_DOMAIN,
                        entry.domainBlueId());
            }
            if (activeDomain != null) {
                details.put(
                        ProcessingTraceConstants.FIELD_ACTIVE_DOMAIN,
                        activeDomain);
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
