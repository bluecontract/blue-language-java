package blue.language.processor.registry;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.model.TypeBlueId;
import blue.language.processor.model.ChannelEventCheckpoint;
import blue.language.processor.model.DocumentUpdate;
import blue.language.processor.model.DocumentUpdateChannel;
import blue.language.processor.model.EmbeddedEventDelivery;
import blue.language.processor.model.EmbeddedNodeChannel;
import blue.language.processor.model.InitializationMarker;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.model.LifecycleChannel;
import blue.language.processor.model.ProcessEmbedded;
import blue.language.processor.model.ProcessingTerminatedMarker;
import blue.language.processor.model.TriggeredEventChannel;
import blue.language.processor.model.TypeGeneralizationPolicy;
import blue.language.processor.model.TypeGeneralizationRule;
import blue.language.utils.BlueIds;
import blue.language.utils.BlueIdCalculator;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlueRuntimeTypeRegistryTest {

    @Test
    void providerReturnsCanonicalNodesForRuntimeTypes() {
        BlueRuntimeTypeRegistry registry = BlueRuntimeTypeRegistry.getDefault();

        for (Map.Entry<RuntimeTypeKey, String> entry : registry.blueIds().entrySet()) {
            List<Node> nodes = registry.asProvider().fetchByBlueId(entry.getValue());
            assertNotNull(nodes, entry.getKey().name());
            assertEquals(1, nodes.size(), entry.getKey().name());
            assertNotNull(nodes.get(0).getName(), entry.getKey().name());
            assertEquals(entry.getValue(),
                    BlueIdCalculator.calculateBlueId(nodes.get(0)),
                    entry.getKey().name());

            List<Node> processorNodes = registry.asProcessorSnapshotProvider()
                    .fetchByBlueId(entry.getValue());
            assertNotNull(processorNodes, entry.getKey().name());
            assertEquals(1, processorNodes.size(), entry.getKey().name());
            assertEquals(entry.getValue(),
                    BlueIdCalculator.calculateBlueId(processorNodes.get(0)),
                    "processor snapshot provider " + entry.getKey().name());
            assertEquals(
                    BlueIdCalculator.calculateBlueId(nodes.get(0)),
                    BlueIdCalculator.calculateBlueId(processorNodes.get(0)),
                    "both registry provider views must expose the same exact node");
        }
        assertEquals(RuntimeBlueIds.REGISTRY_PACKAGE_IDENTITY,
                registry.registryIdentity());
    }

    @Test
    void blueInstancesResolveRuntimeTypeDefinitionsByDefault() {
        Blue blue = new Blue();

        Node resolved = blue.resolve(blue.yamlToNode(
                "type: Document Update Channel\n" +
                "path: /orders"));

        assertEquals(RuntimeBlueIds.DOCUMENT_UPDATE_CHANNEL, resolved.getType().getBlueId());
        assertEquals("Document Update Channel", resolved.getType().getName());
        assertNotNull(resolved.getProperties().get("order"), "Contract field should be inherited");
        assertEquals("/orders", resolved.getProperties().get("path").getValue());
        assertNotNull(blue.getNodeProvider().fetchByBlueId(RuntimeBlueIds.CHANNEL));
    }

    @Test
    void processorManagedTypeIdsAreCalculatedBlueIds() {
        BlueRuntimeTypeRegistry registry = BlueRuntimeTypeRegistry.getDefault();
        Set<String> managed = registry.processorManagedTypeBlueIds();

        assertEquals(RuntimeTypeKey.values().length, managed.size());
        assertTrue(managed.contains(RuntimeBlueIds.DOCUMENT_UPDATE));
        assertTrue(managed.contains(RuntimeBlueIds.PROCESSING_INITIALIZED_MARKER));
        for (String blueId : managed) {
            assertTrue(BlueIds.isPotentialBlueId(blueId), blueId);
            assertFalse(blueId.contains(" "), blueId);
        }
    }

    @Test
    void annotatedProcessorModelTypesUseRuntimeRegistryBlueIds() {
        BlueRuntimeTypeRegistry registry = BlueRuntimeTypeRegistry.getDefault();
        Map<Class<?>, RuntimeTypeKey> expected = new HashMap<>();
        expected.put(ChannelEventCheckpoint.class, RuntimeTypeKey.CHANNEL_EVENT_CHECKPOINT);
        expected.put(DocumentUpdate.class, RuntimeTypeKey.DOCUMENT_UPDATE);
        expected.put(DocumentUpdateChannel.class, RuntimeTypeKey.DOCUMENT_UPDATE_CHANNEL);
        expected.put(EmbeddedEventDelivery.class, RuntimeTypeKey.EMBEDDED_EVENT_DELIVERY);
        expected.put(EmbeddedNodeChannel.class, RuntimeTypeKey.EMBEDDED_NODE_CHANNEL);
        expected.put(InitializationMarker.class, RuntimeTypeKey.PROCESSING_INITIALIZED_MARKER);
        expected.put(JsonPatch.class, RuntimeTypeKey.JSON_PATCH_ENTRY);
        expected.put(LifecycleChannel.class, RuntimeTypeKey.LIFECYCLE_EVENT_CHANNEL);
        expected.put(ProcessEmbedded.class, RuntimeTypeKey.PROCESS_EMBEDDED);
        expected.put(ProcessingTerminatedMarker.class, RuntimeTypeKey.PROCESSING_TERMINATED_MARKER);
        expected.put(TriggeredEventChannel.class, RuntimeTypeKey.TRIGGERED_EVENT_CHANNEL);
        expected.put(TypeGeneralizationPolicy.class, RuntimeTypeKey.TYPE_GENERALIZATION_POLICY);
        expected.put(TypeGeneralizationRule.class, RuntimeTypeKey.TYPE_GENERALIZATION_RULE);

        for (Map.Entry<Class<?>, RuntimeTypeKey> entry : expected.entrySet()) {
            TypeBlueId annotation = entry.getKey().getAnnotation(TypeBlueId.class);
            assertNotNull(annotation, entry.getKey().getSimpleName());
            assertEquals(1, annotation.value().length, entry.getKey().getSimpleName());
            assertEquals(registry.blueId(entry.getValue()), annotation.value()[0], entry.getKey().getSimpleName());
        }
    }
}
