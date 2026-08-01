package blue.language.provider;

import blue.language.registry.BootstrapProvider;

import blue.language.model.wire.BlueLanguageConstants;

import blue.language.model.Node;
import blue.language.processor.registry.BlueRuntimeTypeRegistry;
import blue.language.processor.registry.RuntimeTypeKey;
import blue.language.processor.registry.RuntimeTypeAliases;
import blue.language.registry.BlueCoreTypeRegistry;
import blue.language.identity.DirectBlueIdCalculator;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static blue.language.model.wire.BlueLanguageConstants.BOOLEAN_TYPE_BLUE_ID;
import static blue.language.model.wire.BlueLanguageConstants.CORE_TYPE_BLUE_ID_TO_NAME_MAP;
import static blue.language.model.wire.BlueLanguageConstants.CORE_TYPE_NAME_TO_BLUE_ID_MAP;
import static blue.language.model.wire.BlueLanguageConstants.DICTIONARY_TYPE_BLUE_ID;
import static blue.language.model.wire.BlueLanguageConstants.DOUBLE_TYPE_BLUE_ID;
import static blue.language.model.wire.BlueLanguageConstants.INTEGER_TYPE_BLUE_ID;
import static blue.language.model.wire.BlueLanguageConstants.LIST_TYPE_BLUE_ID;
import static blue.language.model.wire.BlueLanguageConstants.TEXT_TYPE_BLUE_ID;
import static blue.language.utils.UncheckedObjectMapper.YAML_MAPPER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class BootstrapProviderVerificationTest {

    @Test
    void shouldMatchCoreAliasMapAgainstRegistryBlueIds() {
        // given
        Map<String, String> expectedCoreAliases = new LinkedHashMap<>();
        expectedCoreAliases.put("Text", TEXT_TYPE_BLUE_ID);
        expectedCoreAliases.put("Double", DOUBLE_TYPE_BLUE_ID);
        expectedCoreAliases.put("Integer", INTEGER_TYPE_BLUE_ID);
        expectedCoreAliases.put("Boolean", BOOLEAN_TYPE_BLUE_ID);
        expectedCoreAliases.put("List", LIST_TYPE_BLUE_ID);
        expectedCoreAliases.put("Dictionary", DICTIONARY_TYPE_BLUE_ID);

        // when
        Map<String, String> actualCoreAliases = new LinkedHashMap<>(CORE_TYPE_NAME_TO_BLUE_ID_MAP);
        Map<String, String> actualCoreNames = new LinkedHashMap<>(CORE_TYPE_BLUE_ID_TO_NAME_MAP);
        Map<String, String> reportedCoreAliases = new LinkedHashMap<>(
                BlueCoreTypeRegistry.INSTANCE.blueIdsByName());

        // then
        assertEquals(expectedCoreAliases, actualCoreAliases);
        expectedCoreAliases.forEach((name, blueId) ->
                assertEquals(name, actualCoreNames.get(blueId)));
        assertEquals(actualCoreAliases, reportedCoreAliases);
    }

    @Test
    void shouldRetainRuntimeTypeBlueIdsOnlyInLegacyCombinedAliasMap() {
        // given
        BlueRuntimeTypeRegistry registry = BlueRuntimeTypeRegistry.getDefault();
        Map<String, String> expectedRuntimeAliases = new LinkedHashMap<>();
        Map<String, String> expectedRuntimeNames = new LinkedHashMap<>();
        for (RuntimeTypeKey key : RuntimeTypeKey.values()) {
            String name = registry.node(key).getName();
            String blueId = registry.blueId(key);
            expectedRuntimeAliases.put(name, blueId);
            expectedRuntimeNames.put(blueId, name);
        }

        // when
        Map<String, String> actualRuntimeAliases =
                new LinkedHashMap<>(RuntimeTypeAliases.NAME_TO_BLUE_ID);
        Map<String, String> actualDefaultAliases =
                new LinkedHashMap<>(RuntimeTypeAliases.AGGREGATE_NAME_TO_BLUE_ID);
        Map<String, String> actualDefaultNames =
                new LinkedHashMap<>(RuntimeTypeAliases.AGGREGATE_BLUE_ID_TO_NAME);

        // then
        assertEquals(expectedRuntimeAliases, actualRuntimeAliases);
        expectedRuntimeAliases.keySet().forEach(name ->
                assertFalse(CORE_TYPE_NAME_TO_BLUE_ID_MAP.containsKey(name)));
        expectedRuntimeAliases.forEach((name, blueId) ->
                assertEquals(blueId, actualDefaultAliases.get(name)));
        expectedRuntimeNames.forEach((blueId, name) ->
                assertEquals(name, actualDefaultNames.get(blueId)));
        assertFalse(actualDefaultAliases.containsKey("Document Processing Fatal Error"));
    }

    @Test
    void shouldHashBootstrapProviderContentToAdvertisedBlueIds() throws Exception {
        // given
        String[] resources = {
                "transformation/Transformation.blue",
                "transformation/ReplaceInlineTypesWithBlueIds.blue",
                "transformation/InferBasicTypesForUntypedValues.blue"
        };
        Map<String, String> advertisedBlueIds = new LinkedHashMap<>();
        Map<String, List<Node>> fetchedByResource = new LinkedHashMap<>();

        // when
        for (String resource : resources) {
            Node advertised = readResource(resource);
            String blueId = DirectBlueIdCalculator.calculateBlueId(advertised);
            advertisedBlueIds.put(resource, blueId);
            fetchedByResource.put(resource, BootstrapProvider.INSTANCE.fetchByBlueId(blueId));
        }

        // then
        for (String resource : resources) {
            String blueId = advertisedBlueIds.get(resource);
            List<Node> fetched = fetchedByResource.get(resource);
            assertNotNull(fetched, "Bootstrap provider returned null for " + resource);
            assertFalse(fetched.isEmpty(), "Bootstrap provider returned no content for " + resource);
            assertEquals(blueId, DirectBlueIdCalculator.calculateBlueId(withoutRootIdentity(fetched.get(0))), resource);
        }
    }

    private Node withoutRootIdentity(Node node) {
        Node canonical = node.clone();
        if (canonical.getBlueId() != null && !canonical.isReferenceOnly()) {
            canonical.blueId(null);
        }
        return canonical;
    }

    private Node readResource(String resource) throws Exception {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalArgumentException("Missing resource: " + resource);
            }
            return YAML_MAPPER.readValue(stream, Node.class);
        }
    }
}
