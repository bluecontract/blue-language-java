package blue.language.processor;

import blue.language.mapping.NodeToObjectConverter;
import blue.language.mapping.TypeClassResolver;
import blue.language.model.Node;
import blue.language.processor.model.ChannelEventCheckpoint;
import blue.language.processor.model.CheckpointEntry;
import blue.language.processor.model.Contract;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.processor.util.ProcessorContractConstants;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Exact selected checkpoint state must replace the compact effective view. */
final class ContractRefreshServiceCheckpointTest {
    @Test
    void emptySelectedEntriesClearEffectiveMarkerToUsableEmptyMap() {
        ChannelEventCheckpoint checkpoint = compactEffectiveCheckpoint();
        Node selected = checkpointNode(new Node().properties(
                new LinkedHashMap<>()));

        ContractRefreshService.restoreExactCheckpointEntries(
                checkpoint, selected);

        assertEquals(0, checkpoint.getEntries().size());
        assertNull(checkpoint.entry("stale"));
    }

    @Test
    void selectedEmptyEntriesDiscardStaleEffectiveEntries() {
        ChannelEventCheckpoint checkpoint = new ChannelEventCheckpoint()
                .putEntry("stale", "stale-domain", "stale-subject");

        ContractRefreshService.restoreExactCheckpointEntries(
                checkpoint,
                checkpointNode(new Node().properties(
                        new LinkedHashMap<>())));

        assertEquals(0, checkpoint.getEntries().size());
        assertNull(checkpoint.entry("stale"));
    }

    @Test
    void selectedEntriesRestoreExactDomainAndSubject() {
        Node domain = new Node().blueId("domain-blue-id");
        Node subject = new Node().properties(
                "timelineId", new Node().value("timeline"),
                "entry", new Node().value(7L));
        Node selectedEntry = new Node().properties(
                ProcessorContractConstants.KEY_DOMAIN, domain,
                ProcessorContractConstants.KEY_SUBJECT, subject);
        Node selectedEntries = new Node().properties(
                "source", selectedEntry);
        ChannelEventCheckpoint checkpoint = compactEffectiveCheckpoint();

        ContractRefreshService.restoreExactCheckpointEntries(
                checkpoint, checkpointNode(selectedEntries));

        CheckpointEntry restored = checkpoint.entry("source");
        assertEquals("domain-blue-id", restored.domainBlueId());
        assertEquals(subject.getProperties().get("timelineId").getValue(),
                restored.getSubject().getProperties()
                        .get("timelineId").getValue());
    }

    @Test
    void referenceOnlySelectedEntryRetainsMaterializedEffectiveFields() {
        ChannelEventCheckpoint checkpoint = new ChannelEventCheckpoint()
                .putEntry("source", "effective-domain", "effective-subject");
        Node selectedEntries = new Node().properties(
                "source", new Node().blueId("checkpoint-entry-reference"));

        ContractRefreshService.restoreExactCheckpointEntries(
                checkpoint, checkpointNode(selectedEntries));

        CheckpointEntry restored = checkpoint.entry("source");
        assertEquals("effective-domain", restored.domainBlueId());
        assertEquals("effective-subject", restored.getSubject().getBlueId());
    }

    private static ChannelEventCheckpoint compactEffectiveCheckpoint() {
        Node compact = new Node()
                .type(new Node().blueId(
                        RuntimeBlueIds.CHANNEL_EVENT_CHECKPOINT));
        NodeToObjectConverter converter = new NodeToObjectConverter(
                new TypeClassResolver("blue.language.processor.model"));
        return (ChannelEventCheckpoint) converter.convertWithType(
                compact, Contract.class, false);
    }

    private static Node checkpointNode(Node entries) {
        return new Node().properties(
                ProcessorContractConstants.KEY_ENTRIES, entries);
    }
}
