package blue.lang;

import blue.lang.graph.Node;
import blue.lang.graph.Types;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TypesTest {

    @Test
    public void testIsSubtype() throws Exception {

        Node a = new Node().name("A");
        Node b = new Node().name("B").type("A");
        Node c = new Node().name("C").type("B");

        Types types = new Types(Arrays.asList(a, b, c));

        assertTrue(types.isSubtype("B", "A"));
        assertTrue(types.isSubtype("C", "A"));
        assertTrue(types.isSubtype("A", "A"));
        assertTrue(types.isSubtype("B", "B"));
        assertFalse(types.isSubtype("B", "C"));

    }

}