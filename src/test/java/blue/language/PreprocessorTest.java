package blue.language;

import blue.language.model.Node;
import blue.language.preprocess.Preprocessor;
import blue.language.preprocess.TransformationProcessor;
import blue.language.preprocess.TransformationProcessorProvider;
import blue.language.provider.BootstrapProvider;
import blue.language.utils.NodeTransformer;
import blue.language.utils.Properties;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static blue.language.utils.Properties.CORE_TYPE_BLUE_ID_TO_NAME_MAP;
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
                     "  type: 84ZWw2aoqB6dWRM6N1qWwgcXGrjfeKexTNdWxxAEcECH\n" +
                     "d:\n" +
                     "  type:\n" +
                     "    blueId: 84ZWw2aoqB6dWRM6N1qWwgcXGrjfeKexTNdWxxAEcECH";

        Blue blue = new Blue();
        Node node = blue.preprocess(blue.yamlToNode(doc));

        assertEquals(CORE_TYPE_BLUE_ID_TO_NAME_MAP.get("Integer"), node.getProperties().get("a").getType().getName());
        assertEquals("Integer", node.getProperties().get("b").getType().getValue());
        assertEquals("84ZWw2aoqB6dWRM6N1qWwgcXGrjfeKexTNdWxxAEcECH", node.getProperties().get("c").getType().getBlueId());
        assertEquals("84ZWw2aoqB6dWRM6N1qWwgcXGrjfeKexTNdWxxAEcECH", node.getProperties().get("d").getType().getBlueId());

        assertFalse(node.getProperties().get("a").getType().isInlineValue());
        assertFalse(node.getProperties().get("b").getType().isInlineValue());
        assertTrue(node.getProperties().get("c").getType().isInlineValue());
        assertFalse(node.getProperties().get("d").getType().isInlineValue());
    }

    @Test
    public void testItemsAsBlueId() throws Exception {
        String doc = "name: Abc\n" +
                     "items: 84ZWw2aoqB6dWRM6N1qWwgcXGrjfeKexTNdWxxAEcECH";

        Blue blue = new Blue();
        Node node = blue.preprocess(blue.yamlToNode(doc));
        assertEquals("84ZWw2aoqB6dWRM6N1qWwgcXGrjfeKexTNdWxxAEcECH", node.getItems().get(0).getBlueId());
    }

    @Test
    public void testReplaceValuesMatchingBlueIdWithBlueId() throws Exception {
        String doc = "implicit: 84ZWw2aoqB6dWRM6N1qWwgcXGrjfeKexTNdWxxAEcECH\n" +
                     "explicit:\n" +
                     "  value: 84ZWw2aoqB6dWRM6N1qWwgcXGrjfeKexTNdWxxAEcECH\n" +
                     "nonBlueId: regularValue";

        Blue blue = new Blue();
        Node node = blue.preprocess(blue.yamlToNode(doc));

        Node implicitNode = node.getProperties().get("implicit");
        assertNull(implicitNode.getValue());
        assertEquals("84ZWw2aoqB6dWRM6N1qWwgcXGrjfeKexTNdWxxAEcECH", implicitNode.getBlueId());

        Node explicitNode = node.getProperties().get("explicit");
        assertEquals("84ZWw2aoqB6dWRM6N1qWwgcXGrjfeKexTNdWxxAEcECH", explicitNode.getValue());
        assertNull(explicitNode.getBlueId());
        assertFalse(explicitNode.isInlineValue());

        Node nonBlueIdNode = node.getProperties().get("nonBlueId");
        assertEquals("regularValue", nonBlueIdNode.getValue());
        assertNull(nonBlueIdNode.getBlueId());
        assertTrue(nonBlueIdNode.isInlineValue());
    }

    @Test
    public void testPreprocessWithCustomBlueExtendingDefaultBlue() throws Exception {
        String doc = "blue:\n" +
                     "  - blueId:\n" +
                     "      D5HTFSjSg2roM6mRnFkQqcQoA8YDeKWqcAH9sd4aBBnB\n" +
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

}
