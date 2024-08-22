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

    @Test
    public void testNodeTransformer() throws Exception {
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        Blue blue = new Blue(nodeProvider);

        String personType = "name: Person\n" +
                            "age:\n" +
                            "  type: Integer\n" +
                            "secretCode:\n" +
                            "  type: Text";

        nodeProvider.addSingleDocs(personType);
        String personTypeId = nodeProvider.getBlueIdByName("Person");

        String personInstance = "name: John Doe\n" +
                                "type:\n" +
                                "  blueId: " + personTypeId + "\n" +
                                "age: 30\n" +
                                "secretCode: ABC123";

        Node personNode = blue.yamlToNode(personInstance);

        String matchTypeWithWrongSecret = "type:\n" +
                                     "  blueId: " + personTypeId + "\n" +
                                     "secretCode: ABC987";

        String matchTypeWithoutSecret = "type:\n" +
                                        "  blueId: " + personTypeId + "\n" +
                                        "age: 30";

        NodeTypeMatcher matcher = new NodeTypeMatcher(blue);

        assertFalse(matcher.matchesType(personNode, blue.yamlToNode(matchTypeWithWrongSecret)));
        assertTrue(matcher.matchesType(personNode, blue.yamlToNode(matchTypeWithoutSecret)));

        NodeTypeMatcher.TargetTypeTransformer transformer = (targetType) -> {
            if (targetType.getProperties() != null) {
                Node result = targetType.clone();
                result.getProperties().remove("secretCode");
                return result;
            }
            return targetType;
        };

        assertTrue(matcher.matchesType(personNode, blue.yamlToNode(matchTypeWithoutSecret), transformer));
        assertTrue(matcher.matchesType(personNode, blue.yamlToNode(matchTypeWithWrongSecret), transformer));
    }

}
