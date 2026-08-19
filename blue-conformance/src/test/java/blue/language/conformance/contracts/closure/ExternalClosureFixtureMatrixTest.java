package blue.language.conformance.contracts.closure;

import blue.language.conformance.contracts.ClosureFixtureRuntime;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.closure.ClosureInvocationInput;
import blue.language.processor.closure.ClosureProcessResult;
import blue.language.processor.closure.ComponentKind;
import blue.language.processor.closure.ComponentSnapshot;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ExternalClosureFixtureMatrixTest {

    private static final List<String> CYCLIC_AND_REPRESENTATION_TARGETS =
            Arrays.asList(
                    "c-clo-02-acyclic-pure-reference-parity",
                    "c-clo-02-acyclic-materialized-parity",
                    "c-clo-05-direct-both-members",
                    "c-clo-19-multiple-public-roots-canonical",
                    "c-clo-19-public-event-boundary",
                    "c-clo-19-single-public-root",
                    "c-clo-20-late-outer-failure",
                    "c-clo-22-a10-attach-a5-needs-resources",
                    "c-clo-23-00-attach-a5-retry",
                    "c-clo-27-multiple-scc-one-closure",
                    "c-clo-28-containing-spine-identity-gas",
                    "c-clo-28-mixed-result-shape",
                    "c-clo-30-language-cyclic-oracles",
                    "c-clo-33-checkpoint-domain-retirement",
                    "c-clo-35-00-remove-and-create-successor",
                    "c-clo-35-01-readd-committed-successor");

    @Test
    void shouldAdmitPriorityExternalFixtureShapesWithoutExpectedProjection() {
        ClosureFixtureParser parser = new ClosureFixtureParser();
        for (String id : CYCLIC_AND_REPRESENTATION_TARGETS) {
            ClosureFixtureParser.ParsedFixture parsed = parser.parse(
                    currentExternalEntry(id));
            assertEquals(id, parsed.entry().id());
            assertNotNull(parsed.admit());
            for (ComponentSnapshot component : parsed.components()) {
                if (component.kind() == ComponentKind.CYCLIC) {
                    assertNotNull(component.masterBlueId());
                    assertNotNull(component.cyclicProofIdentity());
                    assertNotNull(component.completeCyclicProof());
                    assertEquals(
                            component.orderedMemberDocumentIds().size(),
                            component.completeCyclicProof()
                                    .declaredPlaceholderSet().size());
                }
            }
        }
    }

    @Test
    void shouldAdmitPureReferenceAndMaterializedParityInputs() {
        ClosureFixtureParser parser = new ClosureFixtureParser();
        ClosureInvocationInput pure = parser.parse(
                currentExternalEntry(
                        "c-clo-02-acyclic-pure-reference-parity"))
                .admit();
        ClosureInvocationInput materialized = parser.parse(
                currentExternalEntry(
                        "c-clo-02-acyclic-materialized-parity"))
                .admit();

        assertEquals(
                pure.invocationIdentity(),
                materialized.invocationIdentity());
        assertEquals(
                pure.snapshot().closureIdentity(),
                materialized.snapshot().closureIdentity());
        assertEquals(
                pure.snapshot().components().size(),
                materialized.snapshot().components().size());
    }

    @Test
    void shouldExecutePureReferenceAndMaterializedParityThroughRealFacade() {
        ClosureFixtureInventory.Entry pureEntry = currentExternalEntry(
                "c-clo-02-acyclic-pure-reference-parity");
        ClosureFixtureInventory.Entry materializedEntry = currentExternalEntry(
                "c-clo-02-acyclic-materialized-parity");
        ClosureConformanceHarness harness = new ClosureConformanceHarness();

        // Both attempts finish before either expected result becomes visible.
        ClosureConformanceHarness.ExternalResult pure =
                runExternalFixture(harness, pureEntry);
        ClosureConformanceHarness.ExternalResult materialized =
                runExternalFixture(harness, materializedEntry);

        assertExpectedCompleteStatus(pureEntry, pure);
        assertExpectedCompleteStatus(materializedEntry, materialized);
        ClosureProcessResult pureResult = pure.attempt().processResult();
        ClosureProcessResult materializedResult =
                materialized.attempt().processResult();
        assertEquals(pureResult.outputClosureIdentity(),
                materializedResult.outputClosureIdentity());
        assertEquals(pureResult.graphGeneration(),
                materializedResult.graphGeneration());
        assertEquals(pureResult.occurrenceBindingSetIdentity(),
                materializedResult.occurrenceBindingSetIdentity());
        assertEquals(pureResult.graphChangesIdentity(),
                materializedResult.graphChangesIdentity());
        assertEquals(pureResult.subscriptionDeltasIdentity(),
                materializedResult.subscriptionDeltasIdentity());
        assertEquals(pureResult.checkpointWritesIdentity(),
                materializedResult.checkpointWritesIdentity());
        assertEquals(pureResult.publicEventsIdentity(),
                materializedResult.publicEventsIdentity());
        assertEquals(pureResult.totalGas(), materializedResult.totalGas());
        assertEquals(pureResult.gasTraceIdentity(),
                materializedResult.gasTraceIdentity());
    }

    @Test
    void shouldAdmitEveryExternalProcessInputWithoutExpectedProjection() {
        ClosureFixtureParser parser = new ClosureFixtureParser();
        List<ClosureFixtureInventory.Entry> entries =
                currentExternalEntries();

        assertEquals(
                ClosureFixtureInventory.EXTERNAL_PROCESS_FIXTURE_COUNT,
                entries.size());
        for (ClosureFixtureInventory.Entry entry : entries) {
            assertNotNull(parser.parse(entry).admit(), entry.id());
        }
    }

    @Test
    void shouldTruthfullyClassifyEveryExternalProcessFixture(
            TestReporter reporter) {
        List<ClosureFixtureInventory.Entry> entries =
                currentExternalEntries();
        ClosureConformanceHarness harness = new ClosureConformanceHarness();
        Map<ClosureConformanceHarness.ExternalStatus, Integer> counts =
                new EnumMap<ClosureConformanceHarness.ExternalStatus, Integer>(
                        ClosureConformanceHarness.ExternalStatus.class);

        assertEquals(
                ClosureFixtureInventory.EXTERNAL_PROCESS_FIXTURE_COUNT,
                entries.size());
        for (ClosureFixtureInventory.Entry entry : entries) {
            ClosureConformanceHarness.ExternalResult result =
                    runExternalFixture(harness, entry);
            counts.put(
                    result.status(),
                    Integer.valueOf(counts.containsKey(result.status())
                            ? counts.get(result.status()).intValue() + 1
                            : 1));
            reporter.publishEntry(
                    entry.id(),
                    result.status().name() + ": " + result.surface());

            assertEquals(entry.id(), result.entry().id());
            assertNotNull(result.status());
            assertNotNull(result.surface());
            assertFalse(result.surface().isEmpty());
            assertFalse(result.implementationConformanceClaimed());
            requireConsistentClassification(result);
        }
        int classified = 0;
        for (Integer count : counts.values()) {
            classified += count.intValue();
        }
        reporter.publishEntry("summary", counts.toString());
        assertEquals(entries.size(), classified);
    }

    /**
     * Discovers current fixture paths from the manifest but deliberately
     * rebinds entries to the resource bytes now on disk.  This keeps the
     * runtime matrix usable while package publication metadata is being
     * regenerated; the strict released-package inventory remains a separate
     * gate and is not weakened here.
     */
    private static List<ClosureFixtureInventory.Entry>
            currentExternalEntries() {
        JsonNode manifest = readYaml(ClosureFixtureInventory.MANIFEST);
        ArrayList<ClosureFixtureInventory.Entry> result =
                new ArrayList<ClosureFixtureInventory.Entry>();
        for (JsonNode file : requiredArray(manifest, "files")) {
            if (!"closure-fixture".equals(file.path("role").asText())) {
                continue;
            }
            String path = requiredText(file, "path");
            JsonNode fixture = readYaml(
                    ClosureFixtureInventory.FIXTURE_ROOT + path);
            if (!"process-closure".equals(
                    requiredText(fixture, "operation"))) {
                continue;
            }
            JsonNode cause = requiredObject(
                    requiredObject(fixture, "input"), "cause");
            if (!"external".equals(requiredText(cause, "kind"))) {
                continue;
            }
            result.add(new ClosureFixtureInventory.Entry(
                    requiredText(fixture, "id"),
                    path,
                    "process-closure",
                    textList(requiredArray(fixture, "vectors")),
                    "current-unpublished-resource",
                    0L));
        }
        assertEquals(
                ClosureFixtureInventory.EXTERNAL_PROCESS_FIXTURE_COUNT,
                result.size(),
                "current external PROCESS_CLOSURE fixture count");
        return Collections.unmodifiableList(result);
    }

    private static ClosureFixtureInventory.Entry currentExternalEntry(
            String id) {
        for (ClosureFixtureInventory.Entry entry : currentExternalEntries()) {
            if (id.equals(entry.id())) {
                return entry;
            }
        }
        throw new AssertionError("Missing current external fixture: " + id);
    }

    private static JsonNode readYaml(String resource) {
        return ClosureFixtureInventory.readCurrentResource(resource);
    }

    private static JsonNode requiredObject(JsonNode value, String field) {
        JsonNode child = value.get(field);
        if (child == null || !child.isObject()) {
            throw new AssertionError(field + " must be an object");
        }
        return child;
    }

    private static JsonNode requiredArray(JsonNode value, String field) {
        JsonNode child = value.get(field);
        if (child == null || !child.isArray()) {
            throw new AssertionError(field + " must be an array");
        }
        return child;
    }

    private static String requiredText(JsonNode value, String field) {
        JsonNode child = value.get(field);
        if (child == null || !child.isTextual() || child.asText().isEmpty()) {
            throw new AssertionError(field + " must be non-empty Text");
        }
        return child.asText();
    }

    private static List<String> textList(JsonNode values) {
        ArrayList<String> result = new ArrayList<String>();
        for (JsonNode value : values) {
            if (!value.isTextual() || value.asText().isEmpty()) {
                throw new AssertionError(
                        "vector must be non-empty Text");
            }
            result.add(value.asText());
        }
        return result;
    }

    private static void requireConsistentClassification(
            ClosureConformanceHarness.ExternalResult result) {
        switch (result.status()) {
            case EXECUTED:
                assertNotNull(result.attempt());
                assertTrue(result.attempt().isComplete());
                assertFalse(result.attempt().processResult().status()
                        == ProcessorStatus.CAPABILITY_FAILURE);
                assertNull(result.failure());
                return;
            case NEEDS_RESOURCES:
                assertNotNull(result.attempt());
                assertFalse(result.attempt().isComplete());
                assertNull(result.failure());
                return;
            case FAIL_CLOSED:
                assertNotNull(result.failure());
                assertNull(result.attempt());
                return;
            case UNSUPPORTED:
                assertTrue(result.failure() != null
                        || (result.attempt() != null
                        && result.attempt().isComplete()
                        && result.attempt().processResult().status()
                        == ProcessorStatus.CAPABILITY_FAILURE));
                return;
            default:
                throw new AssertionError(
                        "Unclassified external fixture outcome: "
                                + result.status());
        }
    }

    private static ClosureConformanceHarness.ExternalResult runExternalFixture(
            ClosureConformanceHarness harness,
            ClosureFixtureInventory.Entry entry) {
        JsonNode fixture = ClosureFixtureInventory.readFixture(entry);
        ObjectNode runtimeEnvelope = JsonNodeFactory.instance.objectNode();
        runtimeEnvelope.set(
                "runtime",
                ClosureFixtureInventory.requiredObject(fixture, "runtime")
                        .deepCopy());
        try (ClosureFixtureRuntime runtime =
                     ClosureFixtureRuntime.fromFixture(runtimeEnvelope)) {
            return harness.runExternalFixture(
                    entry, runtime.processor());
        }
    }

    private static void assertExpectedCompleteStatus(
            ClosureFixtureInventory.Entry entry,
            ClosureConformanceHarness.ExternalResult actual) {
        JsonNode expected = requiredObject(
                ClosureFixtureInventory.readFixture(entry), "expected");
        assertEquals("Complete", requiredText(expected, "attemptOutcome"));
        assertEquals(ClosureConformanceHarness.ExternalStatus.EXECUTED,
                actual.status(), actual.surface());
        assertTrue(actual.attempt().isComplete());
        assertEquals(requiredText(expected, "status"),
                actual.attempt().processResult().status().wireValue());
    }
}
