package blue.language.utils;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.provider.BasicNodeProvider;
import org.junit.jupiter.api.Test;

import static blue.language.utils.UncheckedObjectMapper.YAML_MAPPER;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class NodeTypeMatcherTest {

    @Test
    public void testBasic() throws Exception {

        BasicNodeProvider nodeProvider = new BasicNodeProvider();

        String a = "name: A\n" +
                   "type: Text\n" +
                   "constraints:\n" +
                   "  minLength: 3";

        String b = "name: B\n" +
                   "x:\n" +
                   "  type:\n" +
                   "    name: A\n" +
                   "    type: Text\n" +
                   "    constraints:\n" +
                   "      minLength: 3";

        String c = "name: C";

        nodeProvider.addSingleDocs(a, b, c);
        String bId = nodeProvider.getBlueIdByName("B");
        String cId = nodeProvider.getBlueIdByName("C");

        String bInst = "name: B Instance\n" +
                   "type:\n" +
                   "  blueId: " + bId + "\n" +
                   "x: ABC";
        nodeProvider.addSingleDocs(bInst);

        String typeOK1 = "x:\n" +
                   "  constraints:\n" +
                   "    minLength: 3\n" +
                   "y:\n" +
                   "  constraints:\n" +
                   "    minLength: 5";

        String typeOK2 = "type:\n" +
                         "  blueId: " + bId + "\n" +
                         "x: ABC";

        String typeFail1 = "x:\n" +
                           "  constraints:\n" +
                           "    minLength: 4";

        String typeFail2 = "x:\n" +
                        "  constraints:\n" +
                        "    minLength: 3\n" +
                        "y:\n" +
                        "  constraints:\n" +
                        "    minLength: 5\n" +
                        "    required: true";

        String typeFail3 = "type:\n" +
                         "  blueId: " + cId + "\n" +
                         "x: ABC";

        String typeFail4 = "type:\n" +
                         "  blueId: " + bId + "\n" +
                         "x: ABC\n" +
                         "y: d";

        Blue blue = new Blue(nodeProvider);
        Node bInstNode = nodeProvider.findNodeByName("B Instance").orElseThrow(() -> new IllegalArgumentException("No \"B Instance\" available."));

        assertTrue(blue.nodeMatchesType(bInstNode, blue.yamlToNode(typeOK1)));
        assertTrue(blue.nodeMatchesType(bInstNode, blue.yamlToNode(typeOK2)));
        assertFalse(blue.nodeMatchesType(bInstNode, blue.yamlToNode(typeFail1)));
        assertFalse(blue.nodeMatchesType(bInstNode, blue.yamlToNode(typeFail2)));
        assertFalse(blue.nodeMatchesType(bInstNode, blue.yamlToNode(typeFail3)));
        assertFalse(blue.nodeMatchesType(bInstNode, blue.yamlToNode(typeFail4)));

    }

}
