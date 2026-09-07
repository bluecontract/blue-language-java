package blue.language.processor.closure;

import blue.language.model.Node;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static blue.language.processor.closure.CompositionCampaignFixture.*;
import static org.junit.jupiter.api.Assertions.*;

final class ProspectiveBirthAuthenticationTest {
    @Test
    void shouldRejectInventedBirthThatNoAttemptDemanded() {
        // given
        try (CompositionCampaignFixture fixture = new CompositionCampaignFixture()) {
            DocumentId rootId = new DocumentId("root");
            ClosureInvocationInput input = fixture.admission(snapshot(
                    Collections.singletonMap(rootId, document("parent")),
                    Collections.emptyList(), rootId), 100_000L);
            ClosureAttemptResult attempt = fixture.admit(input);
            assertEquals(ClosureAttemptResult.Kind.COMPLETE, attempt.kind());
            assertTrue(attempt.processResult().commits());
            assertTrue(attempt.resourceDemands().isEmpty());

            Node child = document("invented");
            ManagedOccurrenceEvidenceDemand invented = ManagedOccurrenceEvidenceDemand.derived(
                    input.cause().causeIdentity(), input.snapshot().closureIdentity(),
                    input.snapshot().graphGeneration(), rootId, "/children/new",
                    id(process("collectionPaths", "/children")), id(child), 0L);

            // when / then
            assertThrows(IllegalArgumentException.class, () -> {
                DocumentId inventedId = new DocumentId("invented");
                ClosureInvocationInput expanded = ClosureEvidenceFactory.withProspectiveBirths(input,
                        Collections.singletonList(new ManagedDocumentBirth(invented, inventedId, child)));
                ClosureProcessResult injected = fixture.admit(expanded).processResult();
                assertTrue(injected.commits(), diagnostic(injected));
                assertTrue(injected.resultingDocuments().stream().anyMatch(
                        result -> result.documentId().equals(inventedId)));
            });
            assertEquals(1, input.snapshot().managedDocuments().size());
        }
    }

    @Test
    void shouldRejectPublicCopiesEvenAfterTheOriginalDemandWasEmitted() {
        // given
        try (CompositionCampaignFixture fixture = new CompositionCampaignFixture()) {
            Node child = document("child");
            ClosureInvocationInput input = birthInput(fixture, child, 100_000L);
            ClosureAttemptResult suspended = fixture.admit(input);
            assertEquals(ClosureAttemptResult.Kind.NEEDS_RESOURCES, suspended.kind());
            ManagedOccurrenceEvidenceDemand emitted =
                    (ManagedOccurrenceEvidenceDemand) suspended.resourceDemands().get(0);
            ManagedOccurrenceEvidenceDemand constructed = new ManagedOccurrenceEvidenceDemand(
                    emitted.demandIdentity(), emitted.logicalCauseIdentity(),
                    emitted.inputClosureIdentity(), emitted.inputGraphGeneration(),
                    emitted.sourceDocumentId(), emitted.sourcePath(),
                    emitted.processEmbeddedDeclarationIdentity(), emitted.suppliedValueBlueId(),
                    emitted.demandOrdinal(), child);
            ManagedOccurrenceEvidenceDemand derived = ManagedOccurrenceEvidenceDemand.derived(
                    emitted.logicalCauseIdentity(), emitted.inputClosureIdentity(),
                    emitted.inputGraphGeneration(), emitted.sourceDocumentId(), emitted.sourcePath(),
                    emitted.processEmbeddedDeclarationIdentity(), emitted.suppliedValueBlueId(),
                    emitted.demandOrdinal(), child);

            // when / then: a caller-created attempt cannot confer runtime authority.
            for (ManagedOccurrenceEvidenceDemand copy : Arrays.asList(constructed, derived)) {
                ClosureAttemptResult fabricated = ClosureAttemptResult.needsResources(
                        Collections.singletonList(copy));
                ManagedOccurrenceEvidenceDemand forged =
                        (ManagedOccurrenceEvidenceDemand) fabricated.resourceDemands().get(0);
                assertEquals(emitted.demandIdentity(), forged.demandIdentity());
                assertThrows(IllegalArgumentException.class,
                        () -> ClosureEvidenceFactory.withProspectiveBirths(input,
                                Collections.singletonList(new ManagedDocumentBirth(
                                        forged, new DocumentId("child"), child))));
            }
            ClosureInvocationInput expanded = ClosureEvidenceFactory.withProspectiveBirths(input,
                    Collections.singletonList(new ManagedDocumentBirth(
                            emitted, new DocumentId("child"), child)));
            assertTrue(fixture.admit(expanded).processResult().commits());
        }
    }

    @Test
    void shouldBindEmittedDemandToTheCompleteInvocationIncludingGasPolicy() {
        // given
        try (CompositionCampaignFixture fixture = new CompositionCampaignFixture()) {
            Node child = document("child");
            ClosureInvocationInput input = birthInput(fixture, child, 100_000L);
            ManagedOccurrenceEvidenceDemand emitted = (ManagedOccurrenceEvidenceDemand)
                    fixture.admit(input).resourceDemands().get(0);
            ClosureInvocationInput changedPolicy = fixture.admission(input.snapshot(), 200_000L);
            assertEquals(input.cause().causeIdentity(), changedPolicy.cause().causeIdentity());
            assertEquals(input.snapshot().closureIdentity(), changedPolicy.snapshot().closureIdentity());
            assertNotEquals(input.invocationIdentity(), changedPolicy.invocationIdentity());

            // when / then
            ManagedDocumentBirth birth = new ManagedDocumentBirth(emitted, new DocumentId("child"), child);
            assertThrows(IllegalArgumentException.class,
                    () -> ClosureEvidenceFactory.withProspectiveBirths(changedPolicy,
                            Collections.singletonList(birth)));
            ClosureInvocationInput sameInput = fixture.admission(input.snapshot(), 100_000L);
            assertNotSame(input, sameInput);
            assertEquals(input.invocationIdentity(), sameInput.invocationIdentity());
            ClosureInvocationInput expanded = ClosureEvidenceFactory.withProspectiveBirths(sameInput,
                    Collections.singletonList(birth));
            assertTrue(fixture.admit(expanded).processResult().commits());
        }
    }

    private static ClosureInvocationInput birthInput(
            CompositionCampaignFixture fixture, Node child, long gas) {
        Node root = document("parent")
                .properties("children", new Node().properties(Collections.emptyMap()))
                .properties("install", new Node().properties("new", child));
        root.getContracts().properties("embedded", process("collectionPaths", "/children"));
        DocumentId rootId = new DocumentId("root");
        return fixture.admission(snapshot(Collections.singletonMap(rootId, root),
                Collections.emptyList(), rootId), gas);
    }
}
