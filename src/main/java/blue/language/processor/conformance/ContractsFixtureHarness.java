package blue.language.processor.conformance;

import blue.language.Blue;
import blue.language.BlueContractsConformanceReport;
import blue.language.NodeProvider;
import blue.language.conformance.ConformancePlan;
import blue.language.model.Node;
import blue.language.processor.ConformanceChangedPath;
import blue.language.processor.ConformancePlannerOverride;
import blue.language.processor.ContractMatchingService;
import blue.language.processor.CheckpointDomain;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.EffectiveContractSnapshot;
import blue.language.processor.ExternalDeliveryPlan;
import blue.language.processor.ExternalDeliverySnapshot;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.GasSchedule;
import blue.language.processor.GasTraceEntry;
import blue.language.processor.ProcessAttemptResult;
import blue.language.processor.ProcessingConformanceTrace;
import blue.language.processor.ProcessingDebugResult;
import blue.language.processor.ProcessingSnapshotManager;
import blue.language.processor.ProcessingTraceRecord;
import blue.language.processor.PlatformCommitCompanion;
import blue.language.processor.ProcessorDiagnostic;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.SubscriptionDelta;
import blue.language.processor.VerifiedExecutionEvidence;
import blue.language.processor.model.JsonPatch;
import blue.language.snapshot.FrozenNode;
import blue.language.snapshot.ResolvedSnapshot;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.NodeToMapListOrValue;
import blue.language.utils.UncheckedObjectMapper;
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

/**
 * Closed executable harness for one Blue Contracts 1.0 fixture envelope.
 *
 * <p>Actual values are built only from production API return values, canonical
 * run records, and declared feeder/runtime algorithms. The {@code expected}
 * subtree is read solely by {@link ContractsAssertionEvaluator} after
 * execution.</p>
 */
public final class ContractsFixtureHarness {

    private static final String FIXTURE_INIT_CHANNEL =
            "_fixture_init_channel";
    private static final String FIXTURE_INIT_HANDLER =
            "_fixture_init_handler";
    private static final String FIXTURE_ABSENT_CHILD_PATH =
            "/_fixture_absent_child";
    private static final String FIXTURE_EMBEDDED_CHANNEL =
            "_fixture_embedded_channel";
    private static final String FIXTURE_FORWARD_HANDLER =
            "_fixture_forward_handler";
    private static final String FIXTURE_CHILD_EMITTER_HANDLER =
            "_fixture_child_emitter_handler";
    private static final String FIXTURE_TRIGGERED_CHANNEL =
            "_fixture_triggered_channel";
    private static final String FIXTURE_NESTED_HANDLER =
            "_fixture_nested_handler";
    private static final String FIXTURE_UPDATE_CHANNEL =
            "_fixture_update_channel";
    private static final String FIXTURE_CASCADE_HANDLER =
            "_fixture_cascade_handler";
    private static final String FIXTURE_LIFECYCLE_CHANNEL =
            "_fixture_lifecycle_channel";
    private static final String FIXTURE_LIFECYCLE_HANDLER =
            "_fixture_lifecycle_handler";
    private static final String FIXTURE_VALUE_FIELD =
            "_fixture_value";
    private static final String FIXTURE_LIST_FIELD =
            "_fixture_list";

    private static final ObjectMapper YAML = new ObjectMapper(
            YAMLFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .build());
    private static final String CONTRACTS_REGISTRY_ROOT =
            "registry/blue-contracts-1.0/";
    private static final String LANGUAGE_REGISTRY_ROOT =
            "registry/blue-language-1.0/";

    private final ClosedContractsFixtureValidator validator =
            new ClosedContractsFixtureValidator();
    private final ContractsProjectionCatalog projectionCatalog =
            new ContractsProjectionCatalog();
    private final ContractsAssertionEvaluator assertions =
            new ContractsAssertionEvaluator();
    private final ContractsGasSchedule gasSchedule =
            new ContractsGasSchedule();
    private final RegistryEnvironment registry = RegistryEnvironment.load();

    public ContractsConformanceProjection execute(JsonNode fixture,
                                                  Blue ignoredHost,
                                                  boolean completeCounterCoverage) {
        validator.validate(fixture);
        projectionCatalog.validateFixtureAssertions(fixture);

        String operation = fixture.path("operation").asText();
        JsonNode input = fixture.path("input");
        if ("gas-micro".equals(operation) && !input.has("root")) {
            ContractsConformanceProjection projection =
                    executeStandaloneGas(fixture, completeCounterCoverage);
            assertions.evaluate(fixture, projection);
            return projection;
        }

        validateExecutableControls(fixture);
        boolean requiresExecutionEvidence = !"platform".equals(operation);
        PreparedInput base = prepare(
                input, null, null, requiresExecutionEvidence);
        ContractsConformanceProjection projection;
        if ("platform".equals(operation)) {
            projection = executePlatform(fixture, base);
        } else if ("process-attempt".equals(operation)) {
            projection = executeAttempt(fixture, base);
        } else if ("process".equals(operation)) {
            projection = executeProcess(fixture, base);
        } else if ("gas-micro".equals(operation)) {
            ProcessExecution execution = runProcess(base);
            projection = projectProcess(base, execution);
            addCompositeGasAudit(
                    projection, execution.trace, completeCounterCoverage);
        } else {
            throw new IllegalArgumentException(
                    "Unsupported Contracts 1.0 fixture operation: " + operation);
        }

        executeVariants(fixture, base, projection);
        assertions.evaluate(fixture, projection);
        return projection;
    }

    public void validate(JsonNode fixture) {
        validator.validate(fixture);
        projectionCatalog.validateFixtureAssertions(fixture);
    }

    /**
     * Rejects controls whose causal path is absent from the published input.
     * The harness must not manufacture an embedded scope or count a handler
     * that can never be selected as coverage of the declared control.
     */
    private void validateExecutableControls(JsonNode fixture) {
        JsonNode input = fixture.path("input");
        JsonNode runtime = input.path("runtime");
        if (!runtime.isObject()) {
            return;
        }

        String fixtureId = fixture.path("id").asText();
        ObjectNode root = requireObject(
                input.get("root"), "input.root").deepCopy();
        applyBuilders(root, input.path("builders"));
        List<ScopeValue> scopes = enumerateDeclaredScopes(root);
        Set<String> scopePaths = new LinkedHashSet<>();
        for (ScopeValue scope : scopes) {
            scopePaths.add(scope.path);
        }
        JsonNode feeder = input.path("feeder");
        JsonNode selectedChild = firstNonRootDeliveryHintOrNull(feeder);

        if (runtime.has("childEmissions")) {
            if (runtime.get("childEmissions").size() == 0) {
                contradiction(
                        fixtureId,
                        "runtime.childEmissions",
                        "the emission list is empty");
            }
            requireSelectedChild(
                    fixtureId,
                    "runtime.childEmissions",
                    selectedChild,
                    scopePaths);
        }

        JsonNode cascade = runtime.path("cascadeMutation");
        if (!cascade.isObject()) {
            return;
        }
        if (cascade.path(
                "replaceScopeDuringLifecycle").asBoolean(false)) {
            String target = cascade.path("replaceScope").asText(null);
            requireEmbeddedTarget(
                    fixtureId,
                    "runtime.cascadeMutation.replaceScopeDuringLifecycle",
                    target,
                    scopePaths,
                    "no exact non-root replacement scope is declared");
        }
        if (cascade.path(
                "sourceCutOffDuringUpdate").asBoolean(false)) {
            String target = cascade.path("replaceScope").asText(null);
            if (target == null && selectedChild != null) {
                target = selectedChild.path("scopePath").asText(null);
            }
            requireEmbeddedTarget(
                    fixtureId,
                    "runtime.cascadeMutation.sourceCutOffDuringUpdate",
                    target,
                    scopePaths,
                    "the only possible Document Update source is Root");
            if (selectedChild == null
                    || !target.equals(
                    selectedChild.path("scopePath").asText())
                    || !selectedChildCanProduceUpdate(
                    root, runtime, selectedChild)) {
                contradiction(
                        fixtureId,
                        "runtime.cascadeMutation.sourceCutOffDuringUpdate",
                        "no selected Handler in " + target
                                + " can originate the update being cut off");
            }
        }
    }

    private static void requireSelectedChild(
            String fixtureId,
            String control,
            JsonNode selectedChild,
            Set<String> scopePaths) {
        if (selectedChild == null) {
            contradiction(
                    fixtureId,
                    control,
                    "deliverySnapshot contains no non-root occurrence");
        }
        String path = selectedChild.path("scopePath").asText();
        if (!scopePaths.contains(path)) {
            contradiction(
                    fixtureId,
                    control,
                    "selected child " + path
                            + " is not reachable through Process Embedded");
        }
    }

    private static void requireEmbeddedTarget(
            String fixtureId,
            String control,
            String target,
            Set<String> scopePaths,
            String absentReason) {
        if (target == null || "/".equals(target)) {
            contradiction(fixtureId, control, absentReason);
        }
        if (!scopePaths.contains(target)) {
            contradiction(
                    fixtureId,
                    control,
                    "replacement target " + target
                            + " is not a declared embedded scope root");
        }
    }

    private static void contradiction(String fixtureId,
                                      String control,
                                      String reason) {
        throw new FixturePackageContradictionException(
                fixtureId, control, reason);
    }

    private ContractsConformanceProjection executeStandaloneGas(
            JsonNode fixture,
            boolean completeCounterCoverage) {
        ContractsGasSchedule.GasMicroResult actual =
                gasSchedule.evaluate(fixture, completeCounterCoverage);
        return actual.projection()
                .put("__gas.trace", actual.trace())
                .put("__gas.totalGas", actual.totalGas())
                .put("__gas.admitted", actual.admitted())
                .put("__gas.failedChargeAbsent", actual.failedChargeAbsent())
                .put("__gas.listFoldStepRecomputed",
                        actual.listFoldStepRecomputed())
                .put("__gas.textBlockExamined", actual.textBlockExamined())
                .put("__gas.validationProofReused",
                        actual.validationProofReused())
                .put("__gas.directIdentityHashBlock",
                        actual.directIdentityHashBlock())
                .put("__gas.integerLimbOperation",
                        actual.integerLimbOperation());
    }

    private ContractsConformanceProjection executeProcess(JsonNode fixture,
                                                          PreparedInput input) {
        ProcessExecution execution = runProcess(input);
        ContractsConformanceProjection projection =
                projectProcess(input, execution);
        if (fixture.path("input").path("feeder")
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
                            ? "trace"
                            : retryTrace);
        }
        return projection;
    }

    private static Map<String, Object> canonicalAttemptTrace(
            ProcessExecution execution) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", execution.result.status().wireValue());
        result.put("gas", gasEntries(execution.trace.gas(), false));
        result.put("semanticDemands",
                new ArrayList<>(execution.trace.semanticDemands()));
        List<Map<String, Object>> records = new ArrayList<>();
        for (ProcessingTraceRecord record : execution.trace.records()) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("sequence", record.sequence());
            value.put("kind", record.kind().name());
            value.put("scopePath", record.scopePath());
            value.put("contractKey", record.contractKey());
            value.put("logicalPath", record.logicalPath());
            value.put("details", record.details());
            if (record.node() != null) {
                value.put("node",
                        NodeToMapListOrValue.get(record.node()));
            }
            records.add(value);
        }
        result.put("records", records);
        return result;
    }

    private ContractsConformanceProjection executeAttempt(JsonNode fixture,
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
            bundle.processor.close();
        }
    }

    private ContractsConformanceProjection executePlatform(JsonNode fixture,
                                                           PreparedInput input) {
        JsonNode feeder = fixture.path("input").path("feeder");
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
                    compactDeliveryHints(feeder.path("deliverySnapshot")),
                    compactDeliveries(canonicalDeliveries))) {
                projection.put("platform.status", "feeder-nonconformance");
            }
        }
        if (feeder.path("currentEventAddsChannel").asBoolean(false)) {
            List<Object> order = orderKeyValues(feeder.path("eventOrderKey"));
            projection.put("feeder.newInterval.startAfterExternalOrderKey", order);
            projection.put("feeder.currentSnapshot",
                    compactDeliveries(canonicalDeliveries));
        }
        if (feeder.has("intervalHistory")) {
            List<String> activeIds = deriveIntervals(
                    feeder.get("intervalHistory"),
                    orderKeyValues(feeder.path("eventOrderKey")));
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

    private void executeVariants(JsonNode fixture,
                                 PreparedInput base,
                                 ContractsConformanceProjection projection) {
        JsonNode variants = fixture.path("input").path("variants");
        if (!variants.isArray()) {
            return;
        }
        ProcessExecution prior = null;
        for (JsonNode variant : variants) {
            String name = variant.path("name").asText();
            Node priorRoot = variant.path("sameEvent").asBoolean(false)
                    && prior != null
                    ? prior.result.document()
                    : null;
            PreparedInput transformed = prepare(
                    fixture.path("input"),
                    variant,
                    priorRoot,
                    !"platform".equals(fixture.path("operation").asText()));
            if ("platform".equals(fixture.path("operation").asText())) {
                ContractsConformanceProjection child =
                        executePlatform(fixture, transformed);
                projection.putVariant(name, child);
                continue;
            }
            ProcessExecution execution = runProcess(transformed);
            ContractsConformanceProjection child =
                    projectProcess(transformed, execution);
            if (variant.has("listOperation")) {
                child.put("trace", gasCounterTree(execution.trace.gas()));
            }
            projection.putVariant(name, child);
            prior = execution;
        }
    }

    private ProcessExecution runProcess(PreparedInput input) {
        ProcessorBundle bundle = processor(input);
        try {
            ProcessingDebugResult debug;
            if (input.snapshotRootForm()) {
                ResolvedSnapshot snapshot =
                        input.referenceBackedRootForm()
                                ? bundle.blue.loadSnapshot(
                                input.root.getBlueId())
                                : bundle.blue.resolveToSnapshot(input.root);
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
            bundle.processor.close();
        }
    }

    private ProcessorBundle processor(PreparedInput input) {
        ScriptedContractsRuntime scripted =
                new ScriptedContractsRuntime(input.runtimeControls);
        MockExternalChannelProcessor channel =
                new MockExternalChannelProcessor(
                        scripted,
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
        Blue fixtureBlue = new Blue(provider);
        ProcessingSnapshotManager snapshots = new ProcessingSnapshotManager() {
            @Override
            public ResolvedSnapshot fromDocument(Node document) {
                return fixtureBlue.resolveToSnapshot(document);
            }

            @Override
            public ResolvedSnapshot fromDocumentPreservingPaths(
                    Node document,
                    Collection<String> preservedPaths) {
                return fixtureBlue.resolveToSnapshotPreservingPaths(
                        document, preservedPaths);
            }

            @Override
            public ResolvedSnapshot fromDocumentTransientPreservingPaths(
                    Node document,
                    Collection<String> preservedPaths) {
                return fixtureBlue.resolveToSnapshotPreservingPaths(
                        document, preservedPaths);
            }

            @Override
            public FrozenNode materializeVerifiedExactReference(
                    FrozenNode reference) {
                if (!reference.isReferenceOnly()) {
                    return reference;
                }
                return fixtureBlue.loadSnapshot(
                        reference.getReferenceBlueId())
                        .frozenCanonicalRoot();
            }

            @Override
            public ResolvedSnapshot applyPatch(ResolvedSnapshot snapshot,
                                               JsonPatch patch) {
                return fixtureBlue.applyCanonicalPatch(snapshot, patch);
            }

            @Override
            public ResolvedSnapshot cacheSnapshot(ResolvedSnapshot snapshot) {
                fixtureBlue.cacheResolvedSnapshot(snapshot);
                return snapshot;
            }
        };

        DocumentProcessor.Builder builder = DocumentProcessor.builder()
                .withMatchingService(new ContractMatchingService(fixtureBlue))
                .withConformanceEngine(fixtureBlue.conformanceEngine())
                .withSnapshotManager(snapshots)
                .withGasSchedule(GasSchedule.contracts10())
                .withRuntimeRegistryIdentity(
                        BlueContractsConformanceReport
                                .CONTRACTS_REGISTRY_PACKAGE_IDENTITY)
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
                String rootBlueId = BlueIdCalculator.calculateBlueId(root);
                String eventBlueId = BlueIdCalculator.calculateBlueId(event);
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
                fixtureBlue,
                provider);
    }

    private PreparedInput prepare(JsonNode input,
                                  JsonNode variant,
                                  Node previousRoot,
                                  boolean requiresExecutionEvidence) {
        String rootForm = variant != null
                ? variant.path("rootForm").asText("inline")
                : "inline";
        String cacheMode = variant != null
                ? variant.path("cache").asText("cold")
                : "cold";
        String batchingMode = variant != null
                ? variant.path("batching").asText("unbatched")
                : "unbatched";
        ObjectNode declaredRoot =
                requireObject(input.get("root"), "input.root").deepCopy();
        applyBuilders(declaredRoot, input.path("builders"));
        installRuntimeContracts(
                declaredRoot,
                input.path("runtime"),
                input.path("feeder"));
        FixtureGeneralization generalization =
                FixtureGeneralization.create(
                        declaredRoot, input.path("runtime"));
        ObjectNode rootJson = declaredRoot;
        if (previousRoot != null) {
            rootJson = (ObjectNode) UncheckedObjectMapper.JSON_MAPPER.valueToTree(
                    NodeToMapListOrValue.get(previousRoot));
            materializeRetryContracts(rootJson, declaredRoot);
        }
        if (variant != null) {
            applyVariant(rootJson, variant);
        }
        Node event = readNode(input.get("event"));
        String eventBlueId = BlueIdCalculator.calculateBlueId(event);
        Node checkpointSubjectOverride =
                variant != null && variant.has("checkpointSubject")
                        ? rawCheckpointSubject(
                        variant.get("checkpointSubject"))
                        : null;

        Map<String, Node> providerNodes = verifyProviderNodes(input.path("provider"));
        if (generalization != null) {
            for (Map.Entry<String, Node> entry :
                    generalization.nodesByBlueId.entrySet()) {
                putDerivedProviderNode(
                        providerNodes,
                        entry.getKey(),
                        entry.getValue());
            }
        }
        JsonNode feeder = input.path("feeder");
        List<DerivedDelivery> deliveries = deriveDeliveries(
                rootJson, input.path("event"), feeder.path("deliverySnapshot"),
                eventBlueId, checkpointSubjectOverride);
        if (variant != null
                && (checkpointSubjectOverride != null
                || (previousRoot != null
                && variant.path("sameEvent").asBoolean(false)))) {
            seedVariantCheckpoints(rootJson, deliveries);
        }
        Node materializedRoot = readNode(rootJson);
        String inlineRootBlueId =
                BlueIdCalculator.calculateBlueId(
                        materializedRoot);
        String rootBlueId = inlineRootBlueId;
        Node exactProviderRoot = materializedRoot;
        if (referenceBackedRootForm(rootForm)) {
            Node canonicalReference =
                    canonicalReferenceRoot(
                            materializedRoot,
                            providerNodes);
            rootBlueId =
                    BlueIdCalculator.calculateBlueId(
                            canonicalReference);
            if (!inlineRootBlueId.equals(rootBlueId)) {
                throw new IllegalStateException(
                        "Preprocessing changed the exact Root identity "
                                + "between inline and reference forms");
            }
            exactProviderRoot = canonicalReference;
        }
        if (!"inline".equals(rootForm)) {
            putDerivedProviderNode(
                    providerNodes, rootBlueId, exactProviderRoot);
        }
        Node root = referenceBackedRootForm(rootForm)
                ? new Node().blueId(rootBlueId)
                : materializedRoot;
        for (DerivedDelivery delivery : deliveries) {
            putDerivedProviderNode(
                    providerNodes,
                    delivery.checkpointDomainBlueId,
                    delivery.checkpointDomainNode);
            putDerivedProviderNode(
                    providerNodes,
                    delivery.snapshot.checkpointSubjectBlueId(),
                    delivery.checkpointSubjectNode);
        }

        long managed = requiredLong(feeder, "managedRootRevision");
        long indexed = requiredLong(feeder, "indexedRootRevision");
        if (variant != null && variant.has("rootRevision")) {
            managed = variant.get("rootRevision").asLong();
            indexed = managed;
        }
        ExternalOrderKey eventOrderKey =
                externalOrderKey(feeder.path("eventOrderKey"));
        List<SubscriptionDelta.Entry> activeSubscriptionIntervals =
                deriveActiveSubscriptionIntervals(
                        rootJson,
                        feeder.path("deliverySnapshot"));
        VerifiedExecutionEvidence builtEvidence = null;
        ExternalDeliveryPlan builtPlan = null;
        if (requiresExecutionEvidence) {
            VerifiedExecutionEvidence.Builder evidence =
                    VerifiedExecutionEvidence.builder(rootBlueId, eventBlueId)
                            .revisions(managed, indexed)
                            .runtimeRegistryIdentity(
                                    BlueContractsConformanceReport
                                            .CONTRACTS_REGISTRY_PACKAGE_IDENTITY)
                            .eventOrderKey(eventOrderKey)
                            .activeSubscriptionIntervals(
                                    activeSubscriptionIntervals);
            for (DerivedDelivery delivery : deliveries) {
                evidence.delivery(delivery.snapshot);
            }
            for (String blueId : providerNodes.keySet()) {
                evidence.availableExactNode(blueId);
            }
            String unavailableAt =
                    input.path("provider").path(
                            "transientUnavailableAt").asText(null);
            if (unavailableAt != null) {
                evidence.requiredExactNode(
                        requiredSelectedBodyBlueId(
                                rootJson, deliveries, unavailableAt));
            }
            builtEvidence = evidence.build();
            if (!inlineRootBlueId.equals(
                    builtEvidence.rootBlueId())) {
                throw new IllegalStateException(
                        "Execution evidence Root identity diverged "
                                + "between inline and reference forms");
            }
            ExternalDeliveryPlan.Builder plan =
                    ExternalDeliveryPlan.builder()
                            .revisions(managed, indexed)
                            .eventOrderKey(eventOrderKey)
                            .activeSubscriptionIntervals(
                                    activeSubscriptionIntervals)
                            .exactRuntimeState();
            for (ExternalDeliverySnapshot delivery :
                    builtEvidence.deliveries()) {
                plan.delivery(delivery);
            }
            for (String blueId :
                    builtEvidence.availableExactNodeBlueIds()) {
                plan.availableExactNode(blueId);
            }
            for (String blueId :
                    builtEvidence.requiredExactNodeBlueIds()) {
                plan.requiredExactNode(blueId);
            }
            builtPlan = plan.build();
        }
        return new PreparedInput(
                rootJson,
                root,
                event,
                input.get("runtime"),
                providerNodes,
                deliveries,
                builtEvidence,
                builtPlan,
                generalization,
                checkpointSubjectOverride,
                rootForm,
                cacheMode,
                batchingMode);
    }

    private Node canonicalReferenceRoot(
            Node sourceRoot,
            Map<String, Node> providerNodes) {
        Map<String, Node> exactNodes =
                new LinkedHashMap<>(registry.nodesByBlueId);
        exactNodes.putAll(providerNodes);
        Blue canonicalizer = new Blue(blueId -> {
            Node exact = exactNodes.get(blueId);
            return exact == null
                    ? null
                    : Collections.singletonList(exact.clone());
        });
        try {
            /*
             * Provider content is exact canonical Source, not the completed
             * resolved value. Full resolution here would bake inherited
             * executable-body structure into the reference representation and
             * make an otherwise identical inline/reference pair diverge.
             */
            return canonicalizer.preprocess(
                    sourceRoot.clone());
        } finally {
            canonicalizer.close();
        }
    }

    private static void seedVariantCheckpoints(
            ObjectNode root,
            List<DerivedDelivery> deliveries) {
        for (DerivedDelivery delivery : deliveries) {
            JsonNode scopeValue =
                    jsonAt(root, delivery.snapshot.scopePath());
            if (!(scopeValue instanceof ObjectNode)) {
                throw new IllegalArgumentException(
                        "Checkpoint variant selected a missing scope "
                                + delivery.snapshot.scopePath());
            }
            ObjectNode contracts =
                    contractsObject((ObjectNode) scopeValue);
            ObjectNode checkpoint;
            if (contracts.has("checkpoint")) {
                checkpoint = requireObject(
                        contracts.get("checkpoint"),
                        "variant checkpoint");
            } else {
                checkpoint = contracts.putObject("checkpoint");
                checkpoint.putObject("type").put(
                        "blueId",
                        registryId("ChannelEventCheckpoint"));
            }
            ObjectNode entries =
                    objectField(checkpoint, "entries", true);
            ObjectNode stored =
                    entries.putObject(
                            delivery.snapshot.channelKey());
            stored.putObject("domain").put(
                    "blueId",
                    delivery.snapshot.checkpointDomainBlueId());
            stored.putObject("subject").put(
                    "blueId",
                    delivery.snapshot.checkpointSubjectBlueId());
        }
    }

    /**
     * A committed canonical Root may collapse an unchanged direct contract to
     * its exact BlueId. A same-event retry retains the original exact fixture
     * content as provider materialization; expanding that equivalent form is
     * necessary both for canonical preselection and for the fresh processor's
     * provider cache.
     */
    private static void materializeRetryContracts(JsonNode current,
                                                  JsonNode declared) {
        if (current == null || declared == null
                || !current.isObject() || !declared.isObject()) {
            return;
        }
        ObjectNode currentObject = (ObjectNode) current;
        JsonNode currentContracts = currentObject.get("contracts");
        JsonNode declaredContracts = declared.get("contracts");
        if (isPureReference(currentContracts)
                && declaredContracts != null
                && declaredContracts.isObject()) {
            currentObject.set(
                    "contracts", declaredContracts.deepCopy());
            currentContracts = currentObject.get("contracts");
        }
        if (currentContracts != null && currentContracts.isObject()
                && declaredContracts != null && declaredContracts.isObject()) {
            List<String> keys = new ArrayList<>();
            declaredContracts.fieldNames().forEachRemaining(keys::add);
            for (String key : keys) {
                JsonNode value = currentContracts.get(key);
                JsonNode exact = declaredContracts.get(key);
                if (value == null) {
                    ((ObjectNode) currentContracts).set(
                            key, exact.deepCopy());
                    continue;
                }
                if (matchesResolvedMaterialization(value, exact)) {
                    ((ObjectNode) currentContracts).set(
                            key, exact.deepCopy());
                    continue;
                }
                if (!isPureReference(value)) {
                    continue;
                }
                String reference = value.path("blueId").asText();
                if (reference.equals(
                        BlueIdCalculator.calculateBlueId(readNode(exact)))) {
                    ((ObjectNode) currentContracts).set(
                            key, exact.deepCopy());
                }
            }
        }
        Iterator<Map.Entry<String, JsonNode>> fields =
                currentObject.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            if ("contracts".equals(entry.getKey())) {
                continue;
            }
            JsonNode declaredChild = declared.get(entry.getKey());
            if (entry.getValue().isObject()
                    && declaredChild != null
                    && declaredChild.isObject()) {
                materializeRetryContracts(
                        entry.getValue(), declaredChild);
            }
        }
    }

    private static boolean isPureReference(JsonNode value) {
        return value != null
                && value.isObject()
                && value.size() == 1
                && value.path("blueId").isTextual();
    }

    private static boolean matchesResolvedMaterialization(
            JsonNode actual,
            JsonNode declared) {
        if (actual == null || declared == null) {
            return actual == declared;
        }
        if (actual.equals(declared)) {
            return true;
        }
        if (declared.isValueNode()) {
            JsonNode resolvedValue =
                    actual.isObject() ? actual.get("value") : null;
            return resolvedValue != null
                    && matchesResolvedMaterialization(
                    resolvedValue, declared);
        }
        if (declared.isArray()) {
            JsonNode actualItems = actual.isArray()
                    ? actual
                    : actual.isObject()
                    ? actual.get("items")
                    : null;
            if (actualItems == null
                    || !actualItems.isArray()
                    || actualItems.size() != declared.size()) {
                return false;
            }
            for (int index = 0; index < declared.size(); index++) {
                if (!matchesResolvedMaterialization(
                        actualItems.get(index),
                        declared.get(index))) {
                    return false;
                }
            }
            return true;
        }
        if (!declared.isObject()
                || !actual.isObject()
                || actual.size() != declared.size()) {
            return false;
        }
        Iterator<Map.Entry<String, JsonNode>> fields =
                declared.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (!matchesResolvedMaterialization(
                    actual.get(field.getKey()),
                    field.getValue())) {
                return false;
            }
        }
        return true;
    }

    private static void putDerivedProviderNode(
            Map<String, Node> providerNodes,
            String blueId,
            Node exactNode) {
        if (!blueId.equals(BlueIdCalculator.calculateBlueId(exactNode))) {
            throw new IllegalArgumentException(
                    "Derived provider content does not match " + blueId);
        }
        Node previous = providerNodes.put(blueId, exactNode.clone());
        if (previous != null
                && !semanticEquals(
                normalizeNode(previous), normalizeNode(exactNode))) {
            throw new IllegalArgumentException(
                    "Conflicting exact provider content for " + blueId);
        }
    }

    private static Node checkpointDomainNode(
            String effectiveTypeBlueId,
            List<String> sourceContributionNodeBlueIds,
            String runtimeDiscriminator) {
        Node domain = new Node()
                .properties("contractsVersion",
                        new Node().value("1.0"))
                .properties("effectiveTypeBlueId",
                        new Node().value(effectiveTypeBlueId));
        List<Node> contributions = new ArrayList<>();
        for (String blueId : sourceContributionNodeBlueIds) {
            contributions.add(new Node().value(blueId));
        }
        domain.properties("sourceContributionNodeBlueIds",
                new Node().items(contributions));
        if (runtimeDiscriminator != null
                && !runtimeDiscriminator.isEmpty()) {
            domain.properties("runtimeDiscriminator",
                    new Node().value(runtimeDiscriminator));
        }
        return domain;
    }

    private static JsonNode firstNonRootDeliveryHint(
            JsonNode feeder) {
        JsonNode hint = firstNonRootDeliveryHintOrNull(feeder);
        if (hint == null) {
            throw new IllegalArgumentException(
                    "A selected non-root delivery is required");
        }
        return hint;
    }

    private static JsonNode firstNonRootDeliveryHintOrNull(
            JsonNode feeder) {
        JsonNode hints = feeder != null
                ? feeder.path("deliverySnapshot")
                : null;
        if (hints == null || !hints.isArray()) {
            return null;
        }
        for (JsonNode hint : hints) {
            if (!"/".equals(
                    hint.path("scopePath").asText())) {
                return hint;
            }
        }
        return null;
    }

    private List<String> directRootChildScopePaths(
            ObjectNode root) {
        List<String> result = new ArrayList<>();
        for (ScopeValue scope : enumerateDeclaredScopes(root)) {
            if (scopeDepth(scope.path) == 1) {
                result.add(scope.path);
            }
        }
        return result;
    }

    private static boolean selectedChildCanProduceUpdate(
            ObjectNode root,
            JsonNode runtime,
            JsonNode selectedChild) {
        String scopePath =
                selectedChild.path("scopePath").asText();
        String channelKey =
                selectedChild.path("channelKey").asText();
        JsonNode scope = jsonAt(root, scopePath);
        JsonNode contracts = scope == null
                ? null
                : scope.get("contracts");
        if (contracts == null || !contracts.isObject()) {
            return false;
        }
        Iterator<Map.Entry<String, JsonNode>> entries =
                contracts.fields();
        while (entries.hasNext()) {
            Map.Entry<String, JsonNode> entry = entries.next();
            JsonNode handler = entry.getValue();
            if (!MockTypeBlueIds.MOCK_HANDLER.equals(
                    handler.path("type").path(
                            "blueId").asText(null))
                    || !channelKey.equals(
                    handler.path("channel").asText(null))) {
                continue;
            }
            JsonNode result = scriptedHandlerResult(
                    runtime,
                    scopePath,
                    entry.getKey(),
                    handler);
            if (nonEmptyResultList(result, "patches")) {
                return true;
            }
        }
        return false;
    }

    private static JsonNode scriptedHandlerResult(
            JsonNode runtime,
            String scopePath,
            String handlerKey,
            JsonNode handler) {
        JsonNode script = runtime.path("handlers").get(
                ScriptedContractsRuntime.contractPath(
                        scopePath, handlerKey));
        return script != null && script.has("result")
                ? script.get("result")
                : handler.get("result");
    }

    private static boolean nonEmptyResultList(
            JsonNode result,
            String field) {
        JsonNode value = result != null
                ? result.get(field)
                : null;
        if (value != null && value.isObject()) {
            value = value.get("items");
        }
        return value != null
                && value.isArray()
                && value.size() > 0;
    }

    /**
     * Expands non-Blue runtime controls into ordinary fixture contracts. The
     * installed handlers still have to be discovered, matched, and executed
     * by the production processor; this method never mutates run state.
     */
    private void installRuntimeContracts(ObjectNode root,
                                         JsonNode runtime,
                                         JsonNode feeder) {
        if (runtime == null || !runtime.isObject()) {
            return;
        }

        JsonNode cascade = runtime.get("cascadeMutation");

        if (runtime.has("initializationPatches")) {
            promoteFixtureScalarToObject(root);
            for (ScopeValue scope : enumerateDeclaredScopes(root)) {
                ObjectNode contracts = contractsObject(scope.value);
                installHandlerPair(
                        contracts,
                        FIXTURE_INIT_CHANNEL,
                        registryId("LifecycleEventChannel"),
                        FIXTURE_INIT_HANDLER,
                        null,
                        null);
            }
        }

        if (runtime.has("childEmissions")) {
            JsonNode childHint = firstNonRootDeliveryHint(feeder);
            String childPath = childHint.path("scopePath").asText();
            ObjectNode child = requireObject(
                    jsonAt(root, childPath),
                    "selected child scope " + childPath);
            installScriptedHandler(
                    contractsObject(child),
                    FIXTURE_CHILD_EMITTER_HANDLER,
                    childHint.path("channelKey").asText(),
                    null,
                    UncheckedObjectMapper.JSON_MAPPER.createObjectNode());
        }

        if (runtime.path("rootForwardAll").asBoolean(false)) {
            ObjectNode contracts = contractsObject(root);
            List<String> childPaths = directRootChildScopePaths(root);
            if (childPaths.isEmpty()) {
                /*
                 * The control promises to install the Root handler, not that
                 * the fixture must deliver a descendant occurrence to it.
                 * A non-matching source path keeps that installation ordinary
                 * and inert without manufacturing a child scope.
                 */
                childPaths = Collections.singletonList(
                        FIXTURE_ABSENT_CHILD_PATH);
            }
            for (int index = 0; index < childPaths.size(); index++) {
                String suffix = index == 0 ? "" : "_" + index;
                String channelKey = FIXTURE_EMBEDDED_CHANNEL + suffix;
                ObjectNode channel = installContract(
                        contracts, channelKey,
                        registryId("EmbeddedNodeChannel"));
                channel.put("childPath", childPaths.get(index));
                installScriptedHandler(
                        contracts,
                        FIXTURE_FORWARD_HANDLER + suffix,
                        channelKey,
                        null,
                        UncheckedObjectMapper.JSON_MAPPER.createObjectNode());
            }
        }

        if (runtime.has("nestedEnqueues")) {
            ObjectNode contracts = contractsObject(root);
            installContract(
                    contracts, FIXTURE_TRIGGERED_CHANNEL,
                    registryId("TriggeredEventChannel"));
            installScriptedHandler(
                    contracts,
                    FIXTURE_NESTED_HANDLER,
                    FIXTURE_TRIGGERED_CHANNEL,
                    null,
                    UncheckedObjectMapper.JSON_MAPPER.createObjectNode());
        }

        if (cascade != null && cascade.isObject()) {
            ObjectNode contracts = contractsObject(root);
            boolean lifecycle = cascade.path(
                    "replaceScopeDuringLifecycle").asBoolean(false);
            boolean sourceCutOff = cascade.path(
                    "sourceCutOffDuringUpdate").asBoolean(false);
            if (lifecycle) {
                installHandlerPair(
                        contracts,
                        FIXTURE_LIFECYCLE_CHANNEL,
                        registryId("LifecycleEventChannel"),
                        FIXTURE_LIFECYCLE_HANDLER,
                        registryId("DocumentProcessingInitiated"),
                        UncheckedObjectMapper.JSON_MAPPER.createObjectNode());
            }
            if (sourceCutOff || !lifecycle) {
                ObjectNode channel = installContract(
                        contracts,
                        FIXTURE_UPDATE_CHANNEL,
                        registryId("DocumentUpdateChannel"));
                channel.put("path", "/");
                installScriptedHandler(
                        contracts,
                        FIXTURE_CASCADE_HANDLER,
                        FIXTURE_UPDATE_CHANNEL,
                        null,
                        UncheckedObjectMapper.JSON_MAPPER.createObjectNode());
            }
        }
    }

    private static ObjectNode contractsObject(ObjectNode scope) {
        return objectField(scope, "contracts", true);
    }

    private static void installHandlerPair(
            ObjectNode contracts,
            String channelKey,
            String channelTypeBlueId,
            String handlerKey,
            String eventTypeBlueId,
            ObjectNode result) {
        installContract(contracts, channelKey, channelTypeBlueId);
        installScriptedHandler(
                contracts, handlerKey, channelKey, eventTypeBlueId, result);
    }

    private static ObjectNode installScriptedHandler(
            ObjectNode contracts,
            String handlerKey,
            String channelKey,
            String eventTypeBlueId,
            ObjectNode result) {
        ObjectNode handler = installContract(
                contracts, handlerKey, MockTypeBlueIds.MOCK_HANDLER);
        handler.put("channel", channelKey);
        if (eventTypeBlueId != null) {
            handler.putObject("event")
                    .putObject("type")
                    .put("blueId", eventTypeBlueId);
        }
        if (result != null) {
            handler.set("result", result.deepCopy());
        }
        return handler;
    }

    private static ObjectNode installContract(
            ObjectNode contracts,
            String key,
            String typeBlueId) {
        if (contracts.has(key)) {
            throw new IllegalArgumentException(
                    "Fixture runtime contract key collision: " + key);
        }
        ObjectNode contract = contracts.putObject(key);
        contract.putObject("type").put("blueId", typeBlueId);
        return contract;
    }

    private void applyBuilders(ObjectNode root, JsonNode builders) {
        if (!builders.isArray()) {
            return;
        }
        for (JsonNode builder : builders) {
            String kind = builder.path("kind").asText();
            JsonNode value;
            if ("generated-object".equals(kind)) {
                int count = exactInt(builder.get("memberCount"),
                        "builder.memberCount");
                int width = Math.max(1,
                        Integer.toString(Math.max(0, count - 1)).length());
                ObjectNode object = UncheckedObjectMapper.JSON_MAPPER.createObjectNode();
                for (int index = 0; index < count; index++) {
                    String suffix = String.format("%0" + width + "d", index);
                    object.set(builder.path("keyPrefix").asText() + suffix,
                            builder.get("value").deepCopy());
                }
                value = object;
            } else if ("generated-list".equals(kind)) {
                int count = exactInt(builder.get("itemCount"),
                        "builder.itemCount");
                ArrayNode array = UncheckedObjectMapper.JSON_MAPPER.createArrayNode();
                for (int index = 0; index < count; index++) {
                    array.add(builder.get("item").deepCopy());
                }
                value = array;
            } else if ("repeated-text".equals(kind)) {
                int count = exactInt(builder.get("codePointCount"),
                        "builder.codePointCount");
                String unit = builder.path("text").asText();
                StringBuilder repeated = new StringBuilder();
                for (int index = 0; index < count; index++) {
                    repeated.append(unit);
                }
                value = UncheckedObjectMapper.JSON_MAPPER
                        .getNodeFactory().textNode(repeated.toString());
            } else {
                throw new IllegalArgumentException(
                        "Unsupported Contracts builder: " + kind);
            }
            setPointer(root, builder.path("target").asText(), value);
        }
    }

    private void applyVariant(ObjectNode root, JsonNode variant) {
        if (variant.has("accept")) {
            setAllScriptedChannelAcceptance(root, variant.get("accept").asBoolean());
        }
        if (variant.has("listOperation")) {
            installListOperation(
                    root, variant.get("listOperation"));
        }
        if (variant.has("newEmbeddedSurface")) {
            installEmbeddedSurfaceTransition(
                    root, variant.get("newEmbeddedSurface").asText());
        }
    }

    private static void installListOperation(ObjectNode root,
                                             JsonNode operation) {
        int size = exactInt(operation.get("size"),
                "variant.listOperation.size");
        String kind = operation.path("op").asText();

        promoteFixtureScalarToObject(root);
        ArrayNode list = root.putArray(FIXTURE_LIST_FIELD);
        for (int index = 0; index < size; index++) {
            list.add(0);
        }

        ObjectNode contracts = requireObject(
                root.get("contracts"), "input.root.contracts");
        ObjectNode handler = firstScriptedHandler(contracts);
        if (handler == null) {
            throw new IllegalArgumentException(
                    "listOperation requires an ordinary selected "
                            + "Scripted Handler");
        }
        ObjectNode result = objectField(handler, "result", true);
        ArrayNode patches =
                UncheckedObjectMapper.JSON_MAPPER.createArrayNode();
        result.set("patches", patches);

        if ("append".equals(kind)) {
            int delta = exactInt(
                    operation.get("delta"),
                    "variant.listOperation.delta");
            for (int index = 0; index < delta; index++) {
                ObjectNode patch = patches.addObject();
                patch.put("op", "add");
                patch.put(
                        "path", "/" + FIXTURE_LIST_FIELD + "/-");
                patch.put("val", 1);
            }
            return;
        }
        if (!"replace".equals(kind)) {
            throw new IllegalArgumentException(
                    "Unknown listOperation op: " + kind);
        }
        int index = exactInt(
                operation.get("index"),
                "variant.listOperation.index");
        if (index >= size) {
            throw new IllegalArgumentException(
                    "variant.listOperation.index must be less than size");
        }
        ObjectNode patch = patches.addObject();
        patch.put("op", "replace");
        patch.put(
                "path", "/" + FIXTURE_LIST_FIELD + "/" + index);
        patch.put("val", 1);
    }

    private static boolean snapshotRootForm(String rootForm) {
        return "reference".equals(rootForm)
                || "lazy".equals(rootForm)
                || "eager".equals(rootForm);
    }

    private static boolean referenceBackedRootForm(String rootForm) {
        return "reference".equals(rootForm)
                || "lazy".equals(rootForm);
    }

    private static void setAllScriptedChannelAcceptance(JsonNode node,
                                                        boolean accepted) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            JsonNode type = node.path("type").path("blueId");
            if (MockTypeBlueIds.MOCK_EXTERNAL_CHANNEL.equals(type.asText(null))) {
                ((ObjectNode) node).put("accept", accepted);
            }
            node.elements().forEachRemaining(
                    child -> setAllScriptedChannelAcceptance(child, accepted));
        } else if (node.isArray()) {
            node.elements().forEachRemaining(
                    child -> setAllScriptedChannelAcceptance(child, accepted));
        }
    }

    private void installEmbeddedSurfaceTransition(ObjectNode root,
                                                  String scenario) {
        ObjectNode contracts = objectAt(root, "/contracts", true);
        ObjectNode embedded = installContract(
                contracts,
                "embedded",
                registryId("ProcessEmbedded"));
        if (!embedded.has("paths")) {
            embedded.putArray("paths");
        }
        ObjectNode handler = firstScriptedHandler(contracts);
        if (handler == null) {
            throw new IllegalArgumentException(
                    "newEmbeddedSurface requires a selected Scripted Handler");
        }
        ObjectNode result = objectField(handler, "result", true);
        ArrayNode patches = arrayField(result, "patches", true);
        ObjectNode patch = patches.addObject();
        patch.put("op", "replace");
        patch.put("path", "/contracts/embedded/paths");
        ArrayNode paths = patch.putArray("val");
        if ("cycle".equals(scenario)) {
            paths.add("/");
        } else if ("invalid-path".equals(scenario)) {
            paths.add("not-absolute");
        } else if ("unsupported-channel".equals(scenario)) {
            promoteFixtureScalarToObject(root);
            ObjectNode unsupportedScope =
                    objectField(root, "unsupported", true);
            ObjectNode unsupportedContracts =
                    contractsObject(unsupportedScope);
            ObjectNode unsupportedChannel = installContract(
                    unsupportedContracts,
                    "out",
                    MockTypeBlueIds.MOCK_EXTERNAL_CHANNEL);
            unsupportedChannel.put("order", 0);
            unsupportedChannel.put("accept", true);
            unsupportedChannel.put(
                    "checkpointDomain", "unsupported-v1");
            paths.add("/unsupported");
        } else {
            throw new IllegalArgumentException(
                    "Unknown newEmbeddedSurface transformation: " + scenario);
        }
    }

    private static void promoteFixtureScalarToObject(
            ObjectNode root) {
        JsonNode scalar = root.remove("value");
        if (scalar == null) {
            return;
        }
        if (root.has(FIXTURE_VALUE_FIELD)) {
            throw new IllegalArgumentException(
                    "Fixture scalar promotion key collision");
        }
        root.set(FIXTURE_VALUE_FIELD, scalar);
        JsonNode contracts = root.get("contracts");
        if (contracts == null || !contracts.isObject()) {
            return;
        }
        for (JsonNode contract : contracts) {
            if (!MockTypeBlueIds.MOCK_HANDLER.equals(
                    contract.path("type").path("blueId").asText(null))) {
                continue;
            }
            JsonNode patches =
                    contract.path("result").path("patches");
            if (!patches.isArray()) {
                continue;
            }
            for (JsonNode patch : patches) {
                if (patch.isObject()
                        && "/value".equals(
                        patch.path("path").asText(null))) {
                    ((ObjectNode) patch).put(
                            "path",
                            "/" + FIXTURE_VALUE_FIELD);
                }
            }
        }
    }

    private ContractsConformanceProjection projectProcess(
            PreparedInput input,
            ProcessExecution execution) {
        DocumentProcessingResult result = execution.result;
        ProcessingConformanceTrace trace = execution.trace;
        if (Boolean.getBoolean("blue.contracts.debugHandlers")) {
            System.err.println(
                    "fixture result " + result.status() + " "
                            + NodeToMapListOrValue.get(
                            result.document()));
            for (ProcessingTraceRecord record : trace.records()) {
                System.err.println(
                        "record " + record.kind()
                                + " label="
                                + lifecycleLabel(record.node()));
            }
        }
        ContractsConformanceProjection projection =
                new ContractsConformanceProjection()
                        .put("input.root", input.root)
                        .put("result", publicResult(result))
                        .put("result.status", result.status().wireValue())
                        .put("result.document", result.document())
                        .put("result.events", result.events())
                        .put("result.totalGas", result.totalGas())
                        .put("demands.semantic", trace.semanticDemands())
                        .put("trace.namedEntries", gasEntries(trace.gas(), true))
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

    private List<Map<String, Object>> projectSubscriptionIntervals(
            List<SubscriptionDelta.Entry> intervals) {
        List<Map<String, Object>> result =
                new ArrayList<>();
        for (SubscriptionDelta.Entry interval : intervals) {
            Map<String, Object> projected =
                    new LinkedHashMap<>();
            projected.put("scopePath", interval.scopePath());
            projected.put("channelKey", interval.channelKey());
            projected.put(
                    "effectiveTypeBlueId",
                    interval.effectiveTypeBlueId());
            projected.put(
                    "orderedSourceContributionNodeBlueIds",
                    interval.sourceContributionNodeBlueIds());
            projected.put("order", interval.order());
            projected.put(
                    "subscriptionKeys",
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

    private void projectGeneralization(
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
            if ("/type".equals(record.logicalPath())) {
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

    private void projectCounters(ProcessingConformanceTrace trace,
                                 ContractsConformanceProjection projection) {
        projection.put("trace.counters.contractHeaderRecognized",
                trace.counterQuantity("processor", "contractHeaderRecognized"));
        projection.put("trace.counters.directIdentityHashBlock",
                trace.counterQuantity("semantic", "directIdentityHashBlock"));
        projection.put("trace.counters.textBlockExamined",
                trace.counterQuantity("semantic", "textBlockExamined"));
        projection.put("trace.semantic.nodeIdentityEstablished",
                trace.counterQuantity("semantic", "nodeIdentityEstablished"));
        projection.put("trace.runtime.textBlockConstructed",
                trace.counterQuantity("runtime", "textBlockConstructed"));
    }

    private void projectRecords(PreparedInput input,
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
            value.put("scopePath", record.scopePath());
            value.put("beforePresent",
                    Boolean.valueOf(record.detail("beforePresent")));
            value.put("afterPresent",
                    Boolean.valueOf(record.detail("afterPresent")));
            updates.add(value);
            updateScopes.add(record.scopePath());
        }
        projection.put("trace.documentUpdates", updates);
        projection.put("trace.documentUpdateScopes", updateScopes);

        List<String> markerWrites = new ArrayList<>();
        List<String> lifecycle = new ArrayList<>();
        Set<String> lifecycleScopes = new LinkedHashSet<>();
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

        List<String> checkpointWrites = new ArrayList<>();
        for (ProcessingTraceRecord record :
                trace.records(ProcessingTraceRecord.Kind.CHECKPOINT_WRITE)) {
            checkpointWrites.add(record.scopePath());
        }
        projection.put("trace.checkpointWrites", checkpointWrites);

        List<String> checkpointCleanup = new ArrayList<>();
        for (ProcessingTraceRecord record : trace.records()) {
            if (record.kind() == ProcessingTraceRecord.Kind.CHECKPOINT_CLEANUP
                    || (record.kind()
                    == ProcessingTraceRecord.Kind.CHECKPOINT_WRITE
                    && "cleanup".equals(record.detail("action")))) {
                checkpointCleanup.add(record.contractKey());
            }
        }
        projection.put("trace.checkpointCleanupKeys", checkpointCleanup);

        boolean newDomain = false;
        for (ProcessingTraceRecord record :
                trace.records(ProcessingTraceRecord.Kind.CHECKPOINT_COMPARE)) {
            if ("false".equals(record.detail("domainMatches"))) {
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
            String label = record.detail("label");
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
                protectedStateUnchanged(input.root, execution.result.document()));
        projection.put("trace.terminationEvents",
                terminationEventCount(trace));
    }

    private void projectContractSnapshots(ProcessingConformanceTrace trace,
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

    private void projectEventTrace(ProcessingConformanceTrace trace,
                                   ContractsConformanceProjection projection) {
        List<String> deliveryOrder = new ArrayList<>();
        List<String> occurrenceOrder = new ArrayList<>();
        Set<String> drainOwners = new LinkedHashSet<>();
        for (ProcessingTraceRecord record : trace.records()) {
            if (record.kind() == ProcessingTraceRecord.Kind.EVENT_DEQUEUED) {
                occurrenceOrder.add(traceEventLabel(record));
                String owner = record.detail("drainOwner");
                if (owner != null) {
                    drainOwners.add(owner);
                }
            } else if (record.kind()
                    == ProcessingTraceRecord.Kind.EVENT_DELIVERED) {
                String mode = record.detail("mode");
                String label = traceEventLabel(record);
                deliveryOrder.add(record.scopePath() + ":"
                        + (mode != null ? mode : "event") + ":" + label);
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

    private static String traceEventLabel(ProcessingTraceRecord record) {
        String label = record.detail("event");
        if (label == null) {
            label = record.detail("eventLabel");
        }
        if (label == null) {
            label = eventLabel(record.node());
        }
        return label;
    }

    private void projectChangedSpines(ProcessExecution execution,
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

    private void addCompositeGasAudit(
            ContractsConformanceProjection projection,
            ProcessingConformanceTrace trace,
            boolean completeCounterCoverage) {
        projection.put("manifest.counterCoverage.complete",
                completeCounterCoverage);

        projection.put("trace.nodeManifestOpened.sameId",
                trace.counterQuantity("semantic", "nodeManifestOpened"));
        projection.put("trace.validationProofReused",
                trace.counterQuantity("semantic", "validationProofReused"));
        projection.put("trace.textBlockExamined",
                trace.counterQuantity("semantic", "textBlockExamined"));
        projection.put("trace.integerLimbOperation",
                trace.counterQuantity("semantic", "integerLimbOperation"));
        projection.put("trace.sortComparison",
                trace.counterQuantity("semantic", "sortComparison"));
        projection.put("trace.directIdentityHashBlock.changedDirectOnly",
                trace.counterQuantity(
                        "semantic", "directIdentityHashBlock") > 0L);

        long runtimeEntries = 0L;
        for (GasTraceEntry entry : trace.gas()) {
            if ("runtime".equals(entry.namespace())) {
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

    private static long counterPrefixQuantity(
            ContractsConformanceProjection projection,
            String prefix) {
        ContractsConformanceProjection.Presence gas =
                projection.project("trace.namedEntries");
        if (!gas.isPresent() || !(gas.getValue() instanceof List)) {
            return 0L;
        }
        long total = 0L;
        for (Object entry : (List<?>) gas.getValue()) {
            if (!(entry instanceof Map)) {
                continue;
            }
            Object counter = ((Map<?, ?>) entry).get("counter");
            Object quantity = ((Map<?, ?>) entry).get("quantity");
            if (counter != null
                    && String.valueOf(counter).startsWith(prefix)
                    && quantity instanceof Number) {
                total += ((Number) quantity).longValue();
            }
        }
        return total;
    }

    private static Map<String, Object> publicResult(
            DocumentProcessingResult result) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("status", result.status().wireValue());
        value.put("document", NodeToMapListOrValue.get(result.document()));
        List<Object> events = new ArrayList<>();
        for (Node event : result.events()) {
            events.add(NodeToMapListOrValue.get(event));
        }
        value.put("events", events);
        value.put("totalGas", result.totalGas());
        if (result.diagnostic() != null) {
            Map<String, Object> diagnostic = new LinkedHashMap<>();
            diagnostic.put("category",
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

    private static List<Map<String, Object>> gasEntries(
            List<GasTraceEntry> entries,
            boolean omitSequence) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (GasTraceEntry entry : entries) {
            Map<String, Object> value = new LinkedHashMap<>();
            if (!omitSequence) {
                value.put("sequence", entry.sequence());
            }
            value.put("namespace", entry.namespace());
            value.put("counter", entry.counter());
            value.put("quantity", entry.quantity());
            value.put("weight", entry.weight());
            value.put("subtotal", entry.subtotal());
            if (entry.scopePath() != null) {
                value.put("scopePath", entry.scopePath());
            }
            if (entry.contractKey() != null) {
                value.put("contractKey", entry.contractKey());
            }
            if (entry.logicalPath() != null) {
                value.put("logicalPath", entry.logicalPath());
            }
            if (entry.reason() != null
                    && !entry.reason().isEmpty()
                    && !"unspecified".equals(entry.reason())) {
                value.put("reason", entry.reason());
            }
            result.add(value);
        }
        return result;
    }

    private static Map<String, Object> gasCounterTree(
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

    private static String requiredSelectedBodyBlueId(
            ObjectNode root,
            List<DerivedDelivery> deliveries,
            String unavailableAt) {
        if (!"SelectedBody".equals(unavailableAt)
                && unavailableAt.length() >= 32) {
            return unavailableAt;
        }
        for (DerivedDelivery delivery : deliveries) {
            JsonNode scope = jsonAt(root, delivery.snapshot.scopePath());
            JsonNode contracts = scope != null ? scope.get("contracts") : null;
            if (contracts == null || !contracts.isObject()) {
                continue;
            }
            Iterator<Map.Entry<String, JsonNode>> fields = contracts.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                JsonNode contract = entry.getValue();
                if (!MockTypeBlueIds.MOCK_HANDLER.equals(
                        contract.path("type").path("blueId").asText(null))) {
                    continue;
                }
                if (!delivery.snapshot.channelKey().equals(
                        contract.path("channel").asText(null))) {
                    continue;
                }
                JsonNode result = contract.get("result");
                if (result != null) {
                    return BlueIdCalculator.calculateBlueId(readNode(result));
                }
            }
        }
        throw new IllegalArgumentException(
                "transientUnavailableAt did not identify a selected exact body");
    }

    private static List<Map<String, Object>> compactDeliveries(
            List<DerivedDelivery> deliveries) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (DerivedDelivery delivery : deliveries) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("scopePath", delivery.snapshot.scopePath());
            row.put("channelKey", delivery.snapshot.channelKey());
            result.add(row);
        }
        return result;
    }

    private static List<Map<String, Object>> compactDeliveryHints(JsonNode hints) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (JsonNode hint : hints) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("scopePath", hint.path("scopePath").asText());
            row.put("channelKey", hint.path("channelKey").asText());
            result.add(row);
        }
        return result;
    }

    private static void applyMutableRootState(ObjectNode root,
                                              JsonNode state) {
        Iterator<Map.Entry<String, JsonNode>> fields = state.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String key = field.getKey();
            if ("blueId".equals(key)
                    || "type".equals(key)
                    || "contracts".equals(key)) {
                throw new IllegalArgumentException(
                        "acceptanceStateVariants may change only mutable "
                                + "business state, not /" + key);
            }
            root.set(key, field.getValue().deepCopy());
        }
    }

    private static boolean selectedChannelsAccept(
            ObjectNode root,
            List<DerivedDelivery> deliveries) {
        for (DerivedDelivery delivery : deliveries) {
            JsonNode scope =
                    jsonAt(root, delivery.snapshot.scopePath());
            JsonNode contract = scope == null
                    ? null
                    : scope.path("contracts").get(
                    delivery.snapshot.channelKey());
            if (contract == null
                    || !contract.path("accept").asBoolean(false)) {
                return false;
            }
        }
        return true;
    }

    private static List<DerivedDelivery> filterRawIndexCandidates(
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
    private static List<String> drainExternalEventQueue(
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

    private static List<String> deriveIntervals(JsonNode history,
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

    private static List<Object> orderKeyValues(JsonNode node) {
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

    private static ExternalOrderKey externalOrderKey(JsonNode node) {
        return ExternalOrderKey.of(orderKeyValues(node));
    }

    private static List<Map<String, Object>> mutableMapList(
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

    private static String lifecycleLabel(Node event) {
        String type = event != null && event.getType() != null
                ? event.getType().getBlueId()
                : null;
        if (registryId("DocumentProcessingInitiated").equals(type)) {
            return "initiated";
        }
        if (registryId("DocumentProcessingTerminated").equals(type)) {
            return "terminated";
        }
        return "lifecycle";
    }

    private static String eventLabel(Node event) {
        Node id = property(event, "id");
        if (id != null && id.getValue() != null) {
            return String.valueOf(id.getValue());
        }
        return event != null && event.getValue() != null
                ? String.valueOf(event.getValue())
                : null;
    }

    private static String markerLabel(String key) {
        if ("initialized".equals(key)) {
            return "initialized-marker";
        }
        if ("terminated".equals(key)) {
            return "terminated-marker";
        }
        return key;
    }

    private static ProcessingTraceRecord firstMutation(
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

    private static boolean acceptedSnapshotFrozen(
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

    private static long terminationEventCount(
            ProcessingConformanceTrace trace) {
        long count = 0L;
        for (ProcessingTraceRecord record :
                trace.records(ProcessingTraceRecord.Kind.LIFECYCLE)) {
            if ("terminated".equals(lifecycleLabel(record.node()))) {
                count++;
            }
        }
        return count;
    }

    private static boolean protectedStateUnchanged(Node before, Node after) {
        return semanticEquals(
                protectedState(before),
                protectedState(after));
    }

    private static Map<String, Object> protectedState(Node root) {
        Map<String, Object> value = new LinkedHashMap<>();
        Node contracts = root != null ? root.getContracts() : null;
        value.put("initialized", normalizeNode(property(contracts, "initialized")));
        value.put("terminated", normalizeNode(property(contracts, "terminated")));
        value.put("checkpoint", normalizeNode(property(contracts, "checkpoint")));
        Node embedded = property(contracts, "embedded");
        Map<String, Object> embeddedWithoutPaths = null;
        if (embedded != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> raw =
                    (Map<String, Object>) ContractsConformanceProjection.normalize(embedded);
            embeddedWithoutPaths = new LinkedHashMap<>(raw);
            embeddedWithoutPaths.remove("paths");
        }
        value.put("embeddedNonPaths", embeddedWithoutPaths);
        value.put("generalizationPolicy",
                normalizeNode(property(contracts, "typeGeneralizationPolicy")));
        return value;
    }

    private static Object normalizeNode(Node node) {
        return node == null
                ? null
                : ContractsConformanceProjection.normalize(node);
    }

    private static Node property(Node node, String key) {
        return node != null && node.getProperties() != null
                ? node.getProperties().get(key)
                : null;
    }

    private static String occurrence(String scope, String key) {
        return scope + ":" + key;
    }

    private static int scopeDepth(String scope) {
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

    private static String resolveScope(String scope, String relative) {
        if (relative == null || !relative.startsWith("/")) {
            throw new IllegalArgumentException(
                    "Embedded path must be an absolute relative pointer");
        }
        return "/".equals(scope) ? relative : scope + relative;
    }

    private static Node readNode(JsonNode value) {
        if (value == null) {
            throw new IllegalArgumentException("Blue value is required");
        }
        return UncheckedObjectMapper.JSON_MAPPER.convertValue(value, Node.class);
    }

    /**
     * Variant checkpoint subjects are exact fixture-channel outputs, not
     * authored document fields. Preserve the raw scalar Blue value instead of
     * applying mapper type inference.
     */
    private static Node rawCheckpointSubject(JsonNode value) {
        if (value == null || value.isNull()) {
            throw new IllegalArgumentException(
                    "checkpointSubject must be exact BlueId Input");
        }
        if (value.isValueNode()) {
            return new Node().value(
                    UncheckedObjectMapper.JSON_MAPPER.convertValue(
                            value, Object.class));
        }
        return readNode(value);
    }

    private static ObjectNode requireObject(JsonNode value, String path) {
        if (value == null || !value.isObject()) {
            throw new IllegalArgumentException(path + " must be an object");
        }
        return (ObjectNode) value;
    }

    private static long requiredLong(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null
                || !value.isIntegralNumber()
                || !value.canConvertToLong()
                || value.asLong() < 0L) {
            throw new IllegalArgumentException(
                    field + " must be a non-negative long");
        }
        return value.asLong();
    }

    private static int exactInt(JsonNode value, String path) {
        if (value == null
                || !value.isIntegralNumber()
                || !value.canConvertToInt()
                || value.asInt() < 0) {
            throw new IllegalArgumentException(
                    path + " must be a non-negative int");
        }
        return value.asInt();
    }

    @SuppressWarnings("unchecked")
    private static boolean semanticEquals(Object left, Object right) {
        left = ContractsConformanceProjection.normalize(left);
        right = ContractsConformanceProjection.normalize(right);
        if (left instanceof Number && right instanceof Number) {
            return new java.math.BigDecimal(left.toString()).compareTo(
                    new java.math.BigDecimal(right.toString())) == 0;
        }
        if (left instanceof Map && right instanceof Map) {
            Map<String, Object> l = (Map<String, Object>) left;
            Map<String, Object> r = (Map<String, Object>) right;
            if (!l.keySet().equals(r.keySet())) {
                return false;
            }
            for (String key : l.keySet()) {
                if (!semanticEquals(l.get(key), r.get(key))) {
                    return false;
                }
            }
            return true;
        }
        if (left instanceof List && right instanceof List) {
            List<Object> l = (List<Object>) left;
            List<Object> r = (List<Object>) right;
            if (l.size() != r.size()) {
                return false;
            }
            for (int index = 0; index < l.size(); index++) {
                if (!semanticEquals(l.get(index), r.get(index))) {
                    return false;
                }
            }
            return true;
        }
        return Objects.equals(left, right);
    }

    private static void setPointer(ObjectNode root,
                                   String pointer,
                                   JsonNode value) {
        List<String> segments = pointerSegments(pointer);
        if (segments.isEmpty()) {
            throw new IllegalArgumentException(
                    "Builder target cannot replace the Root");
        }
        ObjectNode current = root;
        for (int index = 0; index < segments.size() - 1; index++) {
            String segment = segments.get(index);
            JsonNode child = current.get(segment);
            if (child == null) {
                child = current.putObject(segment);
            }
            if (!child.isObject()) {
                throw new IllegalArgumentException(
                        "Builder target crosses a non-object at " + segment);
            }
            current = (ObjectNode) child;
        }
        current.set(segments.get(segments.size() - 1), value.deepCopy());
    }

    private static JsonNode jsonAt(JsonNode root, String pointer) {
        JsonNode current = root;
        for (String segment : pointerSegments(pointer)) {
            if (current == null) {
                return null;
            }
            if (current.isObject()) {
                current = current.get(segment);
            } else if (current.isArray()) {
                int index;
                try {
                    index = Integer.parseInt(segment);
                } catch (NumberFormatException invalid) {
                    return null;
                }
                current = index >= 0 && index < current.size()
                        ? current.get(index)
                        : null;
            } else {
                return null;
            }
        }
        return current;
    }

    private static List<String> pointerSegments(String pointer) {
        if (pointer == null || pointer.isEmpty() || "/".equals(pointer)) {
            return Collections.emptyList();
        }
        if (!pointer.startsWith("/")) {
            throw new IllegalArgumentException(
                    "RFC 6901 pointer must start with '/': " + pointer);
        }
        List<String> result = new ArrayList<>();
        String[] raw = pointer.substring(1).split("/", -1);
        for (String segment : raw) {
            result.add(unescapePointer(segment));
        }
        return result;
    }

    private static String unescapePointer(String segment) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < segment.length(); index++) {
            char c = segment.charAt(index);
            if (c != '~') {
                result.append(c);
                continue;
            }
            if (index + 1 >= segment.length()) {
                throw new IllegalArgumentException(
                        "Malformed RFC 6901 escape");
            }
            char escape = segment.charAt(++index);
            if (escape == '0') {
                result.append('~');
            } else if (escape == '1') {
                result.append('/');
            } else {
                throw new IllegalArgumentException(
                        "Malformed RFC 6901 escape ~" + escape);
            }
        }
        return result.toString();
    }

    private static ObjectNode objectAt(ObjectNode root,
                                       String pointer,
                                       boolean create) {
        JsonNode existing = jsonAt(root, pointer);
        if (existing != null) {
            if (!existing.isObject()) {
                throw new IllegalArgumentException(
                        pointer + " is not an object");
            }
            return (ObjectNode) existing;
        }
        if (!create) {
            return null;
        }
        ObjectNode created =
                UncheckedObjectMapper.JSON_MAPPER.createObjectNode();
        setPointer(root, pointer, created);
        return (ObjectNode) jsonAt(root, pointer);
    }

    private static ObjectNode objectField(ObjectNode parent,
                                          String field,
                                          boolean create) {
        JsonNode value = parent.get(field);
        if (value == null && create) {
            return parent.putObject(field);
        }
        if (value == null) {
            return null;
        }
        if (!value.isObject()) {
            throw new IllegalArgumentException(field + " is not an object");
        }
        return (ObjectNode) value;
    }

    private static ArrayNode arrayField(ObjectNode parent,
                                        String field,
                                        boolean create) {
        JsonNode value = parent.get(field);
        if (value == null && create) {
            return parent.putArray(field);
        }
        if (value == null) {
            return null;
        }
        if (!value.isArray()) {
            throw new IllegalArgumentException(field + " is not a list");
        }
        return (ArrayNode) value;
    }

    private static ObjectNode firstScriptedHandler(ObjectNode contracts) {
        Iterator<JsonNode> values = contracts.elements();
        while (values.hasNext()) {
            JsonNode value = values.next();
            if (value.isObject()
                    && MockTypeBlueIds.MOCK_HANDLER.equals(
                    value.path("type").path("blueId").asText(null))) {
                return (ObjectNode) value;
            }
        }
        return null;
    }

    private interface ObjectVisitor {
        void visit(ObjectNode value);
    }

    private static void visit(JsonNode node, ObjectVisitor visitor) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            visitor.visit((ObjectNode) node);
            node.elements().forEachRemaining(child -> visit(child, visitor));
        } else if (node.isArray()) {
            node.elements().forEachRemaining(child -> visit(child, visitor));
        }
    }

    private static String registryId(String key) {
        return RegistryEnvironment.INSTANCE.idByKey.get(key);
    }

    private static final class RegistryEnvironment {
        private static final RegistryEnvironment INSTANCE = loadInternal();

        final Map<String, Node> nodesByBlueId;
        final Map<String, String> idByKey;

        private RegistryEnvironment(Map<String, Node> nodesByBlueId,
                                    Map<String, String> idByKey) {
            this.nodesByBlueId =
                    Collections.unmodifiableMap(new LinkedHashMap<>(nodesByBlueId));
            this.idByKey =
                    Collections.unmodifiableMap(new LinkedHashMap<>(idByKey));
        }

        static RegistryEnvironment load() {
            return INSTANCE;
        }

        Node require(String blueId) {
            Node value = nodesByBlueId.get(blueId);
            if (value == null) {
                throw new IllegalStateException(
                        "Registry has no exact node " + blueId);
            }
            return value.clone();
        }

        boolean isSubtype(String candidate, String parent) {
            if (candidate == null || parent == null) {
                return false;
            }
            Set<String> visited = new LinkedHashSet<>();
            String current = candidate;
            while (current != null && visited.add(current)) {
                if (parent.equals(current)) {
                    return true;
                }
                Node node = nodesByBlueId.get(current);
                current = node != null && node.getType() != null
                        ? node.getType().getBlueId()
                        : null;
            }
            return false;
        }

        private static RegistryEnvironment loadInternal() {
            Map<String, Node> nodes = new LinkedHashMap<>();
            Map<String, String> keys = new LinkedHashMap<>();
            loadRegistry(CONTRACTS_REGISTRY_ROOT, nodes, keys);
            loadRegistry(LANGUAGE_REGISTRY_ROOT, nodes, keys);
            if (!MockTypeBlueIds.MOCK_EXTERNAL_CHANNEL.equals(
                    keys.get("ScriptedExternalChannel"))
                    || !MockTypeBlueIds.MOCK_HANDLER.equals(
                    keys.get("ScriptedHandler"))) {
                throw new IllegalStateException(
                        "Fixture runtime registry identity mismatch");
            }
            return new RegistryEnvironment(nodes, keys);
        }

        private static void loadRegistry(String root,
                                         Map<String, Node> nodes,
                                         Map<String, String> keys) {
            JsonNode manifest = readYaml(root + "manifest.yaml");
            JsonNode entries = manifest.get("entries");
            if (entries == null || !entries.isArray()) {
                throw new IllegalStateException(
                        "Registry manifest has no entries: " + root);
            }
            for (JsonNode entry : entries) {
                String key = entry.path("key").asText();
                String blueId = entry.path("blueId").asText();
                String path = entry.path("path").asText();
                Node node = readNode(readYaml(root + path));
                String calculated = BlueIdCalculator.calculateBlueId(node);
                if (!blueId.equals(calculated)) {
                    throw new IllegalStateException(
                            "Registry node identity mismatch for "
                                    + root + path);
                }
                Node duplicate = nodes.put(blueId, node);
                if (duplicate != null
                        && !semanticEquals(
                        normalizeNode(duplicate), normalizeNode(node))) {
                    throw new IllegalStateException(
                            "Registry BlueId collision for " + blueId);
                }
                keys.put(key, blueId);
            }
        }
    }

    private static JsonNode readYaml(String resource) {
        try (InputStream input = ContractsFixtureHarness.class
                .getClassLoader().getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException(
                        "Missing Contracts harness resource " + resource);
            }
            return YAML.readTree(input);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Unable to read Contracts harness resource " + resource,
                    exception);
        }
    }

    private static final class ScopeValue {
        final String path;
        final ObjectNode value;

        ScopeValue(String path, ObjectNode value) {
            this.path = path;
            this.value = value;
        }
    }

    private static final class DerivedDelivery {
        final ExternalDeliverySnapshot snapshot;
        final String checkpointDomainBlueId;
        final Node checkpointDomainNode;
        final Node checkpointSubjectNode;

        DerivedDelivery(ExternalDeliverySnapshot snapshot,
                        String checkpointDomainBlueId,
                        Node checkpointDomainNode,
                        Node checkpointSubjectNode) {
            this.snapshot = snapshot;
            this.checkpointDomainBlueId = checkpointDomainBlueId;
            this.checkpointDomainNode = checkpointDomainNode.clone();
            this.checkpointSubjectNode = checkpointSubjectNode.clone();
        }
    }

    private static final class FixtureGeneralization {
        final List<String> candidates;
        final String validCandidate;
        final Map<String, String> blueIdByCandidate;
        final Map<String, Node> nodesByBlueId;

        private FixtureGeneralization(
                List<String> candidates,
                String validCandidate,
                Map<String, String> blueIdByCandidate,
                Map<String, Node> nodesByBlueId) {
            this.candidates = Collections.unmodifiableList(
                    new ArrayList<>(candidates));
            this.validCandidate = validCandidate;
            this.blueIdByCandidate = Collections.unmodifiableMap(
                    new LinkedHashMap<>(blueIdByCandidate));
            this.nodesByBlueId = Collections.unmodifiableMap(
                    new LinkedHashMap<>(nodesByBlueId));
        }

        static FixtureGeneralization create(
                ObjectNode root,
                JsonNode runtime) {
            JsonNode declared = runtime != null
                    ? runtime.get("generalizationCandidates")
                    : null;
            if (declared == null) {
                return null;
            }
            List<String> candidates = new ArrayList<>();
            for (JsonNode candidate : declared) {
                candidates.add(candidate.asText());
            }
            String validCandidate =
                    runtime.path("validCandidate").asText(null);
            if (candidates.isEmpty()
                    || validCandidate == null
                    || !candidates.contains(validCandidate)) {
                throw new IllegalArgumentException(
                        "Generalization controls require a valid candidate "
                                + "from the declared ancestor chain");
            }
            if (root.has("type")) {
                throw new IllegalArgumentException(
                        "Generalization fixture root already declares a type");
            }

            Map<String, String> blueIds = new LinkedHashMap<>();
            Map<String, Node> nodes = new LinkedHashMap<>();
            String parentBlueId = registryId("Integer");
            for (int index = candidates.size() - 1;
                 index >= 0;
                 index--) {
                Node typeNode = new Node()
                        .type(new Node().blueId(parentBlueId));
                String blueId =
                        BlueIdCalculator.calculateBlueId(typeNode);
                blueIds.put(candidates.get(index), blueId);
                nodes.put(blueId, typeNode);
                parentBlueId = blueId;
            }
            Map<String, String> orderedBlueIds =
                    new LinkedHashMap<>();
            for (String candidate : candidates) {
                orderedBlueIds.put(
                        candidate, blueIds.get(candidate));
            }
            root.putObject("type").put(
                    "blueId",
                    orderedBlueIds.get(candidates.get(0)));
            return new FixtureGeneralization(
                    candidates,
                    validCandidate,
                    orderedBlueIds,
                    nodes);
        }

        FixtureGeneralizationPlanner newPlanner() {
            return new FixtureGeneralizationPlanner(this);
        }
    }

    private static final class FixtureGeneralizationPlanner
            implements ConformancePlannerOverride {
        private final FixtureGeneralization definition;
        private final List<String> tested = new ArrayList<>();
        private String selected;

        private FixtureGeneralizationPlanner(
                FixtureGeneralization definition) {
            this.definition = definition;
        }

        @Override
        public boolean applies() {
            return true;
        }

        @Override
        public ConformancePlan plan(
                FrozenNode canonicalRoot,
                FrozenNode resolvedRoot,
                List<ConformanceChangedPath> changedPaths) {
            if (selected != null) {
                return ConformancePlan.unchanged(
                        canonicalRoot, resolvedRoot);
            }
            for (String candidate : definition.candidates) {
                tested.add(candidate);
                if (definition.validCandidate.equals(candidate)) {
                    selected = candidate;
                    break;
                }
            }
            if (selected == null) {
                throw new IllegalStateException(
                        "No valid fixture generalization candidate");
            }

            String selectedBlueId =
                    definition.blueIdByCandidate.get(selected);
            Node nextCanonicalNode = canonicalRoot.toNode()
                    .type(new Node().blueId(selectedBlueId));
            Node nextResolvedNode = resolvedRoot.toNode()
                    .type(new Node().blueId(selectedBlueId));
            FrozenNode nextCanonical =
                    FrozenNode.fromNode(nextCanonicalNode);
            FrozenNode nextResolved =
                    FrozenNode.fromResolvedNode(nextResolvedNode);
            return ConformancePlan.generalized(
                    nextCanonical,
                    nextResolved,
                    Collections.emptyList(),
                    Collections.singletonList("/type"),
                    false);
        }

        List<String> tested() {
            return Collections.unmodifiableList(
                    new ArrayList<>(tested));
        }

        String selected() {
            return selected;
        }
    }

    private static final class PreparedInput {
        final ObjectNode rootJson;
        final Node root;
        final Node event;
        final JsonNode runtimeControls;
        final Map<String, Node> providerNodes;
        final List<DerivedDelivery> derivedDeliveries;
        final VerifiedExecutionEvidence evidence;
        final ExternalDeliveryPlan deliveryPlan;
        final FixtureGeneralization generalization;
        final Node checkpointSubjectOverride;
        final String rootForm;
        final String cacheMode;
        final String batchingMode;

        PreparedInput(ObjectNode rootJson,
                      Node root,
                      Node event,
                      JsonNode runtimeControls,
                      Map<String, Node> providerNodes,
                      List<DerivedDelivery> derivedDeliveries,
                      VerifiedExecutionEvidence evidence,
                      ExternalDeliveryPlan deliveryPlan,
                      FixtureGeneralization generalization,
                      Node checkpointSubjectOverride,
                      String rootForm,
                      String cacheMode,
                      String batchingMode) {
            this.rootJson = rootJson.deepCopy();
            this.root = root;
            this.event = event;
            this.runtimeControls = runtimeControls != null
                    ? runtimeControls.deepCopy()
                    : null;
            this.providerNodes =
                    Collections.unmodifiableMap(new LinkedHashMap<>(providerNodes));
            this.derivedDeliveries = derivedDeliveries;
            this.evidence = evidence;
            this.deliveryPlan = deliveryPlan;
            this.generalization = generalization;
            this.checkpointSubjectOverride =
                    checkpointSubjectOverride != null
                            ? checkpointSubjectOverride.clone()
                            : null;
            this.rootForm = rootForm;
            this.cacheMode = cacheMode;
            this.batchingMode = batchingMode;
        }

        boolean snapshotRootForm() {
            return ContractsFixtureHarness.snapshotRootForm(rootForm);
        }

        boolean referenceBackedRootForm() {
            return ContractsFixtureHarness.referenceBackedRootForm(
                    rootForm);
        }
    }

    private static final class ProcessorBundle {
        final DocumentProcessor processor;
        final ScriptedContractsRuntime runtime;
        final FixtureGeneralizationPlanner generalization;
        final Blue blue;
        final FixturePhysicalProvider provider;

        ProcessorBundle(DocumentProcessor processor,
                        ScriptedContractsRuntime runtime,
                        FixtureGeneralizationPlanner generalization,
                        Blue blue,
                        FixturePhysicalProvider provider) {
            this.processor = processor;
            this.runtime = runtime;
            this.generalization = generalization;
            this.blue = blue;
            this.provider = provider;
        }
    }

    /**
     * Physical fixture provider used to make warm/cold and
     * batched/unbatched variants real preparation strategies. None of these
     * counters are exposed through semantic projections or gas traces.
     */
    private static final class FixturePhysicalProvider
            implements NodeProvider {
        private final Map<String, Node> backing = new LinkedHashMap<>();
        private final Map<String, Node> cache = new LinkedHashMap<>();
        private final String cacheMode;
        private final String batchingMode;
        private final int initialCacheEntries;
        private long requests;
        private long backendLoads;
        private int largestBackendLoad;

        FixturePhysicalProvider(Map<String, Node> nodes,
                                String cacheMode,
                                String batchingMode) {
            if (!"cold".equals(cacheMode)
                    && !"warm".equals(cacheMode)) {
                throw new IllegalArgumentException(
                        "Unsupported fixture cache mode: " + cacheMode);
            }
            if (!"unbatched".equals(batchingMode)
                    && !"batched".equals(batchingMode)) {
                throw new IllegalArgumentException(
                        "Unsupported fixture batching mode: "
                                + batchingMode);
            }
            this.cacheMode = cacheMode;
            this.batchingMode = batchingMode;
            for (Map.Entry<String, Node> entry : nodes.entrySet()) {
                backing.put(entry.getKey(), entry.getValue().clone());
            }
            if ("warm".equals(cacheMode)) {
                copyAll(backing, cache);
            }
            this.initialCacheEntries = cache.size();
        }

        @Override
        public List<Node> fetchByBlueId(String blueId) {
            requests++;
            Node cached = cache.get(blueId);
            if (cached != null) {
                return Collections.singletonList(cached.clone());
            }
            if ("batched".equals(batchingMode)) {
                backendLoads++;
                largestBackendLoad =
                        Math.max(largestBackendLoad, backing.size());
                copyAll(backing, cache);
            } else {
                backendLoads++;
                Node exact = backing.get(blueId);
                if (exact != null) {
                    cache.put(blueId, exact.clone());
                    largestBackendLoad =
                            Math.max(largestBackendLoad, 1);
                }
            }
            Node loaded = cache.get(blueId);
            return loaded == null
                    ? null
                    : Collections.singletonList(loaded.clone());
        }

        void verifyPreparation() {
            if ("cold".equals(cacheMode)
                    && initialCacheEntries != 0) {
                throw new AssertionError(
                        "Cold provider began with cached content");
            }
            if ("warm".equals(cacheMode)
                    && initialCacheEntries != backing.size()) {
                throw new AssertionError(
                        "Warm provider did not preload exact content");
            }
            if ("unbatched".equals(batchingMode)
                    && largestBackendLoad > 1) {
                throw new AssertionError(
                        "Unbatched provider performed a bulk load");
            }
            if ("batched".equals(batchingMode)
                    && backendLoads > 0
                    && largestBackendLoad != backing.size()) {
                throw new AssertionError(
                        "Batched provider did not load one physical batch");
            }
            if (requests > 0
                    && "cold".equals(cacheMode)
                    && backendLoads == 0) {
                throw new AssertionError(
                        "Cold provider request bypassed physical storage");
            }
        }

        private static void copyAll(Map<String, Node> source,
                                    Map<String, Node> target) {
            for (Map.Entry<String, Node> entry : source.entrySet()) {
                target.put(entry.getKey(), entry.getValue().clone());
            }
        }
    }

    private static final class ProcessExecution {
        final DocumentProcessingResult result;
        final ProcessingConformanceTrace trace;
        final PlatformCommitCompanion platformCommitCompanion;
        final FixtureGeneralizationPlanner generalization;

        ProcessExecution(DocumentProcessingResult result,
                         ProcessingConformanceTrace trace,
                         PlatformCommitCompanion platformCommitCompanion,
                         FixtureGeneralizationPlanner generalization) {
            this.result = result;
            this.trace = trace;
            this.platformCommitCompanion =
                    platformCommitCompanion;
            this.generalization = generalization;
        }
    }

    private Map<String, Node> verifyProviderNodes(JsonNode provider) {
        Map<String, Node> result = new LinkedHashMap<>();
        JsonNode nodes = provider.get("nodes");
        if (nodes == null) {
            return result;
        }
        nodes.fields().forEachRemaining(entry -> {
            Node node = readNode(entry.getValue());
            String actual = BlueIdCalculator.calculateBlueId(node);
            if (!entry.getKey().equals(actual)) {
                throw new IllegalArgumentException(
                        "Provider node identity mismatch: expected "
                                + entry.getKey() + " but calculated " + actual);
            }
            result.put(entry.getKey(), node);
        });
        return result;
    }

    private List<DerivedDelivery> deriveDeliveries(
            ObjectNode root,
            JsonNode event,
            JsonNode hints,
            String eventBlueId,
            Node checkpointSubjectOverride) {
        String subscriptionKey = event.path("subscriptionKey").asText(null);
        if (subscriptionKey == null) {
            throw new IllegalArgumentException(
                    "Fixture event requires subscriptionKey");
        }
        Map<String, JsonNode> hintByOccurrence = new LinkedHashMap<>();
        for (JsonNode hint : hints) {
            String occurrence = occurrence(
                    hint.path("scopePath").asText(),
                    hint.path("channelKey").asText());
            if (hintByOccurrence.put(occurrence, hint) != null) {
                throw new IllegalArgumentException(
                        "Duplicate delivery hint " + occurrence);
            }
        }

        List<ScopeValue> scopes = enumerateDeclaredScopes(root);
        List<DerivedDelivery> result = new ArrayList<>();
        for (ScopeValue scope : scopes) {
            JsonNode contracts = scope.value.get("contracts");
            if (contracts == null || !contracts.isObject()
                    || contracts.has("terminated")) {
                continue;
            }
            Iterator<Map.Entry<String, JsonNode>> fields = contracts.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                JsonNode contract = entry.getValue();
                String typeBlueId = contract.path("type").path("blueId").asText(null);
                if (!registry.isSubtype(typeBlueId, registryId("ExternalChannel"))) {
                    continue;
                }
                if (!subscriptionKey.equals(
                        contract.path("subscriptionKey").asText(null))) {
                    continue;
                }
                String key = occurrence(scope.path, entry.getKey());
                JsonNode hint = hintByOccurrence.remove(key);
                int order = contract.path("order").asInt(0);
                if (hint != null && hint.has("order")
                        && hint.get("order").asInt() != order) {
                    throw new IllegalArgumentException(
                            "Delivery hint order mismatch at " + key);
                }
                Node contractNode = readNode(contract);
                String contribution = BlueIdCalculator.calculateBlueId(contractNode);
                String domain = contract.path("checkpointDomain").asText(null);
                if (domain == null) {
                    throw new IllegalArgumentException(
                            "External Channel has no checkpointDomain at " + key);
                }
                List<String> contributions =
                        Collections.singletonList(contribution);
                Node domainNode = checkpointDomainNode(
                        typeBlueId,
                        contributions,
                        domain);
                String domainBlueId =
                        BlueIdCalculator.calculateBlueId(domainNode);
                String canonicalDomainBlueId = CheckpointDomain.derive(
                        typeBlueId, contributions, domain);
                if (!domainBlueId.equals(canonicalDomainBlueId)) {
                    throw new IllegalStateException(
                            "Checkpoint domain derivation drift");
                }
                String subjectBlueId = eventBlueId;
                Node subjectNode = readNode(event);
                if (checkpointSubjectOverride != null) {
                    subjectNode = checkpointSubjectOverride.clone();
                    subjectBlueId =
                            BlueIdCalculator.calculateBlueId(
                                    subjectNode);
                }
                ExternalDeliverySnapshot.Builder snapshot =
                        ExternalDeliverySnapshot.builder(scope.path, entry.getKey())
                                .order(order)
                                .sourceContribution(contribution)
                                .effectiveTypeBlueId(typeBlueId)
                                .subscriptionKey(subscriptionKey)
                                .checkpointDomainBlueId(domainBlueId)
                                .checkpointSubjectBlueId(subjectBlueId);
                if (hint != null && hint.has("activationStartExclusive")) {
                    snapshot.activationStartExclusive(
                            externalOrderKey(
                                    hint.get("activationStartExclusive")));
                }
                result.add(new DerivedDelivery(
                        snapshot.build(),
                        domainBlueId,
                        domainNode,
                        subjectNode));
            }
        }
        result.sort(Comparator
                .comparingInt((DerivedDelivery value) ->
                        scopeDepth(value.snapshot.scopePath()))
                .reversed()
                .thenComparing(value -> value.snapshot.scopePath())
                .thenComparingInt(value -> value.snapshot.order())
                .thenComparing(value -> value.snapshot.channelKey()));
        if (!hintByOccurrence.isEmpty()) {
            throw new IllegalArgumentException(
                    "Delivery hint is not derivable from the exact Root: "
                            + hintByOccurrence.keySet());
        }
        List<String> derivedKeys = new ArrayList<>();
        for (DerivedDelivery delivery : result) {
            derivedKeys.add(occurrence(
                    delivery.snapshot.scopePath(),
                    delivery.snapshot.channelKey()));
        }
        List<String> hintedKeys = new ArrayList<>();
        for (JsonNode hint : hints) {
            hintedKeys.add(occurrence(
                    hint.path("scopePath").asText(),
                    hint.path("channelKey").asText()));
        }
        /*
         * platform/canonicalPreselection deliberately exercises an omission
         * and is classified by executePlatform. Every other hint set must be
         * the complete canonical preselection.
         */
        if (!hintedKeys.equals(derivedKeys)) {
            // The caller distinguishes the declared platform omission.
            if (hints.size() != 0) {
                throw new IllegalArgumentException(
                        "Delivery hints are not the complete canonical order: "
                                + hintedKeys + " != " + derivedKeys);
            }
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Builds the complete retained active index surface independently of the
     * current event's canonical preselection. The fixture platform treats
     * admission revision zero as the activation revision of the supplied
     * authoritative Root.
     */
    private List<SubscriptionDelta.Entry>
    deriveActiveSubscriptionIntervals(
            ObjectNode root,
            JsonNode deliveryHints) {
        Map<String, ExternalOrderKey> starts =
                new LinkedHashMap<>();
        for (JsonNode hint : deliveryHints) {
            if (hint.has("activationStartExclusive")) {
                starts.put(
                        occurrence(
                                hint.path("scopePath").asText(),
                                hint.path("channelKey").asText()),
                        externalOrderKey(
                                hint.get("activationStartExclusive")));
            }
        }
        List<SubscriptionDelta.Entry> result =
                new ArrayList<>();
        for (ScopeValue scope : enumerateDeclaredScopes(root)) {
            JsonNode contracts = scope.value.get("contracts");
            if (contracts == null || !contracts.isObject()
                    || contracts.has("terminated")) {
                continue;
            }
            Iterator<Map.Entry<String, JsonNode>> fields =
                    contracts.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry =
                        fields.next();
                JsonNode contract = entry.getValue();
                String typeBlueId =
                        contract.path("type").path("blueId")
                                .asText(null);
                if (!registry.isSubtype(
                        typeBlueId,
                        registryId("ExternalChannel"))) {
                    continue;
                }
                List<String> subscriptionKeys =
                        new ArrayList<>();
                JsonNode plural =
                        contract.get("subscriptionKeys");
                if (plural != null && plural.isArray()) {
                    for (JsonNode key : plural) {
                        if (!key.isTextual()
                                || key.asText().isEmpty()) {
                            throw new IllegalArgumentException(
                                    "Invalid retained subscription key at "
                                            + scope.path + "/"
                                            + entry.getKey());
                        }
                        subscriptionKeys.add(key.asText());
                    }
                } else {
                    String singular =
                            contract.path("subscriptionKey")
                                    .asText(null);
                    if (singular != null
                            && !singular.isEmpty()) {
                        subscriptionKeys.add(singular);
                    }
                }
                if (subscriptionKeys.isEmpty()) {
                    throw new IllegalArgumentException(
                            "Active External Channel has no subscription "
                                    + "keys at " + scope.path + "/"
                                    + entry.getKey());
                }
                Node contractNode = readNode(contract);
                String contribution =
                        BlueIdCalculator.calculateBlueId(
                                contractNode);
                String discriminator =
                        contract.path("checkpointDomain")
                                .asText(null);
                if (discriminator == null) {
                    throw new IllegalArgumentException(
                            "Active External Channel has no checkpoint "
                                    + "domain at " + scope.path + "/"
                                    + entry.getKey());
                }
                String domain = CheckpointDomain.derive(
                        typeBlueId,
                        Collections.singletonList(contribution),
                        discriminator);
                result.add(new SubscriptionDelta.Entry(
                        scope.path,
                        entry.getKey(),
                        typeBlueId,
                        Collections.singletonList(contribution),
                        contract.path("order").asInt(0),
                        subscriptionKeys,
                        domain,
                        0L,
                        starts.get(occurrence(
                                scope.path, entry.getKey())),
                        null));
            }
        }
        return Collections.unmodifiableList(result);
    }

    private List<ScopeValue> enumerateDeclaredScopes(ObjectNode root) {
        List<ScopeValue> result = new ArrayList<>();
        Set<String> visitedIds = new LinkedHashSet<>();
        enumerateDeclaredScopes("/", root, result, visitedIds);
        return result;
    }

    private void enumerateDeclaredScopes(String path,
                                         ObjectNode scope,
                                         List<ScopeValue> result,
                                         Set<String> ancestry) {
        result.add(new ScopeValue(path, scope));
        String identity = BlueIdCalculator.calculateBlueId(readNode(scope));
        if (!ancestry.add(identity)) {
            throw new IllegalArgumentException(
                    "Embedded scope ancestry cycle at " + path);
        }
        JsonNode embedded = scope.path("contracts").path("embedded");
        JsonNode paths = embedded.path("paths");
        if (paths.isArray()) {
            for (JsonNode declared : paths) {
                String childPath = resolveScope(path, declared.asText());
                JsonNode child = jsonAt(result.get(0).value, childPath);
                if (child == null || child.isMissingNode() || child.isNull()) {
                    continue;
                }
                if (!child.isObject()) {
                    throw new IllegalArgumentException(
                            "Embedded scope is not an object at " + childPath);
                }
                enumerateDeclaredScopes(
                        childPath,
                        (ObjectNode) child,
                        result,
                        new LinkedHashSet<>(ancestry));
            }
        }
    }

}
