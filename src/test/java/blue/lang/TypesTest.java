package blue.lang;

import blue.lang.utils.BasicNodesProvider;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static blue.lang.TestUtils.useNodeNameAsBlueIdProvider;
import static blue.lang.Types.isSubtype;
import static blue.lang.utils.UncheckedObjectMapper.YAML_MAPPER;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TypesTest {

    @Test
    public void testBasic() throws Exception {

        Node a = new Node().name("A");
        Node b = new Node().name("B").type("A");
        Node c = new Node().name("C").type("B");

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

        String person = "name: Person\n" +
                "surname:\n" +
                "  type: Text\n" +
                "age:\n" +
                "  type: Integer";

        String alice = "name: Alice\n" +
                "type: 9ZMxNv72wXjcMoa5FZG2J52aSH4ofF27c3mHdxZQWE8e";

        String alice2 = "name: Alice2\n" +
                "type:\n" +
                "  name: Person\n" +
                "  blueId: 9ZMxNv72wXjcMoa5FZG2J52aSH4ofF27c3mHdxZQWE8e";

        String alice3 = "name: Alice3\n" +
                "type:\n" +
                "  name: Person\n" +
                "  surname:\n" +
                "    type: Text\n" +
                "  age:\n" +
                "    type: Integer";

        Map<String, Node> nodes = Stream.of(person, alice, alice2, alice3)
                .map(doc -> YAML_MAPPER.readValue(doc, Node.class))
                .collect(Collectors.toMap(Node::getName, node -> node));
        BasicNodesProvider nodesProvider = new BasicNodesProvider(nodes.values());

        assertTrue(isSubtype(nodes.get("Alice"), nodes.get("Alice"), nodesProvider));
        assertFalse(isSubtype(nodes.get("Person"), nodes.get("Alice"), nodesProvider));

        assertTrue(isSubtype(nodes.get("Alice"), nodes.get("Person"), nodesProvider));
        assertTrue(isSubtype(nodes.get("Alice2"), nodes.get("Person"), nodesProvider));
        assertTrue(isSubtype(nodes.get("Alice3"), nodes.get("Person"), nodesProvider));
    }

}