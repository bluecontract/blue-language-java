package blue.language.utils;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.provider.BasicNodeProvider;
import java.util.*;
import org.junit.jupiter.api.Test;

import static blue.language.utils.UncheckedObjectMapper.YAML_MAPPER;
import static blue.language.utils.Properties.DICTIONARY_TYPE_BLUE_ID;
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

        String typeOK2 = "x: ABC";

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
//        assertFalse(blue.nodeMatchesType(bInstNode, blue.yamlToNode(typeFail2)));
        assertFalse(blue.nodeMatchesType(bInstNode, blue.yamlToNode(typeFail3)));
        assertFalse(blue.nodeMatchesType(bInstNode, blue.yamlToNode(typeFail4)));

    }

    @Test
    public void testBasicTypeValueShapeNoConstraints() throws Exception {

        BasicNodeProvider nodeProvider = new BasicNodeProvider();

        String a = "name: A\n" +
                   "value: AAA";

        nodeProvider.addSingleDocs(a);

        String b = "name: B\n" +
                   "x:\n" +
                   "  type:\n" +
                   "    blueId: " + nodeProvider.getBlueIdByName("A");

        nodeProvider.addSingleDocs(b);

        String c = "name: C";
        nodeProvider.addSingleDocs(c);

        String bId = nodeProvider.getBlueIdByName("B");
        String cId = nodeProvider.getBlueIdByName("C");

        String bInst = "name: B Instance\n" +
                       "type:\n" +
                       "  blueId: " + bId + "\n" +
                       "x: AAA";
        nodeProvider.addSingleDocs(bInst);

        String typeOK1 = "x:\n" +
                         "  type:\n" +
                         "    blueId: " + nodeProvider.getBlueIdByName("A");

        String typeOK2 = "x: AAA";

        String typeFailWrongType = "x:\n" +
                                   "  type:\n" +
                                   "    name: C";

        String typeFailWrongBlue = "type:\n" +
                                   "  blueId: " + cId + "\n" +
                                   "x: AAA";

        String typeFailExtraProp = "type:\n" +
                                   "  blueId: " + bId + "\n" +
                                   "x: AAA\n" +
                                   "y: d";

        Blue blue = new Blue(nodeProvider);
        Node bInstNode = nodeProvider.getNodeByName("B Instance");

        assertTrue(blue.nodeMatchesType(bInstNode, blue.yamlToNode(typeOK1)));
        assertTrue(blue.nodeMatchesType(bInstNode, blue.yamlToNode(typeOK2)));
        assertFalse(blue.nodeMatchesType(bInstNode, blue.yamlToNode(typeFailWrongType)));
        assertFalse(blue.nodeMatchesType(bInstNode, blue.yamlToNode(typeFailWrongBlue)));
        assertFalse(blue.nodeMatchesType(bInstNode, blue.yamlToNode(typeFailExtraProp)));
    }

    @Test
    public void testNestedShapesListsAndProperties() throws Exception {
        BasicNodeProvider nodeProvider = new BasicNodeProvider();

        String item = "name: Item\n" +
                      "value: 1";

        nodeProvider.addSingleDocs(item);
        String itemId = nodeProvider.getBlueIdByName("Item");

        String item2 = "name: Item2\n" +
                       "value: 2";
        nodeProvider.addSingleDocs(item2);
        String item2Id = nodeProvider.getBlueIdByName("Item2");

        String listOwnerDoc = "name: ListOwner\n" +
                              "items:\n" +
                              "  - blueId: " + itemId;
        nodeProvider.addSingleDocs(listOwnerDoc);

        String container = "name: Container\n" +
                           "list:\n" +
                           "  blueId: " + nodeProvider.getBlueIdByName("ListOwner");
        nodeProvider.addSingleDocs(container);

        Blue blue = new Blue(nodeProvider);

        Node containerInst = nodeProvider.getNodeByName("Container");

        String okNestedShape = "list:\n" +
                               "  items:\n" +
                               "    - blueId: " + itemId;
        assertTrue(blue.nodeMatchesType(containerInst, blue.yamlToNode(okNestedShape)));

        String failNestedValue = "list:\n" +
                                 "  items:\n" +
                                 "    - blueId: " + item2Id;
        assertFalse(blue.nodeMatchesType(containerInst, blue.yamlToNode(failNestedValue)));

        String failExtra = "list:\n" +
                           "  items:\n" +
                           "    - value: 1\n" +
                           "      extra: something";
        assertFalse(blue.nodeMatchesType(containerInst, blue.yamlToNode(failExtra)));
    }

    @Test
    public void testBlueIdExactMatchesWhenRequested() throws Exception {
        BasicNodeProvider nodeProvider = new BasicNodeProvider();

        String alpha = "name: Alpha";
        String beta = "name: Beta";
        nodeProvider.addSingleDocs(alpha, beta);

        String alphaId = nodeProvider.getBlueIdByName("Alpha");
        String betaId = nodeProvider.getBlueIdByName("Beta");

        String container = "name: Holder\n" +
                           "x:\n" +
                           "  blueId: " + alphaId;
        nodeProvider.addSingleDocs(container);

        Blue blue = new Blue(nodeProvider);
        Node node = nodeProvider.getNodeByName("Holder");

        String ok = "x:\n" +
                    "  blueId: " + alphaId;
        assertTrue(blue.nodeMatchesType(node, blue.yamlToNode(ok)));

        String fail = "x:\n" +
                      "  blueId: " + betaId;
        assertFalse(blue.nodeMatchesType(node, blue.yamlToNode(fail)));
    }

    @Test
    public void testListItemTypeEnforcesItemShape() throws Exception {
        BasicNodeProvider nodeProvider = new BasicNodeProvider();

        String allowedItem = "name: Allowed Item\n" +
                             "value: ok";
        String forbiddenItem = "name: Forbidden Item\n" +
                               "value: not-ok";
        nodeProvider.addSingleDocs(allowedItem, forbiddenItem);

        String allowedItemId = nodeProvider.getBlueIdByName("Allowed Item");
        String forbiddenItemId = nodeProvider.getBlueIdByName("Forbidden Item");

        String listWithAllowed = "name: Allowed Container\n" +
                                 "itemsList:\n" +
                                 "  type: List\n" +
                                 "  items:\n" +
                                 "    - blueId: " + allowedItemId + "\n";

        String listWithForbidden = "name: Forbidden Container\n" +
                                   "itemsList:\n" +
                                   "  type: List\n" +
                                   "  items:\n" +
                                   "    - blueId: " + forbiddenItemId + "\n";

        nodeProvider.addSingleDocs(listWithAllowed, listWithForbidden);

        Blue blue = new Blue(nodeProvider);

        String targetTypeYaml = "itemsList:\n" +
                                "  type: List\n" +
                                "  itemType:\n" +
                                "    blueId: " + allowedItemId;

        Node targetType = blue.yamlToNode(targetTypeYaml);

        assertTrue(blue.nodeMatchesType(nodeProvider.getNodeByName("Allowed Container"), targetType));
        assertFalse(blue.nodeMatchesType(nodeProvider.getNodeByName("Forbidden Container"), targetType));
    }

    @Test
    public void testImplicitListDictionaryStructuralMatching() throws Exception {
        BasicNodeProvider nodeProvider = new BasicNodeProvider();

        String implicitListDoc = "name: ImplicitListNode\n" +
                                 "items:\n" +
                                 "  - value: 1\n" +
                                 "  - value: 2";
        nodeProvider.addSingleDocs(implicitListDoc);

        String implicitDictDoc = "name: ImplicitDictNode\n" +
                                 "a:\n" +
                                 "  value: 1\n" +
                                 "b:\n" +
                                 "  value: 2";
        nodeProvider.addSingleDocs(implicitDictDoc);

        Blue blue = new Blue(nodeProvider);

        Node implicitListNode = nodeProvider.getNodeByName("ImplicitListNode");
        Node implicitDictNode = nodeProvider.getNodeByName("ImplicitDictNode");

        String listTypeOnly = "type: List";
        String dictTypeOnly = "type: Dictionary";

        assertTrue(blue.nodeMatchesType(implicitListNode, blue.yamlToNode(listTypeOnly)));
        assertTrue(blue.nodeMatchesType(implicitDictNode, blue.yamlToNode(dictTypeOnly)));

        assertFalse(blue.nodeMatchesType(implicitListNode, blue.yamlToNode(dictTypeOnly)));
        assertFalse(blue.nodeMatchesType(implicitDictNode, blue.yamlToNode(listTypeOnly)));

        String explicitTextButHasItems = "type: Text\n" +
                                         "items:\n" +
                                         "  - value: 1";
        assertFalse(blue.nodeMatchesType(implicitListNode, blue.yamlToNode(explicitTextButHasItems)));
    }

    @Test
    public void testEventPayloadsYamlImplicitVsExplicitList() throws Exception {
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        Blue blue = new Blue(nodeProvider);

        String targetTypeYaml = "message:\n" +
                                "  request:\n" +
                                "    type: List";
        Node targetType = blue.yamlToNode(targetTypeYaml);

        String explicitListEventYaml = "message:\n" +
                                       "  request:\n" +
                                       "    type: List\n" +
                                       "    items:\n" +
                                       "      - 1\n" +
                                       "      - 2\n" +
                                       "      - 3";
        Node explicitEventNode = blue.yamlToNode(explicitListEventYaml);
        assertTrue(blue.nodeMatchesType(explicitEventNode, targetType));

        String implicitArrayEventYaml = "message:\n" +
                                        "  request:\n" +
                                        "    - 1\n" +
                                        "    - 2\n" +
                                        "    - 3";
        Node implicitEventNode = blue.yamlToNode(implicitArrayEventYaml);
        assertTrue(blue.nodeMatchesType(implicitEventNode, targetType));

        String wrongShapeEventYaml = "message:\n" +
                                     "  request:\n" +
                                     "    a: 1\n" +
                                     "    b: 2";
        Node wrongEventNode = blue.yamlToNode(wrongShapeEventYaml);
        assertFalse(blue.nodeMatchesType(wrongEventNode, targetType));
    }

    @Test
    public void testDictionaryValueTypeEnforcesPropertyTypes() throws Exception {
        BasicNodeProvider nodeProvider = new BasicNodeProvider();

        String activationState = "name: Activation State\n" +
                                 "value: active";
        String wrongState = "name: Wrong State\n" +
                            "value: wrong";
        nodeProvider.addSingleDocs(activationState, wrongState);

        String activationStateId = nodeProvider.getBlueIdByName("Activation State");
        String wrongStateId = nodeProvider.getBlueIdByName("Wrong State");

        Blue blue = new Blue(nodeProvider);

        String targetTypeYaml = "participantsState:\n" +
                                "  type: Dictionary\n" +
                                "  valueType:\n" +
                                "    blueId: " + activationStateId;
        Node targetType = blue.yamlToNode(targetTypeYaml);

        java.util.function.Function<String, Node> createNodeWithValueBlueId = (valueBlueId) ->
                new Node().name("Container").properties("participantsState",
                        new Node().type(new Node().blueId(DICTIONARY_TYPE_BLUE_ID))
                                .properties("alice",
                                        new Node().blueId(valueBlueId).type(new Node().blueId(valueBlueId))
                                )
                );

        Node matchingNode = createNodeWithValueBlueId.apply(activationStateId);
        assertTrue(blue.nodeMatchesType(matchingNode, targetType));

        Node mismatchedNode = createNodeWithValueBlueId.apply(wrongStateId);
        assertFalse(blue.nodeMatchesType(mismatchedNode, targetType));
    }
}