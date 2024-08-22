package blue.language;

import blue.language.model.Node;
import blue.language.preprocess.Preprocessor;
import blue.language.preprocess.TransformationProcessor;
import blue.language.preprocess.TransformationProcessorProvider;
import blue.language.provider.BootstrapProvider;
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
                     "    blueId: 84ZWw2aoqB6dWRM6N1qWwgcXGrjfeKexTNdWxxAEcECH";

        Blue blue = new Blue();
        Node node = blue.preprocess(blue.yamlToNode(doc));

        assertEquals(CORE_TYPE_BLUE_ID_TO_NAME_MAP.get("Integer"), node.getProperties().get("a").getType().getName());
        assertEquals("Integer", node.getProperties().get("b").getType().getValue());
        assertEquals("84ZWw2aoqB6dWRM6N1qWwgcXGrjfeKexTNdWxxAEcECH", node.getProperties().get("c").getType().getBlueId());

        assertFalse(node.getProperties().get("a").getType().isInlineValue());
        assertFalse(node.getProperties().get("b").getType().isInlineValue());
        assertFalse(node.getProperties().get("c").getType().isInlineValue());
    }

    @Test
    public void testItemsAsBlueId() throws Exception {
        String doc = "name: Abc\n" +
                     "items:\n" +
                     "  blueId: 84ZWw2aoqB6dWRM6N1qWwgcXGrjfeKexTNdWxxAEcECH";

        Blue blue = new Blue();
        Node node = blue.preprocess(blue.yamlToNode(doc));
        assertEquals("84ZWw2aoqB6dWRM6N1qWwgcXGrjfeKexTNdWxxAEcECH", node.getItems().get(0).getBlueId());
    }

    @Test
    public void testPreprocessWithCustomBlueExtendingDefaultBlue() throws Exception {
        String doc = "blue:\n" +
                     "  - blueId:\n" +
                     "      " + DEFAULT_BLUE_BLUE_ID + "\n" +
                     "  - name: MyTestTransformation\n" +
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
