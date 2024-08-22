package blue.language;

import blue.language.model.Node;
import blue.language.provider.BasicNodeProvider;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static blue.language.TestUtils.useNodeNameAsBlueIdProvider;
import static blue.language.utils.Types.isSubtype;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TypesTest {

    @Test
    public void testBasic() throws Exception {

        Node a = new Node().name("A");
        Node b = new Node().name("B").type(a);
        Node c = new Node().name("C").type(b);

        List<Node> nodes = Arrays.asList(a, b, c);
        NodeProvider nodeProvider = useNodeNameAsBlueIdProvider(nodes);

        assertTrue(isSubtype(b, a, nodeProvider));
        assertTrue(isSubtype(c, a, nodeProvider));
        assertTrue(isSubtype(a, a, nodeProvider));
        assertTrue(isSubtype(b, b, nodeProvider));
        assertFalse(isSubtype(b, c, nodeProvider));

    }

    @Test
    public void testDifferentSubtypeVariations() throws Exception {

        BasicNodeProvider nodeProvider = new BasicNodeProvider();

        String person = "name: Person\n" +
                        "surname:\n" +
                        "  type: Text\n" +
                        "age:\n" +
                        "  type: Integer";
        nodeProvider.addSingleDocs(person);

        String alice = "name: Alice\n" +
                       "type:\n" +
                       "  blueId: " + nodeProvider.getBlueIdByName("Person");

        String alice2 = "name: Alice2\n" +
                        "type:\n" +
                        "  name: Person\n" +
                        "  blueId: " + nodeProvider.getBlueIdByName("Person");

        String alice3 = "name: Alice3\n" +
                        "type:\n" +
                        "  name: Person\n" +
                        "  surname:\n" +
                        "    type: Text\n" +
                        "  age:\n" +
                        "    type: Integer";
        nodeProvider.addSingleDocs(alice, alice2, alice3);

        assertTrue(isSubtype(nodeProvider.getNodeByName("Alice"), nodeProvider.getNodeByName("Alice"), nodeProvider));
        assertFalse(isSubtype(nodeProvider.getNodeByName("Person"), nodeProvider.getNodeByName("Alice"), nodeProvider));

        assertTrue(isSubtype(nodeProvider.getNodeByName("Alice"), nodeProvider.getNodeByName("Person"), nodeProvider));
        assertTrue(isSubtype(nodeProvider.getNodeByName("Alice2"), nodeProvider.getNodeByName("Person"), nodeProvider));
        assertTrue(isSubtype(nodeProvider.getNodeByName("Alice3"), nodeProvider.getNodeByName("Person"), nodeProvider));
    }

}