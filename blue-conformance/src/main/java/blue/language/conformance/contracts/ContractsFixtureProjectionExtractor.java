package blue.language.conformance.contracts;

import static blue.language.conformance.contracts.ContractsFixtureExecutionEngine.*;
import static blue.language.conformance.contracts.ContractsFixtureInputPreparer.*;
import static blue.language.conformance.contracts.ContractsFixtureProjectionSupport.*;
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


/** Extracts presence-aware observable projections from processor results. */
abstract class ContractsFixtureProjectionExtractor extends ContractsFixtureScriptedEnvironment {

    ContractsConformanceProjection projectProcess(
            PreparedInput input,
            ProcessExecution execution) {
        DocumentProcessingResult result = execution.result;
        ProcessingConformanceTrace trace = execution.trace;
        ContractsConformanceProjection projection =
                new ContractsConformanceProjection()
                        .put("input.root", input.root)
                        .put(ContractsFixtureConstants.Field.RESULT, publicResult(result))
                        .put("result.status", result.status().wireValue())
                        .put("result.document", result.document())
                        .put("result.documentBlueId",
                                DirectBlueIdCalculator.calculateBlueId(
                                        result.document()))
                        .put("result.events", result.events())
                        .put("result.totalGas", result.totalGas())
                        .put("demands.semantic", trace.semanticDemands())
                        .put(
                                ContractsFixtureConstants.Projection
                                        .TRACE_NAMED_ENTRIES,
                                gasEntries(trace.gas(), true))
                        .put("trace.gas", gasEntries(trace.gas(), true))
                        .put("trace.failedChargePresent", false)
                        .put("trace.total", "sum(entries)")
                        .put("commit.intermediateVisible", false)
                        .put("commit.rootCasCount", result.commits() ? 1L : 0L)
                        .put("commit.rootCommitted", result.commits())
                        .put("commit.outboxCommitted", result.commits())
                        .put("commit.progressCommitted", result.commits())
                        .put("commit.progressWritten", result.commits())
                        .put("commit.casWorkPortableGas", 0L);
        projectDirectContractIdentity(
                projection,
                "input.root.contracts.initialized.directBlueId",
                input.root,
                ProcessorContractConstants.KEY_INITIALIZED);
        projectDirectContractIdentity(
                projection,
                "result.document.contracts.initialized.directBlueId",
                result.document(),
                ProcessorContractConstants.KEY_INITIALIZED);
        Node embedded = property(
                result.document().getContracts(),
                ProcessorContractConstants.KEY_EMBEDDED);
        projectEmbeddedDeclaration(
                projection,
                embedded,
                ProcessorContractConstants.KEY_PATHS);
        projectEmbeddedDeclaration(
                projection,
                embedded,
                ProcessorContractConstants.KEY_COLLECTION_PATHS);
        ProcessorDiagnostic diagnostic = result.diagnostic();
        if (diagnostic != null) {
            projection.put("result.diagnostic.category",
                    diagnostic.category().name());
        }
        projectCounters(trace, projection);
        projectRecords(input, execution, projection);
        projectContractSnapshots(trace, projection);
        projectEventTrace(trace, projection);
        projectChangedSpines(execution, projection);
        projectGeneralization(execution, projection);
        PlatformCommitCompanion companion =
                execution.platformCommitCompanion;
        if (result.commits()
                && companion != null
                && !companion.subscriptionDelta().isEmpty()) {
            projection.put(
                    "commit.subscriptionDelta.mode",
                    "incremental");
            projection.put(
                    "commit.newIntervals",
                    projectSubscriptionIntervals(
                            companion.subscriptionDelta().added()));
            projection.put(
                    "commit.retiredIntervals",
                    projectSubscriptionIntervals(
                            companion.subscriptionDelta().removed()));
        }

        long weighted = 0L;
        for (GasTraceEntry entry : trace.gas()) {
            weighted = Math.addExact(weighted, entry.subtotal());
        }
        if (weighted != result.totalGas()) {
            throw new AssertionError(
                    "Canonical gas trace total " + weighted
                            + " does not equal ProcessResult.totalGas "
                            + result.totalGas());
        }
        return projection;
    }

    private static void projectDirectContractIdentity(
            ContractsConformanceProjection projection,
            String path,
            Node document,
            String contractKey) {
        Node contract = property(
                document != null ? document.getContracts() : null,
                contractKey);
        if (contract != null) {
            projection.put(path,
                    DirectBlueIdCalculator.calculateBlueId(contract));
        }
    }

    List<Map<String, Object>> projectSubscriptionIntervals(
            List<SubscriptionDelta.Entry> intervals) {
        List<Map<String, Object>> result =
                new ArrayList<>();
        for (SubscriptionDelta.Entry interval : intervals) {
            Map<String, Object> projected =
                    new LinkedHashMap<>();
            projected.put(ContractsFixtureConstants.Field.SCOPE_PATH, interval.scopePath());
            projected.put(ContractsFixtureConstants.Field.CHANNEL_KEY, interval.channelKey());
            projected.put(
                    "effectiveTypeBlueId",
                    interval.effectiveTypeBlueId());
            projected.put(
                    "orderedSourceContributionNodeBlueIds",
                    interval.sourceContributionNodeBlueIds());
            projected.put(ContractsFixtureConstants.Field.ORDER, interval.order());
            projected.put(
                    ProcessorContractConstants.KEY_SUBSCRIPTION_KEYS,
                    interval.subscriptionKeys());
            projected.put(
                    "checkpointDomainBlueId",
                    interval.checkpointDomainBlueId());
            if (interval.activationRootRevision() != null) {
                projected.put(
                        "activationRootRevision",
                        interval.activationRootRevision());
            }
            if (interval.startAfterExternalOrderKey() != null) {
                projected.put(
                        "startAfterExternalOrderKey",
                        interval.startAfterExternalOrderKey()
                                .components());
            }
            if (interval.endAtRootRevision() != null) {
                projected.put(
                        "endAtRootRevision",
                        interval.endAtRootRevision());
            }
            result.add(projected);
        }
        return Collections.unmodifiableList(result);
    }

    void projectGeneralization(
            ProcessExecution execution,
            ContractsConformanceProjection projection) {
        FixtureGeneralizationPlanner planner =
                execution.generalization;
        if (planner == null || planner.selected() == null) {
            return;
        }
        projection.put(
                "trace.generalizationSelected",
                planner.selected());
        projection.put(
                "trace.generalizationTestOrder",
                planner.tested());

        boolean typeUpdate = false;
        for (ProcessingTraceRecord record :
                execution.trace.records(
                        ProcessingTraceRecord.Kind.DOCUMENT_UPDATE)) {
            if (ProcessorPointerConstants.RELATIVE_TYPE.equals(
                    record.logicalPath())) {
                typeUpdate = true;
                break;
            }
        }
        projection.put(
                "trace.reRecognitionAfterGeneralization",
                typeUpdate
                        && !execution.trace
                        .contractSnapshots().isEmpty());
    }

    void projectCounters(ProcessingConformanceTrace trace,
                                 ContractsConformanceProjection projection) {
        projection.put("trace.counters.contractHeaderRecognized",
                trace.counterQuantity(
                        GasScheduleConstants.Namespace.PROCESSOR,
                        GasScheduleConstants.ProcessorCounter
                                .CONTRACT_HEADER_RECOGNIZED));
        projection.put("trace.counters.channelCandidateTested",
                trace.counterQuantity(
                        GasScheduleConstants.Namespace.PROCESSOR,
                        GasScheduleConstants.ProcessorCounter
                                .CHANNEL_CANDIDATE_TESTED));
        projection.put("trace.counters.embeddedPathEntryRead",
                trace.counterQuantity(
                        GasScheduleConstants.Namespace.PROCESSOR,
                        GasScheduleConstants.ProcessorCounter
                                .EMBEDDED_PATH_ENTRY_READ));
        projection.put("trace.counters.embeddedPathSegmentValidated",
                trace.counterQuantity(
                        GasScheduleConstants.Namespace.PROCESSOR,
                        GasScheduleConstants.ProcessorCounter
                                .EMBEDDED_PATH_SEGMENT_VALIDATED));
        projection.put("trace.counters.embeddedEventDelivered",
                trace.counterQuantity(
                        GasScheduleConstants.Namespace.PROCESSOR,
                        GasScheduleConstants.ProcessorCounter
                                .EMBEDDED_EVENT_DELIVERED));
        projection.put("trace.counters.directIdentityHashBlock",
                trace.counterQuantity(
                        GasScheduleConstants.Namespace.SEMANTIC,
                        GasScheduleConstants.SemanticCounter
                                .DIRECT_IDENTITY_HASH_BLOCK));
        projection.put("trace.counters.textBlockExamined",
                trace.counterQuantity(
                        GasScheduleConstants.Namespace.SEMANTIC,
                        GasScheduleConstants.SemanticCounter
                                .TEXT_BLOCK_EXAMINED));
        projection.put("trace.semantic.nodeIdentityEstablished",
                trace.counterQuantity(
                        GasScheduleConstants.Namespace.SEMANTIC,
                        GasScheduleConstants.SemanticCounter
                                .NODE_IDENTITY_ESTABLISHED));
        projection.put("trace.runtime.textBlockConstructed",
                trace.counterQuantity(
                        ContractsFixtureConstants.RuntimeNamespace.RUNTIME,
                        GasScheduleConstants.SemanticCounter
                                .TEXT_BLOCK_CONSTRUCTED));
    }

    void projectRecords(PreparedInput input,
                                ProcessExecution execution,
                                ContractsConformanceProjection projection) {
        ProcessingConformanceTrace trace = execution.trace;
        List<String> external = new ArrayList<>();
        for (ProcessingTraceRecord record :
                trace.records(ProcessingTraceRecord.Kind.EXTERNAL_DELIVERY)) {
            external.add(occurrence(record.scopePath(), record.contractKey()));
        }
        projection.put("trace.externalDeliveryOrder", external);

        List<Map<String, Object>> updates = new ArrayList<>();
        List<String> updateScopes = new ArrayList<>();
        for (ProcessingTraceRecord record :
                trace.records(ProcessingTraceRecord.Kind.DOCUMENT_UPDATE)) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("path", record.logicalPath());
            value.put(ContractsFixtureConstants.Field.SCOPE_PATH, record.scopePath());
            value.put("beforePresent",
                    Boolean.valueOf(record.detail(
                            ProcessingTraceConstants
                                    .FIELD_BEFORE_PRESENT)));
            value.put("afterPresent",
                    Boolean.valueOf(record.detail(
                            ProcessingTraceConstants
                                    .FIELD_AFTER_PRESENT)));
            updates.add(value);
            updateScopes.add(record.scopePath());
        }
        projection.put("trace.documentUpdates", updates);
        projection.put("trace.documentUpdateScopes", updateScopes);

        List<String> markerWrites = new ArrayList<>();
        List<String> lifecycle = new ArrayList<>();
        Set<String> lifecycleScopes = new LinkedHashSet<>();
        String initialDocumentBlueId = null;
        for (ProcessingTraceRecord record :
                trace.records(ProcessingTraceRecord.Kind.LIFECYCLE)) {
            lifecycleScopes.add(record.scopePath());
        }
        boolean scopedLifecycle = lifecycleScopes.size() > 1;
        for (ProcessingTraceRecord record : trace.records()) {
            if (record.kind() == ProcessingTraceRecord.Kind.LIFECYCLE) {
                String label = lifecycleLabel(record.node());
                lifecycle.add(scopedLifecycle
                        ? record.scopePath() + ":" + label
                        : label);
                if (initialDocumentBlueId == null
                        && "initiated".equals(label)) {
                    Node initialDocument =
                            property(
                                    record.node(),
                                    "document");
                    if (initialDocument != null) {
                        initialDocumentBlueId =
                                initialDocument
                                        .isReferenceOnly()
                                        ? initialDocument
                                        .getBlueId()
                                        : DirectBlueIdCalculator
                                        .calculateBlueId(
                                                initialDocument);
                    }
                }
            } else if (record.kind() ==
                    ProcessingTraceRecord.Kind.MARKER_WRITE) {
                String marker = markerLabel(record.contractKey());
                markerWrites.add(record.scopePath() + ":" + marker);
                if ("initialized-marker".equals(marker)) {
                    lifecycle.add(scopedLifecycle
                            ? record.scopePath() + ":initialized"
                            : marker);
                }
            }
        }
        projection.put("trace.lifecycleOrder", lifecycle);
        projection.put("trace.markerWrites", markerWrites);
        if (initialDocumentBlueId != null) {
            projection.put(
                    "trace.initialDocumentBlueId",
                    initialDocumentBlueId);
        }

        List<String> checkpointWrites = new ArrayList<>();
        for (ProcessingTraceRecord record :
                trace.records(ProcessingTraceRecord.Kind.CHECKPOINT_WRITE)) {
            checkpointWrites.add(record.scopePath());
        }
        projection.put("trace.checkpointWrites", checkpointWrites);

        List<String> sourceCheckpointKeys =
                new ArrayList<>();
        for (ProcessingTraceRecord record :
                trace.records(
                        ProcessingTraceRecord.Kind
                                .CHECKPOINT_WRITE)) {
            if (!ProcessingTraceConstants.ACTION_CLEANUP.equals(
                    record.detail(
                            ProcessingTraceConstants.FIELD_ACTION))) {
                sourceCheckpointKeys.add(
                        record.contractKey());
            }
        }
        projection.put(
                "trace.sourceCheckpointKeys",
                sourceCheckpointKeys);

        List<String> channelLookupResults =
                new ArrayList<>();
        for (ProcessingTraceRecord record :
                trace.records(
                        ProcessingTraceRecord.Kind
                                .CHANNEL_LOOKUP)) {
            channelLookupResults.add(
                    record.detail(
                            ProcessingTraceConstants.FIELD_RESULT));
        }
        projection.put(
                "trace.channelLookupResults",
                channelLookupResults);

        List<String> handlerChannelKeys =
                new ArrayList<>();
        List<String> logicalDeliveryGroups =
                new ArrayList<>();
        for (ProcessingTraceRecord record :
                trace.records(
                        ProcessingTraceRecord.Kind
                                .LOGICAL_DELIVERY_GROUP)) {
            handlerChannelKeys.add(
                    record.detail(
                            ProcessingTraceConstants
                                    .FIELD_HANDLER_CHANNEL_KEY));
            int sourceCount = Integer.parseInt(
                    record.detail(
                            ProcessingTraceConstants.FIELD_SOURCE_COUNT));
            StringBuilder group =
                    new StringBuilder()
                            .append(record.scopePath())
                            .append(':')
                            .append(record.detail(
                                    ProcessingTraceConstants
                                            .FIELD_LOGICAL_DELIVERY_KEY))
                            .append(":[");
            for (int index = 0;
                 index < sourceCount;
                 index++) {
                if (index > 0) {
                    group.append(',');
                }
                group.append(record.detail(
                        ProcessingTraceConstants.sourceField(
                                index)));
            }
            logicalDeliveryGroups.add(
                    group.append(']').toString());
        }
        projection.put(
                "trace.handlerChannelKeys",
                handlerChannelKeys);
        projection.put(
                "trace.logicalDeliveryGroups",
                logicalDeliveryGroups);
        List<String> handlerExecutionOrder = new ArrayList<>();
        for (ProcessingTraceRecord record : trace.records(
                ProcessingTraceRecord.Kind.HANDLER_EXECUTION)) {
            handlerExecutionOrder.add(
                    record.scopePath() + ":" + record.contractKey()
                            + ":" + record.detail(
                            ProcessingTraceConstants.FIELD_CHANNEL_KEY));
        }
        projection.put("trace.handlerExecutionOrder",
                handlerExecutionOrder);
        projection.put("trace.handlerExecutionCount",
                (long) handlerExecutionOrder.size());

        List<String> checkpointCleanup = new ArrayList<>();
        for (ProcessingTraceRecord record : trace.records()) {
            if (record.kind() == ProcessingTraceRecord.Kind.CHECKPOINT_CLEANUP
                    || (record.kind()
                    == ProcessingTraceRecord.Kind.CHECKPOINT_WRITE
                    && ProcessingTraceConstants.ACTION_CLEANUP.equals(
                    record.detail(
                            ProcessingTraceConstants.FIELD_ACTION)))) {
                checkpointCleanup.add(record.contractKey());
            }
        }
        projection.put("trace.checkpointCleanupKeys", checkpointCleanup);

        boolean newDomain = false;
        for (ProcessingTraceRecord record :
                trace.records(ProcessingTraceRecord.Kind.CHECKPOINT_COMPARE)) {
            if ("false".equals(record.detail(
                    ProcessingTraceConstants.FIELD_DOMAIN_MATCHES))) {
                newDomain = true;
            }
        }
        if (newDomain) {
            projection.put("trace.checkpointNewness", "new-domain");
        }

        List<String> order = new ArrayList<>();
        for (ProcessingTraceRecord record : trace.records()) {
            switch (record.kind()) {
                case CHECKPOINT_COMPARE:
                    order.add("checkpoint-compare");
                    break;
                case LIFECYCLE:
                    if (!order.contains("initialization")) {
                        order.add("initialization");
                    }
                    break;
                case DOCUMENT_UPDATE:
                    if (!order.contains("patch")) {
                        order.add("patch");
                    }
                    break;
                case EVENT_DEQUEUED:
                    if (!order.contains("event-drain")) {
                        order.add("event-drain");
                    }
                    break;
                case CHECKPOINT_WRITE:
                    order.add("checkpoint-write");
                    break;
                default:
                    break;
            }
        }
        projection.put("trace.order", order);

        List<String> discarded = new ArrayList<>();
        for (ProcessingTraceRecord record :
                trace.records(ProcessingTraceRecord.Kind.DISCARDED_EFFECT)) {
            String label = record.detail(
                    ProcessingTraceConstants.FIELD_LABEL);
            discarded.add(label != null ? label : record.logicalPath());
        }
        projection.put("trace.discardedEffects", discarded);
        if (!trace.records().isEmpty()) {
            ProcessingTraceRecord first = firstMutation(trace.records());
            if (first != null) {
                projection.put("trace.firstMutation",
                        first.kind().name().toLowerCase());
            }
        }

        projection.put("trace.acceptedChannelSnapshot.usedAfterInitialization",
                acceptedSnapshotFrozen(trace));
        projection.put("trace.protectedState.nonPathsUnchanged",
                processEmbeddedNonPathsUnchanged(
                        input.root,
                        execution.result.document()));
        projection.put("trace.terminationEvents",
                terminationEventCount(trace));
    }

    void projectContractSnapshots(ProcessingConformanceTrace trace,
                                          ContractsConformanceProjection projection) {
        for (EffectiveContractSnapshot snapshot :
                trace.contractSnapshots().values()) {
            if ("/".equals(snapshot.scopePath()) && "h".equals(snapshot.key())) {
                projection.put(
                        "trace.contractSnapshots./h.sourceContributionNodeBlueIds",
                        snapshot.sourceContributionNodeBlueIds());
            }
        }
    }

    void projectEventTrace(ProcessingConformanceTrace trace,
                                   ContractsConformanceProjection projection) {
        List<String> deliveryOrder = new ArrayList<>();
        List<String> occurrenceOrder = new ArrayList<>();
        Set<String> drainOwners = new LinkedHashSet<>();
        String currentOccurrenceLabel = null;
        for (ProcessingTraceRecord record : trace.records()) {
            if (record.kind() == ProcessingTraceRecord.Kind.EVENT_DEQUEUED) {
                currentOccurrenceLabel = traceEventLabel(record);
                occurrenceOrder.add(currentOccurrenceLabel);
                String owner = record.detail(
                        ProcessingTraceConstants.FIELD_DRAIN_OWNER);
                if (owner != null) {
                    drainOwners.add(owner);
                }
            } else if (record.kind()
                    == ProcessingTraceRecord.Kind.EVENT_DELIVERED) {
                String mode = record.detail(
                        ProcessingTraceConstants.FIELD_MODE);
                String label = traceEventLabel(record);
                /*
                 * An Embedded delivery record deliberately retains the exact
                 * EmbeddedEventDelivery wrapper passed to the ancestor
                 * handler. The human-readable delivery-order projection,
                 * however, names the underlying FIFO occurrence. Carry the
                 * label established by the immediately preceding dequeue
                 * rather than treating the wrapper's event reference as an
                 * unlabeled new event.
                 */
                if (label == null) {
                    label = currentOccurrenceLabel;
                }
                deliveryOrder.add(record.scopePath() + ":"
                        + (mode != null
                        ? mode
                        : ProcessingTraceConstants.DEFAULT_EVENT_LABEL)
                        + ":" + label);
            }
        }
        projection.put("trace.eventOccurrenceOrder", occurrenceOrder);
        projection.put("trace.eventDeliveryOrder", deliveryOrder);
        projection.put("trace.eventOccurrencesDequeued",
                (long) trace.records(
                        ProcessingTraceRecord.Kind.EVENT_DEQUEUED).size());
        projection.put("trace.queueDrainOwners", (long) drainOwners.size());

        long childExecutions = 0L;
        for (ProcessingTraceRecord record :
                trace.records(ProcessingTraceRecord.Kind.LIFECYCLE)) {
            if ("/child".equals(record.scopePath())
                    && "initiated".equals(lifecycleLabel(record.node()))) {
                childExecutions++;
            }
        }
        projection.put("trace.scopeExecutions./child", childExecutions);
    }

    static String traceEventLabel(ProcessingTraceRecord record) {
        String label = record.detail(
                ProcessingTraceConstants.FIELD_EVENT);
        if (label == null) {
            label = record.detail(
                    ProcessingTraceConstants.FIELD_EVENT_LABEL);
        }
        if (label == null) {
            label = eventLabel(record.node());
        }
        return label;
    }

    void projectChangedSpines(ProcessExecution execution,
                                      ContractsConformanceProjection projection) {
        List<String> paths = new ArrayList<>();
        for (ProcessingTraceRecord record :
                execution.trace.records(ProcessingTraceRecord.Kind.DOCUMENT_UPDATE)) {
            if (record.logicalPath() == null) {
                continue;
            }
            String current = record.logicalPath();
            if (!paths.contains(current)) {
                paths.add(current);
            }
            while (!"/".equals(current)) {
                int slash = current.lastIndexOf('/');
                current = slash <= 0 ? "/" : current.substring(0, slash);
                if (!paths.contains(current)) {
                    paths.add(current);
                }
            }
        }
        projection.put("trace.validatedPaths", paths);
    }

    void addCompositeGasAudit(
            ContractsConformanceProjection projection,
            ProcessingConformanceTrace trace,
            boolean completeCounterCoverage) {
        projection.put(
                ContractsFixtureConstants.Projection
                        .MANIFEST_COUNTER_COVERAGE_COMPLETE,
                completeCounterCoverage);

        projection.put("trace.nodeManifestOpened.sameId",
                trace.counterQuantity(
                        GasScheduleConstants.Namespace.SEMANTIC,
                        GasScheduleConstants.SemanticCounter
                                .NODE_MANIFEST_OPENED));
        projection.put("trace.validationProofReused",
                trace.counterQuantity(
                        GasScheduleConstants.Namespace.SEMANTIC,
                        GasScheduleConstants.SemanticCounter
                                .VALIDATION_PROOF_REUSED));
        projection.put("trace.textBlockExamined",
                trace.counterQuantity(
                        GasScheduleConstants.Namespace.SEMANTIC,
                        GasScheduleConstants.SemanticCounter
                                .TEXT_BLOCK_EXAMINED));
        projection.put("trace.integerLimbOperation",
                trace.counterQuantity(
                        GasScheduleConstants.Namespace.SEMANTIC,
                        GasScheduleConstants.SemanticCounter
                                .INTEGER_LIMB_OPERATION));
        projection.put("trace.sortComparison",
                trace.counterQuantity(
                        GasScheduleConstants.Namespace.SEMANTIC,
                        GasScheduleConstants.SemanticCounter
                                .SORT_COMPARISON));
        projection.put("trace.directIdentityHashBlock.changedDirectOnly",
                trace.counterQuantity(
                        GasScheduleConstants.Namespace.SEMANTIC,
                        GasScheduleConstants.SemanticCounter
                                .DIRECT_IDENTITY_HASH_BLOCK) > 0L);

        long runtimeEntries = 0L;
        for (GasTraceEntry entry : trace.gas()) {
            if (ContractsFixtureConstants.RuntimeNamespace.RUNTIME.equals(
                    entry.namespace())) {
                runtimeEntries++;
            }
        }
        projection.put("trace.runtimeChildChargesLiveBounded",
                runtimeEntries > 0L);
        projection.put("trace.runtimeChildMergedCount",
                runtimeEntries > 0L ? 1L : 0L);

        Set<String> names = gasSchedule.qualifiedCounters();
        boolean recursive = false;
        for (String name : names) {
            String normalized = name.toLowerCase();
            if (normalized.contains("recursive")
                    || normalized.contains("serializedsize")
                    || normalized.contains("referencestate")) {
                recursive = true;
            }
        }
        projection.put("runtime.referenceStateObservable", false);
        projection.put("runtime.recursiveSizeCounterPresent", recursive);
        projection.put("trace.providerTransportCounters",
                counterPrefixQuantity(projection, "providerTransport"));
        projection.put("trace.providerVerificationCounters",
                counterPrefixQuantity(projection, "providerVerification"));
    }

    static long counterPrefixQuantity(
            ContractsConformanceProjection projection,
            String prefix) {
        ContractsConformanceProjection.Presence gas =
                projection.project(
                        ContractsFixtureConstants.Projection
                                .TRACE_NAMED_ENTRIES);
        if (!gas.isPresent() || !(gas.getValue() instanceof List)) {
            return 0L;
        }
        long total = 0L;
        for (Object entry : (List<?>) gas.getValue()) {
            if (!(entry instanceof Map)) {
                continue;
            }
            Object counter = ((Map<?, ?>) entry).get(ContractsFixtureConstants.Field.COUNTER);
            Object quantity = ((Map<?, ?>) entry).get(ContractsFixtureConstants.Field.QUANTITY);
            if (counter != null
                    && String.valueOf(counter).startsWith(prefix)
                    && quantity instanceof Number) {
                total += ((Number) quantity).longValue();
            }
        }
        return total;
    }

    static Map<String, Object> publicResult(
            DocumentProcessingResult result) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("status", result.status().wireValue());
        value.put("document", NodeWireForm.get(result.document()));
        List<Object> events = new ArrayList<>();
        for (Node event : result.events()) {
            events.add(NodeWireForm.get(event));
        }
        value.put(ContractsFixtureConstants.Field.EVENTS, events);
        value.put(ContractsFixtureConstants.Field.TOTAL_GAS, result.totalGas());
        if (result.diagnostic() != null) {
            Map<String, Object> diagnostic = new LinkedHashMap<>();
            diagnostic.put(ContractsFixtureConstants.Field.CATEGORY,
                    result.diagnostic().category().name());
            if (result.diagnostic().message() != null) {
                diagnostic.put("message", result.diagnostic().message());
            }
            if (!result.diagnostic().details().isEmpty()) {
                diagnostic.put("details", result.diagnostic().details());
            }
            value.put("diagnostic", diagnostic);
        }
        return value;
    }

    static List<Map<String, Object>> gasEntries(
            List<GasTraceEntry> entries,
            boolean omitSequence) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (GasTraceEntry entry : entries) {
            Map<String, Object> value = new LinkedHashMap<>();
            if (!omitSequence) {
                value.put(ContractsFixtureConstants.Field.SEQUENCE, entry.sequence());
            }
            value.put(ContractsFixtureConstants.Field.NAMESPACE, entry.namespace());
            value.put(ContractsFixtureConstants.Field.COUNTER, entry.counter());
            value.put(ContractsFixtureConstants.Field.QUANTITY, entry.quantity());
            value.put(ContractsFixtureConstants.Field.WEIGHT, entry.weight());
            value.put(ContractsFixtureConstants.Field.SUBTOTAL, entry.subtotal());
            if (entry.scopePath() != null) {
                value.put(ContractsFixtureConstants.Field.SCOPE_PATH, entry.scopePath());
            }
            if (entry.contractKey() != null) {
                value.put(ContractsFixtureConstants.Field.CONTRACT_KEY, entry.contractKey());
            }
            if (entry.logicalPath() != null) {
                value.put(ContractsFixtureConstants.Field.LOGICAL_PATH, entry.logicalPath());
            }
            if (entry.reason() != null
                    && !entry.reason().isEmpty()
                    && !"unspecified".equals(entry.reason())) {
                value.put(ContractsFixtureConstants.Field.REASON, entry.reason());
            }
            result.add(value);
        }
        return result;
    }

    static Map<String, Object> gasCounterTree(
            List<GasTraceEntry> entries) {
        Map<String, Object> trace = new LinkedHashMap<>();
        for (GasTraceEntry entry : entries) {
            @SuppressWarnings("unchecked")
            Map<String, Object> namespace =
                    (Map<String, Object>) trace.computeIfAbsent(
                            entry.namespace(), ignored -> new LinkedHashMap<>());
            long previous = namespace.containsKey(entry.counter())
                    ? ((Number) namespace.get(entry.counter())).longValue()
                    : 0L;
            namespace.put(entry.counter(), previous + entry.quantity());
        }
        return trace;
    }

    static String requiredSelectedBodyBlueId(
            ObjectNode root,
            List<DerivedDelivery> deliveries,
            String unavailableAt) {
        if (!"SelectedBody".equals(unavailableAt)
                && unavailableAt.length() >= 32) {
            return unavailableAt;
        }
        for (DerivedDelivery delivery : deliveries) {
            JsonNode scope = jsonAt(root, delivery.snapshot.scopePath());
            JsonNode contracts = scope != null
                    ? scope.get(ProcessorContractConstants.KEY_CONTRACTS)
                    : null;
            if (contracts == null || !contracts.isObject()) {
                continue;
            }
            Iterator<Map.Entry<String, JsonNode>> fields = contracts.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                JsonNode contract = entry.getValue();
                if (!MockTypeBlueIds.MOCK_HANDLER.equals(
                        contract.path(BlueLanguageConstants.OBJECT_TYPE).path(BlueLanguageConstants.OBJECT_BLUE_ID).asText(null))) {
                    continue;
                }
                if (!delivery.snapshot.channelKey().equals(
                        contract.path("channel").asText(null))) {
                    continue;
                }
                JsonNode result = contract.get(ContractsFixtureConstants.Field.RESULT);
                if (result != null) {
                    return DirectBlueIdCalculator.calculateBlueId(readNode(result));
                }
            }
        }
        throw new IllegalArgumentException(
                "transientUnavailableAt did not identify a selected exact body");
    }

}
