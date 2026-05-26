package blue.language.processor;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.processor.contracts.TestEventChannelProcessor;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.model.SetProperty;
import blue.language.processor.model.TestEvent;
import blue.language.processor.registry.RuntimeBlueIds;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentProcessorHandlerFailureTest {

    @Test
    void handlerRuntimeExceptionCausesScopedFatalTermination() {
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

        DocumentProcessingResult result = blue.processDocument(document, event("evt-handler-fail"));

        assertFalse(result.capabilityFailure());
        Node terminated = result.document().getAsNode("/contracts/terminated");
        assertNotNull(terminated);
        assertEquals("fatal", terminated.getProperties().get("cause").getValue());
        assertNull(nodeAt(result.document(), "/throwWithoutPatch"));
        assertTrue(result.totalGas() > 0L, "handler overhead and fatal termination gas should remain charged");
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

        DocumentProcessingResult result = blue.processDocument(document, event("evt-buffer-fail"));

        assertFalse(result.capabilityFailure());
        assertNull(nodeAt(result.document(), "/shouldNotApply"),
                "buffered effects from the failing handler must be discarded");
        Node terminated = result.document().getAsNode("/contracts/terminated");
        assertNotNull(terminated);
        assertEquals("fatal", terminated.getProperties().get("cause").getValue());
    }

    @Test
    void handlerFailurePreservesPriorHandlerEffects() {
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

        DocumentProcessingResult result = blue.processDocument(document, event("evt-prior-preserved"));

        assertEquals(new BigInteger("7"), result.document().get("/prior"));
        assertNull(nodeAt(result.document(), "/shouldNotApply"));
        Node terminated = result.document().getAsNode("/contracts/terminated");
        assertNotNull(terminated);
        assertEquals("fatal", terminated.getProperties().get("cause").getValue());
    }

    private Blue blueWithThrowingProcessor() {
        Blue blue = ProcessorTestSupport.blue();
        blue.registerContractProcessor(new TestEventChannelProcessor());
        blue.registerContractProcessor(new ConditionalThrowingSetPropertyProcessor());
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
