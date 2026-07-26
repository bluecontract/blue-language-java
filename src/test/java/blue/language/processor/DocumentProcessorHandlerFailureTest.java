package blue.language.processor;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.model.SetProperty;
import blue.language.processor.model.TestEvent;
import blue.language.processor.registry.RuntimeBlueIds;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentProcessorHandlerFailureTest {

    @Test
    void handlerRuntimeExceptionRollsBackWithoutTerminationMarker() {
        Blue blue = blueWithThrowingProcessor();
        Node document = blue.yamlToNode("name: Handler Failure\n" +
                "contracts:\n" +
                "  initialized:\n" +
                "    type:\n" +
                "      blueId: " + RuntimeBlueIds.PROCESSING_INITIALIZED_MARKER + "\n" +
                "    documentId: existing\n" +
                "  events:\n" +
                "    type:\n" +
                "      blueId: BHRKnD9toWwiU34GJvqLJ3Rtiv6W7Mmubai7CdrA1i3L\n" +
                "  fail:\n" +
                "    channel: events\n" +
                "    type:\n" +
                "      blueId: 8Vii45Ph3HBUX2ZMEarxXXUBDPrXemrvqJergPr3BNts\n" +
                "    propertyKey: /throwWithoutPatch\n" +
                "    propertyValue: 1\n");

        String input = document.toString();
        DocumentProcessingResult result =
                blue.processDocument(
                        document, event("evt-handler-fail"));

        assertFalse(result.capabilityFailure());
        assertEquals(ProcessorStatus.RUNTIME_FATAL,
                result.status());
        assertFalse(result.commits());
        assertEquals(input, result.document().toString());
        assertFalse(result.document().getContracts()
                .getProperties().containsKey("terminated"));
        assertNull(nodeAt(result.document(), "/throwWithoutPatch"));
        assertTrue(result.events().isEmpty());
        assertTrue(result.totalGas() > 0L,
                "admitted work remains charged on deterministic failure");
    }

    @Test
    void handlerThrowAfterBufferingPatchDoesNotApplyBufferedPatch() {
        Blue blue = blueWithThrowingProcessor();
        Node document = blue.yamlToNode("name: Handler Buffer Failure\n" +
                "contracts:\n" +
                "  initialized:\n" +
                "    type:\n" +
                "      blueId: " + RuntimeBlueIds.PROCESSING_INITIALIZED_MARKER + "\n" +
                "    documentId: existing\n" +
                "  events:\n" +
                "    type:\n" +
                "      blueId: BHRKnD9toWwiU34GJvqLJ3Rtiv6W7Mmubai7CdrA1i3L\n" +
                "  fail:\n" +
                "    channel: events\n" +
                "    type:\n" +
                "      blueId: 8Vii45Ph3HBUX2ZMEarxXXUBDPrXemrvqJergPr3BNts\n" +
                "    propertyKey: /shouldNotApply\n" +
                "    propertyValue: 2\n");

        String input = document.toString();
        DocumentProcessingResult result =
                blue.processDocument(
                        document, event("evt-buffer-fail"));

        assertFalse(result.capabilityFailure());
        assertEquals(ProcessorStatus.RUNTIME_FATAL,
                result.status());
        assertFalse(result.commits());
        assertEquals(input, result.document().toString());
        assertNull(nodeAt(result.document(), "/shouldNotApply"),
                "buffered effects from the failing handler must be discarded");
        assertFalse(result.document().getContracts()
                .getProperties().containsKey("terminated"));
        assertTrue(result.events().isEmpty());
    }

    @Test
    void handlerFailureRollsBackPriorHandlerEffects() {
        Blue blue = blueWithThrowingProcessor();
        Node document = blue.yamlToNode("name: Handler Prior Effects\n" +
                "contracts:\n" +
                "  initialized:\n" +
                "    type:\n" +
                "      blueId: " + RuntimeBlueIds.PROCESSING_INITIALIZED_MARKER + "\n" +
                "    documentId: existing\n" +
                "  events:\n" +
                "    type:\n" +
                "      blueId: BHRKnD9toWwiU34GJvqLJ3Rtiv6W7Mmubai7CdrA1i3L\n" +
                "  first:\n" +
                "    order: 0\n" +
                "    channel: events\n" +
                "    type:\n" +
                "      blueId: 8Vii45Ph3HBUX2ZMEarxXXUBDPrXemrvqJergPr3BNts\n" +
                "    propertyKey: /prior\n" +
                "    propertyValue: 7\n" +
                "  fail:\n" +
                "    order: 1\n" +
                "    channel: events\n" +
                "    type:\n" +
                "      blueId: 8Vii45Ph3HBUX2ZMEarxXXUBDPrXemrvqJergPr3BNts\n" +
                "    propertyKey: /shouldNotApply\n" +
                "    propertyValue: 9\n");

        String input = document.toString();
        DocumentProcessingResult result =
                blue.processDocument(
                        document,
                        event("evt-prior-preserved"));

        assertEquals(ProcessorStatus.RUNTIME_FATAL,
                result.status());
        assertFalse(result.commits());
        assertEquals(input, result.document().toString());
        assertNull(nodeAt(result.document(), "/prior"));
        assertNull(nodeAt(result.document(), "/shouldNotApply"));
        assertFalse(result.document().getContracts()
                .getProperties().containsKey("terminated"));
        assertTrue(result.events().isEmpty());
    }

    private Blue blueWithThrowingProcessor() {
        Blue blue = ProcessorTestSupport.blue();
        blue.registerContractProcessor(
                DocumentProcessorExactFeederSupport
                        .testEventChannelProcessor());
        blue.registerContractProcessor(new ConditionalThrowingSetPropertyProcessor());
        DocumentProcessorExactFeederSupport.install(blue);
        return blue;
    }

    private Node event(String id) {
        return new TestEvent().eventId(id).toNode();
    }

    private Node nodeAt(Node document, String pointer) {
        try {
            return document.getNode(pointer);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static final class ConditionalThrowingSetPropertyProcessor implements HandlerProcessor<SetProperty> {
        @Override
        public Class<SetProperty> contractType() {
            return SetProperty.class;
        }

        @Override
        public void execute(SetProperty contract, ProcessorExecutionContext context) {
            String propertyKey = contract.getPropertyKey() != null ? contract.getPropertyKey() : "/x";
            if ("/throwWithoutPatch".equals(propertyKey)) {
                throw new IllegalArgumentException("handler failed before buffering effects");
            }
            JsonPatch patch = JsonPatch.add(context.resolvePointer(propertyKey), new Node().value(contract.getPropertyValue()));
            context.applyPatch(patch);
            if ("/shouldNotApply".equals(propertyKey)) {
                throw new IllegalArgumentException("handler failed after buffering effects");
            }
        }
    }
}
