package blue.language.conformance.contracts.closure;

import blue.language.conformance.contracts.ClosureFixtureRuntime;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.closure.BlueClosureContracts;
import blue.language.processor.closure.ClosureAttemptResult;
import blue.language.processor.closure.ClosureExecutionObserver;
import blue.language.processor.closure.ClosureImplementationEvidence;
import blue.language.processor.closure.ClosureInvocationInput;
import blue.language.processor.closure.ComponentSnapshot;
import blue.language.processor.closure.DocumentId;
import blue.language.processor.closure.DocumentStepEvidence;
import blue.language.processor.closure.ManagedDocumentSnapshot;
import blue.language.processor.closure.SccPartitioner;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ClosureConformanceHarnessTest {

    @Test
    void shouldVerifyAndInventoryExactFinalClosureFixtures() {
        ClosureConformanceSuite.InventoryReport report =
                ClosureConformanceSuite.inventory();
        List<ClosureFixtureInventory.Entry> inventory = report.fixtures();

        assertEquals(93, inventory.size());
        assertTrue(report.closureInventoryExact());
        assertFalse(report.implementationConformanceClaimed());
        assertEquals(
                ClosureFixtureInventory.PACKAGE_IDENTITY,
                report.fixturePackageIdentity());
        ClosureFixtureInventory.Entry separateDocuments =
                ClosureFixtureInventory.requireById(
                        ClosureFixtureInventory.C_CLO_34);
        assertEquals(
                "closure/c-clo-34-separate-document-steps.yaml",
                separateDocuments.path());
        assertEquals("process-closure", separateDocuments.operation());
        assertEquals(
                Collections.singletonList("C-CLO-34"),
                separateDocuments.vectors());
        assertEquals(
                "f5f37b38a057fa2f16e579a2a859e6b6c995c3b79d88e7b709434201cbd89b9e",
                separateDocuments.sha256());
        assertEquals(120198L, separateDocuments.bytes());
    }

    @Test
    void shouldParseCclo34IntoCoreTypesAndPreserveCondensationOrder() {
        ClosureFixtureParser.ParsedFixture parsed =
                new ClosureFixtureParser().parse(
                        ClosureFixtureInventory.requireById(
                                ClosureFixtureInventory.C_CLO_34));

        assertEquals(
                Arrays.asList(new DocumentId("a"), new DocumentId("b")),
                documentIds(parsed.documents()));
        assertEquals(
                Arrays.asList(
                        Collections.singletonList(new DocumentId("a")),
                        Collections.singletonList(new DocumentId("b"))),
                componentMembers(parsed.components()));
        assertEquals(
                componentMembers(parsed.components()),
                new SccPartitioner().partition(parsed.graph()));
        assertTrue(parsed.graph().hasEdge(
                new DocumentId("b"), new DocumentId("a")));
        assertFalse(parsed.graph().hasEdge(
                new DocumentId("a"), new DocumentId("b")));
    }

    @Test
    void shouldNeverClaimConformanceWithoutCoreExecution() {
        ClosureConformanceHarness.Result result =
                new ClosureConformanceHarness().runCclo34(null, null);

        assertFalse(result.implementationConformanceClaimed());
        assertFalse(result.status()
                == ClosureConformanceHarness.Status.INVARIANT_VERIFIED);
        assertNotNull(result.failure());
    }

    @Test
    void shouldExecuteCclo34ThroughTheProductionClosureFacade() {
        ClosureFixtureInventory.Entry entry =
                ClosureFixtureInventory.requireById(
                        ClosureFixtureInventory.C_CLO_34);
        JsonNode fixture = ClosureFixtureInventory.readFixture(entry);
        Capture capture = new Capture();

        try (ClosureFixtureRuntime runtime =
                     ClosureFixtureRuntime.fromFixture(fixture);
             BlueClosureContracts contracts = new BlueClosureContracts(
                     runtime.processor(), capture)) {
            ClosureConformanceHarness.Result result =
                    new ClosureConformanceHarness().runCclo34(
                            contracts,
                            new ClosureConformanceHarness.TraceAdapter() {
                                @Override
                                public List<DocumentStepEvidence>
                                        documentSteps(
                                        ClosureInvocationInput input,
                                        ClosureAttemptResult attempt) {
                                    assertNotNull(capture.evidence);
                                    return capture.evidence
                                            .documentStepTrace();
                                }
                            });

            if (result.failure() != null) {
                throw new AssertionError(
                        "C-CLO-34 production execution failed",
                        result.failure());
            }
            assertEquals(
                    ClosureConformanceHarness.Status.INVARIANT_VERIFIED,
                    result.status());
            assertEquals(ProcessorStatus.SUCCESS,
                    result.attempt().processResult().status());
            assertEquals(4, result.documentSteps().size());
            List<DocumentId> expectedTargets = Arrays.asList(
                    new DocumentId("a"),
                    new DocumentId("a"),
                    new DocumentId("b"),
                    new DocumentId("a"));
            for (int index = 0;
                    index < result.documentSteps().size();
                    index++) {
                DocumentStepEvidence step = result.documentSteps()
                        .get(index);
                assertEquals(expectedTargets.get(index),
                        step.targetDocumentId());
                assertEquals(step.targetDocumentId(),
                        step.executionRootDocumentId());
                assertEquals(Collections.emptyList(),
                        step.ambientContainingDocumentIds());
            }
            assertFalse(result.implementationConformanceClaimed());
        }
    }

    @Test
    void shouldRejectMissingDocumentStepEvidenceInsteadOfUsingExpected() {
        ClosureFixtureInventory.Entry entry =
                ClosureFixtureInventory.requireById(
                        ClosureFixtureInventory.C_CLO_34);

        assertThrows(
                AssertionError.class,
                () -> ClosureConformanceHarness.verifySeparateDocumentTrace(
                        ClosureFixtureInventory.readFixture(entry),
                        Collections.emptyList()));
    }

    private static List<DocumentId> documentIds(
            List<ManagedDocumentSnapshot> documents) {
        java.util.ArrayList<DocumentId> result =
                new java.util.ArrayList<DocumentId>();
        for (ManagedDocumentSnapshot document : documents) {
            result.add(document.documentId());
        }
        return result;
    }

    private static List<List<DocumentId>> componentMembers(
            List<ComponentSnapshot> components) {
        java.util.ArrayList<List<DocumentId>> result =
                new java.util.ArrayList<List<DocumentId>>();
        for (ComponentSnapshot component : components) {
            result.add(component.orderedMemberDocumentIds());
        }
        return result;
    }

    private static final class Capture
            implements ClosureExecutionObserver {
        private ClosureImplementationEvidence evidence;

        @Override
        public void onExecutionEvidence(
                ClosureImplementationEvidence value) {
            this.evidence = value;
        }
    }
}
