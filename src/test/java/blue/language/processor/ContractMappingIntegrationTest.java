package blue.language.processor;

import blue.language.Blue;
import blue.language.mapping.NodeToObjectConverter;
import blue.language.model.Node;
import blue.language.processor.model.Contract;
import blue.language.processor.model.ChannelEventCheckpoint;
import blue.language.processor.model.DocumentUpdateChannel;
import blue.language.processor.model.EmbeddedNodeChannel;
import blue.language.processor.model.InitializationMarker;
import blue.language.processor.model.LifecycleChannel;
import blue.language.processor.model.ProcessEmbedded;
import blue.language.processor.model.ProcessingFailureMarker;
import blue.language.processor.model.SetProperty;
import blue.language.processor.model.TriggeredEventChannel;
import blue.language.utils.TypeClassResolver;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ContractMappingIntegrationTest {

    @Test
    void loadsAllContractsFromBlueYaml() throws Exception {
        String yaml = Files.readString(Path.of("src/test/resources/processor/contracts/all-contracts.blue"));

        Blue blue = new Blue();
        Node document = blue.yamlToNode(yaml);
        assertNotNull(document);
        Node contractsNode = document.getProperties().get("contracts");
        assertNotNull(contractsNode, "contracts node should be present");

        Map<String, Node> contractEntries = contractsNode.getProperties();
        assertNotNull(contractEntries);

        NodeToObjectConverter converter = new NodeToObjectConverter(new TypeClassResolver("blue.language.processor.model"));

        Contract embeddedContract = converter.convertWithType(contractEntries.get("embedded"), Contract.class, false);
        assertTrue(embeddedContract instanceof ProcessEmbedded);
        assertEquals(2, ((ProcessEmbedded) embeddedContract).getPaths().size());

        Contract updateContract = converter.convertWithType(contractEntries.get("documentUpdate"), Contract.class, false);
        assertNotNull(updateContract);
        assertEquals(DocumentUpdateChannel.class, updateContract.getClass());
        assertEquals("/", ((DocumentUpdateChannel) updateContract).getPath());

        Contract triggeredContract = converter.convertWithType(contractEntries.get("triggered"), Contract.class, false);
        assertTrue(triggeredContract instanceof TriggeredEventChannel);

        Contract lifecycleContract = converter.convertWithType(contractEntries.get("lifecycleChannel"), Contract.class, false);
        assertTrue(lifecycleContract instanceof LifecycleChannel);

        Contract embeddedNodeContract = converter.convertWithType(contractEntries.get("embeddedNode"), Contract.class, false);
        assertTrue(embeddedNodeContract instanceof EmbeddedNodeChannel);
        assertEquals("/payment", ((EmbeddedNodeChannel) embeddedNodeContract).getChildPath());

        Contract checkpointContract = converter.convertWithType(contractEntries.get("checkpoint"), Contract.class, false);
        assertTrue(checkpointContract instanceof ChannelEventCheckpoint);
        ChannelEventCheckpoint checkpoint = (ChannelEventCheckpoint) checkpointContract;
        Node storedEvent = checkpoint.lastEvent("external");
        assertNotNull(storedEvent);
        Node eventIdNode = storedEvent.getProperties().get("eventId");
        assertNotNull(eventIdNode);
        assertEquals("evt-001", eventIdNode.getValue());

        Contract initializedContract = converter.convertWithType(contractEntries.get("initialized"), Contract.class, false);
        assertTrue(initializedContract instanceof InitializationMarker);
        assertEquals("doc-123", ((InitializationMarker) initializedContract).getDocumentId());

        Contract failureContract = converter.convertWithType(contractEntries.get("failure"), Contract.class, false);
        assertTrue(failureContract instanceof ProcessingFailureMarker);
        ProcessingFailureMarker failure = (ProcessingFailureMarker) failureContract;
        assertEquals("RuntimeFatal", failure.getCode());
        assertEquals("boundary violation", failure.getReason());

        Contract setPropertyContract = converter.convertWithType(contractEntries.get("setProperty"), Contract.class, false);
        assertNotNull(setPropertyContract);
        assertEquals(SetProperty.class, setPropertyContract.getClass());
        SetProperty setProperty = (SetProperty) setPropertyContract;
        assertEquals("lifecycleChannel", setProperty.getChannelKey());
        assertEquals("/x", setProperty.getPropertyKey());
        assertEquals(7, setProperty.getPropertyValue());
        assertEquals("/custom/path/", setProperty.getPath());
    }
}
