package blue.language;

import blue.language.model.Node;
import blue.language.preprocess.Preprocessor;
import blue.language.preprocess.TransformationProcessor;
import blue.language.preprocess.TransformationProcessorProvider;
import blue.language.provider.BootstrapProvider;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.NodeTransformer;
import blue.language.utils.Properties;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Map;
import java.util.Optional;

import static blue.language.preprocess.Preprocessor.DEFAULT_BLUE_BLUE_ID;
import static blue.language.utils.Properties.*;
import static blue.language.utils.UncheckedObjectMapper.YAML_MAPPER;
import static org.junit.jupiter.api.Assertions.*;

public class PreprocessorTest {

    @Test
    public void testType() throws Exception {
        String doc = "a:\n" +
                     "  type: Integer\n" +
                     "b:\n" +
                     "  type:\n" +
                     "    value: Integer\n" +
                     "c:\n" +
                     "  type:\n" +
                     "    blueId: 84ZWw2aoqB6dWRM6N1qWwgcXGrjfeKexTNdWxxAEcECH\n" +
                     "d:\n" +
                     "  type: Channel";

        Blue blue = new Blue();
        Node node = blue.preprocess(blue.yamlToNode(doc));

        assertEquals(CORE_TYPE_BLUE_ID_TO_NAME_MAP.get("Integer"), node.getProperties().get("a").getType().getName());
        assertEquals("Integer", node.getProperties().get("b").getType().getValue());
        assertEquals("84ZWw2aoqB6dWRM6N1qWwgcXGrjfeKexTNdWxxAEcECH", node.getProperties().get("c").getType().getBlueId());
        assertEquals(DEFAULT_BLUE_TYPE_NAME_TO_BLUE_ID_MAP.get("Channel"), node.getProperties().get("d").getType().getBlueId());

        assertFalse(node.getProperties().get("a").getType().isInlineValue());
        assertFalse(node.getProperties().get("b").getType().isInlineValue());
        assertFalse(node.getProperties().get("c").getType().isInlineValue());
        assertFalse(node.getProperties().get("d").getType().isInlineValue());
    }

    @Test
    public void testItemsAsBlueId() throws Exception {
        String doc = "name: Abc\n" +
                     "items:\n" +
                     "  blueId: 84ZWw2aoqB6dWRM6N1qWwgcXGrjfeKexTNdWxxAEcECH";

        assertThrows(RuntimeException.class, () -> new Blue().yamlToNode(doc));
    }

    @Test
    public void testPreprocessWithCustomBlueExtendingDefaultBlue() throws Exception {
        String doc = "blue:\n" +
                     "  items:\n" +
                     "    - blueId: " + DEFAULT_BLUE_BLUE_ID + "\n" +
                     "    - name: MyTestTransformation\n" +
                     "x:\n" +
                     "  type: Integer\n" +
                     "y: ABC";
        Node node = YAML_MAPPER.readValue(doc, Node.class);

        TransformationProcessor changeABCtoXYZ = document -> NodeTransformer.transform(document, docNode -> {
            Node result = docNode.clone();
            if (docNode.getValue() != null && "ABC".equals(docNode.getValue()))
                result.value("XYZ");
            return result;
        });
        TransformationProcessorProvider provider = transformation -> {
            if ("MyTestTransformation".equals(transformation.getName()))
                return Optional.of(changeABCtoXYZ);
            return Preprocessor.getStandardProvider().getProcessor(transformation);
        };
        Preprocessor preprocessor = new Preprocessor(provider, BootstrapProvider.INSTANCE);
        Node result = preprocessor.preprocess(node);

        assertEquals(Properties.INTEGER_TYPE_BLUE_ID, result.getAsText("/x/type/blueId"));
        assertEquals("XYZ", result.getAsText("/y/value"));
    }

    @Test
    public void preprocessorPreprocessAppliesDefaultBaselineWhenBlueOmitted() {
        Node raw = YAML_MAPPER.readValue("x: 1", Node.class);

        Node result = new Preprocessor(BootstrapProvider.INSTANCE).preprocess(raw);

        assertEquals(INTEGER_TYPE_BLUE_ID, result.getAsText("/x/type/blueId"));
    }

    @Test
    public void preprocessorPreprocessWithDefaultBlueMatchesBluePreprocess() {
        Node raw = YAML_MAPPER.readValue("x: 1", Node.class);

        Node direct = new Preprocessor(BootstrapProvider.INSTANCE).preprocessWithDefaultBlue(raw);
        Node viaBlue = new Blue().preprocess(raw.clone());

        assertEquals(BlueIdCalculator.calculateBlueId(direct), BlueIdCalculator.calculateBlueId(viaBlue));
    }

    @Test
    public void preprocessorPreprocessWithoutDefaultBlueIsExplicit() {
        Node raw = YAML_MAPPER.readValue("x: 1", Node.class);

        Node result = new Preprocessor(BootstrapProvider.INSTANCE).preprocessWithoutDefaultBlue(raw);

        assertNull(result.getProperties().get("x").getType());
        assertEquals(BigInteger.ONE, result.getProperties().get("x").getValue());
    }

    @Test
    public void blueImportsCannotRedefineDefaultRuntimeAliases() {
        Node raw = YAML_MAPPER.readValue(
                "blue:\n" +
                "  imports:\n" +
                "    Channel:\n" +
                "      blueId: " + TEXT_TYPE_BLUE_ID + "\n" +
                "x:\n" +
                "  type: Channel",
                Node.class);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new Preprocessor(BootstrapProvider.INSTANCE).preprocess(raw));

        assertTrue(error.getMessage().contains("default Blue alias \"Channel\""));
    }

    @Test
    public void testTypeConsistencyAfterMultiplePreprocessing() throws Exception {
        String doc = "a:\n" +
                     "  type: Text\n" +
                     "b:\n" +
                     "  type:\n" +
                     "    blueId: " + TEXT_TYPE_BLUE_ID;

        Blue blue = new Blue();
        Node node = blue.yamlToNode(doc);

        Node preprocessedOnce = blue.preprocess(node);
        Node preprocessedTwice = blue.preprocess(preprocessedOnce);

        String aTypeBlueId = preprocessedTwice.getProperties().get("a").getType().getAsText("/blueId");
        String bTypeBlueId = preprocessedTwice.getProperties().get("b").getType().getAsText("/blueId");

        assertEquals(aTypeBlueId, bTypeBlueId);

        assertEquals(preprocessedOnce.getAsText("/blueId"), preprocessedTwice.getAsText("/blueId"));
    }

    @Test
    public void testNodeProcessingAndDeserialization() throws Exception {
        String doc = "x: 1\n" +
                     "y:\n" +
                     "  value: 1\n" +
                     "z:\n" +
                     "  type: Integer\n" +
                     "  value: 1\n" +
                     "v:\n" +
                     "  type:\n" +
                     "    blueId: " + INTEGER_TYPE_BLUE_ID + "\n" +
                     "  value: 1";

        Blue blue = new Blue();

        Node preprocessedNode = blue.yamlToNode(doc);

        Node expectedPreprocessed = new Node()
                .properties(
                        "x", new Node().type(new Node().blueId(INTEGER_TYPE_BLUE_ID).inlineValue(false)).value(BigInteger.ONE).inlineValue(true),
                        "y", new Node().type(new Node().blueId(INTEGER_TYPE_BLUE_ID).inlineValue(false)).value(BigInteger.ONE).inlineValue(false),
                        "z", new Node().type(new Node().blueId(INTEGER_TYPE_BLUE_ID).inlineValue(false)).value(BigInteger.ONE).inlineValue(false),
                        "v", new Node().type(new Node().blueId(INTEGER_TYPE_BLUE_ID).inlineValue(false)).value(BigInteger.ONE).inlineValue(false)
                )
                .inlineValue(false);

        assertNodesEqual(expectedPreprocessed, preprocessedNode);

        Node rawNode = YAML_MAPPER.readValue(doc, Node.class);

        Node expectedRaw = new Node()
                .properties(
                        "x", new Node().value(BigInteger.ONE).inlineValue(true),
                        "y", new Node().value(BigInteger.ONE).inlineValue(false),
                        "z", new Node().type(new Node().value("Integer").inlineValue(true)).value(BigInteger.ONE).inlineValue(false),
                        "v", new Node().type(new Node().blueId(INTEGER_TYPE_BLUE_ID).inlineValue(false)).value(BigInteger.ONE).inlineValue(false)
                )
                .inlineValue(false);

        assertNodesEqual(expectedRaw, rawNode);
    }

    @Test
    public void blueImportsReplaceTypeAliasesAndAreRemoved() {
        String personBlueId = BlueIdCalculator.calculateBlueId(new Node().value("PersonType"));
        String keyBlueId = BlueIdCalculator.calculateBlueId(new Node().value("KeyType"));
        String valueBlueId = BlueIdCalculator.calculateBlueId(new Node().value("ValueType"));
        String doc = "blue:\n" +
                     "  imports:\n" +
                     "    Person:\n" +
                     "      blueId: " + personBlueId + "\n" +
                     "    Key:\n" +
                     "      blueId: " + keyBlueId + "\n" +
                     "    Value:\n" +
                     "      blueId: " + valueBlueId + "\n" +
                     "person:\n" +
                     "  type: Person\n" +
                     "people:\n" +
                     "  type: List\n" +
                     "  itemType: Person\n" +
                     "dict:\n" +
                     "  type: Dictionary\n" +
                     "  keyType: Key\n" +
                     "  valueType: Value";

        Node node = new Blue().yamlToNode(doc);

        assertNull(node.getBlue());
        assertEquals(personBlueId, node.getAsText("/person/type/blueId"));
        assertEquals(personBlueId, node.getAsText("/people/itemType/blueId"));
        assertEquals(keyBlueId, node.getAsText("/dict/keyType/blueId"));
        assertEquals(valueBlueId, node.getAsText("/dict/valueType/blueId"));
    }

    @Test
    public void blueImportsRejectInvalidShapes() {
        String personBlueId = BlueIdCalculator.calculateBlueId(new Node().value("PersonType"));

        assertThrows(RuntimeException.class, () -> new Blue().yamlToNode(
                "blue:\n" +
                "  imports:\n" +
                "    Person:\n" +
                "      value: x\n" +
                "x:\n" +
                "  type: Person"));

        assertThrows(RuntimeException.class, () -> new Blue().yamlToNode(
                "blue:\n" +
                "  imports:\n" +
                "    Text:\n" +
                "      blueId: " + personBlueId + "\n" +
                "x:\n" +
                "  type: Text"));

        assertThrows(RuntimeException.class, () -> new Blue().yamlToNode(
                "blue:\n" +
                "  imports:\n" +
                "    Person:\n" +
                "      blueId: " + personBlueId + "\n" +
                "    Person:\n" +
                "      blueId: " + personBlueId + "\n" +
                "x:\n" +
                "  type: Person"));

        assertThrows(RuntimeException.class, () -> new Blue().yamlToNode(
                "blue:\n" +
                "  imports:\n" +
                "    Person:\n" +
                "      blueId: not-a-real-blueid\n" +
                "x:\n" +
                "  type: Person"));

        assertThrows(RuntimeException.class, () -> new Blue().yamlToNode(
                "blue:\n" +
                "  imports:\n" +
                "    Person:\n" +
                "      blueId: this#0\n" +
                "x:\n" +
                "  type: Person"));

        assertThrows(RuntimeException.class, () -> new Blue().yamlToNode(
                "blue:\n" +
                "  imports:\n" +
                "    Person:\n" +
                "      blueId: " + personBlueId + "#0\n" +
                "x:\n" +
                "  type: Person"));
    }

    @Test
    public void blueImportsDoNotDropOtherBlueTransforms() {
        String personBlueId = BlueIdCalculator.calculateBlueId(new Node().value("PersonType"));
        String doc = "blue:\n" +
                     "  imports:\n" +
                     "    Person:\n" +
                     "      blueId: " + personBlueId + "\n" +
                     "  items:\n" +
                     "    - name: MyTestTransformation\n" +
                     "x:\n" +
                     "  type: Person\n" +
                     "y: ABC";
        Node node = YAML_MAPPER.readValue(doc, Node.class);

        TransformationProcessor changeABCtoXYZ = document -> NodeTransformer.transform(document, docNode -> {
            Node result = docNode.clone();
            if ("ABC".equals(docNode.getValue())) {
                result.value("XYZ");
            }
            return result;
        });
        TransformationProcessorProvider provider = transformation -> {
            if ("MyTestTransformation".equals(transformation.getName())) {
                return Optional.of(changeABCtoXYZ);
            }
            return Optional.empty();
        };

        Node result = new Preprocessor(provider, BootstrapProvider.INSTANCE).preprocess(node);

        assertEquals(personBlueId, result.getAsText("/x/type/blueId"));
        assertEquals("XYZ", result.getAsText("/y/value"));
        assertNull(result.getBlue());
    }

    private void assertNodesEqual(Node expected, Node actual) {
        assertEquals(expected.isInlineValue(), actual.isInlineValue());
        assertEquals(expected.getValue(), actual.getValue());

        if (expected.getType() != null) {
            assertNotNull(actual.getType());
            assertNodesEqual(expected.getType(), actual.getType());
        } else {
            assertNull(actual.getType());
        }

        if (expected.getProperties() != null) {
            assertNotNull(actual.getProperties());
            assertEquals(expected.getProperties().size(), actual.getProperties().size());
            for (Map.Entry<String, Node> entry : expected.getProperties().entrySet()) {
                assertTrue(actual.getProperties().containsKey(entry.getKey()));
                assertNodesEqual(entry.getValue(), actual.getProperties().get(entry.getKey()));
            }
        } else {
            assertNull(actual.getProperties());
        }

        assertEquals(expected.getBlueId(), actual.getBlueId());
    }

}
