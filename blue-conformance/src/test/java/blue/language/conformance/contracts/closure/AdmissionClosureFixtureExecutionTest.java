package blue.language.conformance.contracts.closure;

import blue.language.codec.jackson.UncheckedObjectMapper;
import blue.language.conformance.contracts.ClosureFixtureRuntime;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.closure.BlueClosureContracts;
import blue.language.processor.closure.ClosureAttemptResult;
import blue.language.processor.closure.ClosureExecutionObserver;
import blue.language.processor.closure.ClosureImplementationEvidence;
import blue.language.processor.closure.ClosureInvocationInput;
import blue.language.processor.closure.DocumentStepEvidence;
import blue.language.processor.closure.GasTraceEntry;
import blue.language.processor.closure.RejectedCharge;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Executes released admission inputs without exposing expected output to core. */
final class AdmissionClosureFixtureExecutionTest {

    @Test
    void shouldGenuinelyExecuteReleasedStaticAndBoundedAdmissions() {
        List<String> ids = Arrays.asList(
                "c-clo-01-static-cycle-admission",
                "c-clo-01-cyclic-pure-reference-parity",
                "c-clo-01-cyclic-materialized-parity",
                "c-clo-07-self-cycle",
                "c-clo-16-limit-at-bound",
                "c-clo-18-locality-1000-unrelated",
                "c-clo-21-admission-zero-direct");
        for (String id : ids) {
            Execution execution;
            try {
                execution = execute(id);
            } catch (RuntimeException failure) {
                throw new AssertionError(id, failure);
            }
            assertEquals(ProcessorStatus.SUCCESS,
                    execution.attempt.processResult().status(),
                    diagnostic(id, execution.attempt));
            assertTrue(execution.attempt.processResult().commits(), id);
            assertNotNull(execution.evidence, id);
            assertTrue(execution.evidence.complete(), id);
            for (DocumentStepEvidence step
                    : execution.evidence.documentStepTrace()) {
                assertEquals(step.targetDocumentId(),
                        step.executionRootDocumentId(), id);
                assertEquals("/", step.scopePath(), id);
                assertEquals("ISOLATED_DOCUMENT",
                        step.executionMode(), id);
                assertTrue(step.ambientContainingDocumentIds().isEmpty(), id);
            }
        }
    }

    @Test
    void shouldFormCycleDuringOrdinaryInitializationPatches() {
        Execution execution = execute(
                "c-clo-08-cycle-during-initialization");
        assertEquals(ProcessorStatus.SUCCESS,
                execution.attempt.processResult().status(),
                diagnostic("C-CLO-08", execution.attempt));
        assertTrue(execution.attempt.processResult().commits());
        assertEquals(4, execution.evidence.workTrace().size());
        assertEquals(4, execution.evidence.documentStepTrace().size());
        assertEquals(2,
                execution.evidence.tentativeFinalizations().size());
        for (DocumentStepEvidence step
                : execution.evidence.documentStepTrace()) {
            assertEquals(step.targetDocumentId(),
                    step.executionRootDocumentId());
            assertTrue(step.ambientContainingDocumentIds().isEmpty());
        }
    }

    @Test
    void shouldRejectReleasedLimitAndGasProbesAtTheirTrueBoundaries() {
        Execution above = execute("c-clo-17-limit-above-bound");
        assertEquals(ProcessorStatus.PORTABLE_LIMIT_EXCEEDED,
                above.attempt.processResult().status(),
                diagnostic("C-CLO-17", above.attempt));
        assertEquals(0L, above.attempt.processResult().totalGas());
        assertTrue(above.evidence.workTrace().isEmpty());
        assertTrue(above.evidence.documentStepTrace().isEmpty());

        Execution invocation = execute(
                "c-clo-31-invocation-owned-gas-rejection");
        assertGasRejection(
                invocation,
                "processInvocation",
                RejectedCharge.Owner.Kind.INVOCATION);
        assertTrue(invocation.evidence.workTrace().isEmpty());

        Execution finalization = execute(
                "c-clo-32-finalization-owned-gas-rejection");
        assertGasRejection(
                finalization,
                "tentativeComponentFinalization",
                RejectedCharge.Owner.Kind.FINALIZATION);
        assertEquals(1, finalization.evidence.workTrace().size());
        assertEquals(1,
                finalization.evidence.documentStepTrace().size());
        assertTrue(finalization.evidence.tentativeFinalizations().isEmpty());
    }

    private static void assertGasRejection(
            Execution execution,
            String counter,
            RejectedCharge.Owner.Kind owner) {
        assertEquals(ProcessorStatus.GAS_LIMIT_EXCEEDED,
                execution.attempt.processResult().status());
        assertFalse(execution.attempt.processResult().commits());
        assertNotNull(execution.attempt.processResult().rejectedCharge());
        assertEquals(counter,
                execution.attempt.processResult().rejectedCharge().counter());
        assertEquals(owner,
                execution.attempt.processResult().rejectedCharge()
                        .owner().kind());
    }

    private static Execution execute(String id) {
        ClosureFixtureInventory.Entry entry =
                new ClosureFixtureInventory.Entry(
                        id,
                        "closure/" + id + ".yaml",
                        "admit-closure",
                        Collections.<String>emptyList(),
                        "bind-only-admission-gate",
                        0L);
        ClosureInvocationInput input = new ClosureFixtureParser()
                .parse(entry)
                .admit();
        Capture capture = new Capture();
        ClosureAttemptResult attempt;
        try (ClosureFixtureRuntime runtime =
                     ClosureFixtureRuntime.fromFixture(
                             ClosureFixtureInventory.readFixture(entry));
             BlueClosureContracts contracts = new BlueClosureContracts(
                     runtime.processor(), capture)) {
            attempt = contracts.admitClosure(input);
        }
        assertTrue(attempt.isComplete(), id);
        assertNotNull(capture.evidence, id);
        dumpActualGasTraceWhenRequested(id, attempt);
        assertExactGasTrace(
                id,
                ClosureFixtureInventory.readFixture(entry),
                attempt);
        return new Execution(attempt, capture.evidence);
    }

    private static void dumpActualGasTraceWhenRequested(
            String id,
            ClosureAttemptResult attempt) {
        String target = System.getenv("BLUE_ADMISSION_ACTUAL_GAS");
        if (target == null || target.isEmpty()) {
            return;
        }
        ArrayNode rows = JsonNodeFactory.instance.arrayNode();
        for (GasTraceEntry item : attempt.processResult().gasTrace()) {
            ObjectNode row = rows.addObject();
            row.put("sequence", item.sequence());
            row.put("namespace", item.namespace().wireValue());
            row.put("counter", item.counter());
            row.put("quantity", item.quantity());
            row.put("weight", item.weight());
            row.put("subtotal", item.subtotal());
            put(row, "documentId", item.documentId() == null
                    ? null : item.documentId().value());
            put(row, "scopePath", item.scopePath());
            put(row, "activationGeneration", item.activationGeneration());
            put(row, "componentGeneration", item.componentGeneration());
            put(row, "contractKey", item.contractKey());
            put(row, "logicalPath", item.logicalPath());
            put(row, "workOccurrenceId", item.workOccurrenceId());
            put(row, "reason", item.reason());
        }
        ObjectNode output = JsonNodeFactory.instance.objectNode();
        output.put("totalGas", attempt.processResult().totalGas());
        output.set("gasTrace", rows);
        try {
            Path directory = Paths.get(target);
            Files.createDirectories(directory);
            UncheckedObjectMapper.JSON_MAPPER
                    .writerWithDefaultPrettyPrinter()
                    .writeValue(directory.resolve(id + ".json").toFile(), output);
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "Unable to dump admission gas trace", failure);
        }
    }

    private static void put(
            ObjectNode row,
            String field,
            String value) {
        if (value != null) {
            row.put(field, value);
        }
    }

    private static void put(
            ObjectNode row,
            String field,
            Long value) {
        if (value != null) {
            row.put(field, value.longValue());
        }
    }

    private static void assertExactGasTrace(
            String id,
            JsonNode fixture,
            ClosureAttemptResult attempt) {
        JsonNode expected = ClosureFixtureInventory.requiredObject(
                fixture, "expected");
        JsonNode rows = ClosureFixtureInventory.requiredArray(
                expected, "gasTrace");
        List<GasTraceEntry> actual = attempt.processResult().gasTrace();
        for (int index = 0;
                index < Math.min(rows.size(), actual.size()); index++) {
            JsonNode row = rows.get(index);
            GasTraceEntry entry = actual.get(index);
            String prefix = id + " gasTrace[" + index + "] ";
            assertEquals(
                    ClosureFixtureInventory.requiredLong(row, "sequence"),
                    entry.sequence(), prefix + "sequence");
            assertEquals(
                    ClosureFixtureInventory.requiredText(row, "namespace"),
                    entry.namespace().wireValue(), prefix + "namespace");
            assertEquals(
                    ClosureFixtureInventory.requiredText(row, "counter"),
                    entry.counter(), prefix + "counter");
            assertEquals(
                    ClosureFixtureInventory.requiredLong(row, "quantity"),
                    entry.quantity(), prefix + "quantity");
            assertEquals(
                    ClosureFixtureInventory.requiredLong(row, "weight"),
                    entry.weight(), prefix + "weight");
            assertEquals(
                    ClosureFixtureInventory.requiredLong(row, "subtotal"),
                    entry.subtotal(), prefix + "subtotal");
            assertEquals(textOrNull(row, "documentId"),
                    entry.documentId() == null
                            ? null : entry.documentId().value(),
                    prefix + "documentId");
            assertEquals(textOrNull(row, "scopePath"),
                    entry.scopePath(), prefix + "scopePath");
            assertEquals(longOrNull(row, "activationGeneration"),
                    entry.activationGeneration(),
                    prefix + "activationGeneration");
            assertEquals(longOrNull(row, "componentGeneration"),
                    entry.componentGeneration(),
                    prefix + "componentGeneration");
            assertEquals(textOrNull(row, "contractKey"),
                    entry.contractKey(), prefix + "contractKey");
            assertEquals(textOrNull(row, "logicalPath"),
                    entry.logicalPath(), prefix + "logicalPath");
            assertEquals(textOrNull(row, "workOccurrenceId"),
                    entry.workOccurrenceId(),
                    prefix + "workOccurrenceId");
            assertEquals(row.hasNonNull("reason"),
                    entry.reason() != null,
                    prefix + "reason presence");
        }
        assertEquals(rows.size(), actual.size(), id + " gasTrace size");
        assertEquals(
                ClosureFixtureInventory.requiredLong(expected, "totalGas"),
                attempt.processResult().totalGas(),
                id + " totalGas");
    }

    private static String textOrNull(JsonNode value, String field) {
        JsonNode child = value.get(field);
        return child == null || child.isNull() ? null : child.asText();
    }

    private static Long longOrNull(JsonNode value, String field) {
        JsonNode child = value.get(field);
        return child == null || child.isNull()
                ? null : Long.valueOf(child.longValue());
    }

    private static String diagnostic(
            String id,
            ClosureAttemptResult attempt) {
        return id + ": "
                + (attempt.processResult().diagnostic() == null
                ? "no diagnostic"
                : attempt.processResult().diagnostic().message());
    }

    private static final class Capture implements ClosureExecutionObserver {
        private ClosureImplementationEvidence evidence;

        @Override
        public void onExecutionEvidence(
                ClosureImplementationEvidence value) {
            evidence = value;
        }
    }

    private static final class Execution {
        private final ClosureAttemptResult attempt;
        private final ClosureImplementationEvidence evidence;

        private Execution(
                ClosureAttemptResult attempt,
                ClosureImplementationEvidence evidence) {
            this.attempt = attempt;
            this.evidence = evidence;
        }
    }
}
