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

/**
 * Compiles identity-free full-lifecycle sources into exact executable closure
 * fixtures by executing the normative production admission API.
 *
 * <p>The compiler never consumes an expected result. Source {@code expect}
 * values are post-execution assertions only. Every identity-bearing input is
 * constructed through Language or Contracts production APIs.</p>
 */
public final class FullLifecycleFixtureExporter {

    static final int SOURCE_COUNT = 10;
    static final int FIXTURE_COUNT = 13;
    static final String SOURCE_SCHEMA =
            "blue-contracts-full-lifecycle-source/1.0";
    static final String FIXTURE_SCHEMA =
            "blue-contracts-closure-fixture/1.0";
    static final String SUCCESS_MARKER =
            "FULL_LIFECYCLE_FIXTURES_EXPORTED count=13";

    private static final ObjectMapper YAML = new ObjectMapper(
            YAMLFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .build());
    private static final ObjectMapper OUTPUT_YAML = new ObjectMapper(
            YAMLFactory.builder()
                    .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                    .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
                    .build());
    private static final DirectBlueIdCalculator DIRECT =
            new DirectBlueIdCalculator();
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final String DUMMY_BLUE_ID =
            "11111111111111111111111111111111";
    private static final Map<String, String> EXPECTED_SOURCE_IDS =
            expectedSourceIds();

    private FullLifecycleFixtureExporter() {
    }

    /**
     * Runs the deterministic fixture exporter from the command line.
     *
     * @param args source root, candidate package root, and empty output root
     */
    public static void main(String[] args) {
        try {
            if (args.length != 3) {
                throw new IllegalArgumentException(
                        "usage: FullLifecycleFixtureExporter <sourceRoot> "
                                + "<packageRoot> <outputRoot>");
            }
            export(Paths.get(args[0]), Paths.get(args[1]),
                    Paths.get(args[2]));
            System.out.println(SUCCESS_MARKER);
        } catch (RuntimeException | IOException failure) {
            String message = failure.getMessage();
            System.err.println("FULL_LIFECYCLE_FIXTURE_EXPORT_FAILED "
                    + (message == null
                    ? failure.getClass().getSimpleName() : message));
            System.exit(2);
        }
    }

    static List<Path> export(
            Path sourceRoot,
            Path packageRoot,
            Path outputRoot) throws IOException {
        Path sources = existingAbsoluteDirectory(sourceRoot, "source root");
        Path candidate = existingAbsoluteDirectory(
                packageRoot, "candidate package root");
        Path output = emptyAbsoluteDirectory(outputRoot, "output root");
        CandidatePackage candidatePackage = CandidatePackage.load(candidate);

        List<Path> sourceFiles;
        try (Stream<Path> files = Files.list(sources)) {
            sourceFiles = files
                    .filter(path -> path.getFileName().toString()
                            .matches("fl-adm-[0-9]{2}.*\\.yaml"))
                    .sorted()
                    .collect(Collectors.toList());
        }
        require(sourceFiles.size() == SOURCE_COUNT,
                "source root must contain exactly 10 FL-ADM YAML sources");
        LinkedHashSet<String> sourceNames = sourceFiles.stream()
                .map(path -> path.getFileName().toString())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        require(sourceNames.equals(EXPECTED_SOURCE_IDS.keySet()),
                "source root must contain the exact FL-ADM-01..10 family files; "
                        + "expected " + EXPECTED_SOURCE_IDS.keySet()
                        + " but found " + sourceNames);

        Path parent = output.getParent();
        Path staging = Files.createTempDirectory(
                parent, ".full-lifecycle-fixtures-");
        boolean published = false;
        try {
            Compiler compiler = new Compiler(candidatePackage);
            ArrayList<JsonNode> parsedSources = new ArrayList<JsonNode>();
            for (Path sourceFile : sourceFiles) {
                try {
                    JsonNode source = readYaml(sourceFile);
                    validateSourceFamily(sourceFile, source);
                    compiler.preflight(source);
                    parsedSources.add(source);
                } catch (RuntimeException failure) {
                    throw new IllegalArgumentException(
                            sourceFile.getFileName() + ": "
                                    + (failure.getMessage() == null
                                    ? failure.getClass().getSimpleName()
                                    : failure.getMessage()),
                            failure);
                }
            }
            ArrayList<Path> generated = new ArrayList<Path>();
            for (int sourceIndex = 0;
                    sourceIndex < sourceFiles.size(); sourceIndex++) {
                Path sourceFile = sourceFiles.get(sourceIndex);
                try {
                    JsonNode source = parsedSources.get(sourceIndex);
                    for (CompiledFixture fixture : compiler.compile(source)) {
                        Path target = staging.resolve(fixture.fileName);
                        byte[] bytes = deterministicYaml(fixture.envelope);
                        Files.write(target, bytes);
                        generated.add(target);
                    }
                } catch (RuntimeException failure) {
                    throw new IllegalArgumentException(
                            sourceFile.getFileName() + ": "
                                    + (failure.getMessage() == null
                                    ? failure.getClass().getSimpleName()
                                    : failure.getMessage()),
                            failure);
                }
            }
            require(generated.size() == FIXTURE_COUNT,
                    "sources must compile to exactly 13 fixtures");
            ArrayList<String> names = generated.stream()
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .collect(Collectors.toCollection(ArrayList::new));
            require(names.size() == new HashSet<String>(names).size(),
                    "compiled fixture names must be unique");

            Files.delete(output);
            try {
                Files.move(staging, output, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.createDirectory(output);
                throw new IllegalStateException(
                        "output filesystem does not support atomic publication",
                        unsupported);
            }
            published = true;
            ArrayList<Path> result = new ArrayList<Path>();
            for (String name : names) {
                result.add(output.resolve(name));
            }
            return Collections.unmodifiableList(result);
        } finally {
            if (!published) {
                deleteTree(staging);
                if (!Files.exists(output)) {
                    Files.createDirectory(output);
                }
            }
        }
    }

    private static final class Compiler {
        private final CandidatePackage candidate;

        Compiler(CandidatePackage candidate) {
            this.candidate = candidate;
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
                return FixtureJson.input(input, graph.inputOrder);
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
                envelope.set("expected", FixtureJson.expected(
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

    private static final class CompiledFixture {
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

    /** Exact executable fixture projection; no identity or semantics live here. */
    private static final class FixtureJson {

        static ObjectNode input(
                ClosureInvocationInput invocation,
                List<String> inputOrder) {
            AffectedClosureSnapshot snapshot = invocation.snapshot();
            ObjectNode result = JSON.objectNode();
            result.put("graphGeneration", snapshot.graphGeneration());
            result.set("cause", cause((AdmissionCause) invocation.cause()));
            result.putNull("admissionCandidate");
            result.putNull("admissionCandidateIdentity");
            ObjectNode documents = result.putObject("documents");
            Set<DocumentId> emitted = new LinkedHashSet<DocumentId>();
            for (String idValue : inputOrder) {
                DocumentId id = new DocumentId(idValue);
                ManagedDocumentSnapshot value = snapshot.managedDocument(id);
                require(value != null,
                        "inputOrder contains unknown document " + idValue);
                documents.set(idValue, document(value));
                emitted.add(id);
            }
            require(emitted.size() == snapshot.managedDocuments().size(),
                    "inputOrder must cover every managed document exactly once");
            result.set("occurrences", occurrences(snapshot.occurrences()));
            result.set("components", components(snapshot.components()));
            result.putArray("directDeliveries");
            result.set("publicRootDocumentIds",
                    documentIds(snapshot.publicRootDocumentIds()));
            result.put("directDeliverySnapshotIdentity",
                    invocation.directDeliverySnapshotIdentity());
            result.put("occurrenceBindingSetIdentity",
                    snapshot.occurrenceBindingSetIdentity());
            result.put("closureIdentity", snapshot.closureIdentity());
            result.put("invocationIdentity", invocation.invocationIdentity());
            result.set("gasPolicy", gasPolicy(invocation.executionPolicy()));
            result.set("environment", environment(invocation.environment()));
            return result;
        }

        static ObjectNode expected(
                ClosureAttemptResult attempt,
                ClosureImplementationEvidence implementation) {
            require(attempt.isComplete(),
                    "export supports only completed attempts");
            ClosureProcessResult value = attempt.processResult();
            ObjectNode result = JSON.objectNode();
            result.put("attemptOutcome", "Complete");
            result.put("status", value.status().wireValue());
            result.put("invocationIdentity", value.invocationIdentity());
            result.put("inputClosureIdentity", value.inputClosureIdentity());
            result.put("outputClosureIdentity", value.outputClosureIdentity());
            if (value.diagnostic() != null) {
                result.put("diagnostic", value.diagnostic().category().name());
            }
            result.put("atomic", value.atomic());
            result.set("workTrace", work(implementation.workTrace()));
            result.set("documentStepTrace",
                    documentSteps(implementation.documentStepTrace()));
            if (value.rejectedWorkOccurrence() != null) {
                result.set("rejectedWorkOccurrence",
                        work(value.rejectedWorkOccurrence()));
            }
            if (value.rejectedCharge() != null) {
                result.set("rejectedCharge",
                        rejectedCharge(value.rejectedCharge()));
            }
            result.set("tentativeFinalizations",
                    finalizations(implementation.tentativeFinalizations()));
            result.put("graphGeneration", value.graphGeneration());
            result.set("resultingDocuments",
                    resultingDocuments(value.resultingDocuments()));
            result.set("resultingComponents",
                    components(value.resultingComponents()));
            result.set("occurrenceBindings",
                    occurrences(value.occurrenceBindings()));
            result.put("occurrenceBindingSetIdentity",
                    value.occurrenceBindingSetIdentity());
            result.set("graphChanges", graphChanges(value.graphChanges()));
            result.put("graphChangesIdentity", value.graphChangesIdentity());
            result.set("subscriptionDeltas",
                    subscriptionDeltas(value.subscriptionDeltas()));
            result.put("subscriptionDeltasIdentity",
                    value.subscriptionDeltasIdentity());
            result.set("checkpointWrites",
                    checkpointWrites(value.checkpointWrites()));
            result.put("checkpointWritesIdentity",
                    value.checkpointWritesIdentity());
            result.set("publicEvents", publicEvents(value.publicEvents()));
            result.put("publicEventsIdentity", value.publicEventsIdentity());
            result.put("rollbackToInput", value.rollbackToInput());
            result.put("totalGas", value.totalGas());
            result.set("gasTrace", gasTrace(value.gasTrace()));
            result.put("gasTraceIdentity", value.gasTraceIdentity());
            if (value.platformCommitCompanion() != null) {
                result.set("platformCommitCompanion",
                        companion(value.platformCommitCompanion()));
            }
            return result;
        }

        private static ObjectNode cause(AdmissionCause value) {
            ObjectNode result = JSON.objectNode();
            result.put("kind", value.kind().wireValue());
            result.put("causeIdentity", value.causeIdentity());
            result.put("admissionKind", value.admissionKind().name());
            result.put("label", value.label());
            nullable(result, "triggeringEventBlueId",
                    value.triggeringEventBlueId());
            nullable(result, "parentTransitionIdentity",
                    value.parentTransitionIdentity());
            result.put("policyIdentity", value.policyIdentity());
            return result;
        }

        private static ObjectNode document(ManagedDocumentSnapshot value) {
            ObjectNode result = JSON.objectNode();
            result.put("documentId", value.documentId().value());
            result.put(BlueLanguageConstants.OBJECT_BLUE_ID, value.blueId());
            result.set("document", wire(value.document()));
            result.put("initialized", value.initialized());
            result.put("terminated", value.terminated());
            result.put("publicRoot", value.publicRoot());
            result.put("epoch", value.epoch());
            result.put("componentGeneration", value.componentGeneration());
            return result;
        }

        private static ObjectNode occurrence(ManagedOccurrenceBinding value) {
            ObjectNode result = JSON.objectNode();
            result.put("occurrenceIdentity", value.occurrenceIdentity());
            result.put("bindingIdentity", value.bindingIdentity());
            result.put("bindingPolicyIdentity", value.bindingPolicyIdentity());
            result.put("sourceDocumentId", value.sourceDocumentId().value());
            result.put("sourcePath", value.sourcePath());
            result.put("activationGeneration", value.activationGeneration());
            result.put("targetDocumentId", value.targetDocumentId().value());
            result.put("expectedTargetBlueId", value.expectedTargetBlueId());
            result.put("active", value.active());
            nullable(result, "pendingHistoricalEpoch",
                    value.pendingHistoricalEpoch());
            return result;
        }

        private static ArrayNode occurrences(
                List<ManagedOccurrenceBinding> values) {
            ArrayNode result = JSON.arrayNode();
            for (ManagedOccurrenceBinding value : values) {
                result.add(occurrence(value));
            }
            return result;
        }

        private static ObjectNode component(ComponentSnapshot value) {
            ObjectNode result = JSON.objectNode();
            result.put("componentIdentity", value.componentIdentity());
            result.put("componentStateIdentity",
                    value.componentStateIdentity());
            result.put("componentGeneration", value.componentGeneration());
            result.put("kind", value.kind().name());
            result.set("orderedMemberDocumentIds",
                    documentIds(value.orderedMemberDocumentIds()));
            result.set("orderedMemberBlueIds",
                    textArray(value.orderedMemberBlueIds()));
            if (value.completeCyclicProof() != null) {
                result.put("masterBlueId", value.masterBlueId());
                result.set("completeCyclicProof", cyclicProof(value));
                result.put("cyclicProofIdentity",
                        value.cyclicProofIdentity());
            }
            return result;
        }

        private static ObjectNode cyclicProof(ComponentSnapshot value) {
            ObjectNode result = JSON.objectNode();
            result.put("componentIdentity", value.componentIdentity());
            result.put("masterBlueId", value.masterBlueId());
            ArrayNode states = result.putArray("memberStates");
            for (int index = 0;
                    index < value.orderedMemberDocumentIds().size(); index++) {
                ObjectNode state = states.addObject();
                state.put("documentId", value.orderedMemberDocumentIds()
                        .get(index).value());
                state.put(BlueLanguageConstants.OBJECT_BLUE_ID,
                        value.orderedMemberBlueIds().get(index));
            }
            ArrayNode placeholders = result.putArray(
                    "declaredPlaceholderSet");
            for (Node placeholder
                    : value.completeCyclicProof().declaredPlaceholderSet()) {
                placeholders.add(wire(placeholder));
            }
            return result;
        }

        private static ArrayNode components(List<ComponentSnapshot> values) {
            ArrayNode result = JSON.arrayNode();
            for (ComponentSnapshot value : values) {
                result.add(component(value));
            }
            return result;
        }

        private static ObjectNode gasPolicy(ExecutionPolicy value) {
            ObjectNode result = JSON.objectNode();
            result.put("policyIdentity", value.identity());
            result.put("sharedLimit", value.sharedLimit());
            ObjectNode local = result.putObject("localLimits");
            for (Map.Entry<DocumentId, Long> entry
                    : value.localLimits().entrySet()) {
                local.put(entry.getKey().value(), entry.getValue().longValue());
            }
            result.put("label", value.label());
            return result;
        }

        private static ObjectNode environment(ClosureEnvironment value) {
            ObjectNode result = JSON.objectNode();
            result.put("blueLanguageSpecificationIdentity",
                    value.blueLanguageSpecificationIdentity());
            result.put("contractsSpecificationIdentity",
                    value.contractsSpecificationIdentity());
            result.put("runtimeRegistryIdentity",
                    value.runtimeRegistryIdentity());
            result.put("gasManifestIdentity", value.gasManifestIdentity());
            result.set("managedDocumentIdentityPolicy", labeled(
                    value.managedDocumentIdentityPolicy()));
            result.set("managedBindingPolicy", labeled(
                    value.managedBindingPolicy()));
            result.set("exactNodeProviderDomain", labeled(
                    value.exactNodeProviderDomain()));
            result.set("externalOrderPolicy", labeled(
                    value.externalOrderPolicy()));
            ObjectNode portable = result.putObject("portableLimitPolicy");
            portable.put("identity", value.portableLimitPolicy().identity());
            portable.put("label", value.portableLimitPolicy().label());
            ArrayNode limits = portable.putArray("limits");
            for (Map.Entry<String, Long> entry
                    : value.portableLimitPolicy().limits().entrySet()) {
                ObjectNode limit = limits.addObject();
                limit.put("name", entry.getKey());
                limit.put(BlueLanguageConstants.OBJECT_VALUE,
                        entry.getValue().longValue());
            }
            result.put("cyclicFinalizerIdentity",
                    value.cyclicFinalizerIdentity());
            result.put("cyclicProofVerifierIdentity",
                    value.cyclicProofVerifierIdentity());
            return result;
        }

        private static ObjectNode labeled(
                ClosureEnvironment.LabeledIdentityEvidence value) {
            ObjectNode result = JSON.objectNode();
            result.put("identity", value.identity());
            result.put("label", value.label());
            return result;
        }

        private static ArrayNode work(List<ClosureWorkOccurrence> values) {
            ArrayNode result = JSON.arrayNode();
            for (ClosureWorkOccurrence value : values) {
                result.add(work(value));
            }
            return result;
        }

        private static ObjectNode work(ClosureWorkOccurrence value) {
            ObjectNode result = JSON.objectNode();
            result.put("ordinal", value.ordinal());
            result.put("kind", value.kind().name());
            result.put("targetDocumentId", value.targetDocumentId().value());
            result.put("channelKey", value.channelKey());
            if (value.eventBlueId() != null) {
                result.put("eventBlueId", value.eventBlueId());
            }
            if (value.occurrenceOrdinal() != null) {
                result.put("occurrenceOrdinal",
                        value.occurrenceOrdinal().longValue());
            }
            result.put("targetManagedScopeIdentity",
                    value.targetManagedScopeIdentity());
            result.put("sourceOccurrenceIdentity",
                    value.sourceOccurrenceIdentity());
            result.put("workIdentity", value.workIdentity());
            return result;
        }

        private static ArrayNode documentSteps(
                List<DocumentStepEvidence> values) {
            ArrayNode result = JSON.arrayNode();
            for (DocumentStepEvidence value : values) {
                ObjectNode item = result.addObject();
                item.put("stepOrdinal", value.stepOrdinal());
                item.put("workOrdinal", value.workOrdinal());
                item.put("targetDocumentId",
                        value.targetDocumentId().value());
                item.put("executionRootDocumentId",
                        value.executionRootDocumentId().value());
                item.put("scopePath", value.scopePath());
                item.put("executionMode", value.executionMode());
                item.set("ambientContainingDocumentIds",
                        documentIds(value.ambientContainingDocumentIds()));
            }
            return result;
        }

        private static ArrayNode finalizations(
                List<TentativeFinalization> values) {
            ArrayNode result = JSON.arrayNode();
            for (TentativeFinalization value : values) {
                ObjectNode item = result.addObject();
                item.put("ordinal", value.ordinal());
                ObjectNode boundary = item.putObject("boundary");
                boundary.put("kind", value.boundary().kind().name());
                if (value.boundary().afterWorkOrdinal() != null) {
                    boundary.put("afterWorkOrdinal",
                            value.boundary().afterWorkOrdinal().longValue());
                }
                item.put("masterBlueId", value.masterBlueId());
                item.put("canonicalBytes", value.canonicalBytes());
                ObjectNode members = item.putObject("memberBlueIds");
                for (Map.Entry<DocumentId, String> entry
                        : value.memberBlueIds().entrySet()) {
                    members.put(entry.getKey().value(), entry.getValue());
                }
            }
            return result;
        }

        private static ArrayNode resultingDocuments(
                List<ResultingDocument> values) {
            ArrayNode result = JSON.arrayNode();
            for (ResultingDocument value : values) {
                ObjectNode item = result.addObject();
                item.put("documentId", value.documentId().value());
                item.put("beforeBlueId", value.beforeBlueId());
                item.put("afterBlueId", value.afterBlueId());
                item.set("document", wire(value.document()));
                item.put("initialized", value.initialized());
                item.put("terminated", value.terminated());
                item.put("publicRoot", value.publicRoot());
                item.put("epoch", value.epoch());
                item.put("componentGeneration", value.componentGeneration());
                item.put("componentIdentity", value.componentIdentity());
                item.put("componentStateIdentity",
                        value.componentStateIdentity());
                nullable(item, "memberIndex", value.memberIndex());
            }
            return result;
        }

        private static ArrayNode graphChanges(List<GraphChange> values) {
            ArrayNode result = JSON.arrayNode();
            for (GraphChange value : values) {
                ObjectNode item = result.addObject();
                item.put("graphChangeOrdinal", value.graphChangeOrdinal());
                item.put("changeKind", value.changeKind().name());
                item.put("sourceDocumentId",
                        value.sourceDocumentId().value());
                item.put("sourcePath", value.sourcePath());
                nullable(item, "beforeActivationGeneration",
                        value.beforeActivationGeneration());
                nullable(item, "beforeOccurrenceIdentity",
                        value.beforeOccurrenceIdentity());
                nullable(item, "beforeBindingIdentity",
                        value.beforeBindingIdentity());
                nullableDocument(item, "beforeTargetDocumentId",
                        value.beforeTargetDocumentId());
                nullable(item, "beforeTargetBlueId",
                        value.beforeTargetBlueId());
                nullable(item, "afterActivationGeneration",
                        value.afterActivationGeneration());
                nullable(item, "afterOccurrenceIdentity",
                        value.afterOccurrenceIdentity());
                nullable(item, "afterBindingIdentity",
                        value.afterBindingIdentity());
                nullableDocument(item, "afterTargetDocumentId",
                        value.afterTargetDocumentId());
                nullable(item, "afterTargetBlueId",
                        value.afterTargetBlueId());
            }
            return result;
        }

        private static ArrayNode subscriptionDeltas(
                List<SubscriptionDelta> values) {
            ArrayNode result = JSON.arrayNode();
            for (SubscriptionDelta value : values) {
                ObjectNode item = result.addObject();
                item.put("subscriptionDeltaOrdinal",
                        value.subscriptionDeltaOrdinal());
                item.put("operation", value.operation().name());
                item.put("targetManagedScopeIdentity",
                        value.targetManagedScopeIdentity());
                item.put("channelOccurrenceIdentity",
                        value.channelOccurrenceIdentity());
                nullable(item, "beforeSubscriptionIdentity",
                        value.beforeSubscriptionIdentity());
                nullable(item, "afterSubscriptionIdentity",
                        value.afterSubscriptionIdentity());
                nullable(item, "beforeDocumentBlueId",
                        value.beforeDocumentBlueId());
                nullable(item, "afterDocumentBlueId",
                        value.afterDocumentBlueId());
                nullable(item, "beforeGraphGeneration",
                        value.beforeGraphGeneration());
                nullable(item, "afterGraphGeneration",
                        value.afterGraphGeneration());
                nullable(item, "beforeComponentGeneration",
                        value.beforeComponentGeneration());
                nullable(item, "afterComponentGeneration",
                        value.afterComponentGeneration());
                nullableNode(item, "beforeSubscription",
                        value.beforeSubscription() == null
                                ? null
                                : subscription(value.beforeSubscription()));
                nullableNode(item, "afterSubscription",
                        value.afterSubscription() == null
                                ? null
                                : subscription(value.afterSubscription()));
            }
            return result;
        }

        private static ObjectNode subscription(SubscriptionState value) {
            ObjectNode result = JSON.objectNode();
            result.put("subscriptionIdentity", value.subscriptionIdentity());
            result.set("channelOccurrence",
                    channelOccurrence(value.channelOccurrence()));
            result.put("documentBlueId", value.documentBlueId());
            result.put("graphGeneration", value.graphGeneration());
            result.put("componentGeneration", value.componentGeneration());
            return result;
        }

        private static ObjectNode channelOccurrence(ChannelOccurrence value) {
            ObjectNode result = JSON.objectNode();
            result.put("channelOccurrenceIdentity",
                    value.channelOccurrenceIdentity());
            result.put("managedDocumentId",
                    value.managedDocumentId().value());
            result.put("scopePath", value.scopePath());
            result.put("scopeActivationGeneration",
                    value.scopeActivationGeneration());
            result.put("rawChannelKey", value.rawChannelKey());
            result.put("effectiveRuntimeContributionBlueId",
                    value.effectiveRuntimeContributionBlueId());
            result.put("subscriptionHeaderBlueId",
                    value.subscriptionHeaderBlueId());
            return result;
        }

        private static ArrayNode checkpointWrites(
                List<CheckpointWrite> values) {
            ArrayNode result = JSON.arrayNode();
            for (CheckpointWrite value : values) {
                ObjectNode item = result.addObject();
                item.put("checkpointWriteOrdinal",
                        value.checkpointWriteOrdinal());
                item.put("targetManagedScopeIdentity",
                        value.targetManagedScopeIdentity());
                item.put("rawChannelKey", value.rawChannelKey());
                item.put("beforePresent", value.beforePresent());
                nullable(item, "beforeDomainBlueId",
                        value.beforeDomainBlueId());
                nullableNode(item, "beforeDomainValue",
                        value.beforeDomainValue() == null
                                ? null : checkpointDomain(
                                value.beforeDomainValue()));
                nullable(item, "beforeSubjectBlueId",
                        value.beforeSubjectBlueId());
                item.put("afterPresent", value.afterPresent());
                nullable(item, "afterDomainBlueId",
                        value.afterDomainBlueId());
                nullableNode(item, "afterDomainValue",
                        value.afterDomainValue() == null
                                ? null : checkpointDomain(
                                value.afterDomainValue()));
                nullable(item, "afterSubjectBlueId",
                        value.afterSubjectBlueId());
            }
            return result;
        }

        private static ObjectNode checkpointDomain(
                CheckpointDomainValue value) {
            ObjectNode result = JSON.objectNode();
            result.put("contractsVersion", value.contractsVersion());
            result.put("effectiveTypeBlueId",
                    value.effectiveTypeBlueId());
            result.set("sourceContributionNodeBlueIds",
                    textArray(value.sourceContributionNodeBlueIds()));
            if (!value.deterministicDependencyNodeBlueIds().isEmpty()) {
                result.set("deterministicDependencyNodeBlueIds",
                        textArray(value.deterministicDependencyNodeBlueIds()));
            }
            if (value.runtimeDiscriminator() != null) {
                result.put("runtimeDiscriminator",
                        value.runtimeDiscriminator());
            }
            return result;
        }

        private static ArrayNode publicEvents(
                List<PublicEventOccurrence> values) {
            ArrayNode result = JSON.arrayNode();
            for (PublicEventOccurrence value : values) {
                ObjectNode item = result.addObject();
                item.put("publicEventOrdinal", value.publicEventOrdinal());
                item.put("eventOccurrenceOrdinal",
                        value.eventOccurrenceOrdinal());
                item.put("publicRootDocumentId",
                        value.publicRootDocumentId().value());
                item.put("eventOccurrenceIdentity",
                        value.eventOccurrenceIdentity());
                item.put("eventBlueId", value.eventBlueId());
                item.set("event", wire(value.event()));
            }
            return result;
        }

        private static ArrayNode gasTrace(List<GasTraceEntry> values) {
            ArrayNode result = JSON.arrayNode();
            for (GasTraceEntry value : values) {
                ObjectNode item = result.addObject();
                item.put("sequence", value.sequence());
                item.put("namespace", value.namespace().wireValue());
                item.put("counter", value.counter());
                item.put("quantity", value.quantity());
                item.put("weight", value.weight());
                item.put("subtotal", value.subtotal());
                if (value.documentId() != null) {
                    item.put("documentId", value.documentId().value());
                }
                if (value.scopePath() != null) {
                    item.put("scopePath", value.scopePath());
                }
                if (value.activationGeneration() != null) {
                    item.put("activationGeneration",
                            value.activationGeneration().longValue());
                }
                if (value.componentGeneration() != null) {
                    item.put("componentGeneration",
                            value.componentGeneration().longValue());
                }
                if (value.contractKey() != null) {
                    item.put("contractKey", value.contractKey());
                }
                if (value.logicalPath() != null) {
                    item.put("logicalPath", value.logicalPath());
                }
                if (value.workOccurrenceId() != null) {
                    item.put("workOccurrenceId",
                            value.workOccurrenceId());
                }
                if (value.reason() != null) {
                    item.put("reason", value.reason());
                }
            }
            return result;
        }

        private static ObjectNode rejectedCharge(RejectedCharge value) {
            ObjectNode result = JSON.objectNode();
            result.put("rejectedChargeIdentity",
                    value.rejectedChargeIdentity());
            result.put("namespace", value.namespace().wireValue());
            result.put("counter", value.counter());
            result.put("quantity", value.quantity());
            result.put("weight", value.weight());
            result.put("subtotal", value.subtotal());
            ObjectNode cap = result.putObject("applicableCap");
            cap.put("kind", value.applicableCap().kind().name());
            if (value.applicableCap().documentId() != null) {
                cap.put("documentId",
                        value.applicableCap().documentId().value());
            }
            result.put("remainingBeforeCharge",
                    value.remainingBeforeCharge());
            ObjectNode owner = result.putObject("owner");
            owner.put("kind", value.owner().kind().name());
            if (value.owner().workOccurrenceIdentity() != null) {
                owner.put("workOccurrenceIdentity",
                        value.owner().workOccurrenceIdentity());
            }
            if (value.owner().finalizationOrdinal() != null) {
                owner.put("finalizationOrdinal",
                        value.owner().finalizationOrdinal().longValue());
                owner.put("componentIdentity",
                        value.owner().componentIdentity());
                owner.put("componentGeneration",
                        value.owner().componentGeneration().longValue());
            }
            return result;
        }

        private static ObjectNode companion(ClosureCommitCompanion value) {
            ObjectNode result = JSON.objectNode();
            result.put("companionIdentity", value.companionIdentity());
            result.put("invocationIdentity", value.invocationIdentity());
            result.put("inputClosureIdentity", value.inputClosureIdentity());
            result.put("outputClosureIdentity", value.outputClosureIdentity());
            result.put("expectedInputGraphGeneration",
                    value.expectedInputGraphGeneration());
            ArrayNode inputDocuments = result.putArray(
                    "expectedInputDocuments");
            for (ClosureCommitCompanion.InputDocument item
                    : value.expectedInputDocuments()) {
                ObjectNode encoded = inputDocuments.addObject();
                encoded.put("documentId", item.documentId().value());
                encoded.put(BlueLanguageConstants.OBJECT_BLUE_ID,
                        item.blueId());
            }
            ArrayNode inputComponents = result.putArray(
                    "expectedInputComponents");
            for (ClosureCommitCompanion.InputComponent item
                    : value.expectedInputComponents()) {
                ObjectNode encoded = inputComponents.addObject();
                encoded.put("componentIdentity", item.componentIdentity());
                encoded.put("componentStateIdentity",
                        item.componentStateIdentity());
                encoded.put("componentGeneration",
                        item.componentGeneration());
                nullable(encoded, "masterBlueId", item.masterBlueId());
            }
            result.put("inputOccurrenceBindingSetIdentity",
                    value.inputOccurrenceBindingSetIdentity());
            result.put("outputGraphGeneration",
                    value.outputGraphGeneration());
            ArrayNode documentDeltas = result.putArray("resultingDocuments");
            for (ClosureCommitCompanion.DocumentDelta item
                    : value.resultingDocuments()) {
                ObjectNode encoded = documentDeltas.addObject();
                encoded.put("documentId", item.documentId().value());
                encoded.put("beforeBlueId", item.beforeBlueId());
                encoded.put("afterBlueId", item.afterBlueId());
            }
            ArrayNode resultComponents = result.putArray(
                    "resultingComponents");
            for (ClosureCommitCompanion.ResultComponent item
                    : value.resultingComponents()) {
                ObjectNode encoded = resultComponents.addObject();
                encoded.put("componentIdentity", item.componentIdentity());
                encoded.put("componentStateIdentity",
                        item.componentStateIdentity());
                nullable(encoded, "cyclicProofIdentity",
                        item.cyclicProofIdentity());
            }
            result.put("occurrenceBindingSetIdentity",
                    value.occurrenceBindingSetIdentity());
            result.put("graphChangesIdentity", value.graphChangesIdentity());
            result.put("checkpointWritesIdentity",
                    value.checkpointWritesIdentity());
            result.put("subscriptionDeltasIdentity",
                    value.subscriptionDeltasIdentity());
            result.put("publicEventsIdentity", value.publicEventsIdentity());
            result.put("gasTraceIdentity", value.gasTraceIdentity());
            result.put("blueLanguageSpecificationIdentity",
                    value.blueLanguageSpecificationIdentity());
            result.put("contractsSpecificationIdentity",
                    value.contractsSpecificationIdentity());
            result.put("managedDocumentIdentityPolicyIdentity",
                    value.managedDocumentIdentityPolicyIdentity());
            result.put("managedBindingPolicyIdentity",
                    value.managedBindingPolicyIdentity());
            result.put("exactNodeProviderDomainIdentity",
                    value.exactNodeProviderDomainIdentity());
            result.put("externalOrderPolicyIdentity",
                    value.externalOrderPolicyIdentity());
            result.put("runtimeRegistryIdentity",
                    value.runtimeRegistryIdentity());
            result.put("gasManifestIdentity", value.gasManifestIdentity());
            result.put("portableLimitPolicyIdentity",
                    value.portableLimitPolicyIdentity());
            result.put("cyclicFinalizerIdentity",
                    value.cyclicFinalizerIdentity());
            result.put("cyclicProofVerifierIdentity",
                    value.cyclicProofVerifierIdentity());
            return result;
        }
    }

    private static void assertExpectations(
            String id,
            JsonNode expect,
            Map<String, JsonNode> events,
            ClosureProcessResult result,
            ClosureImplementationEvidence evidence) {
        require(text(expect, "status").equals(result.status().wireValue()),
                id + " expected status disagrees with execution");
        require(requiredBoolean(expect, "atomic") == result.atomic(),
                id + " expected atomic disagrees with execution");
        require(requiredBoolean(expect, "rollbackToInput")
                        == result.rollbackToInput(),
                id + " expected rollbackToInput disagrees with execution");
        if (expect.has("diagnosticCategory")) {
            require(result.diagnostic() != null
                            && text(expect, "diagnosticCategory").equals(
                            result.diagnostic().category().name()),
                    id + " expected diagnosticCategory disagrees with execution");
        } else {
            require(result.diagnostic() == null,
                    id + " produced an undeclared diagnostic");
        }

        Map<String, ResultingDocument> documents =
                new LinkedHashMap<String, ResultingDocument>();
        for (ResultingDocument document : result.resultingDocuments()) {
            documents.put(document.documentId().value(), document);
        }
        for (JsonNode expected : optionalArray(expect, "documents")) {
            String documentId = text(expected, "documentId");
            ResultingDocument actual = documents.get(documentId);
            require(actual != null,
                    id + " expectation names unknown document " + documentId);
            if (expected.has("initialized")) {
                require(requiredBoolean(expected, "initialized")
                                == actual.initialized(),
                        id + " initialized assertion failed for " + documentId);
            }
            if (expected.has("terminated")) {
                require(requiredBoolean(expected, "terminated")
                                == actual.terminated(),
                        id + " terminated assertion failed for " + documentId);
            }
        }
        for (JsonNode expected : optionalArray(expect, "pointers")) {
            String documentId = text(expected, "documentId");
            ResultingDocument actual = documents.get(documentId);
            require(actual != null,
                    id + " pointer names unknown document " + documentId);
            String pointer = text(expected, "pointer");
            Node selected = NodePathEditor.getOrNull(
                    actual.document(), pointer);
            require(selected != null,
                    id + " expected pointer is absent: "
                            + documentId + pointer);
            JsonNode actualValue = semanticValue(selected);
            JsonNode expectedValue = expected.get(
                    BlueLanguageConstants.OBJECT_VALUE);
            require(actualValue.equals(expectedValue),
                    id + " expected pointer value disagrees: "
                            + documentId + pointer + " (expected "
                            + expectedValue + ", actual "
                            + actualValue + ")");
        }
        for (JsonNode expected : optionalArray(expect, "absentPointers")) {
            String documentId = text(expected, "documentId");
            ResultingDocument actual = documents.get(documentId);
            require(actual != null,
                    id + " absentPointer names unknown document " + documentId);
            String pointer = text(expected, "pointer");
            require(NodePathEditor.getOrNull(actual.document(), pointer) == null,
                    id + " expected pointer is present: "
                            + documentId + pointer);
        }

        Map<String, Long> workCounts = new LinkedHashMap<String, Long>();
        ArrayList<String> kinds = new ArrayList<String>();
        for (ClosureWorkOccurrence work : evidence.workTrace()) {
            String kind = work.kind().name();
            kinds.add(kind);
            workCounts.put(kind, Long.valueOf(
                    workCounts.getOrDefault(kind, Long.valueOf(0L)) + 1L));
        }
        HashSet<String> declaredCounts = new HashSet<String>();
        for (JsonNode expected : optionalArray(expect, "workCounts")) {
            String kind = text(expected, "kind");
            require(declaredCounts.add(kind),
                    id + " expected work kinds must be unique");
            long actual = workCounts.getOrDefault(
                    kind, Long.valueOf(0L)).longValue();
            require(actual == requiredLong(expected, "count"),
                    id + " work count assertion failed for " + kind);
        }
        if (expect.has("workContainsInOrder")) {
            List<String> subsequence = textValues(
                    array(expect, "workContainsInOrder"));
            int cursor = 0;
            for (String kind : kinds) {
                if (cursor < subsequence.size()
                        && subsequence.get(cursor).equals(kind)) {
                    cursor++;
                }
            }
            require(cursor == subsequence.size(),
                    id + " workContainsInOrder assertion failed");
        }
        if (expect.has("workKindsExact")) {
            require(kinds.equals(textValues(array(expect, "workKindsExact"))),
                    id + " workKindsExact assertion failed");
        }

        ArrayNode expectedPublic = optionalArray(expect, "publicEvents");
        require(expectedPublic.size() == result.publicEvents().size(),
                id + " public event count assertion failed");
        for (int index = 0; index < expectedPublic.size(); index++) {
            JsonNode expected = expectedPublic.get(index);
            PublicEventOccurrence actual = result.publicEvents().get(index);
            JsonNode event = events.get(text(expected, "event"));
            require(event != null,
                    id + " public event expectation names unknown event");
            require(text(event, "_exportedBlueId").equals(
                            actual.eventBlueId())
                            && text(expected, "emitter").equals(
                            actual.publicRootDocumentId().value()),
                    id + " public event assertion failed at index " + index);
        }
        if (expect.has("distinctEventOccurrenceIdentities")) {
            LinkedHashSet<String> identities = new LinkedHashSet<String>();
            for (PublicEventOccurrence event : result.publicEvents()) {
                identities.add(event.eventOccurrenceIdentity());
            }
            for (ClosureWorkOccurrence work : evidence.workTrace()) {
                if ((work.kind().name().equals("TRIGGERED_EVENT")
                        || work.kind().name().equals("EMBEDDED_EVENT"))
                        && work.sourceOccurrenceIdentity() != null) {
                    identities.add(work.sourceOccurrenceIdentity());
                }
            }
            require(identities.size() == requiredLong(
                            expect, "distinctEventOccurrenceIdentities"),
                    id + " distinct event occurrence assertion failed");
        }
        if (expect.has("eventOccurrenceOrdinals")) {
            ArrayList<Long> ordinals = new ArrayList<Long>();
            for (PublicEventOccurrence event : result.publicEvents()) {
                ordinals.add(Long.valueOf(event.eventOccurrenceOrdinal()));
            }
            require(ordinals.equals(longValues(
                            array(expect, "eventOccurrenceOrdinals"))),
                    id + " eventOccurrenceOrdinals assertion failed");
        }
        if (expect.has("deliverySourceOccurrenceOrdinals")) {
            ArrayList<Long> ordinals = new ArrayList<Long>();
            for (ClosureWorkOccurrence work : evidence.workTrace()) {
                if (work.occurrenceOrdinal() != null) {
                    ordinals.add(work.occurrenceOrdinal());
                }
            }
            require(ordinals.equals(longValues(array(
                            expect, "deliverySourceOccurrenceOrdinals"))),
                    id + " deliverySourceOccurrenceOrdinals assertion failed");
        }
        require(result.checkpointWrites().size()
                        == requiredLong(expect, "checkpointWrites"),
                id + " checkpointWrites assertion failed");

        ArrayList<String> boundaries = new ArrayList<String>();
        long terminationMarkers = 0L;
        for (TentativeFinalization finalization
                : evidence.tentativeFinalizations()) {
            String kind = finalization.boundary().kind().name();
            boundaries.add(kind);
            if ("TERMINATION_MARKER".equals(kind)) {
                terminationMarkers++;
            }
        }
        if (expect.has("terminationMarkerCount")) {
            require(terminationMarkers == requiredLong(
                            expect, "terminationMarkerCount"),
                    id + " terminationMarkerCount assertion failed");
        }
        if (expect.has("finalizationBoundary")) {
            require(boundaries.contains(text(expect, "finalizationBoundary")),
                    id + " finalizationBoundary assertion failed");
        }
        if (expect.has("finalizationBoundariesExact")) {
            require(boundaries.equals(textValues(array(
                            expect, "finalizationBoundariesExact"))),
                    id + " finalizationBoundariesExact assertion failed");
        }
        boolean companion = result.platformCommitCompanion() != null;
        require(companion == "present".equals(
                        text(expect, "commitCompanion")),
                id + " commitCompanion assertion failed");
    }

    private static void verifyParity(
            JsonNode source,
            List<CompiledFixture> fixtures) {
        JsonNode expect = object(source, "expect");
        if (!expect.has("parityAcrossCases")) {
            return;
        }
        require(fixtures.size() > 1,
                text(source, "id") + " parity requires multiple cases");
        for (String projection : textValues(array(
                expect, "parityAcrossCases"))) {
            JsonNode baseline = parityProjection(fixtures.get(0), projection);
            for (int index = 1; index < fixtures.size(); index++) {
                require(baseline.equals(parityProjection(
                                fixtures.get(index), projection)),
                        text(source, "id") + " parity failed for "
                                + projection);
            }
        }
    }

    private static JsonNode parityProjection(
            CompiledFixture fixture,
            String projection) {
        ObjectNode expected = object(fixture.envelope, "expected");
        if ("invocationIdentity".equals(projection)) {
            return fixture.input.get("invocationIdentity");
        }
        if ("workTrace".equals(projection)) {
            ObjectNode value = JSON.objectNode();
            value.set("workTrace", expected.get("workTrace"));
            value.set("documentStepTrace", expected.get("documentStepTrace"));
            return value;
        }
        if ("finalizations".equals(projection)) {
            return expected.get("tentativeFinalizations");
        }
        if ("gas".equals(projection)) {
            ObjectNode value = JSON.objectNode();
            copyIfPresent(expected, value, "totalGas");
            copyIfPresent(expected, value, "gasTraceIdentity");
            copyIfPresent(expected, value, "gasTrace");
            return value;
        }
        if ("rejectionEvidence".equals(projection)) {
            ObjectNode value = JSON.objectNode();
            copyIfPresent(expected, value, "status");
            copyIfPresent(expected, value, "diagnostic");
            copyIfPresent(expected, value, "rejectedCharge");
            copyIfPresent(expected, value, "rejectedWorkOccurrence");
            copyIfPresent(expected, value, "rollbackToInput");
            return value;
        }
        if ("processResult".equals(projection)) {
            ObjectNode value = expected.deepCopy();
            value.remove(Arrays.asList(
                    "workTrace", "documentStepTrace",
                    "tentativeFinalizations", "gasTrace"));
            return value;
        }
        throw new IllegalArgumentException(
                "unsupported parity projection " + projection);
    }

    private static Map<String, String> expectedSourceIds() {
        LinkedHashMap<String, String> values =
                new LinkedHashMap<String, String>();
        values.put("fl-adm-01-root-patch-event.yaml",
                "fl-adm-01-root-patch-event");
        values.put("fl-adm-02-duplicate-equal-events.yaml",
                "fl-adm-02-duplicate-equal-events");
        values.put("fl-adm-03-non-public-containing-route.yaml",
                "fl-adm-03-non-public-containing-route");
        values.put("fl-adm-04-document-update-continuation.yaml",
                "fl-adm-04-document-update-continuation");
        values.put("fl-adm-05-graceful-termination.yaml",
                "fl-adm-05-graceful-termination");
        values.put("fl-adm-06-canonical-order-representation-parity.yaml",
                "fl-adm-06-order-representation");
        values.put("fl-adm-07-finite-cyclic-route.yaml",
                "fl-adm-07-finite-cyclic-route");
        values.put("fl-adm-08-infinite-cycle-gas-retry.yaml",
                "fl-adm-08-infinite-cycle-gas-retry");
        values.put("fl-adm-09-late-member-rollback.yaml",
                "fl-adm-09-late-member-rollback");
        values.put("fl-adm-10-unknown-occurrence.yaml",
                "fl-adm-10-unknown-occurrence");
        return Collections.unmodifiableMap(values);
    }

    private static void validateSourceFamily(Path sourceFile, JsonNode value) {
        String fileName = sourceFile.getFileName().toString();
        String expectedId = EXPECTED_SOURCE_IDS.get(fileName);
        require(expectedId != null,
                "unexpected full-lifecycle source file " + fileName);
        ObjectNode source = requiredObject(value, "source");
        require(expectedId.equals(text(source, "id")),
                fileName + " must declare id " + expectedId);
        String expectedScenario = "FL-ADM-" + fileName.substring(7, 9);
        require(expectedScenario.equals(text(source, "scenario")),
                fileName + " must declare scenario " + expectedScenario);
    }

    private static void validateSourceEnvelope(JsonNode value) {
        ObjectNode source = requiredObject(value, "source");
        validateStrictSourceSchema(source);
        require(SOURCE_SCHEMA.equals(text(
                        source, BlueLanguageConstants.OBJECT_SCHEMA)),
                "unsupported full-lifecycle source schema");
        require("admit-closure".equals(text(source, "operation")),
                "full-lifecycle source operation must be admit-closure");
        require(text(source, "id").matches(
                        "fl-adm-[0-9]{2}(?:-[a-z0-9-]+)?"),
                "invalid full-lifecycle source id");
        require(text(source, "scenario").matches("FL-ADM-[0-9]{2}"),
                "invalid full-lifecycle scenario");
        require(!text(source, "description").isEmpty(),
                "source description must not be empty");
        object(source, "events");
        ObjectNode documents = object(source, "documents");
        require(documents.size() > 0,
                "source must declare at least one document");
        array(source, "occurrences");
        object(source, "runtime");
        object(source, "gas");
        object(source, "expect");
        if (source.has("inputOrder")) {
            validatePermutation(array(source, "inputOrder"), documents,
                    "inputOrder");
        }
        if (source.has("cases")) {
            ArrayNode cases = array(source, "cases");
            require(cases.size() > 0, "cases must not be empty");
            LinkedHashSet<String> suffixes = new LinkedHashSet<String>();
            for (JsonNode caseValue : cases) {
                String suffix = text(caseValue, "suffix");
                require(suffix.matches("[a-z0-9][a-z0-9-]*")
                                && suffixes.add(suffix),
                        "case suffixes must be valid and unique");
                if (caseValue.has("inputOrder")) {
                    validatePermutation(array(caseValue, "inputOrder"),
                            documents, "case inputOrder");
                }
                if (caseValue.has("repeatOf")) {
                    require(suffixes.contains(text(caseValue, "repeatOf")),
                            "repeatOf must name an earlier case");
                }
            }
        }
        validateAuthoredReferences(source);
    }

    /**
     * Applies the closed authored-source surface from source-schema.yaml.
     * Blue values remain intentionally open; every compiler-control object is
     * closed here so a typo can never become ignored input.
     */
    private static void validateStrictSourceSchema(ObjectNode source) {
        validateObjectShape(source, "source",
                fields(BlueLanguageConstants.OBJECT_SCHEMA,
                        "id", "scenario", "description",
                        "operation", "events", "documents", "occurrences",
                        "runtime", "gas", "expect"),
                fields(BlueLanguageConstants.OBJECT_SCHEMA,
                        "id", "scenario", "description",
                        "operation", "events", "documents", "inputOrder",
                        "occurrences", "runtime", "gas", "cases",
                        "expect"));

        ObjectNode documents = object(source, "documents");
        Iterator<Map.Entry<String, JsonNode>> documentFields =
                documents.fields();
        while (documentFields.hasNext()) {
            Map.Entry<String, JsonNode> field = documentFields.next();
            ObjectNode wrapper = requiredObject(field.getValue(),
                    "documents." + field.getKey());
            validateObjectShape(wrapper,
                    "documents." + field.getKey(),
                    fields("publicRoot", "document"),
                    fields("publicRoot", "document"));
            requiredBoolean(wrapper, "publicRoot");
        }

        ArrayNode occurrences = array(source, "occurrences");
        for (int index = 0; index < occurrences.size(); index++) {
            ObjectNode occurrence = requiredObject(occurrences.get(index),
                    "occurrences[" + index + "]");
            String label = "occurrences[" + index + "]";
            validateObjectShape(occurrence, label,
                    fields("sourceDocumentId", "sourcePath",
                            "activationGeneration", "targetDocumentId",
                            "active"),
                    fields("sourceDocumentId", "sourcePath",
                            "activationGeneration", "targetDocumentId",
                            "active"));
            text(occurrence, "sourceDocumentId");
            String pointer = text(occurrence, "sourcePath");
            require(pointer.matches(
                            "^/(?:[^~]|~0|~1)*(?:/(?:[^~]|~0|~1)*)*$"),
                    label + ".sourcePath is not a valid JSON Pointer");
            require(requiredLong(occurrence, "activationGeneration") >= 1L,
                    label + ".activationGeneration must be at least 1");
            text(occurrence, "targetDocumentId");
            requiredBoolean(occurrence, "active");
        }

        validateRuntimeSchema(object(source, "runtime"));
        validateGasSchema(object(source, "gas"));
        if (source.has("inputOrder")) {
            validateUniqueTextArray(array(source, "inputOrder"),
                    "inputOrder");
        }
        if (source.has("cases")) {
            validateCasesSchema(array(source, "cases"));
        }
        validateExpectSchema(object(source, "expect"));
    }

    private static void validateRuntimeSchema(ObjectNode runtime) {
        validateObjectShape(runtime, "runtime",
                fields("handlers", "initializationHandlers"),
                fields("handlers", "initializationHandlers"));
        for (String bucketName : Arrays.asList(
                "handlers", "initializationHandlers")) {
            ObjectNode bucket = object(runtime, bucketName);
            Iterator<Map.Entry<String, JsonNode>> handlers = bucket.fields();
            while (handlers.hasNext()) {
                Map.Entry<String, JsonNode> handler = handlers.next();
                String label = "runtime." + bucketName + "."
                        + handler.getKey();
                ObjectNode result = requiredObject(handler.getValue(), label);
                validateObjectShape(result, label,
                        Collections.<String>emptySet(),
                        fields("patches", "events", "termination", "fail"));
                if (result.has("patches")) {
                    ArrayNode patches = array(result, "patches");
                    for (int index = 0; index < patches.size(); index++) {
                        validatePatchSchema(requiredObject(
                                patches.get(index),
                                label + ".patches[" + index + "]"),
                                label + ".patches[" + index + "]");
                    }
                }
                if (result.has("events")) {
                    array(result, "events");
                }
                if (result.has("termination")) {
                    ObjectNode termination = object(result, "termination");
                    validateObjectShape(termination,
                            label + ".termination",
                            fields("cause"), fields("cause", "reason"));
                    text(termination, "cause");
                    if (termination.has("reason")) {
                        require(termination.get("reason").isTextual(),
                                label + ".termination.reason must be Text");
                    }
                }
                if (result.has("fail")) {
                    text(result, "fail");
                    require(!result.has("patches")
                                    && !result.has("events")
                                    && !result.has("termination"),
                            label + ".fail is exclusive with successful output");
                }
            }
        }
    }

    private static void validatePatchSchema(ObjectNode patch, String label) {
        validateObjectShape(patch, label,
                fields("op", "path"), fields("op", "path", "val"));
        String operation = text(patch, "op");
        require(fields("add", "replace", "remove").contains(operation),
                label + ".op must be add, replace, or remove");
        text(patch, "path");
        if ("remove".equals(operation)) {
            require(!patch.has("val"),
                    label + ".val is forbidden for remove");
        } else {
            require(patch.has("val"),
                    label + ".val is required for " + operation);
        }
    }

    private static void validateGasSchema(ObjectNode gas) {
        validateObjectShape(gas, "gas", fields("sharedLimit"),
                fields("sharedLimit"));
        JsonNode limit = gas.get("sharedLimit");
        require((limit.isTextual()
                        && "release-default".equals(limit.textValue()))
                        || (limit.isIntegralNumber()
                        && limit.canConvertToLong()
                        && limit.longValue() >= 0L),
                "gas.sharedLimit must be release-default or a non-negative Integer");
    }

    private static void validateCasesSchema(ArrayNode cases) {
        require(cases.size() > 0, "cases must not be empty");
        for (int index = 0; index < cases.size(); index++) {
            ObjectNode value = requiredObject(cases.get(index),
                    "cases[" + index + "]");
            String label = "cases[" + index + "]";
            validateObjectShape(value, label, fields("suffix"),
                    fields("suffix", "inputOrder", "representation",
                            "repeatOf"));
            require(text(value, "suffix").matches(
                            "[a-z0-9][a-z0-9-]*"),
                    label + ".suffix is invalid");
            if (value.has("inputOrder")) {
                validateUniqueTextArray(array(value, "inputOrder"),
                        label + ".inputOrder");
            }
            if (value.has("representation")) {
                ObjectNode representation = object(value, "representation");
                validateObjectShape(representation,
                        label + ".representation",
                        fields("documentId", "path", "form"),
                        fields("documentId", "path", "form"));
                text(representation, "documentId");
                text(representation, "path");
                require(fields("pure-reference", "inline").contains(
                                text(representation, "form")),
                        label + ".representation.form is invalid");
            }
            if (value.has("repeatOf")) {
                text(value, "repeatOf");
                require(!value.has("inputOrder")
                                && !value.has("representation"),
                        label + ".repeatOf cannot add inputOrder or representation");
            }
        }
    }

    private static void validateExpectSchema(ObjectNode expect) {
        validateObjectShape(expect, "expect",
                fields("status", "atomic", "rollbackToInput",
                        "publicEvents", "checkpointWrites",
                        "commitCompanion"),
                fields("status", "diagnosticCategory", "atomic",
                        "rollbackToInput", "documents", "pointers",
                        "absentPointers", "workCounts",
                        "workContainsInOrder", "workKindsExact",
                        "publicEvents", "distinctEventOccurrenceIdentities",
                        "eventOccurrenceOrdinals",
                        "deliverySourceOccurrenceOrdinals",
                        "checkpointWrites", "terminationMarkerCount",
                        "finalizationBoundary",
                        "finalizationBoundariesExact", "commitCompanion",
                        "parityAcrossCases"));
        require(fields("success", "gas-limit-exceeded", "runtime-fatal",
                        "subscription-surface-invalid").contains(
                        text(expect, "status")),
                "expect.status is invalid");
        if (expect.has("diagnosticCategory")) {
            text(expect, "diagnosticCategory");
        }
        requiredBoolean(expect, "atomic");
        requiredBoolean(expect, "rollbackToInput");
        validateDocumentExpectations(optionalArray(expect, "documents"));
        validatePointerExpectations(optionalArray(expect, "pointers"), true);
        validatePointerExpectations(
                optionalArray(expect, "absentPointers"), false);
        validateWorkCounts(optionalArray(expect, "workCounts"));
        Set<String> workKinds = fields("INITIALIZATION", "LIFECYCLE",
                "TRIGGERED_EVENT", "EMBEDDED_EVENT", "DOCUMENT_UPDATE");
        validateEnumArray(expect, "workContainsInOrder", workKinds, false);
        validateEnumArray(expect, "workKindsExact", workKinds, false);
        validatePublicEventExpectations(array(expect, "publicEvents"));
        validateOptionalNonNegative(expect,
                "distinctEventOccurrenceIdentities");
        validateOrdinalArray(expect, "eventOccurrenceOrdinals");
        validateOrdinalArray(expect, "deliverySourceOccurrenceOrdinals");
        requiredLong(expect, "checkpointWrites");
        validateOptionalNonNegative(expect, "terminationMarkerCount");
        Set<String> boundaries = fields("WORK", "INITIALIZATION_BATCH",
                "TERMINATION_MARKER", "CHECKPOINT_SETTLEMENT");
        if (expect.has("finalizationBoundary")) {
            require(boundaries.contains(text(expect,
                            "finalizationBoundary")),
                    "expect.finalizationBoundary is invalid");
        }
        validateEnumArray(expect, "finalizationBoundariesExact",
                boundaries, false);
        require(fields("present", "absent").contains(
                        text(expect, "commitCompanion")),
                "expect.commitCompanion is invalid");
        validateEnumArray(expect, "parityAcrossCases",
                fields("invocationIdentity", "processResult", "gas",
                        "workTrace", "finalizations", "rejectionEvidence"),
                true);
    }

    private static void validateDocumentExpectations(ArrayNode values) {
        for (int index = 0; index < values.size(); index++) {
            ObjectNode value = requiredObject(values.get(index),
                    "expect.documents[" + index + "]");
            String label = "expect.documents[" + index + "]";
            validateObjectShape(value, label, fields("documentId"),
                    fields("documentId", "initialized", "terminated"));
            text(value, "documentId");
            if (value.has("initialized")) {
                requiredBoolean(value, "initialized");
            }
            if (value.has("terminated")) {
                requiredBoolean(value, "terminated");
            }
        }
    }

    private static void validatePointerExpectations(
            ArrayNode values, boolean requireValue) {
        for (int index = 0; index < values.size(); index++) {
            String prefix = requireValue ? "expect.pointers["
                    : "expect.absentPointers[";
            String label = prefix + index + "]";
            ObjectNode value = requiredObject(values.get(index), label);
            Set<String> required = requireValue
                    ? fields("documentId", "pointer",
                            BlueLanguageConstants.OBJECT_VALUE)
                    : fields("documentId", "pointer");
            validateObjectShape(value, label, required, required);
            text(value, "documentId");
            text(value, "pointer");
        }
    }

    private static void validateWorkCounts(ArrayNode values) {
        Set<String> kinds = fields("INITIALIZATION", "LIFECYCLE",
                "TRIGGERED_EVENT", "EMBEDDED_EVENT", "DOCUMENT_UPDATE");
        for (int index = 0; index < values.size(); index++) {
            ObjectNode value = requiredObject(values.get(index),
                    "expect.workCounts[" + index + "]");
            String label = "expect.workCounts[" + index + "]";
            validateObjectShape(value, label, fields("kind", "count"),
                    fields("kind", "count"));
            require(kinds.contains(text(value, "kind")),
                    label + ".kind is invalid");
            requiredLong(value, "count");
        }
    }

    private static void validatePublicEventExpectations(ArrayNode values) {
        for (int index = 0; index < values.size(); index++) {
            ObjectNode value = requiredObject(values.get(index),
                    "expect.publicEvents[" + index + "]");
            String label = "expect.publicEvents[" + index + "]";
            validateObjectShape(value, label, fields("event", "emitter"),
                    fields("event", "emitter"));
            text(value, "event");
            text(value, "emitter");
        }
    }

    private static void validateOrdinalArray(JsonNode parent, String field) {
        if (!parent.has(field)) {
            return;
        }
        ArrayNode values = array(parent, field);
        for (JsonNode value : values) {
            require(value.isIntegralNumber() && value.canConvertToLong()
                            && value.longValue() >= 0L,
                    "expect." + field
                            + " must contain non-negative Integers");
        }
    }

    private static void validateOptionalNonNegative(
            JsonNode parent, String field) {
        if (parent.has(field)) {
            requiredLong(parent, field);
        }
    }

    private static void validateEnumArray(
            JsonNode parent,
            String field,
            Set<String> permitted,
            boolean unique) {
        if (!parent.has(field)) {
            return;
        }
        ArrayNode values = array(parent, field);
        LinkedHashSet<String> observed = new LinkedHashSet<String>();
        for (JsonNode value : values) {
            require(value.isTextual()
                            && permitted.contains(value.textValue()),
                    "expect." + field + " contains an invalid value");
            require(!unique || observed.add(value.textValue()),
                    "expect." + field + " must contain unique values");
        }
    }

    private static void validateUniqueTextArray(
            ArrayNode values, String label) {
        List<String> strings = textValues(values);
        require(new LinkedHashSet<String>(strings).size() == strings.size(),
                label + " must contain unique values");
    }

    private static void validateObjectShape(
            ObjectNode value,
            String label,
            Set<String> required,
            Set<String> allowed) {
        Iterator<String> names = value.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            require(allowed.contains(name),
                    label + " contains unknown field " + name);
        }
        for (String name : required) {
            require(value.has(name),
                    label + " is missing required field " + name);
        }
    }

    private static Set<String> fields(String... names) {
        return new LinkedHashSet<String>(Arrays.asList(names));
    }

    private static void validateAuthoredReferences(ObjectNode source) {
        ObjectNode documents = object(source, "documents");
        Set<String> documentIds = new LinkedHashSet<String>();
        documents.fieldNames().forEachRemaining(documentIds::add);
        Set<String> eventIds = new LinkedHashSet<String>();
        object(source, "events").fieldNames().forEachRemaining(eventIds::add);
        for (JsonNode occurrence : array(source, "occurrences")) {
            require(documentIds.contains(text(
                            occurrence, "sourceDocumentId"))
                            && documentIds.contains(text(
                            occurrence, "targetDocumentId")),
                    "occurrence references unknown document");
            require(requiredLong(occurrence, "activationGeneration") > 0L,
                    "occurrence activationGeneration must be positive");
        }
        JsonNode expect = object(source, "expect");
        for (String field : Arrays.asList(
                "documents", "pointers", "absentPointers")) {
            for (JsonNode item : optionalArray(expect, field)) {
                require(documentIds.contains(text(item, "documentId")),
                        field + " expectation references unknown document");
            }
        }
        for (JsonNode item : optionalArray(expect, "publicEvents")) {
            require(eventIds.contains(text(item, "event"))
                            && documentIds.contains(text(item, "emitter")),
                    "public event expectation has an unresolved reference");
        }
        HashSet<String> expectedKinds = new HashSet<String>();
        for (JsonNode item : optionalArray(expect, "workCounts")) {
            require(expectedKinds.add(text(item, "kind")),
                    "expected work kinds must be unique");
        }
    }

    private static void validateRuntime(
            JsonNode source,
            ObjectNode runtime) {
        ObjectNode handlers = object(runtime, "handlers");
        ObjectNode initialization = object(runtime, "initializationHandlers");
        Set<String> keys = new LinkedHashSet<String>();
        for (ObjectNode bucket : Arrays.asList(handlers, initialization)) {
            Iterator<Map.Entry<String, JsonNode>> fields = bucket.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                require(keys.add(field.getKey()),
                        "runtime Handler appears in both buckets: "
                                + field.getKey());
                String[] parts = field.getKey().split("/", 2);
                require(parts.length == 2,
                        "runtime Handler key must be documentId/contractKey");
                JsonNode wrapper = object(source, "documents").get(parts[0]);
                require(wrapper != null,
                        "runtime Handler references unknown document");
                JsonNode contract = requiredObject(wrapper.get("document"),
                        "authored document")
                        .path(BlueLanguageConstants.OBJECT_CONTRACTS)
                        .path(parts[1]);
                require(contract.isObject(),
                        "runtime key does not select an authored contract");
                ObjectNode result = requiredObject(
                        field.getValue(), "runtime Handler result");
                if (result.has("fail")) {
                    require(result.size() == 1,
                            "runtime fail is exclusive with successful output");
                }
                for (JsonNode patch : optionalArray(result, "patches")) {
                    String path = text(patch, "path");
                    require(!path.equals("/contracts/initialized")
                                    && !path.startsWith(
                                    "/contracts/initialized/")
                                    && !path.equals("/contracts/terminated")
                                    && !path.startsWith(
                                    "/contracts/terminated/")
                                    && !path.equals("/contracts/checkpoint")
                                    && !path.startsWith(
                                    "/contracts/checkpoint/"),
                            "runtime patch targets processor-owned state");
                }
            }
        }
    }

    private static List<JsonNode> cases(JsonNode source) {
        if (!source.has("cases")) {
            return Collections.singletonList(null);
        }
        ArrayList<JsonNode> result = new ArrayList<JsonNode>();
        array(source, "cases").forEach(result::add);
        return result;
    }

    private static List<String> inputOrder(
            JsonNode source,
            JsonNode caseValue,
            ObjectNode documents) {
        JsonNode selected = caseValue != null && caseValue.has("inputOrder")
                ? caseValue.get("inputOrder") : source.get("inputOrder");
        if (selected == null) {
            ArrayList<String> sorted = new ArrayList<String>();
            documents.fieldNames().forEachRemaining(sorted::add);
            Collections.sort(sorted);
            return sorted;
        }
        ArrayList<String> result = new ArrayList<String>(
                textValues((ArrayNode) selected));
        validatePermutation((ArrayNode) selected, documents, "inputOrder");
        return result;
    }

    private static void validatePermutation(
            ArrayNode order,
            ObjectNode documents,
            String label) {
        LinkedHashSet<String> values = new LinkedHashSet<String>(
                textValues(order));
        LinkedHashSet<String> expected = new LinkedHashSet<String>();
        documents.fieldNames().forEachRemaining(expected::add);
        require(values.size() == order.size() && values.equals(expected),
                label + " must be a complete document permutation");
    }

    private static void validateDocumentOccurrenceMacros(
            JsonNode source,
            JsonNode caseValue,
            List<String> inputOrder) {
        ObjectNode documents = object(source, "documents");
        LinkedHashMap<String, String> targetsByLocation =
                new LinkedHashMap<String, String>();
        for (JsonNode occurrence : array(source, "occurrences")) {
            String sourceDocumentId = text(
                    occurrence, "sourceDocumentId");
            String sourcePath = text(occurrence, "sourcePath");
            String targetDocumentId = text(
                    occurrence, "targetDocumentId");
            String location = occurrenceLocation(
                    sourceDocumentId, sourcePath);
            require(targetsByLocation.put(location, targetDocumentId) == null,
                    "multiple occurrences declare the same source path: "
                            + sourceDocumentId + sourcePath);
            JsonNode body = requiredObject(
                    documents.get(sourceDocumentId),
                    "document " + sourceDocumentId).get("document");
            JsonNode authored = body == null
                    ? null : body.at(sourcePath);
            require(authored != null && !authored.isMissingNode(),
                    "occurrence source path is absent in authored document: "
                            + sourceDocumentId + sourcePath);
            validateAlignedDocumentMacro(
                    authored,
                    sourceDocumentId,
                    sourcePath,
                    targetDocumentId,
                    inputOrder,
                    hasExplicitInputOrder(source, caseValue));
        }

        Iterator<Map.Entry<String, JsonNode>> fields = documents.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> document = fields.next();
            JsonNode body = requiredObject(document.getValue(),
                    "document " + document.getKey()).get("document");
            validateNoUnboundDocumentMacros(
                    body,
                    document.getKey(),
                    "",
                    targetsByLocation,
                    inputOrder,
                    hasExplicitInputOrder(source, caseValue));
        }
    }

    private static void validateAlignedDocumentMacro(
            JsonNode authored,
            String sourceDocumentId,
            String sourcePath,
            String targetDocumentId,
            List<String> inputOrder,
            boolean explicitInputOrder) {
        ObjectNode object = requiredObject(authored,
                "managed occurrence value " + sourceDocumentId + sourcePath);
        if (object.has("$documentBlueId")
                || object.has("$documentInline")) {
            require(object.size() == 1,
                    "document macro must be the entire occurrence value at "
                            + sourceDocumentId + sourcePath);
            String macro = object.has("$documentBlueId")
                    ? "$documentBlueId" : "$documentInline";
            require(targetDocumentId.equals(text(object, macro)),
                    "document macro target disagrees with occurrence at "
                            + sourceDocumentId + sourcePath);
            return;
        }
        JsonNode identity = object.get(
                BlueLanguageConstants.OBJECT_BLUE_ID);
        require(identity != null && identity.isTextual()
                        && identity.textValue().startsWith("this#")
                        && object.size() == 1,
                "managed occurrence value must be a document macro or this#n "
                        + "placeholder at " + sourceDocumentId + sourcePath);
        require(explicitInputOrder,
                "this#n placeholder requires explicit inputOrder at "
                        + sourceDocumentId + sourcePath);
        int index = placeholderIndex(identity.textValue(),
                sourceDocumentId + sourcePath);
        require(index < inputOrder.size()
                        && targetDocumentId.equals(inputOrder.get(index)),
                identity.textValue() + " disagrees with occurrence target "
                        + targetDocumentId + " at "
                        + sourceDocumentId + sourcePath);
    }

    private static void validateNoUnboundDocumentMacros(
            JsonNode value,
            String documentId,
            String pointer,
            Map<String, String> targetsByLocation,
            List<String> inputOrder,
            boolean explicitInputOrder) {
        if (value == null || value.isValueNode()) {
            return;
        }
        if (value.isArray()) {
            for (int index = 0; index < value.size(); index++) {
                validateNoUnboundDocumentMacros(
                        value.get(index), documentId,
                        pointer + "/" + index,
                        targetsByLocation, inputOrder, explicitInputOrder);
            }
            return;
        }
        ObjectNode object = (ObjectNode) value;
        boolean documentMacro = object.has("$documentBlueId")
                || object.has("$documentInline");
        JsonNode identity = object.get(
                BlueLanguageConstants.OBJECT_BLUE_ID);
        boolean placeholder = identity != null && identity.isTextual()
                && identity.textValue().startsWith("this#");
        if (documentMacro || placeholder) {
            String target = targetsByLocation.get(
                    occurrenceLocation(documentId, pointer));
            require(target != null,
                    "document macro/placeholder has no declared occurrence at "
                            + documentId + pointer);
            validateAlignedDocumentMacro(object, documentId, pointer, target,
                    inputOrder, explicitInputOrder);
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            validateNoUnboundDocumentMacros(
                    field.getValue(), documentId,
                    pointer + "/" + escapePointer(field.getKey()),
                    targetsByLocation, inputOrder, explicitInputOrder);
        }
    }

    private static boolean hasExplicitInputOrder(
            JsonNode source, JsonNode caseValue) {
        return (caseValue != null && caseValue.has("inputOrder"))
                || source.has("inputOrder");
    }

    private static int placeholderIndex(String value, String label) {
        try {
            int index = Integer.parseInt(value.substring(5));
            require(index >= 0,
                    "invalid cyclic placeholder " + value + " at " + label);
            return index;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(
                    "invalid cyclic placeholder " + value + " at " + label,
                    invalid);
        }
    }

    private static String occurrenceLocation(
            String documentId, String pointer) {
        return documentId + "\u0000" + pointer;
    }

    private static String escapePointer(String value) {
        return value.replace("~", "~0").replace("/", "~1");
    }

    private static String referenceIdentityAt(Node body, String path) {
        Node selected = NodePathEditor.getOrNull(body, path);
        require(selected != null,
                "occurrence source path is absent: " + path);
        String blueId = selected.getBlueId();
        return blueId == null ? DIRECT.directBlueId(selected) : blueId;
    }

    private static String scalarTextAt(Node body, String path) {
        Node selected = NodePathEditor.getOrNull(body, path);
        require(selected != null && selected.getValue() instanceof String,
                "documentId must be an authored Text value");
        return (String) selected.getValue();
    }

    private static JsonNode readYaml(Path path) {
        try (InputStream input = Files.newInputStream(path)) {
            JsonNode value = YAML.readTree(input);
            require(value != null, "YAML root must not be null: " + path);
            return value;
        } catch (IOException exception) {
            throw new IllegalStateException("unable to read YAML " + path,
                    exception);
        }
    }

    private static byte[] deterministicYaml(JsonNode value) {
        try {
            String yaml = OUTPUT_YAML.writeValueAsString(value)
                    .replace("\r\n", "\n");
            if (!yaml.endsWith("\n")) {
                yaml = yaml + "\n";
            }
            return yaml.getBytes(StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "unable to serialize executable fixture", exception);
        }
    }

    private static Node node(JsonNode value) {
        return UncheckedObjectMapper.JSON_MAPPER.convertValue(
                value, Node.class);
    }

    private static JsonNode wire(Node value) {
        return UncheckedObjectMapper.JSON_MAPPER.valueToTree(
                NodeWireForm.get(value, NodeWireForm.Strategy.SIMPLE));
    }

    private static JsonNode semanticValue(Node value) {
        if (value.getValue() != null) {
            return UncheckedObjectMapper.JSON_MAPPER.valueToTree(
                    value.getValue());
        }
        if (value.getItems() != null) {
            ArrayNode result = JSON.arrayNode();
            for (Node item : value.getItems()) {
                result.add(semanticValue(item));
            }
            return result;
        }
        if (value.getProperties() != null) {
            ObjectNode result = JSON.objectNode();
            for (Map.Entry<String, Node> entry
                    : value.getProperties().entrySet()) {
                result.set(entry.getKey(), semanticValue(entry.getValue()));
            }
            return result;
        }
        if (value.getBlueId() != null) {
            ObjectNode result = JSON.objectNode();
            result.put(BlueLanguageConstants.OBJECT_BLUE_ID,
                    value.getBlueId());
            return result;
        }
        return JSON.nullNode();
    }

    private static ObjectNode requiredObject(JsonNode value, String label) {
        require(value != null && value.isObject(),
                label + " must be an object");
        return (ObjectNode) value;
    }

    private static ObjectNode object(JsonNode parent, String field) {
        return requiredObject(parent.get(field), field);
    }

    private static ArrayNode array(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        require(value != null && value.isArray(),
                field + " must be an array");
        return (ArrayNode) value;
    }

    private static ArrayNode optionalArray(JsonNode parent, String field) {
        return parent.has(field) ? array(parent, field) : JSON.arrayNode();
    }

    private static String text(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        require(value != null && value.isTextual()
                        && !value.textValue().isEmpty(),
                field + " must be non-empty Text");
        return value.textValue();
    }

    private static String nullableText(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        require(value.isTextual() && !value.textValue().isEmpty(),
                field + " must be null or non-empty Text");
        return value.textValue();
    }

    private static long requiredLong(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        require(value != null && value.isIntegralNumber()
                        && value.canConvertToLong() && value.longValue() >= 0L,
                field + " must be a non-negative Integer");
        return value.longValue();
    }

    private static boolean requiredBoolean(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        require(value != null && value.isBoolean(),
                field + " must be Boolean");
        return value.booleanValue();
    }

    private static String sha256(String value, String label) {
        require(value != null && value.matches("[0-9a-f]{64}"),
                label + " identity must be a lowercase SHA-256 digest");
        return "sha256:" + value;
    }

    private static List<String> textValues(ArrayNode values) {
        ArrayList<String> result = new ArrayList<String>();
        for (JsonNode value : values) {
            require(value.isTextual() && !value.textValue().isEmpty(),
                    "array item must be non-empty Text");
            result.add(value.textValue());
        }
        return result;
    }

    private static List<Long> longValues(ArrayNode values) {
        ArrayList<Long> result = new ArrayList<Long>();
        for (JsonNode value : values) {
            require(value.isIntegralNumber() && value.longValue() >= 0L,
                    "array item must be a non-negative Integer");
            result.add(Long.valueOf(value.longValue()));
        }
        return result;
    }

    private static ArrayNode documentIds(List<DocumentId> values) {
        ArrayNode result = JSON.arrayNode();
        for (DocumentId value : values) {
            result.add(value.value());
        }
        return result;
    }

    private static ArrayNode textArray(List<String> values) {
        ArrayNode result = JSON.arrayNode();
        for (String value : values) {
            result.add(value);
        }
        return result;
    }

    private static void nullable(
            ObjectNode target,
            String field,
            Object value) {
        if (value == null) {
            target.putNull(field);
        } else if (value instanceof Long) {
            target.put(field, ((Long) value).longValue());
        } else if (value instanceof Integer) {
            target.put(field, ((Integer) value).intValue());
        } else {
            target.put(field, value.toString());
        }
    }

    private static void nullableDocument(
            ObjectNode target,
            String field,
            DocumentId value) {
        if (value == null) {
            target.putNull(field);
        } else {
            target.put(field, value.value());
        }
    }

    private static void nullableNode(
            ObjectNode target,
            String field,
            JsonNode value) {
        if (value == null) {
            target.putNull(field);
        } else {
            target.set(field, value);
        }
    }

    private static void copyIfPresent(
            ObjectNode source,
            ObjectNode target,
            String field) {
        if (source.has(field)) {
            target.set(field, source.get(field));
        }
    }

    private static void rejectUnresolvedMacros(JsonNode value, String label) {
        rejectUnresolvedMacros(value, label, "");
    }

    private static void rejectUnresolvedMacros(
            JsonNode value,
            String label,
            String pointer) {
        if (value.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                require(!field.getKey().startsWith("$"),
                        label + " contains unresolved macro "
                                + field.getKey() + " at " + pointer);
                if (BlueLanguageConstants.OBJECT_BLUE_ID.equals(field.getKey())
                        && field.getValue().isTextual()) {
                    String identity = field.getValue().textValue();
                    boolean canonicalProofPlaceholder =
                            identity.startsWith("this#")
                                    && pointer.contains(
                                    "/completeCyclicProof/declaredPlaceholderSet/");
                    require((!identity.startsWith("this#")
                                    || canonicalProofPlaceholder)
                                    && !DUMMY_BLUE_ID.equals(identity),
                            label + " contains unresolved document identity at "
                                    + pointer + "/" + field.getKey() + ": "
                                    + identity);
                }
                rejectUnresolvedMacros(
                        field.getValue(), label,
                        pointer + "/" + field.getKey().replace("~", "~0")
                                .replace("/", "~1"));
            }
        } else if (value.isArray()) {
            for (int index = 0; index < value.size(); index++) {
                rejectUnresolvedMacros(
                        value.get(index), label, pointer + "/" + index);
            }
        }
    }

    private static Path existingAbsoluteDirectory(Path value, String label) {
        Objects.requireNonNull(value, label);
        require(value.isAbsolute(), label + " must be absolute");
        Path normalized = value.normalize();
        require(Files.isDirectory(normalized),
                label + " must be an existing directory");
        return normalized;
    }

    private static Path emptyAbsoluteDirectory(Path value, String label)
            throws IOException {
        Objects.requireNonNull(value, label);
        require(value.isAbsolute(), label + " must be absolute");
        Path normalized = value.normalize();
        if (!Files.exists(normalized)) {
            Path parent = normalized.getParent();
            require(parent != null && Files.isDirectory(parent),
                    label + " parent must be an existing directory");
            Files.createDirectory(normalized);
        }
        require(Files.isDirectory(normalized),
                label + " must be a directory");
        try (DirectoryStream<Path> entries =
                     Files.newDirectoryStream(normalized)) {
            require(!entries.iterator().hasNext(), label + " must be empty");
        }
        return normalized;
    }

    private static void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            List<Path> reversed = paths.sorted(Comparator.reverseOrder())
                    .collect(Collectors.toList());
            for (Path path : reversed) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
