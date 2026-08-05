package blue.language.conformance.contracts;

import static blue.language.conformance.contracts.ContractsFixtureExecutionEngine.*;
import static blue.language.conformance.contracts.ContractsFixtureInputPreparer.*;
import static blue.language.conformance.contracts.ContractsFixtureProjectionExtractor.*;
import static blue.language.conformance.contracts.ContractsFixtureScriptedEnvironment.*;

import blue.language.model.wire.BlueLanguageConstants;

import blue.language.api.BlueCachePolicy;
import blue.language.runtime.BlueLanguageRuntime;
import blue.language.conformance.ConformanceEngine;
import blue.language.conformance.api.BlueContractsConformanceReport;
import blue.language.provider.NodeProvider;
import blue.language.registry.BootstrapProvider;
import blue.language.provider.SequentialNodeProvider;
import blue.language.provider.VerifiedNodeProvider;
import blue.language.conformance.ConformancePlan;
import blue.language.model.Node;
import blue.language.model.wire.JsonPointer;
import blue.language.processor.ConformanceChangedPath;
import blue.language.processor.ConformancePlannerOverride;
import blue.language.processor.ContractMatchingService;
import blue.language.processor.CheckpointDomain;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.EffectiveContractSnapshot;
import blue.language.processor.EffectiveContractSnapshotConstants;
import blue.language.processor.ExternalDeliveryPlan;
import blue.language.processor.ExternalDeliverySnapshot;
import blue.language.processor.ExternalChannelDependencySnapshot;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.GasSchedule;
import blue.language.processor.GasScheduleConstants;
import blue.language.processor.GasTraceEntry;
import blue.language.processor.ProcessAttemptResult;
import blue.language.processor.ProcessingConformanceTrace;
import blue.language.processor.ProcessingDebugResult;
import blue.language.processor.ProcessingSnapshotManager;
import blue.language.processor.ProcessingTraceConstants;
import blue.language.processor.ProcessingTraceRecord;
import blue.language.processor.PlatformCommitCompanion;
import blue.language.processor.ProcessorDiagnostic;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.SubscriptionDelta;
import blue.language.processor.VerifiedExecutionEvidence;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.processor.registry.BlueRuntimeTypeRegistry;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.processor.util.ProcessorPointerConstants;
import blue.language.snapshot.FrozenNode;
import blue.language.merge.ResolvedSnapshot;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.identity.BlueIds;
import blue.language.model.NodeWireForm;
import blue.language.codec.jackson.UncheckedObjectMapper;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;


/** Projects deterministic event, interval, gas, and embedded-declaration values. */
abstract class ContractsFixtureProjectionSupport extends ContractsFixtureFeederEnvironment {

    static List<Map<String, Object>> compactDeliveries(
            List<DerivedDelivery> deliveries) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (DerivedDelivery delivery : deliveries) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put(ContractsFixtureConstants.Field.SCOPE_PATH, delivery.snapshot.scopePath());
            row.put(ContractsFixtureConstants.Field.CHANNEL_KEY, delivery.snapshot.channelKey());
            result.add(row);
        }
        return result;
    }

    static List<Map<String, Object>> compactDeliveryHints(JsonNode hints) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (JsonNode hint : hints) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put(ContractsFixtureConstants.Field.SCOPE_PATH, hint.path(ContractsFixtureConstants.Field.SCOPE_PATH).asText());
            row.put(ContractsFixtureConstants.Field.CHANNEL_KEY, hint.path(ContractsFixtureConstants.Field.CHANNEL_KEY).asText());
            result.add(row);
        }
        return result;
    }

    static void applyMutableRootState(ObjectNode root,
                                              JsonNode state) {
        Iterator<Map.Entry<String, JsonNode>> fields = state.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String key = field.getKey();
            if (BlueLanguageConstants.OBJECT_BLUE_ID.equals(key)
                    || BlueLanguageConstants.OBJECT_TYPE.equals(key)
                    || ProcessorContractConstants.KEY_CONTRACTS.equals(key)) {
                throw new IllegalArgumentException(
                        "acceptanceStateVariants may change only mutable "
                                + "business state, not /" + key);
            }
            root.set(key, field.getValue().deepCopy());
        }
    }

    static boolean selectedChannelsAccept(
            ObjectNode root,
            List<DerivedDelivery> deliveries) {
        for (DerivedDelivery delivery : deliveries) {
            JsonNode scope =
                    jsonAt(root, delivery.snapshot.scopePath());
            JsonNode contract = scope == null
                    ? null
                    : scope.path(ProcessorContractConstants.KEY_CONTRACTS).get(
                    delivery.snapshot.channelKey());
            if (contract == null
                    || !contract.path(ContractsFixtureConstants.Field.ACCEPT).asBoolean(false)) {
                return false;
            }
        }
        return true;
    }

    static List<DerivedDelivery> filterRawIndexCandidates(
            JsonNode feeder,
            List<DerivedDelivery> deliveries,
            ContractsConformanceProjection projection) {
        JsonNode raw = feeder.get("rawIndexCandidates");
        if (raw == null) {
            return deliveries;
        }
        Set<String> candidates = new LinkedHashSet<>();
        for (JsonNode candidate : raw) {
            String path = candidate.asText();
            if (!path.startsWith("/")) {
                throw new IllegalArgumentException(
                        "rawIndexCandidates entries must be absolute "
                                + "Root pointers: " + path);
            }
            if (!candidates.add(path)) {
                throw new IllegalArgumentException(
                        "Duplicate rawIndexCandidates entry: " + path);
            }
        }

        List<DerivedDelivery> filtered = new ArrayList<>();
        for (DerivedDelivery delivery : deliveries) {
            if (candidates.contains(
                    delivery.snapshot.scopePath())) {
                filtered.add(delivery);
            }
        }
        if (filtered.size() != deliveries.size()) {
            projection.put(
                    "platform.status", "feeder-nonconformance");
        }
        return Collections.unmodifiableList(filtered);
    }

    /**
     * Models the feeder's retained-snapshot state machine. Targets are copied
     * when an event becomes the queue head and are completely drained before
     * the next event may be selected.
     */
    static List<String> drainExternalEventQueue(
            JsonNode eventQueue,
            JsonNode targetsByEvent) {
        Set<String> queuedIds = new LinkedHashSet<>();
        List<String> orderedEvents = new ArrayList<>();
        for (JsonNode event : eventQueue) {
            if (!event.isTextual()
                    || event.asText().isEmpty()) {
                throw new IllegalArgumentException(
                        "eventQueue entries must be non-empty event ids");
            }
            String eventId = event.asText();
            orderedEvents.add(eventId);
            queuedIds.add(eventId);
            if (!targetsByEvent.has(eventId)) {
                throw new IllegalArgumentException(
                        "targetsByEvent has no retained snapshot for "
                                + eventId);
            }
        }
        Iterator<String> targetIds =
                targetsByEvent.fieldNames();
        while (targetIds.hasNext()) {
            String eventId = targetIds.next();
            if (!queuedIds.contains(eventId)) {
                throw new IllegalArgumentException(
                        "targetsByEvent contains unqueued event "
                                + eventId);
            }
        }

        List<String> calls = new ArrayList<>();
        for (String eventId : orderedEvents) {
            List<String> retainedTargets = new ArrayList<>();
            for (JsonNode target : targetsByEvent.get(eventId)) {
                if (!target.isTextual()
                        || !target.asText().startsWith("/")) {
                    throw new IllegalArgumentException(
                            "Retained target for " + eventId
                                    + " must be an absolute Root pointer");
                }
                retainedTargets.add(target.asText());
            }
            for (String target : retainedTargets) {
                calls.add(eventId + ":" + target);
            }
        }
        return calls;
    }

    static List<String> deriveIntervals(JsonNode history,
                                                List<Object> eventOrder) {
        List<String> intervals = new ArrayList<>();
        int ordinal = 0;
        boolean active = false;
        for (JsonNode action : history) {
            String value = action.asText();
            if (value.startsWith("add-")) {
                active = true;
                intervals.add(value.substring(4)
                        + "@" + eventOrder + "#" + ordinal++);
            } else if (value.startsWith("remove-")) {
                active = false;
            } else {
                throw new IllegalArgumentException(
                        "Unknown interval-history action: " + value);
            }
        }
        if (!active && !intervals.isEmpty()) {
            // Closed intervals remain part of the deterministic history.
        }
        return intervals;
    }

    static List<Object> orderKeyValues(JsonNode node) {
        List<Object> result = new ArrayList<>();
        for (JsonNode value : node) {
            if (value.isIntegralNumber()) {
                result.add(value.bigIntegerValue());
            } else if (value.isTextual()) {
                result.add(value.asText());
            } else {
                throw new IllegalArgumentException(
                        "External order component must be Integer or Text");
            }
        }
        return result;
    }

    static ExternalOrderKey externalOrderKey(JsonNode node) {
        return ExternalOrderKey.of(orderKeyValues(node));
    }

    static List<Map<String, Object>> mutableMapList(
            ContractsConformanceProjection.Presence presence) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (!presence.isPresent() || !(presence.getValue() instanceof List)) {
            return result;
        }
        for (Object value : (List<?>) presence.getValue()) {
            if (value instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> map =
                        new LinkedHashMap<>((Map<String, Object>) value);
                result.add(map);
            }
        }
        return result;
    }

    static String lifecycleLabel(Node event) {
        String type = event != null && event.getType() != null
                ? event.getType().getBlueId()
                : null;
        if (registryId("DocumentProcessingInitiated").equals(type)) {
            return "initiated";
        }
        if (registryId("DocumentProcessingTerminated").equals(type)) {
            return ProcessorContractConstants.KEY_TERMINATED;
        }
        return "lifecycle";
    }

    static String eventLabel(Node event) {
        Node id = property(
                event,
                ProcessingTraceConstants.EVENT_LABEL_PROPERTY);
        if (id != null && id.getValue() != null) {
            return String.valueOf(id.getValue());
        }
        return event != null && event.getValue() != null
                ? String.valueOf(event.getValue())
                : null;
    }

    static String markerLabel(String key) {
        if (ProcessorContractConstants.KEY_INITIALIZED.equals(key)) {
            return "initialized-marker";
        }
        if (ProcessorContractConstants.KEY_TERMINATED.equals(key)) {
            return "terminated-marker";
        }
        return key;
    }

    static ProcessingTraceRecord firstMutation(
            List<ProcessingTraceRecord> records) {
        for (ProcessingTraceRecord record : records) {
            switch (record.kind()) {
                case MARKER_WRITE:
                case CHECKPOINT_WRITE:
                case CHECKPOINT_CLEANUP:
                case DOCUMENT_UPDATE:
                case TYPE_GENERALIZATION:
                    return record;
                default:
                    break;
            }
        }
        return null;
    }

    static boolean acceptedSnapshotFrozen(
            ProcessingConformanceTrace trace) {
        List<ProcessingTraceRecord> deliveries =
                trace.records(ProcessingTraceRecord.Kind.EXTERNAL_DELIVERY);
        for (ProcessingTraceRecord delivery : deliveries) {
            long initializationSequence = -1L;
            for (ProcessingTraceRecord record : trace.records()) {
                if (record.sequence() <= delivery.sequence()
                        || !Objects.equals(
                        delivery.scopePath(), record.scopePath())) {
                    continue;
                }
                if (record.kind() == ProcessingTraceRecord.Kind.LIFECYCLE
                        && "initiated".equals(lifecycleLabel(record.node()))) {
                    initializationSequence = record.sequence();
                    continue;
                }
                if (initializationSequence >= 0L
                        && record.sequence() > initializationSequence
                        && (record.kind()
                        == ProcessingTraceRecord.Kind.DOCUMENT_UPDATE
                        || ((record.kind()
                        == ProcessingTraceRecord.Kind.CHECKPOINT_COMPARE
                        || record.kind()
                        == ProcessingTraceRecord.Kind.CHECKPOINT_WRITE)
                        && Objects.equals(
                        delivery.contractKey(), record.contractKey())))) {
                    return true;
                }
            }
        }
        return false;
    }

    static long terminationEventCount(
            ProcessingConformanceTrace trace) {
        long count = 0L;
        for (ProcessingTraceRecord record :
                trace.records(ProcessingTraceRecord.Kind.LIFECYCLE)) {
            if (ProcessorContractConstants.KEY_TERMINATED.equals(
                    lifecycleLabel(record.node()))) {
                count++;
            }
        }
        return count;
    }

    static boolean processEmbeddedNonPathsUnchanged(
            Node before,
            Node after) {
        return semanticEquals(
                processEmbeddedWithoutPaths(before),
                processEmbeddedWithoutPaths(after));
    }

    static Map<String, Object> processEmbeddedWithoutPaths(
            Node root) {
        Node contracts = root != null ? root.getContracts() : null;
        Node embedded = property(
                contracts,
                ProcessorContractConstants.KEY_EMBEDDED);
        if (embedded == null) {
            return null;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> raw =
                (Map<String, Object>)
                        ContractsConformanceProjection.normalize(
                                embedded);
        Map<String, Object> withoutPaths =
                new LinkedHashMap<>(raw);
        withoutPaths.remove(ProcessorContractConstants.KEY_PATHS);
        withoutPaths.remove(
                ProcessorContractConstants.KEY_COLLECTION_PATHS);
        return withoutPaths;
    }

    /**
     * Projects one Process Embedded declaration field when it is present in
     * the resulting document. Both declaration lists are public fixture
     * observables, while absence remains distinguishable from an empty list.
     */
    static void projectEmbeddedDeclaration(
            ContractsConformanceProjection projection,
            Node embedded,
            String field) {
        Node value = property(embedded, field);
        if (value == null) {
            return;
        }
        projection.put(
                "result.document.contracts.embedded." + field,
                NodeWireForm.get(value, NodeWireForm.Strategy.SIMPLE));
    }

    static Object normalizeNode(Node node) {
        return node == null
                ? null
                : ContractsConformanceProjection.normalize(node);
    }

    static Node property(Node node, String key) {
        return node != null && node.getProperties() != null
                ? node.getProperties().get(key)
                : null;
    }

    static String occurrence(String scope, String key) {
        return scope + ":" + key;
    }

    static int scopeDepth(String scope) {
        if ("/".equals(scope)) {
            return 0;
        }
        int depth = 0;
        for (int index = 0; index < scope.length(); index++) {
            if (scope.charAt(index) == '/') {
                depth++;
            }
        }
        return depth;
    }

    static String resolveScope(String scope, String relative) {
        if (relative == null || !relative.startsWith("/")) {
            throw new IllegalArgumentException(
                    "Embedded path must be an absolute relative pointer");
        }
        return "/".equals(scope) ? relative : scope + relative;
    }

}
