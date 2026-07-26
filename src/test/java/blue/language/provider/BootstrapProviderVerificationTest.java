package blue.language.provider;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.processor.registry.BlueRuntimeTypeRegistry;
import blue.language.processor.registry.RuntimeTypeKey;
import blue.language.preprocess.Preprocessor;
import blue.language.utils.BlueIdCalculator;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static blue.language.utils.Properties.BOOLEAN_TYPE_BLUE_ID;
import static blue.language.utils.Properties.BLUE_CONTRACTS_RUNTIME_TYPE_NAME_TO_BLUE_ID_MAP;
import static blue.language.utils.Properties.CORE_TYPE_BLUE_ID_TO_NAME_MAP;
import static blue.language.utils.Properties.CORE_TYPE_NAME_TO_BLUE_ID_MAP;
import static blue.language.utils.Properties.DEFAULT_BLUE_TYPE_BLUE_ID_TO_NAME_MAP;
import static blue.language.utils.Properties.DEFAULT_BLUE_TYPE_NAME_TO_BLUE_ID_MAP;
import static blue.language.utils.Properties.DICTIONARY_TYPE_BLUE_ID;
import static blue.language.utils.Properties.DOUBLE_TYPE_BLUE_ID;
import static blue.language.utils.Properties.INTEGER_TYPE_BLUE_ID;
import static blue.language.utils.Properties.LIST_TYPE_BLUE_ID;
import static blue.language.utils.Properties.TEXT_TYPE_BLUE_ID;
import static blue.language.utils.UncheckedObjectMapper.YAML_MAPPER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class BootstrapProviderVerificationTest {

    @Test
    void coreAliasMapMatchesRegistryBlueIds() {
        assertEquals(TEXT_TYPE_BLUE_ID, CORE_TYPE_NAME_TO_BLUE_ID_MAP.get("Text"));
        assertEquals(DOUBLE_TYPE_BLUE_ID, CORE_TYPE_NAME_TO_BLUE_ID_MAP.get("Double"));
        assertEquals(INTEGER_TYPE_BLUE_ID, CORE_TYPE_NAME_TO_BLUE_ID_MAP.get("Integer"));
        assertEquals(BOOLEAN_TYPE_BLUE_ID, CORE_TYPE_NAME_TO_BLUE_ID_MAP.get("Boolean"));
        assertEquals(LIST_TYPE_BLUE_ID, CORE_TYPE_NAME_TO_BLUE_ID_MAP.get("List"));
        assertEquals(DICTIONARY_TYPE_BLUE_ID, CORE_TYPE_NAME_TO_BLUE_ID_MAP.get("Dictionary"));

        CORE_TYPE_NAME_TO_BLUE_ID_MAP.forEach((name, blueId) ->
                assertEquals(name, CORE_TYPE_BLUE_ID_TO_NAME_MAP.get(blueId)));
        assertEquals(CORE_TYPE_NAME_TO_BLUE_ID_MAP, new Blue().conformanceReport().getCoreRegistryBlueIds());
    }

    @Test
    void defaultBlueAliasMapIncludesRuntimeTypeBlueIds() {
        BlueRuntimeTypeRegistry registry = BlueRuntimeTypeRegistry.getDefault();
        Map<String, String> expectedRuntimeAliases = new LinkedHashMap<>();

        for (RuntimeTypeKey key : RuntimeTypeKey.values()) {
            String name = registry.node(key).getName();
            String blueId = registry.blueId(key);
            expectedRuntimeAliases.put(name, blueId);
            assertEquals(blueId, DEFAULT_BLUE_TYPE_NAME_TO_BLUE_ID_MAP.get(name));
            assertEquals(name, DEFAULT_BLUE_TYPE_BLUE_ID_TO_NAME_MAP.get(blueId));
        }
        assertEquals(expectedRuntimeAliases,
                BLUE_CONTRACTS_RUNTIME_TYPE_NAME_TO_BLUE_ID_MAP);
        assertFalse(DEFAULT_BLUE_TYPE_NAME_TO_BLUE_ID_MAP.containsKey(
                "Document Processing Fatal Error"));
    }

    @Test
    void defaultBlueResourceMappingsMatchDefaultAliasMap() throws Exception {
        Node defaultBlue = readResource("transformation/DefaultBlue.blue");
        Node mappings = defaultBlue.getItems().get(0).getProperties().get("mappings");
        Map<String, String> actual = new LinkedHashMap<>();
        mappings.getProperties().forEach((name, node) -> actual.put(name, (String) node.getValue()));

        assertEquals(DEFAULT_BLUE_TYPE_NAME_TO_BLUE_ID_MAP, actual);
    }

    @Test
    void defaultBlueTransformBlueIdsMatchResources() throws Exception {
        Node defaultBlue = readResource("transformation/DefaultBlue.blue");
        Node transformation = readResource("transformation/Transformation.blue");
        Node replaceInlineTypes = readResource("transformation/ReplaceInlineTypesWithBlueIds.blue");
        Node inferBasicTypes = readResource("transformation/InferBasicTypesForUntypedValues.blue");

        String transformationBlueId = BlueIdCalculator.calculateBlueId(transformation);
        String replaceInlineTypesBlueId = BlueIdCalculator.calculateBlueId(replaceInlineTypes);
        String inferBasicTypesBlueId = BlueIdCalculator.calculateBlueId(inferBasicTypes);

        assertEquals(transformationBlueId, replaceInlineTypes.getType().getBlueId());
        assertEquals(transformationBlueId, inferBasicTypes.getType().getBlueId());
        assertEquals(replaceInlineTypesBlueId, defaultBlue.getItems().get(0).getType().getBlueId());
        assertEquals(inferBasicTypesBlueId, defaultBlue.getItems().get(1).getType().getBlueId());
        assertEquals(BlueIdCalculator.calculateBlueId(defaultBlue.getItems()), Preprocessor.DEFAULT_BLUE_BLUE_ID);
    }

    @Test
    void bootstrapProviderContentHashesToAdvertisedBlueIds() throws Exception {
        for (String resource : new String[]{
                "transformation/Transformation.blue",
                "transformation/ReplaceInlineTypesWithBlueIds.blue",
                "transformation/InferBasicTypesForUntypedValues.blue"}) {
            Node advertised = readResource(resource);
            String blueId = BlueIdCalculator.calculateBlueId(advertised);
            List<Node> fetched = BootstrapProvider.INSTANCE.fetchByBlueId(blueId);

            assertNotNull(fetched, "Bootstrap provider returned null for " + resource);
            assertFalse(fetched.isEmpty(), "Bootstrap provider returned no content for " + resource);
            assertEquals(blueId, BlueIdCalculator.calculateBlueId(withoutRootIdentity(fetched.get(0))), resource);
        }
    }

    @Test
    void allDefaultBlueTransformsAreFetchableAndVerifiedByBlueId() throws Exception {
        Node defaultBlue = readResource("transformation/DefaultBlue.blue");

        for (Node transformationReference : defaultBlue.getItems()) {
            String blueId = transformationReference.getType().getBlueId();
            List<Node> fetched = BootstrapProvider.INSTANCE.fetchByBlueId(blueId);

            assertNotNull(fetched, "Bootstrap provider returned null for DefaultBlue transform " + blueId);
            assertFalse(fetched.isEmpty(), "Bootstrap provider returned no transform content for " + blueId);
            assertEquals(blueId, BlueIdCalculator.calculateBlueId(withoutRootIdentity(fetched.get(0))));
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
