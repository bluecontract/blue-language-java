package blue.language.utils;

import blue.language.Blue;
import blue.language.model.Node;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static blue.language.utils.UncheckedObjectMapper.YAML_MAPPER;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class NodeTypeMatcherTest {

    @Test
    public void testBasic() throws Exception {

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
        String bId = BlueIdCalculator.calculateBlueId(YAML_MAPPER.readValue(b, Node.class));

        String c = "name: C";

        String bInst = "name: B Instance\n" +
                   "type:\n" +
                   "  blueId: " + bId + "\n" +
                   "x: ABC";

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
                         "  blueId: " + BlueIdCalculator.calculateBlueId(YAML_MAPPER.readValue(c, Node.class)) + "\n" +
                         "x: ABC";

        String typeFail4 = "type:\n" +
                         "  blueId: " + bId + "\n" +
                         "x: ABC\n" +
                         "y: d";

        Map<String, Node> nodes = Stream.of(a, b, bInst, c)
                .map(doc -> YAML_MAPPER.readValue(doc, Node.class))
                .collect(Collectors.toMap(Node::getName, node -> node));
        BasicNodesProvider nodeProvider = new BasicNodesProvider(nodes.values());
        Blue blue = new Blue(nodeProvider);

        Node aInstNode = nodes.get("B Instance");
        assertTrue(blue.nodeMatchesType(aInstNode, blue.yamlToNode(typeOK1)));
        assertTrue(blue.nodeMatchesType(aInstNode, blue.yamlToNode(typeOK2)));
        assertFalse(blue.nodeMatchesType(aInstNode, blue.yamlToNode(typeFail1)));
        assertFalse(blue.nodeMatchesType(aInstNode, blue.yamlToNode(typeFail2)));
        assertFalse(blue.nodeMatchesType(aInstNode, blue.yamlToNode(typeFail3)));
        assertFalse(blue.nodeMatchesType(aInstNode, blue.yamlToNode(typeFail4)));

    }

}
