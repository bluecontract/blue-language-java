package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.model.ChannelEventCheckpoint;
import blue.language.processor.model.MarkerContract;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.processor.util.ProcessorPointerConstants;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validates checkpoint marker lifecycle operations without exercising the full engine.
 */
final class CheckpointManagerTest {

    @Test
    void ensureCheckpointCreatesMarkerWhenAbsent() {
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(new Node());
        CheckpointManager manager = new CheckpointManager(runtime, node -> null);
        ContractBundle bundle = ContractBundle.builder().build();

        manager.ensureCheckpointMarker("/", bundle);

        Node stored = ProcessorEngine.nodeAt(runtime.document(), ProcessorPointerConstants.RELATIVE_CHECKPOINT);
        assertNotNull(stored, "checkpoint marker should be written to document");
        assertTrue(bundle.marker(ProcessorContractConstants.KEY_CHECKPOINT) instanceof ChannelEventCheckpoint);
    }

    @Test
    void persistUpdatesCheckpointAndChargesGas() {
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(new Node());
        CheckpointManager manager = new CheckpointManager(runtime, node -> node != null ? "sig" : null);
        ContractBundle bundle = ContractBundle.builder().build();
        manager.ensureCheckpointMarker("/", bundle);

        CheckpointManager.CheckpointRecord record = manager.findCheckpoint(bundle, "testChannel");
        Node eventNode = new Node().value("payload");

        manager.persist("/", bundle, record, "nextSig", eventNode);

        Node stored = ProcessorEngine.nodeAt(runtime.document(),
                ProcessorPointerConstants.relativeCheckpointLastEvent(record.markerKey, record.channelKey));
        assertNotNull(stored);
        assertEquals("payload", stored.getValue());
        assertEquals(20L, runtime.totalGas(), "Checkpoint update should charge gas");
        assertEquals("nextSig", record.lastEventSignature);
    }

    private static final class DummyMarker extends MarkerContract {
    }
}
