package blue.language.conformance.contracts;

import blue.language.codec.jackson.UncheckedObjectMapper;
import blue.language.conformance.contracts.closure.ClosureFixtureConformance;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.NodePathEditor;
import blue.language.model.NodeWireForm;
import blue.language.model.wire.BlueLanguageConstants;
import blue.language.processor.GasSchedule;
import blue.language.processor.ProcessorDiagnostic;
import blue.language.processor.closure.AdmissionCause;
import blue.language.processor.closure.AdmissionKind;
import blue.language.processor.closure.AffectedClosureSnapshot;
import blue.language.processor.closure.BlueClosureContracts;
import blue.language.processor.closure.ChannelOccurrence;
import blue.language.processor.closure.CheckpointDomainValue;
import blue.language.processor.closure.CheckpointWrite;
import blue.language.processor.closure.ClosureAttemptResult;
import blue.language.processor.closure.ClosureCommitCompanion;
import blue.language.processor.closure.ClosureEnvironment;
import blue.language.processor.closure.ClosureEvidenceFactory;
import blue.language.processor.closure.ClosureExecutionObserver;
import blue.language.processor.closure.ClosureImplementationEvidence;
import blue.language.processor.closure.ClosureInvocationInput;
import blue.language.processor.closure.ClosureProcessResult;
import blue.language.processor.closure.ClosureWorkOccurrence;
import blue.language.processor.closure.ComponentFinalizationInput;
import blue.language.processor.closure.ComponentFinalizationKernel;
import blue.language.processor.closure.ComponentFinalizationResult;
import blue.language.processor.closure.ComponentSnapshot;
import blue.language.processor.closure.DocumentId;
import blue.language.processor.closure.DocumentStepEvidence;
import blue.language.processor.closure.ExecutionPolicy;
import blue.language.processor.closure.FinalizedDocumentEvidence;
import blue.language.processor.closure.GasTraceEntry;
import blue.language.processor.closure.GraphChange;
import blue.language.processor.closure.ManagedDocumentGraph;
import blue.language.processor.closure.ManagedDocumentSnapshot;
import blue.language.processor.closure.ManagedOccurrenceBinding;
import blue.language.processor.closure.PublicEventOccurrence;
import blue.language.processor.closure.RejectedCharge;
import blue.language.processor.closure.ResultingDocument;
import blue.language.processor.closure.ScopeAddress;
import blue.language.processor.closure.SubscriptionDelta;
import blue.language.processor.closure.SubscriptionState;
import blue.language.processor.closure.TentativeFinalization;
import blue.language.provider.CyclicSetProof;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static blue.language.conformance.contracts.FullLifecycleFixtureAssertions.assertExpectations;
import static blue.language.conformance.contracts.FullLifecycleFixtureAssertions.verifyParity;
import static blue.language.conformance.contracts.FullLifecycleFixtureExporter.FIXTURE_SCHEMA;
import static blue.language.conformance.contracts.FullLifecycleFixtureFiles.require;
import static blue.language.conformance.contracts.FullLifecycleFixtureSourceValidator.cases;
import static blue.language.conformance.contracts.FullLifecycleFixtureSourceValidator.inputOrder;
import static blue.language.conformance.contracts.FullLifecycleFixtureSourceValidator.referenceIdentityAt;
import static blue.language.conformance.contracts.FullLifecycleFixtureSourceValidator.scalarTextAt;
import static blue.language.conformance.contracts.FullLifecycleFixtureSourceValidator.validateDocumentOccurrenceMacros;
import static blue.language.conformance.contracts.FullLifecycleFixtureSourceValidator.validateRuntime;
import static blue.language.conformance.contracts.FullLifecycleFixtureSourceValidator.validateSourceEnvelope;
import static blue.language.conformance.contracts.FullLifecycleFixtureSupport.DIRECT;
import static blue.language.conformance.contracts.FullLifecycleFixtureSupport.DUMMY_BLUE_ID;
import static blue.language.conformance.contracts.FullLifecycleFixtureSupport.JSON;
import static blue.language.conformance.contracts.FullLifecycleFixtureSupport.array;
import static blue.language.conformance.contracts.FullLifecycleFixtureSupport.node;
import static blue.language.conformance.contracts.FullLifecycleFixtureSupport.nullableText;
import static blue.language.conformance.contracts.FullLifecycleFixtureSupport.object;
import static blue.language.conformance.contracts.FullLifecycleFixtureSupport.readYaml;
import static blue.language.conformance.contracts.FullLifecycleFixtureSupport.rejectUnresolvedMacros;
import static blue.language.conformance.contracts.FullLifecycleFixtureSupport.requiredBoolean;
import static blue.language.conformance.contracts.FullLifecycleFixtureSupport.requiredLong;
import static blue.language.conformance.contracts.FullLifecycleFixtureSupport.requiredObject;
import static blue.language.conformance.contracts.FullLifecycleFixtureSupport.sha256;
import static blue.language.conformance.contracts.FullLifecycleFixtureSupport.text;
import static blue.language.conformance.contracts.FullLifecycleFixtureSupport.wire;

/** Compiles validated full-lifecycle sources through normative admission. */
final class FullLifecycleFixtureCompiler {

    private final CandidatePackage candidate;

    FullLifecycleFixtureCompiler(Path candidateRoot) {
        this.candidate = CandidatePackage.load(candidateRoot);
    }

    void preflight(JsonNode source) {
        validateSourceEnvelope(source);
        Map<String, JsonNode> events = resolveEvents(
                object(source, "events"));
        ObjectNode resolvedRuntime = (ObjectNode) resolveMacros(
                object(source, "runtime"), events, null);
        validateRuntime(source, resolvedRuntime);
        ObjectNode documents = object(source, "documents");
        for (JsonNode caseValue : cases(source)) {
            List<String> order = inputOrder(
                    source, caseValue, documents);
            validateDocumentOccurrenceMacros(
                    source, caseValue, order);
        }
    }

    List<CompiledFixture> compile(JsonNode source) {
        validateSourceEnvelope(source);
        String sourceId = text(source, "id");
        String scenario = text(source, "scenario");
        Map<String, JsonNode> events = resolveEvents(
                object(source, "events"));
        ObjectNode resolvedRuntime = (ObjectNode) resolveMacros(
                object(source, "runtime"), events, null);
        validateRuntime(source, resolvedRuntime);

        List<JsonNode> cases = cases(source);
        ArrayList<CompiledFixture> compiled = new ArrayList<CompiledFixture>();
        LinkedHashMap<String, CompiledFixture> bySuffix =
                new LinkedHashMap<String, CompiledFixture>();
        for (JsonNode caseValue : cases) {
            String suffix = caseValue == null
                    ? null : text(caseValue, "suffix");
            String fixtureId = suffix == null
                    ? sourceId : sourceId + "-" + suffix;
            String repeatOf = caseValue == null
                    ? null : nullableText(caseValue, "repeatOf");
            CompiledFixture result;
            if (repeatOf != null) {
                CompiledFixture repeated = bySuffix.get(repeatOf);
                require(repeated != null,
                        fixtureId + " repeatOf must name an earlier case");
                result = executePrepared(
                        source,
                        scenario,
                        fixtureId,
                        events,
                        resolvedRuntime,
                        repeated.input.deepCopy());
            } else {
                ObjectNode input = prepareInput(
                        source, caseValue, fixtureId,
                        events, resolvedRuntime);
                result = executePrepared(
                        source,
                        scenario,
                        fixtureId,
                        events,
                        resolvedRuntime,
                        input);
            }
            compiled.add(result);
            if (suffix != null) {
                require(bySuffix.put(suffix, result) == null,
                        sourceId + " case suffixes must be unique");
            }
        }
        verifyParity(source, compiled);
        return Collections.unmodifiableList(compiled);
    }

    private ObjectNode prepareInput(
            JsonNode source,
            JsonNode caseValue,
            String fixtureId,
            Map<String, JsonNode> events,
            ObjectNode runtime) {
        ObjectNode runtimeEnvelope = JSON.objectNode();
        runtimeEnvelope.set("runtime", runtime.deepCopy());
        try (ClosureFixtureRuntime fixtureRuntime =
                     ClosureFixtureRuntime.fromFixture(
                             runtimeEnvelope, candidate.root)) {
            ClosureEnvironment environment = ClosureEvidenceFactory.environment(
                    fixtureRuntime.processor(),
                    candidate.languageSpecificationIdentity,
                    candidate.contractsSpecificationIdentity,
                    "nfc-document-lineage-v1",
                    "exact-document-lineage",
                    "fixture-exact-node-provider-v1",
                    "canonical-source-order-v1",
                    "blue-contracts-1.0-portable-limits",
                    GasSchedule.contracts10().portableLimits());
            PreparedGraph graph = prepareGraph(
                    source, caseValue, events,
                    environment.managedBindingPolicyIdentity());
            long sharedLimit = sharedLimit(object(source, "gas"));
            String sourceId = text(source, "id");
            ExecutionPolicy gasPolicy = ClosureEvidenceFactory.executionPolicy(
                    sharedLimit,
                    Collections.<DocumentId, Long>emptyMap(),
                    sharedLimit == GasSchedule.contracts10().maxProcessGas()
                            ? "release-default"
                            : sourceId + "-shared-gas");
            AdmissionCause cause = ClosureEvidenceFactory.admissionCause(
                    AdmissionKind.TOP_LEVEL_ADMISSION,
                    sourceId,
                    null,
                    null,
                    sourceId);
            AffectedClosureSnapshot snapshot =
                    ClosureEvidenceFactory.affectedClosure(
                            1L,
                            graph.documents,
                            graph.occurrences,
                            graph.components,
                            graph.publicRoots);
            ClosureInvocationInput input = ClosureEvidenceFactory.admitClosure(
                    snapshot, cause, null, gasPolicy, environment);
            return FullLifecycleFixtureJson.input(input, graph.inputOrder);
        }
    }

    private CompiledFixture executePrepared(
            JsonNode source,
            String scenario,
            String fixtureId,
            Map<String, JsonNode> events,
            ObjectNode runtime,
            ObjectNode input) {
        ObjectNode executionEnvelope = JSON.objectNode();
        executionEnvelope.put("id", fixtureId);
        executionEnvelope.put("operation", "admit-closure");
        executionEnvelope.set("input", input.deepCopy());
        executionEnvelope.set("runtime", runtime.deepCopy());
        ClosureInvocationInput parsed;
        try {
            parsed = ClosureFixtureConformance.parseAdmissionInput(
                    fixtureId,
                    "closure/" + fixtureId + ".yaml",
                    Collections.singletonList(scenario),
                    executionEnvelope);
        } catch (RuntimeException failure) {
            throw stageFailure(fixtureId, "parse exact input", failure);
        }
        Capture capture = new Capture();
        ClosureAttemptResult attempt;
        try {
            try (ClosureFixtureRuntime fixtureRuntime =
                         ClosureFixtureRuntime.fromFixture(
                                 executionEnvelope, candidate.root);
                 BlueClosureContracts contracts = new BlueClosureContracts(
                         fixtureRuntime.processor(), capture)) {
                attempt = contracts.admitClosureWithLifecycleQueue(parsed);
            }
        } catch (RuntimeException failure) {
            throw stageFailure(fixtureId, "execute normative admission",
                    failure);
        }
        require(attempt.isComplete(),
                fixtureId + " unexpectedly requires provider resources");
        require(capture.evidence != null,
                fixtureId + " did not publish implementation evidence");
        require(capture.evidence.complete(),
                fixtureId + " published incomplete implementation evidence"
                        + (capture.evidence.nonConformanceCode() == null
                        ? "" : ": "
                        + capture.evidence.nonConformanceCode()));
        try {
            assertExpectations(
                    fixtureId,
                    object(source, "expect"),
                    events,
                    attempt.processResult(),
                    capture.evidence);
        } catch (RuntimeException failure) {
            throw stageFailure(fixtureId, "validate source expectations",
                    failure);
        }

        ObjectNode envelope = JSON.objectNode();
        envelope.put(BlueLanguageConstants.OBJECT_SCHEMA, FIXTURE_SCHEMA);
        envelope.put("id", fixtureId);
        ArrayNode vectors = envelope.putArray("vectors");
        vectors.add(scenario);
        envelope.put("category", "admission");
        envelope.put("operation", "admit-closure");
        envelope.put("description", text(source, "description"));
        envelope.put("releaseManifest", "../../release-manifest.yaml");
        envelope.set("input", input.deepCopy());
        envelope.set("runtime", runtime.deepCopy());
        try {
            envelope.set("expected", FullLifecycleFixtureJson.expected(
                    attempt, capture.evidence));
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException(
                    fixtureId + " could not serialize exact expected evidence: "
                            + (failure.getMessage() == null
                            ? failure.getClass().getSimpleName()
                            : failure.getMessage()),
                    failure);
        }
        JsonNode authoredSharedLimit = object(source, "gas")
                .get("sharedLimit");
        ObjectNode sharedLimitSource = envelope.putObject(
                "sharedLimitSource");
        if (authoredSharedLimit.isTextual()) {
            sharedLimitSource.put("kind", "RELEASE_DEFAULT");
        } else {
            sharedLimitSource.put("kind", "FIXTURE_OVERRIDE");
            sharedLimitSource.put("sharedLimit",
                    authoredSharedLimit.longValue());
        }
        ObjectNode provider = envelope.putObject("provider");
        provider.putObject("nodes");
        provider.putArray("expectedRequiredBlueIds");
        provider.putArray("expectedLoads");
        ObjectNode locality = envelope.putObject("locality");
        locality.put("unrelatedDocumentCount", 0);
        locality.put("expectedUnrelatedDocumentsOpened", 0);
        envelope.putObject("limit").putNull("probe");
        rejectUnresolvedMacros(envelope, fixtureId);
        return new CompiledFixture(
                fixtureId + ".yaml",
                input.deepCopy(),
                envelope,
                attempt.processResult(),
                capture.evidence);
    }

    private IllegalArgumentException stageFailure(
            String fixtureId,
            String stage,
            RuntimeException failure) {
        return new IllegalArgumentException(
                fixtureId + " failed to " + stage + ": "
                        + (failure.getMessage() == null
                        ? failure.getClass().getSimpleName()
                        : failure.getMessage()),
                failure);
    }

    private PreparedGraph prepareGraph(
            JsonNode source,
            JsonNode caseValue,
            Map<String, JsonNode> events,
            String bindingPolicyIdentity) {
        ObjectNode authored = object(source, "documents");
        List<String> order = inputOrder(source, caseValue, authored);
        Map<String, Integer> orderIndexes = new HashMap<String, Integer>();
        for (int index = 0; index < order.size(); index++) {
            orderIndexes.put(order.get(index), Integer.valueOf(index));
        }
        validateDocumentOccurrenceMacros(source, caseValue, order);
        LinkedHashMap<DocumentId, Node> bodies =
                new LinkedHashMap<DocumentId, Node>();
        LinkedHashMap<DocumentId, Boolean> publicRoots =
                new LinkedHashMap<DocumentId, Boolean>();
        Iterator<Map.Entry<String, JsonNode>> fields = authored.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            ObjectNode wrapper = requiredObject(entry.getValue(),
                    "document " + entry.getKey());
            JsonNode resolved = resolveMacros(
                    requiredObject(wrapper.get("document"),
                            "document body " + entry.getKey()),
                    events,
                    orderIndexes);
            Node body = node(replaceDocumentMacros(
                    resolved, orderIndexes));
            String authoredId = scalarTextAt(body, "/documentId");
            require(entry.getKey().equals(authoredId),
                    "document wrapper key must equal authored documentId: "
                            + entry.getKey());
            DocumentId id = new DocumentId(entry.getKey());
            bodies.put(id, body);
            publicRoots.put(id, Boolean.valueOf(
                    requiredBoolean(wrapper, "publicRoot")));
        }

        ArrayList<ManagedOccurrenceBinding> initialRows =
                new ArrayList<ManagedOccurrenceBinding>();
        for (JsonNode occurrence : array(source, "occurrences")) {
            DocumentId sourceId = new DocumentId(text(
                    occurrence, "sourceDocumentId"));
            DocumentId targetId = new DocumentId(text(
                    occurrence, "targetDocumentId"));
            require(bodies.containsKey(sourceId)
                            && bodies.containsKey(targetId),
                    "occurrence endpoint is not an authored document");
            String path = text(occurrence, "sourcePath");
            String expected = referenceIdentityAt(
                    bodies.get(sourceId), path);
            initialRows.add(ManagedOccurrenceBinding.derived(
                    bindingPolicyIdentity,
                    sourceId,
                    ScopeAddress.embedded(
                            path,
                            requiredLong(
                                    occurrence, "activationGeneration")),
                    targetId,
                    expected,
                    requiredBoolean(occurrence, "active"),
                    null));
        }
        Collections.sort(initialRows);
        ArrayList<DocumentId> ids = new ArrayList<DocumentId>(bodies.keySet());
        Collections.sort(ids);
        ManagedDocumentGraph graph = ManagedDocumentGraph.fromBindings(
                ids, initialRows);
        LinkedHashMap<DocumentId, Long> generations =
                new LinkedHashMap<DocumentId, Long>();
        for (DocumentId id : ids) {
            generations.put(id, Long.valueOf(1L));
        }
        ComponentFinalizationResult finalized =
                new ComponentFinalizationKernel().finalizeComponents(
                        new ComponentFinalizationInput(
                                graph, generations, bodies, initialRows));

        LinkedHashMap<DocumentId, Node> finalBodies =
                new LinkedHashMap<DocumentId, Node>();
        for (DocumentId id : ids) {
            finalBodies.put(id, finalized.document(id).document());
        }
        applyRepresentation(caseValue, finalBodies, finalized);

        ArrayList<ManagedDocumentSnapshot> documents =
                new ArrayList<ManagedDocumentSnapshot>();
        for (DocumentId id : ids) {
            FinalizedDocumentEvidence exact = finalized.document(id);
            Node represented = finalBodies.get(id);
            require(exact.blueId().equals(
                            DIRECT.directBlueId(represented))
                            || exact.componentKind().name().equals("CYCLIC")
                            || isInlineRepresentationSource(
                            caseValue, id),
                    "representation expansion changed document identity: "
                            + id.value());
            documents.add(new ManagedDocumentSnapshot(
                    id,
                    exact.blueId(),
                    represented,
                    false,
                    false,
                    publicRoots.get(id).booleanValue(),
                    0L,
                    exact.componentGeneration()));
        }
        ArrayList<ComponentSnapshot> components =
                finalized.components().stream()
                        .map(value -> value.component())
                        .collect(Collectors.toCollection(ArrayList::new));
        ArrayList<DocumentId> roots = new ArrayList<DocumentId>();
        for (Map.Entry<DocumentId, Boolean> entry : publicRoots.entrySet()) {
            if (entry.getValue().booleanValue()) {
                roots.add(entry.getKey());
            }
        }
        Collections.sort(roots);
        return new PreparedGraph(
                documents,
                finalized.finalizedGraph().bindings(),
                components,
                roots,
                order);
    }

    private void applyRepresentation(
            JsonNode caseValue,
            Map<DocumentId, Node> bodies,
            ComponentFinalizationResult finalized) {
        if (caseValue == null || !caseValue.has("representation")) {
            return;
        }
        JsonNode representation = object(caseValue, "representation");
        DocumentId source = new DocumentId(text(
                representation, "documentId"));
        String path = text(representation, "path");
        ManagedOccurrenceBinding occurrence = null;
        for (ManagedOccurrenceBinding row
                : finalized.finalizedGraph().activeBindings()) {
            if (row.sourceDocumentId().equals(source)
                    && row.sourcePath().equals(path)) {
                occurrence = row;
                break;
            }
        }
        require(occurrence != null,
                "representation must name an active occurrence path");
        String form = text(representation, "form");
        Node replacement;
        if ("pure-reference".equals(form)) {
            replacement = new Node().blueId(
                    occurrence.expectedTargetBlueId());
        } else if ("inline".equals(form)) {
            replacement = bodies.get(
                    occurrence.targetDocumentId()).clone();
            require(occurrence.expectedTargetBlueId().equals(
                            DIRECT.directBlueId(replacement)),
                    "inline representation is not exact target content");
        } else {
            throw new IllegalArgumentException(
                    "unsupported representation form " + form);
        }
        NodePathEditor.put(bodies.get(source), path, replacement);
    }

    private boolean isInlineRepresentationSource(
            JsonNode caseValue,
            DocumentId documentId) {
        if (caseValue == null || !caseValue.has("representation")) {
            return false;
        }
        JsonNode representation = object(caseValue, "representation");
        return "inline".equals(text(representation, "form"))
                && documentId.value().equals(
                text(representation, "documentId"));
    }

    private Map<String, JsonNode> resolveEvents(ObjectNode authored) {
        LinkedHashMap<String, JsonNode> result =
                new LinkedHashMap<String, JsonNode>();
        Iterator<Map.Entry<String, JsonNode>> fields = authored.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode resolved = resolveMacros(
                    field.getValue(), Collections.emptyMap(), null);
            rejectUnresolvedMacros(resolved, "event " + field.getKey());
            Node event = node(resolved);
            ObjectNode exact = requiredObject(
                    wire(event), "event " + field.getKey());
            exact.put("_exportedBlueId", DIRECT.directBlueId(event));
            result.put(field.getKey(), exact);
        }
        return Collections.unmodifiableMap(result);
    }

    private JsonNode resolveMacros(
            JsonNode value,
            Map<String, JsonNode> events,
            Map<String, Integer> documentOrder) {
        if (value == null || value.isValueNode()) {
            return value == null ? JSON.nullNode() : value.deepCopy();
        }
        if (value.isArray()) {
            ArrayNode result = JSON.arrayNode();
            for (JsonNode item : value) {
                result.add(resolveMacros(item, events, documentOrder));
            }
            return result;
        }
        ObjectNode object = (ObjectNode) value;
        if (object.size() == 1 && object.has("$registryBlueId")) {
            String key = text(object, "$registryBlueId");
            String blueId = candidate.registryBlueIds.get(key);
            require(blueId != null, "unknown registry key " + key);
            ObjectNode reference = JSON.objectNode();
            reference.put(BlueLanguageConstants.OBJECT_BLUE_ID, blueId);
            return reference;
        }
        if (object.size() == 1
                && (object.has("$eventBlueId")
                || object.has("$eventValue"))) {
            boolean reference = object.has("$eventBlueId");
            String key = text(object,
                    reference ? "$eventBlueId" : "$eventValue");
            JsonNode event = events.get(key);
            require(event != null, "unknown event " + key);
            String blueId = text(event, "_exportedBlueId");
            if (reference) {
                ObjectNode result = JSON.objectNode();
                result.put(BlueLanguageConstants.OBJECT_BLUE_ID, blueId);
                return result;
            }
            ObjectNode result = ((ObjectNode) event).deepCopy();
            result.remove("_exportedBlueId");
            return result;
        }
        ObjectNode result = JSON.objectNode();
        object.fields().forEachRemaining(field -> result.set(
                field.getKey(),
                resolveMacros(field.getValue(), events, documentOrder)));
        return result;
    }

    private JsonNode replaceDocumentMacros(
            JsonNode value,
            Map<String, Integer> orderIndexes) {
        if (value == null || value.isValueNode()) {
            return value == null ? JSON.nullNode() : value.deepCopy();
        }
        if (value.isArray()) {
            ArrayNode result = JSON.arrayNode();
            for (JsonNode item : value) {
                result.add(replaceDocumentMacros(item, orderIndexes));
            }
            return result;
        }
        ObjectNode object = (ObjectNode) value;
        if (object.size() == 1
                && (object.has("$documentBlueId")
                || object.has("$documentInline"))) {
            String key = text(object, object.has("$documentBlueId")
                    ? "$documentBlueId" : "$documentInline");
            require(orderIndexes.containsKey(key),
                    "unknown document macro target " + key);
            ObjectNode replacement = JSON.objectNode();
            replacement.put(BlueLanguageConstants.OBJECT_BLUE_ID,
                    DUMMY_BLUE_ID);
            return replacement;
        }
        if (object.size() == 1
                && object.has(BlueLanguageConstants.OBJECT_BLUE_ID)) {
            String blueId = text(
                    object, BlueLanguageConstants.OBJECT_BLUE_ID);
            if (blueId.startsWith("this#")) {
                require(orderIndexes != null,
                        "this placeholder requires explicit inputOrder");
                int index;
                try {
                    index = Integer.parseInt(blueId.substring(5));
                } catch (NumberFormatException invalid) {
                    throw new IllegalArgumentException(
                            "invalid cyclic placeholder " + blueId);
                }
                require(index >= 0 && index < orderIndexes.size(),
                        "cyclic placeholder index is outside inputOrder");
                ObjectNode replacement = JSON.objectNode();
                replacement.put(BlueLanguageConstants.OBJECT_BLUE_ID,
                        DUMMY_BLUE_ID);
                return replacement;
            }
        }
        ObjectNode result = JSON.objectNode();
        object.fields().forEachRemaining(field -> result.set(
                field.getKey(),
                replaceDocumentMacros(field.getValue(), orderIndexes)));
        return result;
    }

    private long sharedLimit(JsonNode gas) {
        JsonNode value = gas.get("sharedLimit");
        require(value != null, "gas.sharedLimit is required");
        if (value.isTextual()
                && "release-default".equals(value.textValue())) {
            return GasSchedule.contracts10().maxProcessGas();
        }
        require(value.canConvertToLong() && value.longValue() >= 0L,
                "gas.sharedLimit must be release-default or non-negative Integer");
        return value.longValue();
    }

    private static final class Capture implements ClosureExecutionObserver {
        private ClosureImplementationEvidence evidence;

        @Override
        public void onExecutionEvidence(ClosureImplementationEvidence value) {
            require(evidence == null,
                    "processor published implementation evidence more than once");
            evidence = Objects.requireNonNull(value, "evidence");
        }
    }

    private static final class PreparedGraph {
        final List<ManagedDocumentSnapshot> documents;
        final List<ManagedOccurrenceBinding> occurrences;
        final List<ComponentSnapshot> components;
        final List<DocumentId> publicRoots;
        final List<String> inputOrder;

        PreparedGraph(
                List<ManagedDocumentSnapshot> documents,
                List<ManagedOccurrenceBinding> occurrences,
                List<ComponentSnapshot> components,
                List<DocumentId> publicRoots,
                List<String> inputOrder) {
            this.documents = documents;
            this.occurrences = occurrences;
            this.components = components;
            this.publicRoots = publicRoots;
            this.inputOrder = inputOrder;
        }
    }

    static final class CompiledFixture {
        final String fileName;
        final ObjectNode input;
        final ObjectNode envelope;
        final ClosureProcessResult result;
        final ClosureImplementationEvidence evidence;

        CompiledFixture(
                String fileName,
                ObjectNode input,
                ObjectNode envelope,
                ClosureProcessResult result,
                ClosureImplementationEvidence evidence) {
            this.fileName = fileName;
            this.input = input;
            this.envelope = envelope;
            this.result = result;
            this.evidence = evidence;
        }
    }

    private static final class CandidatePackage {
        final Path root;
        final String languageSpecificationIdentity;
        final String contractsSpecificationIdentity;
        final Map<String, String> registryBlueIds;

        CandidatePackage(
                Path root,
                String languageSpecificationIdentity,
                String contractsSpecificationIdentity,
                Map<String, String> registryBlueIds) {
            this.root = root;
            this.languageSpecificationIdentity =
                    languageSpecificationIdentity;
            this.contractsSpecificationIdentity =
                    contractsSpecificationIdentity;
            this.registryBlueIds = registryBlueIds;
        }

        static CandidatePackage load(Path root) {
            JsonNode release = readYaml(root.resolve("release-manifest.yaml"));
            String language = text(object(release, "languageDependency"),
                    "specificationSha256");
            String contracts = text(object(release, "specificationDocument"),
                    "sha256");
            JsonNode registryDescriptor = object(release, "contractsRegistry");
            Path manifest = root.resolve(text(
                    registryDescriptor, "path")).normalize();
            require(manifest.startsWith(root),
                    "candidate registry manifest escapes package root");
            JsonNode registry = readYaml(manifest);
            LinkedHashMap<String, String> ids =
                    new LinkedHashMap<String, String>();
            Path registryRoot = manifest.getParent();
            for (JsonNode entry : array(registry, "entries")) {
                String key = text(entry, "key");
                String blueId = text(
                        entry, BlueLanguageConstants.OBJECT_BLUE_ID);
                Path nodePath = registryRoot.resolve(text(
                        entry, "path")).normalize();
                require(nodePath.startsWith(registryRoot),
                        "registry entry escapes package root: " + key);
                Node node = node(readYaml(nodePath));
                require(blueId.equals(DIRECT.directBlueId(node)),
                        "registry BlueId mismatch for " + key);
                require(ids.put(key, blueId) == null,
                        "duplicate registry key " + key);
            }
            require(!ids.isEmpty(), "candidate registry is empty");
            return new CandidatePackage(
                    root, sha256(language, "Language specification"),
                    sha256(contracts, "Contracts specification"),
                    Collections.unmodifiableMap(ids));
        }
    }
}
