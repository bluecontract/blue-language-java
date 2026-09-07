package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.snapshot.FrozenNode;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.runtime.BlueLanguage;
import blue.language.runtime.LanguageProcessing;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ProcessorLifecycleServicesTest {

    @Test
    void shouldCreateInitiatedEventFromExactScopeDocument() {
        // given
        Node scopeDocument = new Node().name("scope");
        FrozenNode exactDocument = FrozenNode.fromResolvedNode(scopeDocument);

        // when
        Node event = LifecycleEventFactory.initiated(exactDocument);

        // then
        assertEquals(
                RuntimeBlueIds.DOCUMENT_PROCESSING_INITIATED,
                event.getType().getBlueId());
        Node captured = event.getProperties().get(
                ProcessorContractConstants.KEY_DOCUMENT);
        assertNotNull(captured);
        assertEquals(exactDocument.blueId(), captured.getBlueId());
    }

    @Test
    void shouldValidateTerminationMarkerIntoClosedProjection() {
        // given
        Node marker = LifecycleEventFactory.terminationMarker(
                "completed", "accepted");

        // when
        ProcessorMarkerStore.TerminationMarker projection =
                ProcessorMarkerStore.validateTerminationMarker(
                        marker, "/contracts/terminated");

        // then
        assertNotNull(projection);
        assertEquals("completed", projection.cause);
        assertEquals("accepted", projection.reason);
    }

    @Test
    void shouldCollapseInlineInitializationDocumentToExactReference() {
        // given
        Node exactDocument = new Node().name("initial scope");
        String expectedBlueId = DirectBlueIdCalculator.calculateBlueId(exactDocument);
        Node marker = new Node()
                .type(new Node().blueId(
                        RuntimeBlueIds.PROCESSING_INITIALIZED_MARKER))
                .properties(
                        ProcessorContractConstants.KEY_DOCUMENT,
                        exactDocument.clone());
        Node root = new Node().contracts(
                new Node().properties(
                        ProcessorContractConstants.KEY_INITIALIZED,
                        marker));

        // when
        try (BlueLanguage language = BlueLanguage.builder().build();
             LanguageProcessing.Scope scope =
                     language.processing().openScope()) {
            ProcessorMarkerStore.collapseInitializationDocuments(
                    root,
                    new LanguageProcessingSnapshotManager(scope));
        }

        // then
        Node collapsed = root.getContracts().getProperties()
                .get(ProcessorContractConstants.KEY_INITIALIZED)
                .getProperties()
                .get(ProcessorContractConstants.KEY_DOCUMENT);
        assertTrue(collapsed.isReferenceOnly());
        assertEquals(expectedBlueId, collapsed.getBlueId());
    }
}
