package blue.language;

import blue.language.utils.MergeReverser;
import blue.language.model.Node;
import blue.language.provider.BasicNodeProvider;
import blue.language.utils.Properties;
import org.junit.jupiter.api.Test;

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

}
