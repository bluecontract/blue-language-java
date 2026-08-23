package blue.language.conformance.contracts.closure;

import blue.language.conformance.api.BlueContractsConformanceReport;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.ProcessorDiagnostic;
import blue.language.processor.GasSchedule;
import blue.language.processor.closure.BlueClosureContracts;
import blue.language.processor.closure.ClosureAttemptResult;
import blue.language.processor.closure.ClosureExecutionObserver;
import blue.language.processor.closure.ClosureImplementationEvidence;
import blue.language.processor.closure.ClosureInvocationInput;
import blue.language.processor.closure.ClosureProcessResult;
import blue.language.processor.closure.ClosureWorkOccurrence;
import blue.language.processor.closure.DocumentId;
import blue.language.processor.closure.DocumentStepEvidence;
import blue.language.processor.closure.GasTraceEntry;
import blue.language.processor.closure.TentativeFinalization;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Executes one closure fixture for the supported release report. */
public final class ClosureFixtureConformance {

    private ClosureFixtureConformance() {
    }

    /**
     * Executes and verifies one inventoried closure fixture.
     *
     * <p>Invocation fixtures are parsed and executed from a copy with the
     * expected subtree removed. Expected evidence becomes visible only after
     * the public facade and observer have completed.</p>
     *
     * @param inventory exact combined-package inventory entry
     * @param fixture exact fixture envelope
     * @param processor independently prepared fixture processor, or
     *     {@code null} for a limit microfixture
     */
    public static void execute(
            BlueContractsConformanceReport.FixtureInventoryEntry inventory,
            JsonNode fixture,
            DocumentProcessor processor) {
        require("closure-fixture".equals(inventory.role()),
                inventory.id() + " is not a closure fixture");
        if ("limit-micro".equals(inventory.operation())) {
            executeLimitMicro(inventory.id(), fixture);
            return;
        }
        if (!"process-closure".equals(inventory.operation())
                && !"admit-closure".equals(inventory.operation())) {
            throw new IllegalArgumentException(
                    "Unsupported closure operation: "
                            + inventory.operation());
        }

        ObjectNode executionFixture = (ObjectNode) fixture.deepCopy();
        executionFixture.remove("expected");
        require(processor != null,
                inventory.id() + " has no prepared fixture processor");
        ClosureFixtureInventory.Entry entry =
                new ClosureFixtureInventory.Entry(
                        inventory.id(),
                        inventory.path(),
                        inventory.operation(),
                        inventory.vectors(),
                        "verified-by-combined-manifest",
                        0L);
        ClosureInvocationInput input = new ClosureFixtureParser()
                .parse(entry, executionFixture)
                .admit();
        Capture capture = new Capture();
        ClosureAttemptResult attempt;
        try (BlueClosureContracts contracts = new BlueClosureContracts(
                     processor, capture)) {
            attempt = "process-closure".equals(inventory.operation())
                    ? contracts.processClosure(input)
                    : contracts.admitClosureWithLifecycleQueue(input);
        }

        ObjectNode expected = (ObjectNode) requiredObject(
                fixture, "expected").deepCopy();
        attachExternalGasTrace(inventory.id(), expected);
        verifyAttempt(inventory.id(), expected, attempt, capture.evidence);
    }

    private static void executeLimitMicro(String id, JsonNode fixture) {
        JsonNode limit = requiredObject(fixture, "limit");
        JsonNode expected = requiredObject(fixture, "expected");
        String name = requiredText(limit, "limit");
        long configured = requiredLong(limit, "configured");
        long observed = requiredLong(limit, "observed");
        require(configured >= 0L && observed >= 0L,
                id + " limit quantities must be non-negative");
        equal(id + ".limit.configured",
                Long.valueOf(GasSchedule.contracts10()
                        .portableLimit(name)),
                Long.valueOf(configured));
        long measured = ClosureLimitMicroEvaluator.measure(
                name, requiredObject(limit, "generator"));
        equal(id + ".limit.observed",
                Long.valueOf(measured), Long.valueOf(observed));
        String decision = measured <= configured ? "ACCEPT" : "REJECT";
        equal(id + ".expected.limitDecision",
                requiredText(expected, "limitDecision"), decision);
        equal(id + ".expected.rejectedStepAdmitted",
                Boolean.valueOf(requiredBoolean(
                        expected, "rejectedStepAdmitted")),
                Boolean.FALSE);
        equal(id + ".expected.subsequentOutcome",
                requiredText(expected, "subsequentOutcome"),
                "NOT_EXECUTED_BY_MICROFIXTURE");
        if ("REJECT".equals(decision)) {
            equal(id + ".expected.diagnostic",
                    ClosureLimitMicroEvaluator.diagnostic(name),
                    requiredText(expected, "diagnostic"));
            equal(id + ".expected.rejectedStep",
                    requiredText(expected, "rejectedStep"),
                    name + ":" + measured);
            require(measured == configured + 1L,
                    id + " first-above microfixture must measure bound + 1");
        } else {
            require(!expected.has("diagnostic")
                            && !expected.has("rejectedStep"),
                    id + " accepted limit microfixture cannot reject a step");
            require(measured == configured,
                    id + " at-bound microfixture must measure its bound");
        }
    }

    private static void attachExternalGasTrace(
            String id,
            ObjectNode expected) {
        JsonNode path = expected.get("gasTraceFile");
        if (path == null || path.isNull()) {
            return;
        }
        require(path.isTextual() && !path.asText().contains(".."),
                id + " has an unsafe gas trace path");
        JsonNode trace = BlueContractsConformanceReport.readFixture(
                "closure/" + path.asText());
        equal(id + ".gasTraceFile.fixture",
                requiredText(trace, "fixture"), id);
        expected.set("gasTrace", requiredArray(trace, "entries").deepCopy());
    }

    private static void verifyAttempt(
            String id,
            JsonNode expected,
            ClosureAttemptResult attempt,
            ClosureImplementationEvidence evidence) {
        require(attempt != null, id + " returned no closure attempt");
        equal(id + ".attemptOutcome",
                requiredText(expected, "attemptOutcome"),
                attempt.isComplete() ? "Complete" : "NeedsResources");
        if (!attempt.isComplete()) {
            equal(id + ".requiredBlueIds",
                    textValues(requiredArray(expected, "requiredBlueIds")),
                    attempt.requiredExactBlueIds());
            require(attempt.processResult() == null,
                    id + " NeedsResources attempt exposed a result");
            require(attempt.totalGas() == null,
                    id + " NeedsResources attempt exposed totalGas");
            require(evidence == null,
                    id + " NeedsResources attempt exposed execution evidence");
            return;
        }

        ClosureProcessResult result = attempt.processResult();
        require(result != null, id + " complete attempt lacks a result");
        equal(id + ".status", requiredText(expected, "status"),
                result.status().wireValue());
        equal(id + ".commits",
                Boolean.valueOf("success".equals(
                        requiredText(expected, "status"))),
                Boolean.valueOf(result.commits()));
        equal(id + ".atomic",
                Boolean.valueOf(requiredBoolean(expected, "atomic")),
                Boolean.valueOf(result.atomic()));
        equal(id + ".invocationIdentity",
                requiredText(expected, "invocationIdentity"),
                result.invocationIdentity());
        equal(id + ".inputClosureIdentity",
                requiredText(expected, "inputClosureIdentity"),
                result.inputClosureIdentity());
        equal(id + ".outputClosureIdentity",
                requiredText(expected, "outputClosureIdentity"),
                result.outputClosureIdentity());
        equal(id + ".graphGeneration",
                Long.valueOf(requiredLong(expected, "graphGeneration")),
                Long.valueOf(result.graphGeneration()));
        equal(id + ".resultingDocuments.size",
                Integer.valueOf(requiredArray(
                        expected, "resultingDocuments").size()),
                Integer.valueOf(result.resultingDocuments().size()));
        equal(id + ".resultingComponents.size",
                Integer.valueOf(requiredArray(
                        expected, "resultingComponents").size()),
                Integer.valueOf(result.resultingComponents().size()));
        equal(id + ".occurrenceBindings.size",
                Integer.valueOf(requiredArray(
                        expected, "occurrenceBindings").size()),
                Integer.valueOf(result.occurrenceBindings().size()));
        equal(id + ".occurrenceBindingSetIdentity",
                requiredText(expected, "occurrenceBindingSetIdentity"),
                result.occurrenceBindingSetIdentity());
        equal(id + ".graphChangesIdentity",
                requiredText(expected, "graphChangesIdentity"),
                result.graphChangesIdentity());
        equal(id + ".subscriptionDeltasIdentity",
                requiredText(expected, "subscriptionDeltasIdentity"),
                result.subscriptionDeltasIdentity());
        equal(id + ".checkpointWritesIdentity",
                requiredText(expected, "checkpointWritesIdentity"),
                result.checkpointWritesIdentity());
        equal(id + ".publicEventsIdentity",
                requiredText(expected, "publicEventsIdentity"),
                result.publicEventsIdentity());
        equal(id + ".rollbackToInput",
                Boolean.valueOf(requiredBoolean(expected, "rollbackToInput")),
                Boolean.valueOf(result.rollbackToInput()));
        equal(id + ".totalGas",
                Long.valueOf(requiredLong(expected, "totalGas")),
                Long.valueOf(result.totalGas()));
        equal(id + ".attempt.totalGas",
                Long.valueOf(result.totalGas()), attempt.totalGas());
        verifyGasTrace(id, requiredArray(expected, "gasTrace"),
                result.gasTrace());
        equal(id + ".gasTraceIdentity",
                requiredText(expected, "gasTraceIdentity"),
                result.gasTraceIdentity());
        verifyDiagnostic(id, expected.get("diagnostic"),
                result.diagnostic());
        verifyRejectedChargeIdentity(id, expected, result);
        verifyCommitCompanionIdentity(id, expected, result);
        verifyImplementationEvidence(
                id, expected, result.invocationIdentity(), evidence);
    }

    static void verifyImplementationEvidence(
            String id,
            JsonNode expected,
            String invocationIdentity,
            ClosureImplementationEvidence evidence) {
        JsonNode workTrace = requiredArray(expected, "workTrace");
        JsonNode documentStepTrace = requiredArray(
                expected, "documentStepTrace");
        JsonNode tentativeFinalizations = requiredArray(
                expected, "tentativeFinalizations");
        if (evidence == null) {
            require(workTrace.size() == 0
                            && documentStepTrace.size() == 0
                            && tentativeFinalizations.size() == 0,
                    id + " complete attempt omitted required "
                            + "implementation evidence");
            return;
        }
        require(evidence.complete(),
                id + " complete attempt lacks complete implementation evidence");
        equal(id + ".implementation.invocationIdentity",
                invocationIdentity, evidence.invocationIdentity());
        verifyWorkTrace(id, workTrace, evidence.workTrace());
        verifyDocumentSteps(
                id, documentStepTrace, evidence.documentStepTrace());
        verifyFinalizations(
                id, tentativeFinalizations,
                evidence.tentativeFinalizations());
    }

    private static void verifyGasTrace(
            String id,
            JsonNode expected,
            List<GasTraceEntry> actual) {
        equal(id + ".gasTrace.size", Integer.valueOf(expected.size()),
                Integer.valueOf(actual.size()));
        for (int index = 0; index < actual.size(); index++) {
            JsonNode item = expected.get(index);
            GasTraceEntry value = actual.get(index);
            String path = id + ".gasTrace[" + index + "]";
            equal(path + ".sequence",
                    Long.valueOf(requiredLong(item, "sequence")),
                    Long.valueOf(value.sequence()));
            equal(path + ".namespace", requiredText(item, "namespace"),
                    value.namespace().wireValue());
            equal(path + ".counter", requiredText(item, "counter"),
                    value.counter());
            equal(path + ".quantity",
                    Long.valueOf(requiredLong(item, "quantity")),
                    Long.valueOf(value.quantity()));
            equal(path + ".weight",
                    Long.valueOf(requiredLong(item, "weight")),
                    Long.valueOf(value.weight()));
            equal(path + ".subtotal",
                    Long.valueOf(requiredLong(item, "subtotal")),
                    Long.valueOf(value.subtotal()));
            equal(path + ".documentId",
                    nullableText(item, "documentId"),
                    documentId(value.documentId()));
            equal(path + ".scopePath",
                    nullableText(item, "scopePath"), value.scopePath());
            equal(path + ".activationGeneration",
                    nullableLong(item, "activationGeneration"),
                    value.activationGeneration());
            equal(path + ".componentGeneration",
                    nullableLong(item, "componentGeneration"),
                    value.componentGeneration());
            equal(path + ".contractKey",
                    nullableText(item, "contractKey"), value.contractKey());
            equal(path + ".logicalPath",
                    nullableText(item, "logicalPath"), value.logicalPath());
            equal(path + ".workOccurrenceId",
                    nullableText(item, "workOccurrenceId"),
                    value.workOccurrenceId());
            verifyDiagnosticReasonPresence(path, item, value.reason());
        }
    }

    /*
     * Contracts 1.0 defines reason as optional, observable diagnostic text.
     * Its wording is not semantic evidence and is removed from the
     * gasTraceIdentity projection. Presence remains part of the observable
     * record, so the combined report verifies that independently.
     */
    static void verifyDiagnosticReasonPresence(
            String path,
            JsonNode expected,
            String actualReason) {
        JsonNode expectedReason = expected == null
                ? null : expected.get("reason");
        boolean expectedPresent = expectedReason != null
                && !expectedReason.isNull();
        if (expectedPresent && !expectedReason.isTextual()) {
            throw new IllegalArgumentException(
                    path + ".reason must be text when present");
        }
        equal(path + ".reason.present",
                Boolean.valueOf(expectedPresent),
                Boolean.valueOf(actualReason != null));
    }

    private static void verifyWorkTrace(
            String id,
            JsonNode expected,
            List<ClosureWorkOccurrence> actual) {
        equal(id + ".workTrace.size", Integer.valueOf(expected.size()),
                Integer.valueOf(actual.size()));
        for (int index = 0; index < actual.size(); index++) {
            JsonNode item = expected.get(index);
            ClosureWorkOccurrence value = actual.get(index);
            String path = id + ".workTrace[" + index + "]";
            equal(path + ".ordinal",
                    Long.valueOf(requiredLong(item, "ordinal")),
                    Long.valueOf(value.ordinal()));
            equal(path + ".kind", requiredText(item, "kind"),
                    value.kind().name());
            equal(path + ".targetDocumentId",
                    requiredText(item, "targetDocumentId"),
                    value.targetDocumentId().value());
            equal(path + ".channelKey", requiredText(item, "channelKey"),
                    value.channelKey());
            equal(path + ".workIdentity",
                    requiredText(item, "workIdentity"),
                    value.workIdentity());
            equal(path + ".occurrenceOrdinal",
                    nullableLong(item, "occurrenceOrdinal"),
                    value.occurrenceOrdinal());
            equal(path + ".targetManagedScopeIdentity",
                    requiredText(item, "targetManagedScopeIdentity"),
                    value.targetManagedScopeIdentity());
            equal(path + ".sourceOccurrenceIdentity",
                    nullableText(item, "sourceOccurrenceIdentity"),
                    value.sourceOccurrenceIdentity());
        }
    }

    private static void verifyDocumentSteps(
            String id,
            JsonNode expected,
            List<DocumentStepEvidence> actual) {
        equal(id + ".documentStepTrace.size",
                Integer.valueOf(expected.size()),
                Integer.valueOf(actual.size()));
        for (int index = 0; index < actual.size(); index++) {
            JsonNode item = expected.get(index);
            DocumentStepEvidence value = actual.get(index);
            String path = id + ".documentStepTrace[" + index + "]";
            equal(path + ".stepOrdinal",
                    Long.valueOf(requiredLong(item, "stepOrdinal")),
                    Long.valueOf(value.stepOrdinal()));
            equal(path + ".workOrdinal",
                    Long.valueOf(requiredLong(item, "workOrdinal")),
                    Long.valueOf(value.workOrdinal()));
            equal(path + ".targetDocumentId",
                    requiredText(item, "targetDocumentId"),
                    value.targetDocumentId().value());
            equal(path + ".executionRootDocumentId",
                    requiredText(item, "executionRootDocumentId"),
                    value.executionRootDocumentId().value());
            equal(path + ".scopePath", requiredText(item, "scopePath"),
                    value.scopePath());
            equal(path + ".executionMode",
                    requiredText(item, "executionMode"),
                    value.executionMode());
            equal(path + ".ambientContainingDocumentIds",
                    textValues(requiredArray(
                            item, "ambientContainingDocumentIds")),
                    documentIds(value.ambientContainingDocumentIds()));
        }
    }

    private static void verifyFinalizations(
            String id,
            JsonNode expected,
            List<TentativeFinalization> actual) {
        equal(id + ".tentativeFinalizations.size",
                Integer.valueOf(expected.size()),
                Integer.valueOf(actual.size()));
        for (int index = 0; index < actual.size(); index++) {
            JsonNode item = expected.get(index);
            TentativeFinalization value = actual.get(index);
            String path = id + ".tentativeFinalizations[" + index + "]";
            equal(path + ".ordinal",
                    Long.valueOf(requiredLong(item, "ordinal")),
                    Long.valueOf(value.ordinal()));
            JsonNode boundary = requiredObject(item, "boundary");
            equal(path + ".boundary.kind",
                    requiredText(boundary, "kind"),
                    value.boundary().kind().name());
            equal(path + ".boundary.afterWorkOrdinal",
                    nullableLong(boundary, "afterWorkOrdinal"),
                    value.boundary().afterWorkOrdinal());
            equal(path + ".masterBlueId",
                    requiredText(item, "masterBlueId"),
                    value.masterBlueId());
            equal(path + ".canonicalBytes",
                    Long.valueOf(requiredLong(item, "canonicalBytes")),
                    Long.valueOf(value.canonicalBytes()));
            JsonNode members = requiredObject(item, "memberBlueIds");
            for (Map.Entry<DocumentId, String> member
                    : value.memberBlueIds().entrySet()) {
                equal(path + ".memberBlueIds." + member.getKey().value(),
                        requiredText(members, member.getKey().value()),
                        member.getValue());
            }
            equal(path + ".memberBlueIds.size",
                    Integer.valueOf(members.size()),
                    Integer.valueOf(value.memberBlueIds().size()));
        }
    }

    private static void verifyDiagnostic(
            String id,
            JsonNode expected,
            ProcessorDiagnostic actual) {
        if (expected == null || expected.isNull()) {
            require(actual == null, id + " unexpectedly produced a diagnostic");
        } else {
            require(actual != null, id + " omitted its diagnostic");
            equal(id + ".diagnostic", expected.asText(),
                    actual.category().name());
        }
    }

    private static void verifyRejectedChargeIdentity(
            String id,
            JsonNode expected,
            ClosureProcessResult result) {
        JsonNode rejected = expected.get("rejectedCharge");
        if (rejected == null || rejected.isNull()) {
            require(result.rejectedCharge() == null,
                    id + " unexpectedly produced rejectedCharge");
        } else {
            require(result.rejectedCharge() != null,
                    id + " omitted rejectedCharge");
            equal(id + ".rejectedChargeIdentity",
                    requiredText(rejected, "rejectedChargeIdentity"),
                    result.rejectedCharge().rejectedChargeIdentity());
        }
    }

    private static void verifyCommitCompanionIdentity(
            String id,
            JsonNode expected,
            ClosureProcessResult result) {
        JsonNode companion = expected.get("platformCommitCompanion");
        if (companion == null || companion.isNull()) {
            require(result.platformCommitCompanion() == null,
                    id + " unexpectedly produced a commit companion");
        } else {
            require(result.platformCommitCompanion() != null,
                    id + " omitted its commit companion");
            equal(id + ".platformCommitCompanion.companionIdentity",
                    requiredText(companion, "companionIdentity"),
                    result.platformCommitCompanion().companionIdentity());
        }
    }

    private static JsonNode requiredObject(JsonNode value, String field) {
        JsonNode child = value == null ? null : value.get(field);
        if (child == null || !child.isObject()) {
            throw new IllegalArgumentException(field + " must be an object");
        }
        return child;
    }

    private static JsonNode requiredArray(JsonNode value, String field) {
        JsonNode child = value == null ? null : value.get(field);
        if (child == null || !child.isArray()) {
            throw new IllegalArgumentException(field + " must be an array");
        }
        return child;
    }

    private static String requiredText(JsonNode value, String field) {
        JsonNode child = value == null ? null : value.get(field);
        if (child == null || !child.isTextual() || child.asText().isEmpty()) {
            throw new IllegalArgumentException(
                    field + " must be non-empty text");
        }
        return child.asText();
    }

    private static String nullableText(JsonNode value, String field) {
        JsonNode child = value == null ? null : value.get(field);
        return child == null || child.isNull() ? null : child.asText();
    }

    private static long requiredLong(JsonNode value, String field) {
        JsonNode child = value == null ? null : value.get(field);
        if (child == null || !child.isIntegralNumber()) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        return child.asLong();
    }

    private static Long nullableLong(JsonNode value, String field) {
        JsonNode child = value == null ? null : value.get(field);
        return child == null || child.isNull()
                ? null : Long.valueOf(child.asLong());
    }

    private static boolean requiredBoolean(JsonNode value, String field) {
        JsonNode child = value == null ? null : value.get(field);
        if (child == null || !child.isBoolean()) {
            throw new IllegalArgumentException(field + " must be Boolean");
        }
        return child.asBoolean();
    }

    private static List<String> textValues(JsonNode values) {
        ArrayList<String> result = new ArrayList<String>();
        for (JsonNode value : values) {
            require(value.isTextual(), "array item must be text");
            result.add(value.asText());
        }
        return result;
    }

    private static List<String> documentIds(List<DocumentId> values) {
        ArrayList<String> result = new ArrayList<String>();
        for (DocumentId value : values) {
            result.add(value.value());
        }
        return result;
    }

    private static String documentId(DocumentId value) {
        return value == null ? null : value.value();
    }

    private static void equal(String field, Object expected, Object actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(field + " expected=" + expected
                    + " actual=" + actual);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class Capture implements ClosureExecutionObserver {
        private ClosureImplementationEvidence evidence;

        @Override
        public void onExecutionEvidence(ClosureImplementationEvidence value) {
            require(evidence == null,
                    "one observer callback is allowed per invocation");
            evidence = value;
        }
    }
}
