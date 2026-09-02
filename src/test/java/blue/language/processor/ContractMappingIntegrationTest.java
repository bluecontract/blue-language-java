package blue.language.processor;

import blue.language.Blue;
import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.mapping.NodeToObjectConverter;
import blue.language.model.Node;
import blue.language.processor.model.Contract;
import blue.language.processor.model.ChannelEventCheckpoint;
import blue.language.processor.model.DocumentUpdateChannel;
import blue.language.processor.model.EmbeddedNodeChannel;
import blue.language.processor.model.InitializationMarker;
import blue.language.processor.model.LifecycleChannel;
import blue.language.processor.model.ProcessEmbedded;
import blue.language.processor.model.SetProperty;
import blue.language.processor.model.TriggeredEventChannel;
import blue.language.processor.model.ProcessorTestTypeBlueIds;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.processor.contracts.SetPropertyContractProcessor;
import blue.language.snapshot.FrozenNode;
import blue.language.merge.ResolvedSnapshot;
import blue.language.mapping.TypeClassResolver;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ContractMappingIntegrationTest {

    @Test
    void shouldLoadAllContractsFromBlueYaml() throws Exception {
        // given
        String yaml = new String(
                Files.readAllBytes(Paths.get("src/test/resources/processor/contracts/all-contracts.blue")),
                StandardCharsets.UTF_8
        );

        Blue blue = ProcessorTestSupport.blue();
        NodeToObjectConverter converter =
                new NodeToObjectConverter(
                        new TypeClassResolver(
                                "blue.language.processor.model"));

        // when
        Node document = blue.yamlToNode(yaml);
        Node contractsNode = document.getContracts();
        Map<String, Node> contractEntries = contractsNode.getProperties();
        Contract embeddedContract = converter.convertWithType(contractEntries.get("embedded"), Contract.class, false);
        Contract updateContract = converter.convertWithType(contractEntries.get("documentUpdate"), Contract.class, false);
        Contract triggeredContract = converter.convertWithType(contractEntries.get("triggered"), Contract.class, false);
        Contract lifecycleContract = converter.convertWithType(contractEntries.get("lifecycleChannel"), Contract.class, false);
        Contract embeddedNodeContract = converter.convertWithType(contractEntries.get("embeddedNode"), Contract.class, false);
        Contract checkpointContract = converter.convertWithType(contractEntries.get("checkpoint"), Contract.class, false);
        ChannelEventCheckpoint checkpoint = (ChannelEventCheckpoint) checkpointContract;
        Contract initializedContract = converter.convertWithType(contractEntries.get("initialized"), Contract.class, false);
        Contract setPropertyContract = converter.convertWithType(contractEntries.get("setProperty"), Contract.class, false);
        SetProperty setProperty = (SetProperty) setPropertyContract;

        // then
        assertNotNull(document);
        assertNotNull(contractsNode, "contracts node should be present");
        assertNotNull(contractEntries);
        assertTrue(embeddedContract instanceof ProcessEmbedded);
        assertEquals(2, ((ProcessEmbedded) embeddedContract).getPaths().size());
        assertNotNull(updateContract);
        assertEquals(DocumentUpdateChannel.class, updateContract.getClass());
        assertEquals("/", ((DocumentUpdateChannel) updateContract).getPath());
        assertTrue(triggeredContract instanceof TriggeredEventChannel);
        assertTrue(lifecycleContract instanceof LifecycleChannel);
        assertTrue(embeddedNodeContract instanceof EmbeddedNodeChannel);
        assertEquals("/payment", ((EmbeddedNodeChannel) embeddedNodeContract).getSourcePath());
        assertTrue(checkpointContract instanceof ChannelEventCheckpoint);
        assertNotNull(checkpoint.entry("external"));
        assertEquals(ProcessorTestTypeBlueIds.TEST_EVENT_CHANNEL,
                checkpoint.entry("external").domainBlueId());
        assertEquals(ProcessorTestTypeBlueIds.TEST_EVENT,
                checkpoint.entry("external").subjectBlueId());
        assertTrue(initializedContract instanceof InitializationMarker);
        assertEquals("doc-123",
                ((InitializationMarker) initializedContract)
                        .getDocument().getAsText("/sample"));
        assertNotNull(setPropertyContract);
        assertEquals(SetProperty.class, setPropertyContract.getClass());
        assertEquals("lifecycleChannel", setProperty.getChannelKey());
        assertEquals("/x", setProperty.getPropertyKey());
        assertEquals(7, setProperty.getPropertyValue());
        assertEquals("/custom/path/", setProperty.getPath());
    }

    @Test
    void shouldVerifyContractLoaderLoadsBundleFromResolvedSnapshotWithoutScopeNodeTraversal() throws Exception {
        // given
        String yaml = new String(
                Files.readAllBytes(Paths.get("src/test/resources/processor/contracts/all-contracts.blue")),
                StandardCharsets.UTF_8
        );

        Blue blue = ProcessorTestSupport.blue();
        Node document = blue.yamlToNode(yaml);
        FrozenNode canonicalRoot = FrozenNode.fromUncheckedCanonicalNode(document);
        ResolvedSnapshot snapshot = new ResolvedSnapshot(canonicalRoot,
                FrozenNode.fromResolvedNode(document),
                canonicalRoot.blueId());
        ContractProcessorRegistry registry = ContractProcessorRegistryBuilder.create()
                .register(new SetPropertyContractProcessor())
                .build();
        TypeClassResolver resolver = new TypeClassResolver("blue.language.processor.model");
        ContractLoader loader = new ContractLoader(registry,
                new NodeToObjectConverter(resolver),
                resolver);

        // when
        ContractBundle bundle = loader.load(snapshot, "/");
        SetProperty setProperty =
                (SetProperty) bundle.handlersFor("lifecycleChannel")
                        .get(0).contract();

        // then
        assertEquals(Arrays.asList("/payment", "/shipping"), bundle.embeddedPaths());
        assertTrue(bundle.hasCheckpoint());
        assertTrue(bundle.marker("initialized") instanceof InitializationMarker);
        assertNotNull(bundle.contractNode("setProperty"));
        assertNotNull(bundle.contractNode("lifecycleChannel"));
        assertSame(bundle.contractNode("setProperty"), bundle.handlersFor("lifecycleChannel").get(0).node());
        assertSame(bundle.contractNode("lifecycleChannel"), bundle.channelBinding("lifecycleChannel").node());
        assertTrue(bundle.contractNodes().containsKey("setProperty"));
        assertEquals(1, bundle.channelsOfType(LifecycleChannel.class).size());
        assertEquals(1, bundle.handlersFor("lifecycleChannel").size());
        assertEquals("/x", setProperty.getPropertyKey());
        assertEquals(7, setProperty.getPropertyValue());
        assertEquals("/custom/path/", setProperty.getPath());
    }

    @Test
    void shouldVerifyProcessorContractLoaderStillFindsContracts() {
        // given
        Node document = ProcessorTestSupport.blue().yamlToNode(
                "contracts:\n" +
                "  lifecycleChannel:\n" +
                "    type:\n" +
                "      blueId: " + RuntimeBlueIds.LIFECYCLE_EVENT_CHANNEL + "\n" +
                "  setProperty:\n" +
                "    channel: lifecycleChannel\n" +
                "    type:\n" +
                "      blueId: " + ProcessorTestTypeBlueIds.SET_PROPERTY + "\n" +
                "    propertyKey: /x\n" +
                "    propertyValue: 7\n");
        ContractProcessorRegistry registry = ContractProcessorRegistryBuilder.create()
                .register(new SetPropertyContractProcessor())
                .build();
        TypeClassResolver resolver = new TypeClassResolver("blue.language.processor.model");
        ContractLoader loader = new ContractLoader(registry,
                new NodeToObjectConverter(resolver),
                resolver);

        // when
        ContractBundle bundle = loader.load(
                FrozenNode.fromResolvedNode(document),
                "/",
                CanonicalTypeIdentityLookup.incomplete());

        // then
        assertNotNull(bundle.contractNode("setProperty"));
        assertTrue(bundle.contractNodes().containsKey("setProperty"));
    }
}
