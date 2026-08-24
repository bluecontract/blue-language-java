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

import blue.language.conformance.contracts.FullLifecycleFixtureExporter.CompiledFixture;

import static blue.language.conformance.contracts.FullLifecycleFixtureExporter.JSON;
import static blue.language.conformance.contracts.FullLifecycleFixtureExporter.array;
import static blue.language.conformance.contracts.FullLifecycleFixtureExporter.copyIfPresent;
import static blue.language.conformance.contracts.FullLifecycleFixtureExporter.longValues;
import static blue.language.conformance.contracts.FullLifecycleFixtureExporter.object;
import static blue.language.conformance.contracts.FullLifecycleFixtureExporter.optionalArray;
import static blue.language.conformance.contracts.FullLifecycleFixtureExporter.requiredBoolean;
import static blue.language.conformance.contracts.FullLifecycleFixtureExporter.requiredLong;
import static blue.language.conformance.contracts.FullLifecycleFixtureExporter.semanticValue;
import static blue.language.conformance.contracts.FullLifecycleFixtureExporter.text;
import static blue.language.conformance.contracts.FullLifecycleFixtureExporter.textValues;
import static blue.language.conformance.contracts.FullLifecycleFixtureFiles.require;

/** Post-execution source assertions and case-parity projections. */
final class FullLifecycleFixtureAssertions {

    private FullLifecycleFixtureAssertions() {
    }

static void assertExpectations(
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
static void verifyParity(
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
}
