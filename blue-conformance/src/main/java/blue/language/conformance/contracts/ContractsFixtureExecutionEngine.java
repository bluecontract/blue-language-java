package blue.language.conformance.contracts;

import static blue.language.conformance.contracts.ContractsFixtureInputPreparer.*;
import static blue.language.conformance.contracts.ContractsFixtureProjectionExtractor.*;
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


/** Executes one prepared fixture operation and its representation variants. */
abstract class ContractsFixtureExecutionEngine extends ContractsFixtureProjectionExtractor {

    static BlueLanguageRuntime languageRuntime(
            NodeProvider nodeProvider) {
        NodeProvider processorLanguageProvider =
                new SequentialNodeProvider(
                        BootstrapProvider.INSTANCE,
                        new VerifiedNodeProvider(
                                BlueRuntimeTypeRegistry.getDefault()
                                        .asProcessorSnapshotProvider()),
                        nodeProvider);
        return BlueLanguageRuntime.create(
                processorLanguageProvider,
                BlueCachePolicy.boundedDefaults(),
                Collections.emptyMap(),
                blueId -> !BlueRuntimeTypeRegistry.getDefault()
                        .isProcessorManagedTypeBlueId(blueId));
    }

    ContractsConformanceProjection executeStandaloneGas(
            JsonNode fixture,
            boolean completeCounterCoverage) {
        ContractsGasSchedule.GasMicroResult actual =
                gasSchedule.evaluate(fixture, completeCounterCoverage);
        return actual.projection()
                .put(ContractsFixtureConstants.Projection.GAS_TRACE,
                        actual.trace())
                .put(ContractsFixtureConstants.Projection.GAS_TOTAL,
                        actual.totalGas())
                .put(ContractsFixtureConstants.Projection.GAS_ADMITTED,
                        actual.admitted())
                .put(
                        ContractsFixtureConstants.Projection
                                .GAS_FAILED_CHARGE_ABSENT,
                        actual.failedChargeAbsent())
                .put(
                        ContractsFixtureConstants.Projection
                                .GAS_LIST_FOLD_STEP_RECOMPUTED,
                        actual.listFoldStepRecomputed())
                .put(
                        ContractsFixtureConstants.Projection
                                .GAS_TEXT_BLOCK_EXAMINED,
                        actual.textBlockExamined())
                .put(
                        ContractsFixtureConstants.Projection
                                .GAS_VALIDATION_PROOF_REUSED,
                        actual.validationProofReused())
                .put(
                        ContractsFixtureConstants.Projection
                                .GAS_DIRECT_IDENTITY_HASH_BLOCK,
                        actual.directIdentityHashBlock())
                .put(
                        ContractsFixtureConstants.Projection
                                .GAS_INTEGER_LIMB_OPERATION,
                        actual.integerLimbOperation());
    }

    ContractsConformanceProjection executeProcess(JsonNode fixture,
                                                          PreparedInput input) {
        ProcessExecution execution = runProcess(input);
        ContractsConformanceProjection projection =
                projectProcess(input, execution);
        if (fixture.path(ContractsFixtureConstants.Field.INPUT)
                .path(ContractsFixtureConstants.Field.FEEDER)
                .path("casConflict").asBoolean(false)) {
            projection.put("commit.rootCommitted", false)
                    .put("commit.outboxCommitted", false)
                    .put("commit.progressCommitted", false)
                    .put("commit.progressWritten", false)
                    .put("commit.casWorkPortableGas", 0L);
        }
        if (execution.result.status()
                == ProcessorStatus.GAS_LIMIT_EXCEEDED) {
            ProcessExecution retry = runProcess(input);
            Object originalTrace = canonicalAttemptTrace(execution);
            Object retryTrace = canonicalAttemptTrace(retry);
            projection.put(
                    "retry.trace",
                    ContractsAssertionEvaluator.deepEquals(
                            originalTrace, retryTrace)
                            ? ContractsFixtureConstants.ProjectionValue
                                    .RETRY_MATCHES_ORIGINAL_TRACE
                            : retryTrace);
        }
        return projection;
    }

    static Map<String, Object> canonicalAttemptTrace(
            ProcessExecution execution) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", execution.result.status().wireValue());
        result.put("gas", gasEntries(execution.trace.gas(), false));
        result.put("semanticDemands",
                new ArrayList<>(execution.trace.semanticDemands()));
        List<Map<String, Object>> records = new ArrayList<>();
        for (ProcessingTraceRecord record : execution.trace.records()) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put(ContractsFixtureConstants.Field.SEQUENCE, record.sequence());
            value.put("kind", record.kind().name());
            value.put(ContractsFixtureConstants.Field.SCOPE_PATH, record.scopePath());
            value.put(ContractsFixtureConstants.Field.CONTRACT_KEY, record.contractKey());
            value.put(ContractsFixtureConstants.Field.LOGICAL_PATH, record.logicalPath());
            value.put("details", record.details());
            if (record.node() != null) {
                value.put("node",
                        NodeWireForm.get(record.node()));
            }
            records.add(value);
        }
        result.put("records", records);
        return result;
    }

    ContractsConformanceProjection executeAttempt(JsonNode fixture,
                                                          PreparedInput input) {
        ProcessorBundle bundle = processor(input);
        try {
            ProcessAttemptResult result = bundle.processor.processAttempt(
                    input.root, input.event, input.evidence);
            ContractsConformanceProjection projection =
                    new ContractsConformanceProjection()
                            .put("input.root", input.root)
                            .put("attempt.kind", result.kind().wireValue())
                            .put("commit.progressCommitted", false)
                            .put("commit.progressWritten", false);
            if (result.isComplete()) {
                projection.put("attempt.processResult",
                        publicResult(result.processResult()));
                projection.put("attempt.portableGas", result.portableGas());
            }
            return projection;
        } finally {
            bundle.close();
        }
    }

    ContractsConformanceProjection executePlatform(JsonNode fixture,
                                                           PreparedInput input) {
        JsonNode feeder = fixture
                .path(ContractsFixtureConstants.Field.INPUT)
                .path(ContractsFixtureConstants.Field.FEEDER);
        ContractsConformanceProjection projection =
                new ContractsConformanceProjection()
                        .put("input.root", input.root);
        long managed = requiredLong(feeder, "managedRootRevision");
        long indexed = requiredLong(feeder, "indexedRootRevision");

        if (managed != indexed && !feeder.has("evaluatedRevision")) {
            projection.put("platform.eventSelected", false);
            projection.put("platform.reason", "index-revision-barrier");
        }
        if (feeder.has("channelLawCases")) {
            List<Boolean> laws = new ArrayList<>();
            for (JsonNode law : feeder.get("channelLawCases")) {
                boolean accepts = law.path("accepts").asBoolean();
                boolean preselects = law.path("preselects").asBoolean();
                boolean intersection = law.path("keyIntersection").asBoolean();
                laws.add((!accepts || preselects)
                        && (!preselects || intersection));
            }
            projection.put("feeder.channelLaws", laws);
        }
        if (feeder.has("acceptanceStateVariants")) {
            int index = 0;
            for (JsonNode state : feeder.get("acceptanceStateVariants")) {
                ObjectNode stateRoot = input.rootJson.deepCopy();
                applyMutableRootState(stateRoot, state);
                boolean accepted = selectedChannelsAccept(
                        stateRoot, input.derivedDeliveries);
                projection.putVariant("state-" + index++,
                        new ContractsConformanceProjection()
                                .put("feeder.acceptanceResult", accepted));
            }
        }
        List<DerivedDelivery> canonicalDeliveries =
                filterRawIndexCandidates(
                        feeder, input.derivedDeliveries, projection);
        projection.put("feeder.canonicalSnapshot",
                compactDeliveries(canonicalDeliveries));

        if (feeder.has("canonicalPreselection")) {
            List<Map<String, Object>> declared =
                    compactDeliveryHints(feeder.get("canonicalPreselection"));
            if (!semanticEquals(compactDeliveries(canonicalDeliveries), declared)
                    || !semanticEquals(
                    compactDeliveryHints(feeder.path(ContractsFixtureConstants.Field.DELIVERY_SNAPSHOT)),
                    compactDeliveries(canonicalDeliveries))) {
                projection.put("platform.status", "feeder-nonconformance");
            }
        }
        if (feeder.path("currentEventAddsChannel").asBoolean(false)) {
            List<Object> order = orderKeyValues(feeder.path(ContractsFixtureConstants.Field.EVENT_ORDER_KEY));
            projection.put("feeder.newInterval.startAfterExternalOrderKey", order);
            projection.put("feeder.currentSnapshot",
                    compactDeliveries(canonicalDeliveries));
        }
        if (feeder.has("intervalHistory")) {
            List<String> activeIds = deriveIntervals(
                    feeder.get("intervalHistory"),
                    orderKeyValues(feeder.path(ContractsFixtureConstants.Field.EVENT_ORDER_KEY)));
            projection.put("feeder.intervalCount", activeIds.size());
            projection.put("feeder.intervalIds", activeIds);
        }
        if (feeder.has("eventQueue") && feeder.has("targetsByEvent")) {
            projection.put(
                    "feeder.callOrder",
                    drainExternalEventQueue(
                            feeder.get("eventQueue"),
                            feeder.get("targetsByEvent")));
        }
        if (feeder.has("evaluatedRevision")
                && feeder.get("evaluatedRevision").asLong() != managed) {
            projection.put("commit.progressCommitted", false);
            projection.put("commit.reason", "revision-conflict");
        }
        if (feeder.has("sameFailureCount")) {
            long count = feeder.get("sameFailureCount").asLong();
            projection.put("platform.deliveryState",
                    count >= 3L ? "quarantined" : "retryable");
            projection.put("platform.retryScheduled", count < 3L);
        }
        return projection;
    }

    void executeVariants(JsonNode fixture,
                                 PreparedInput base,
                                 ContractsConformanceProjection projection) {
        JsonNode variants = fixture
                .path(ContractsFixtureConstants.Field.INPUT)
                .path(ContractsFixtureConstants.Field.VARIANTS);
        if (!variants.isArray()) {
            return;
        }
        ProcessExecution prior = null;
        for (JsonNode variant : variants) {
            String name = variant.path(ContractsFixtureConstants.Field.NAME).asText();
            boolean sameEvent =
                    variant.path(ContractsFixtureConstants.Field.SAME_EVENT).asBoolean(false);
            /*
             * A same-event variant continues from the prior Root only when
             * that PROCESS committed. Noncommitting results already expose
             * the rollback Root, but treating that value as a committed
             * predecessor causes prepare(...) to seed source checkpoints and
             * turns a deterministic retry into a stale attempt. Retrying a
             * failure instead starts from the original exact fixture input.
             */
            Node priorRoot = sameEvent
                    && prior != null
                    && prior.result.commits()
                    ? prior.result.document()
                    : null;
            PreparedInput transformed = prepare(
                    fixture.path(ContractsFixtureConstants.Field.INPUT),
                    variant,
                    priorRoot,
                    !ContractsFixtureConstants.Operation.PLATFORM.equals(
                            fixture.path(
                                    ContractsFixtureConstants.Field.OPERATION)
                                    .asText()),
                    hasVector(fixture, "C-LOOP-01"));
            if (ContractsFixtureConstants.Operation.PLATFORM.equals(
                    fixture.path(
                            ContractsFixtureConstants.Field.OPERATION)
                            .asText())) {
                ContractsConformanceProjection child =
                        executePlatform(fixture, transformed);
                projection.putVariant(name, child);
                continue;
            }
            ProcessExecution execution = runProcess(transformed);
            ContractsConformanceProjection child =
                    projectProcess(transformed, execution);
            if (variant.has(ContractsFixtureConstants.Field.LIST_OPERATION)) {
                child.put(ContractsFixtureConstants.Field.TRACE, gasCounterTree(execution.trace.gas()));
            }
            projection.putVariant(name, child);
            prior = execution;
        }
    }

    ProcessExecution runProcess(PreparedInput input) {
        ProcessorBundle bundle = processor(input);
        try {
            ProcessingDebugResult debug;
            if (input.snapshotRootForm()) {
                ResolvedSnapshot snapshot =
                        input.referenceBackedRootForm()
                                ? bundle.language.snapshots().load(
                                input.root.getBlueId())
                                : bundle.language.snapshots().resolve(input.root);
                debug = bundle.processor.processDocumentWithTrace(
                        snapshot, input.event, input.evidence);
            } else {
                debug = bundle.processor.processDocumentWithTrace(
                        input.root, input.event, input.evidence);
            }
            bundle.provider.verifyPreparation();
            return new ProcessExecution(
                    debug.processResult(),
                    debug.trace(),
                    debug.platformCommitCompanion(),
                    bundle.generalization);
        } finally {
            bundle.close();
        }
    }

    ProcessorBundle processor(PreparedInput input) {
        ScriptedContractsRuntime scripted =
                new ScriptedContractsRuntime(input.runtimeControls);
        MockExternalChannelProcessor channel =
                new MockExternalChannelProcessor(
                        input.checkpointSubjectOverride);
        MockHandlerProcessor handler =
                new MockHandlerProcessor(scripted);

        final Map<String, Node> providerNodes =
                new LinkedHashMap<>(registry.nodesByBlueId);
        providerNodes.putAll(input.providerNodes);
        FixturePhysicalProvider provider =
                new FixturePhysicalProvider(
                        providerNodes,
                        input.cacheMode,
                        input.batchingMode);
        BlueLanguageRuntime fixtureLanguage = languageRuntime(provider);
        ConformanceEngine conformanceEngine =
                fixtureLanguage.newConformanceEngine();
        ProcessingSnapshotManager snapshots = new ProcessingSnapshotManager() {
            @Override
            public ResolvedSnapshot fromDocument(Node document) {
                return fixtureLanguage.snapshots().resolve(document);
            }

            @Override
            public ResolvedSnapshot fromDocumentPreservingPaths(
                    Node document,
                    Collection<String> preservedPaths) {
                return fixtureLanguage.snapshots().resolvePreservingPaths(
                        document, preservedPaths);
            }

            @Override
            public ResolvedSnapshot fromDocumentTransientPreservingPaths(
                    Node document,
                    Collection<String> preservedPaths) {
                return fixtureLanguage.snapshots().resolvePreservingPaths(
                        document, preservedPaths);
            }

            @Override
            public FrozenNode materializeVerifiedExactReference(
                    FrozenNode reference) {
                if (!reference.isReferenceOnly()) {
                    return reference;
                }
                return fixtureLanguage.snapshots().load(
                        reference.getReferenceBlueId())
                        .frozenCanonicalRoot();
            }

            @Override
            public ResolvedSnapshot applyPatch(ResolvedSnapshot snapshot,
                                               JsonPatch patch) {
                return fixtureLanguage.patching().apply(snapshot, patch);
            }

            @Override
            public ResolvedSnapshot cacheSnapshot(ResolvedSnapshot snapshot) {
                fixtureLanguage.snapshots().cache(snapshot);
                return snapshot;
            }
        };

        DocumentProcessor.Builder builder = DocumentProcessor.builder()
                .withMatchingService(new ContractMatchingService(
                        fixtureLanguage))
                .withConformanceEngine(conformanceEngine)
                .withSnapshotManager(snapshots)
                .withGasSchedule(GasSchedule.contracts10())
                .withRuntimeRegistryIdentity(
                        BlueContractsConformanceReport
                                .CONTRACTS_REGISTRY_PACKAGE_IDENTITY)
                .registerContractType(
                        RuntimeBlueIds.FIXTURE_EVENT,
                        FixtureNonChannelContract.Value.class)
                .registerContractProcessor(
                        MockTypeBlueIds.MOCK_EXTERNAL_CHANNEL,
                        registry.require(MockTypeBlueIds.MOCK_EXTERNAL_CHANNEL),
                        channel)
                .registerContractProcessor(
                        MockTypeBlueIds.MOCK_HANDLER,
                        registry.require(MockTypeBlueIds.MOCK_HANDLER),
                        handler);
        FixtureGeneralizationPlanner generalization =
                input.generalization != null
                        ? input.generalization.newPlanner()
                        : null;
        if (generalization != null) {
            builder.withConformancePlannerOverride(generalization);
        }
        if (input.deliveryPlan != null) {
            builder.withExternalDeliveryPlanDeriver((root, event) -> {
                String rootBlueId = DirectBlueIdCalculator.calculateBlueId(root);
                String eventBlueId = DirectBlueIdCalculator.calculateBlueId(event);
                if (!input.evidence.rootBlueId().equals(rootBlueId)
                        || !input.evidence.eventBlueId().equals(eventBlueId)) {
                    throw new IllegalArgumentException(
                            "Fixture delivery plan is bound to another Root/event pair");
                }
                return input.deliveryPlan;
            });
        }
        if (input.runtimeControls != null
                && input.runtimeControls.has("gasLimit")) {
            builder.withGasLimit(
                    requiredLong(input.runtimeControls, "gasLimit"));
        }
        if (input.runtimeControls != null
                && input.runtimeControls.path(
                "gasLimitDuringTermination").asBoolean(false)) {
            builder.withGasLimit(170L);
        }
        return new ProcessorBundle(
                builder.build(),
                scripted,
                generalization,
                fixtureLanguage,
                conformanceEngine,
                provider);
    }

}
