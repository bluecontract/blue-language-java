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
import blue.language.identity.DirectBlueIdCalculator;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
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
    void shouldMatchEveryNamedRuntimeBlueIdToTheClosedRegistry() {
        // given
        BlueRuntimeTypeRegistry registry =
                BlueRuntimeTypeRegistry.getDefault();
        Map<RuntimeTypeKey, String> namedBlueIds =
                new EnumMap<>(RuntimeTypeKey.class);

        // when
        for (RuntimeTypeKey key : RuntimeTypeKey.values()) {
            namedBlueIds.put(key, RuntimeBlueIds.blueId(key));
        }

        // then
        assertEquals(RuntimeTypeKey.values().length,
                registry.blueIds().size());
        assertEquals(registry.blueIds(), namedBlueIds);
    }

    @Test
    void shouldVerifyProviderReturnsCanonicalNodesForRuntimeTypes() {
        // given
        BlueRuntimeTypeRegistry registry = BlueRuntimeTypeRegistry.getDefault();

        // when
        Map<RuntimeTypeKey, List<Node>> providerNodesByType =
                new HashMap<>();
        Map<RuntimeTypeKey, List<Node>>
                processorNodesByType = new HashMap<>();
        for (Map.Entry<RuntimeTypeKey, String> entry : registry.blueIds().entrySet()) {
            providerNodesByType.put(
                    entry.getKey(),
                    registry.asProvider()
                            .fetchByBlueId(entry.getValue()));
            processorNodesByType.put(
                    entry.getKey(),
                    registry.asProcessorSnapshotProvider()
                            .fetchByBlueId(entry.getValue()));
        }

        // then
        for (Map.Entry<RuntimeTypeKey, String> entry :
                registry.blueIds().entrySet()) {
            List<Node> nodes =
                    providerNodesByType.get(entry.getKey());
            assertNotNull(nodes, entry.getKey().name());
            assertEquals(1, nodes.size(), entry.getKey().name());
            assertNotNull(nodes.get(0).getName(), entry.getKey().name());
            assertEquals(entry.getValue(),
                    DirectBlueIdCalculator.calculateBlueId(nodes.get(0)),
                    entry.getKey().name());

            List<Node> processorNodes =
                    processorNodesByType.get(entry.getKey());
            assertNotNull(processorNodes, entry.getKey().name());
            assertEquals(1, processorNodes.size(), entry.getKey().name());
            assertEquals(entry.getValue(),
                    DirectBlueIdCalculator.calculateBlueId(processorNodes.get(0)),
                    "processor snapshot provider " + entry.getKey().name());
            assertEquals(
                    DirectBlueIdCalculator.calculateBlueId(nodes.get(0)),
                    DirectBlueIdCalculator.calculateBlueId(processorNodes.get(0)),
                    "both registry provider views must expose the same exact node");
        }
        assertEquals(RuntimeBlueIds.REGISTRY_PACKAGE_IDENTITY,
                registry.registryIdentity());
    }

    @Test
    void shouldVerifyBlueInstancesResolveRuntimeTypeDefinitionsByDefault() {
        // given
        Blue blue = new Blue();

        // when
        Node resolved = blue.resolve(blue.yamlToNode(
                "type: Document Update Channel\n" +
                "path: /orders"));

        // then
        assertEquals(RuntimeBlueIds.DOCUMENT_UPDATE_CHANNEL, resolved.getType().getBlueId());
        assertEquals("Document Update Channel", resolved.getType().getName());
        assertNotNull(resolved.getProperties().get("order"), "Contract field should be inherited");
        assertEquals("/orders", resolved.getProperties().get("path").getValue());
        assertNotNull(blue.getNodeProvider().fetchByBlueId(RuntimeBlueIds.CHANNEL));
    }

    @Test
    void shouldVerifyProcessorManagedTypeIdsAreCalculatedBlueIds() {
        // given
        BlueRuntimeTypeRegistry registry = BlueRuntimeTypeRegistry.getDefault();
        // when
        Set<String> managed = registry.processorManagedTypeBlueIds();

        // then
        assertEquals(RuntimeTypeKey.values().length, managed.size());
        assertTrue(managed.contains(RuntimeBlueIds.DOCUMENT_UPDATE));
        assertTrue(managed.contains(RuntimeBlueIds.PROCESSING_INITIALIZED_MARKER));
        for (String blueId : managed) {
            assertTrue(BlueIds.isPotentialBlueId(blueId), blueId);
            assertFalse(blueId.contains(" "), blueId);
        }
    }

    @Test
    void shouldVerifyRegisteredSubtypeRecognitionDerivesRolesFromCanonicalAncestry() {
        // given
        BlueRuntimeTypeRegistry registry =
                BlueRuntimeTypeRegistry.getDefault();

        // when
        boolean externalChannel =
                registry.isRegisteredSubtype(
                        RuntimeBlueIds.SCRIPTED_EXTERNAL_CHANNEL,
                        RuntimeTypeKey.EXTERNAL_CHANNEL);
        boolean channel =
                registry.isRegisteredSubtype(
                        RuntimeBlueIds.SCRIPTED_EXTERNAL_CHANNEL,
                        RuntimeTypeKey.CHANNEL);
        boolean handlerAsExternal =
                registry.isRegisteredSubtype(
                        RuntimeBlueIds.SCRIPTED_HANDLER,
                        RuntimeTypeKey.EXTERNAL_CHANNEL);
        boolean unknownAsExternal =
                registry.isRegisteredSubtype(
                        "not-a-registered-runtime-type",
                        RuntimeTypeKey.EXTERNAL_CHANNEL);

        // then
        assertTrue(externalChannel);
        assertTrue(channel);
        assertFalse(handlerAsExternal);
        assertFalse(unknownAsExternal);
    }

    @Test
    void shouldVerifyAnnotatedProcessorModelTypesUseRuntimeRegistryBlueIds() {
        // given
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
        // when
        expected.put(TypeGeneralizationRule.class, RuntimeTypeKey.TYPE_GENERALIZATION_RULE);

        // then
        for (Map.Entry<Class<?>, RuntimeTypeKey> entry : expected.entrySet()) {
            TypeBlueId annotation = entry.getKey().getAnnotation(TypeBlueId.class);
            assertNotNull(annotation, entry.getKey().getSimpleName());
            assertEquals(1, annotation.value().length, entry.getKey().getSimpleName());
            assertEquals(registry.blueId(entry.getValue()), annotation.value()[0], entry.getKey().getSimpleName());
        }
    }
}
