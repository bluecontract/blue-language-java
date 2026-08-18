package blue.language.conformance.contracts.closure;

import blue.language.processor.closure.ComponentSnapshot;
import blue.language.processor.closure.DocumentId;
import blue.language.processor.closure.ManagedDocumentSnapshot;
import blue.language.processor.closure.SccPartitioner;
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

        assertEquals(67, inventory.size());
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
                "df52e73e9bc33b6e50df3a58e487970781c911a46a8e0861bd327e652d2b3ea4",
                separateDocuments.sha256());
        assertEquals(94701L, separateDocuments.bytes());
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
}
