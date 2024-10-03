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
        assertNull(reversed.get("/z/type"));
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

        assertEquals(BlueIdCalculator.calculateBlueId(cNode), BlueIdCalculator.calculateBlueId(reversed));
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
        ), reversed.getAsNode("/list").getItems().get(0).getBlueId());
        assertEquals("C", reversed.getAsNode("/list").getItems().get(1).getValue());
        assertEquals(1, reversed.getAsNode("/map").getProperties().size());
        assertEquals("value3", reversed.getAsText("/map/key3/value"));
    }

}
