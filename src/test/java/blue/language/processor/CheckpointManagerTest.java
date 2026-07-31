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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validates checkpoint marker lifecycle operations without exercising the full engine.
 */
final class CheckpointManagerTest {

    @Test
    void shouldCreateCheckpointMarkerWhenAbsent() {
        // given
        DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(new Node());
        CheckpointManager manager = new CheckpointManager(runtime, node -> null);
        ContractBundle bundle = ContractBundle.builder().build();

        // when
        manager.ensureCheckpointMarker("/", bundle);
        Node stored = ProcessorEngine.nodeAt(
                runtime.document(),
                ProcessorPointerConstants.RELATIVE_CHECKPOINT);

        // then
        assertNotNull(stored, "checkpoint marker should be written to document");
        assertTrue(bundle.marker(ProcessorContractConstants.KEY_CHECKPOINT) instanceof ChannelEventCheckpoint);
    }

    @Test
    void shouldUpdateCheckpointAndChargeGasWhenPersisting() {
        // given
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

        // when
        manager.persist("/", bundle, record, subjectBlueId, eventNode);
        Node stored = ProcessorEngine.nodeAt(runtime.document(),
                ProcessorPointerConstants.relativeCheckpointEntry(
                        record.markerKey, record.channelKey));

        // then
        assertNotNull(stored);
        assertEquals(domainBlueId,
                stored.getAsText("/domain/blueId"));
        assertEquals("payload",
                stored.getAsText("/subject"));
        assertEquals("payload",
                ((ChannelEventCheckpoint) bundle.marker(
                                ProcessorContractConstants
                                        .KEY_CHECKPOINT))
                        .entry("testChannel")
                        .getSubject()
                        .getValue());
        assertEquals(71L, runtime.totalGas(),
                "inline exact checkpoint subjects pay their direct identity work");
        assertEquals(subjectBlueId, record.lastEventSignature);
    }

    @Test
    void shouldReplaceAnExistingRawSourceCheckpointWhenDomainChanges() {
        // given
        Node previousSubject = new Node().value("previous");
        String previousSubjectBlueId =
                BlueIdCalculator.calculateBlueId(previousSubject);
        String previousDomainBlueId =
                BlueIdCalculator.calculateBlueId(
                        new Node().name("previous domain"));
        Node currentSubject = new Node().value("current");
        String currentSubjectBlueId =
                BlueIdCalculator.calculateBlueId(currentSubject);
        String currentDomainBlueId =
                BlueIdCalculator.calculateBlueId(
                        new Node().name("current domain"));
        ChannelEventCheckpoint checkpoint =
                new ChannelEventCheckpoint()
                        .putEntry(
                                "source",
                                previousDomainBlueId,
                                previousSubjectBlueId);
        checkpoint.entry("source").subject(previousSubject);
        ContractBundle bundle = ContractBundle.builder()
                .addMarker(
                        ProcessorContractConstants.KEY_CHECKPOINT,
                        checkpoint)
                .build();
        Node entryNode = new Node()
                .properties(
                        ProcessorContractConstants.KEY_DOMAIN,
                        new Node().blueId(previousDomainBlueId))
                .properties(
                        ProcessorContractConstants.KEY_SUBJECT,
                        previousSubject);
        Node markerNode = new Node()
                .type(new Node().blueId(
                        blue.language.processor.registry.RuntimeBlueIds
                                .CHANNEL_EVENT_CHECKPOINT))
                .properties(
                        ProcessorContractConstants.KEY_ENTRIES,
                        new Node().properties(
                                "source",
                                entryNode));
        DocumentProcessingRuntime runtime =
                new DocumentProcessingRuntime(
                        new Node().properties(
                                ProcessorContractConstants.KEY_CONTRACTS,
                                new Node().properties(
                                        ProcessorContractConstants
                                                .KEY_CHECKPOINT,
                                        markerNode)));
        CheckpointManager manager =
                new CheckpointManager(runtime);
        CheckpointManager.CheckpointRecord record =
                manager.findCheckpoint(
                        bundle,
                        "source",
                        currentDomainBlueId);

        // when
        manager.persist(
                "/",
                bundle,
                record,
                currentSubjectBlueId,
                currentSubject);
        Node stored = runtime.document().getAsNode(
                "/contracts/checkpoint/entries/source");

        // then
        assertEquals(
                currentDomainBlueId,
                stored.getAsText("/domain/blueId"));
        assertEquals(
                "current",
                stored.getAsText("/subject"));
        assertEquals(
                currentDomainBlueId,
                checkpoint.entry("source").domainBlueId());
        assertEquals(
                currentSubjectBlueId,
                checkpoint.entry("source").subjectBlueId());
    }

    private static final class DummyMarker extends MarkerContract {
    }
}
