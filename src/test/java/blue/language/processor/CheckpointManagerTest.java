package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.model.ChannelEventCheckpoint;
import blue.language.processor.model.MarkerContract;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.processor.util.ProcessorPointerConstants;
import blue.language.utils.BlueIdCalculator;
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

        Node eventNode = new Node().value("payload");
        String subjectBlueId = BlueIdCalculator.calculateBlueId(eventNode);
        String domainBlueId = BlueIdCalculator.calculateBlueId(
                new Node().name("test checkpoint domain"));
        CheckpointManager.CheckpointRecord record = manager.findCheckpoint(
                bundle, "testChannel", domainBlueId);

        manager.persist("/", bundle, record, subjectBlueId, eventNode);

        Node stored = ProcessorEngine.nodeAt(runtime.document(),
                ProcessorPointerConstants.relativeCheckpointEntry(
                        record.markerKey, record.channelKey));
        assertNotNull(stored);
        assertEquals(domainBlueId,
                stored.getAsText("/domain/blueId"));
        assertEquals(subjectBlueId,
                stored.getAsText("/subject/blueId"));
        assertEquals(67L, runtime.totalGas(),
                "checkpoint marker and domain-bound entry writes use the exact manifest schedule");
        assertEquals(subjectBlueId, record.lastEventSignature);
    }

    private static final class DummyMarker extends MarkerContract {
    }
}
