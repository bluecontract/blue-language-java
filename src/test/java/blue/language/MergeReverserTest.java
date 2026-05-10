package blue.language;

import blue.language.model.Node;
import blue.language.provider.BasicNodeProvider;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.MergeReverser;
import blue.language.utils.Properties;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

public class MergeReverserTest {

    @Test
    public void testBasic1() throws Exception {

        BasicNodeProvider nodeProvider = new BasicNodeProvider();

        String a = "name: A\n" +
                   "description: Xyz\n" +
                   "x: 1\n" +
                   "y:\n" +
                   "  type: Integer\n" +
                   "z:\n" +
                   "  type: List";
        nodeProvider.addSingleDocs(a);

        String b = "name: B\n" +
                   "type:\n" +
                   "  blueId: " + nodeProvider.getBlueIdByName("A") + "\n" +
                   "x: 1\n" +
                   "y: 2\n" +
                   "z:\n" +
                   "  type: List\n" +
                   "  itemType: Text\n" +
                   "  items:\n" +
                   "    - A\n" +
                   "    - B";
        nodeProvider.addSingleDocs(b);

        Node bNode = nodeProvider.getNodeByName("B");

        Blue blue = new Blue(nodeProvider);
        Node resolved = blue.resolve(bNode);

        MergeReverser reverser = new MergeReverser();
        Node reversed = reverser.reverse(resolved);

        assertFalse(reversed.getProperties().containsKey("x"));
        assertEquals(2, reversed.getAsInteger("/y/value"));
        assertEquals(Properties.LIST_TYPE_BLUE_ID, reversed.getAsText("/z/type/blueId"));
        assertEquals(Properties.TEXT_TYPE_BLUE_ID, reversed.getAsText("/z/itemType/blueId"));
    }

    @Test
    public void testNestedTypes() throws Exception {
        BasicNodeProvider nodeProvider = new BasicNodeProvider();

        String a = "name: A\n" +
                   "x: 5\n" +
                   "y: 10";
        nodeProvider.addSingleDocs(a);

        String b = "name: B\n" +
                   "type:\n" +
                   "  blueId: " + nodeProvider.getBlueIdByName("A") + "\n" +
                   "z: 15";
        nodeProvider.addSingleDocs(b);

        String c = "name: C\n" +
                   "type:\n" +
                   "  blueId: " + nodeProvider.getBlueIdByName("B") + "\n" +
                   "w: 20";
        nodeProvider.addSingleDocs(c);

        Node cNode = nodeProvider.getNodeByName("C");
        Blue blue = new Blue(nodeProvider);
        Node resolved = blue.resolve(cNode);

        MergeReverser reverser = new MergeReverser();
        Node reversed = reverser.reverse(resolved);

        assertEquals("C", reversed.getName());
        assertEquals(nodeProvider.getBlueIdByName("B"), reversed.getType().getBlueId());
        assertEquals(20, reversed.getAsInteger("/w/value"));
        assertFalse(reversed.getProperties().containsKey("x"));
        assertFalse(reversed.getProperties().containsKey("y"));
        assertFalse(reversed.getProperties().containsKey("z"));

        assertEquals(nodeProvider.getBlueIdByName("C"), BlueIdCalculator.calculateBlueId(reversed));
    }

    @Test
    public void testComplexNestedProperties() throws Exception {
        BasicNodeProvider nodeProvider = new BasicNodeProvider();

        String m = "name: M\n" +
                   "a:\n" +
                   "  b:\n" +
                   "    c:\n" +
                   "      d1: 1";
        nodeProvider.addSingleDocs(m);

        String n = "name: N\n" +
                   "c:\n" +
                   "  d2: 1";
        nodeProvider.addSingleDocs(n);

        String p = "name: P\n" +
                   "type:\n" +
                   "  blueId: " + nodeProvider.getBlueIdByName("M") + "\n" +
                   "a:\n" +
                   "  b:\n" +
                   "    type:\n" +
                   "      blueId: " + nodeProvider.getBlueIdByName("N") + "\n" +
                   "    c:\n" +
                   "      d3: 3";
        nodeProvider.addSingleDocs(p);

        Node pNode = nodeProvider.getNodeByName("P");
        Blue blue = new Blue(nodeProvider);
        Node resolved = blue.resolve(pNode);
        assertEquals(1, resolved.getAsInteger("/a/b/c/d1/value"));
        assertEquals(1, resolved.getAsInteger("/a/b/c/d2/value"));
        assertEquals(3, resolved.getAsInteger("/a/b/c/d3/value"));

        MergeReverser reverser = new MergeReverser();
        Node reversed = reverser.reverse(resolved);

        assertEquals("P", reversed.getName());
        assertEquals(nodeProvider.getBlueIdByName("M"), reversed.getType().getBlueId());
        assertEquals(nodeProvider.getBlueIdByName("N"), reversed.getAsNode("/a/b/type").getBlueId());
        assertEquals(3, reversed.getAsInteger("/a/b/c/d3/value"));
        assertFalse(reversed.getProperties().containsKey("d1"));
        assertFalse(reversed.getAsNode("/a/b").getProperties().containsKey("d2"));
    }

    @Test
    public void testInheritedListAndMap() throws Exception {
        BasicNodeProvider nodeProvider = new BasicNodeProvider();

        String base = "name: Base\n" +
                      "list:\n" +
                      "  - A\n" +
                      "  - B\n" +
                      "map:\n" +
                      "  key1: value1\n" +
                      "  key2: value2";
        nodeProvider.addSingleDocs(base);

        String derived = "name: Derived\n" +
                         "type:\n" +
                         "  blueId: " + nodeProvider.getBlueIdByName("Base") + "\n" +
                         "list:\n" +
                         "  - A\n" +
                         "  - B\n" +
                         "  - C\n" +
                         "map:\n" +
                         "  key3: value3";
        nodeProvider.addSingleDocs(derived);

        Node derivedNode = nodeProvider.getNodeByName("Derived");
        Blue blue = new Blue(nodeProvider);
        Node resolved = blue.resolve(derivedNode);

        MergeReverser reverser = new MergeReverser();
        Node reversed = reverser.reverse(resolved);

        assertEquals("Derived", reversed.getName());
        assertEquals(nodeProvider.getBlueIdByName("Base"), reversed.getType().getBlueId());
        assertEquals(2, reversed.getAsNode("/list").getItems().size());
        assertEquals(BlueIdCalculator.calculateBlueId(
                Arrays.asList(
                        blue.yamlToNode("value: A\ntype: Text"),
                        blue.yamlToNode("value: B\ntype: Text")
                )
        ), reversed.getAsNode("/list").getItems().get(0).getPreviousBlueId());
        assertEquals("C", reversed.getAsNode("/list").getItems().get(1).getValue());
        assertEquals(1, reversed.getAsNode("/map").getProperties().size());
        assertEquals("value3", reversed.getAsText("/map/key3/value"));
    }

    @Test
    public void omitsUnchangedInheritedListDuringReverseMinimization() throws Exception {
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs(
                "name: Base\n" +
                "list:\n" +
                "  type: List\n" +
                "  items:\n" +
                "    - A\n" +
                "    - B");
        nodeProvider.addSingleDocs(
                "name: Derived\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Base"));

        Node resolved = new Blue(nodeProvider).resolve(nodeProvider.getNodeByName("Derived"));
        Node reversed = new MergeReverser().reverse(resolved);

        assertTrue(reversed.getProperties() == null || !reversed.getProperties().containsKey("list"));
    }

    @Test
    public void preservesInheritedListPositionalReplacementDuringReverseMinimization() throws Exception {
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs(
                "name: Base\n" +
                "list:\n" +
                "  type: List\n" +
                "  items:\n" +
                "    - A\n" +
                "    - B");
        Blue blue = new Blue(nodeProvider);
        Node inheritedList = blue.resolve(nodeProvider.getNodeByName("Base")).getAsNode("/list");
        String previousBlueId = BlueIdCalculator.calculateBlueId(inheritedList.getItems());
        nodeProvider.addListAndItsItems(inheritedList.getItems());
        nodeProvider.addSingleDocs(
                "name: Derived\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Base") + "\n" +
                "list:\n" +
                "  type: List\n" +
                "  items:\n" +
                "    - $previous:\n" +
                "        blueId: " + previousBlueId + "\n" +
                "    - $pos: 1\n" +
                "      value: C");

        Node resolved = blue.resolve(nodeProvider.getNodeByName("Derived"));
        Node reversed = new MergeReverser().reverse(resolved);
        Node reversedList = reversed.getAsNode("/list");

        assertEquals(2, reversedList.getItems().size());
        assertEquals(previousBlueId, reversedList.getItems().get(0).getPreviousBlueId());
        assertEquals(Integer.valueOf(1), reversedList.getItems().get(1).getPosition());
        assertEquals("C", reversedList.getItems().get(1).getValue());
        assertEquals("C", blue.resolve(reversed).getAsNode("/list").getItems().get(1).getValue());
    }

    @Test
    public void preservesMultipleInheritedListReplacementsAndAppendsDuringReverseMinimization() throws Exception {
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs(
                "name: Base\n" +
                "list:\n" +
                "  type: List\n" +
                "  items:\n" +
                "    - A\n" +
                "    - B\n" +
                "    - C");
        Blue blue = new Blue(nodeProvider);
        Node inheritedList = blue.resolve(nodeProvider.getNodeByName("Base")).getAsNode("/list");
        String previousBlueId = BlueIdCalculator.calculateBlueId(inheritedList.getItems());
        nodeProvider.addListAndItsItems(inheritedList.getItems());
        nodeProvider.addSingleDocs(
                "name: Derived\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Base") + "\n" +
                "list:\n" +
                "  type: List\n" +
                "  items:\n" +
                "    - $previous:\n" +
                "        blueId: " + previousBlueId + "\n" +
                "    - $pos: 0\n" +
                "      value: X\n" +
                "    - $pos: 2\n" +
                "      value: Z\n" +
                "    - D");

        Node reversed = new MergeReverser().reverse(blue.resolve(nodeProvider.getNodeByName("Derived")));
        Node reversedList = reversed.getAsNode("/list");

        assertEquals(4, reversedList.getItems().size());
        assertEquals(previousBlueId, reversedList.getItems().get(0).getPreviousBlueId());
        assertEquals(Integer.valueOf(0), reversedList.getItems().get(1).getPosition());
        assertEquals("X", reversedList.getItems().get(1).getValue());
        assertEquals(Integer.valueOf(2), reversedList.getItems().get(2).getPosition());
        assertEquals("Z", reversedList.getItems().get(2).getValue());
        assertEquals("D", reversedList.getItems().get(3).getValue());

        Node roundTripped = blue.resolve(reversed);
        assertEquals(Arrays.asList("X", "B", "Z", "D"), Arrays.asList(
                roundTripped.getAsNode("/list").getItems().get(0).getValue(),
                roundTripped.getAsNode("/list").getItems().get(1).getValue(),
                roundTripped.getAsNode("/list").getItems().get(2).getValue(),
                roundTripped.getAsNode("/list").getItems().get(3).getValue()));
    }

    @Test
    public void preservesNestedInheritedListItemOverlayDuringReverseMinimization() throws Exception {
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs(
                "name: Base\n" +
                "list:\n" +
                "  type: List\n" +
                "  items:\n" +
                "    - name: first\n" +
                "      details:\n" +
                "        size: M\n" +
                "    - name: second");
        Blue blue = new Blue(nodeProvider);
        Node inheritedList = blue.resolve(nodeProvider.getNodeByName("Base")).getAsNode("/list");
        String previousBlueId = BlueIdCalculator.calculateBlueId(inheritedList.getItems());
        nodeProvider.addListAndItsItems(inheritedList.getItems());
        nodeProvider.addSingleDocs(
                "name: Derived\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Base") + "\n" +
                "list:\n" +
                "  type: List\n" +
                "  items:\n" +
                "    - $previous:\n" +
                "        blueId: " + previousBlueId + "\n" +
                "    - $pos: 0\n" +
                "      details:\n" +
                "        color: red");

        Node reversed = new MergeReverser().reverse(blue.resolve(nodeProvider.getNodeByName("Derived")));
        Node overlay = reversed.getAsNode("/list").getItems().get(1);

        assertEquals(Integer.valueOf(0), overlay.getPosition());
        assertEquals("red", overlay.getAsText("/details/color/value"));
        assertFalse(overlay.getProperties().containsKey("name"));
        assertFalse(overlay.getAsNode("/details").getProperties().containsKey("size"));
        assertEquals("red", blue.resolve(reversed).getAsText("/list/0/details/color/value"));
        assertEquals("M", blue.resolve(reversed).getAsText("/list/0/details/size/value"));
    }

    @Test
    public void preservesReplacementOfInheritedEmptyListPlaceholder() throws Exception {
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs(
                "name: Base\n" +
                "list:\n" +
                "  type: List\n" +
                "  items:\n" +
                "    - $empty: true\n" +
                "    - B");
        Blue blue = new Blue(nodeProvider);
        Node inheritedList = blue.resolve(nodeProvider.getNodeByName("Base")).getAsNode("/list");
        String previousBlueId = BlueIdCalculator.calculateBlueId(inheritedList.getItems());
        nodeProvider.addListAndItsItems(inheritedList.getItems());
        nodeProvider.addSingleDocs(
                "name: Derived\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Base") + "\n" +
                "list:\n" +
                "  type: List\n" +
                "  items:\n" +
                "    - $previous:\n" +
                "        blueId: " + previousBlueId + "\n" +
                "    - $pos: 0\n" +
                "      value: A");

        Node reversed = new MergeReverser().reverse(blue.resolve(nodeProvider.getNodeByName("Derived")));
        Node overlay = reversed.getAsNode("/list").getItems().get(1);

        assertEquals(Integer.valueOf(0), overlay.getPosition());
        assertEquals("A", overlay.getValue());
        assertEquals("A", blue.resolve(reversed).getAsNode("/list").getItems().get(0).getValue());
    }

    @Test
    public void preservesScalarOverrideThatDiffersFromType() throws Exception {
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs(
                "name: Base\n" +
                "status: draft");
        Node resolved = new Blue(nodeProvider).yamlToNode(
                "name: Derived\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Base") + "\n" +
                "status: draft");
        resolved = new Blue(nodeProvider).resolve(resolved);
        resolved.getProperties().get("status").value("published");
        Node reversed = new MergeReverser().reverse(resolved);

        assertEquals("published", reversed.getAsText("/status/value"));
    }

    @Test
    public void preservesSchemaOverrideThatDiffersFromType() throws Exception {
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs(
                "name: Base\n" +
                "schema:\n" +
                "  minLength: 2");
        nodeProvider.addSingleDocs(
                "name: Derived\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Base") + "\n" +
                "schema:\n" +
                "  minLength: 3");

        Node resolved = new Blue(nodeProvider).resolve(nodeProvider.getNodeByName("Derived"));
        Node reversed = new MergeReverser().reverse(resolved);

        assertNotNull(reversed.getSchema());
        assertEquals(3, reversed.getSchema().getMinLengthValue());
    }

}
