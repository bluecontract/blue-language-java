package blue.language.utils.limits;

import blue.language.model.Node;
import blue.language.utils.JsonPointer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NodeToPathLimitsConverterTest {

    private final Node mockNode = new Node();

    @Test
    void testEmptyNode() {
        Node node = new Node();
        assertAllows(node, "/");
        assertRejects(node, "/anyOtherPath");
    }

    @Test
    void testNodeWithSingleProperty() {
        Node node = new Node().properties("prop", new Node());
        assertAllows(node, "/prop");
        assertRejects(node, "/anyOtherPath");
    }

    @Test
    void testNodeWithNestedProperties() {
        Node node = new Node().properties(
                "prop1", new Node().properties("nested", new Node()),
                "prop2", new Node()
        );
        assertAllows(node, "/prop1");
        assertAllows(node, "/prop1/nested");
        assertAllows(node, "/prop2");
        assertRejects(node, "/prop1/nonexistent");
    }

    @Test
    void testNodeWithItems() {
        Node node = new Node().items(new Node(), new Node().properties("itemProp", new Node()));
        assertAllows(node, "/0");
        assertAllows(node, "/1");
        assertAllows(node, "/1/itemProp");
        assertRejects(node, "/2");
    }

    @Test
    void testComplexNode() {
        Node node = new Node().properties(
                "prop1", new Node().items(new Node(), new Node().properties("nestedItemProp", new Node())),
                "prop2", new Node().properties("nestedProp", new Node())
        );
        assertAllows(node, "/prop1");
        assertAllows(node, "/prop1/0");
        assertAllows(node, "/prop1/1");
        assertAllows(node, "/prop1/1/nestedItemProp");
        assertAllows(node, "/prop2");
        assertAllows(node, "/prop2/nestedProp");
        assertRejects(node, "/prop2/nestedProp/xyz");
        assertRejects(node, "/nonexistent");
    }

    @Test
    void testEscapedPropertyNames() {
        Node node = new Node().properties(
                "a/b", new Node().properties("c~d", new Node())
        );

        assertAllows(node, "/a~1b");
        assertAllows(node, "/a~1b/c~0d");
        assertRejects(node, "/a/b");
    }

    @Test
    void testNullNode() {
        assertRejects(null, "/");
        assertRejects(null, "/anyPath");
    }

    private void assertAllows(Node node, String pointer) {
        assertTrue(allows(node, pointer), pointer);
    }

    private void assertRejects(Node node, String pointer) {
        assertFalse(allows(node, pointer), pointer);
    }

    private boolean allows(Node node, String pointer) {
        PathLimits limits = NodeToPathLimitsConverter.convert(node);
        List<String> segments = JsonPointer.split(pointer);
        if (segments.isEmpty()) {
            return limits.shouldExtendPathSegment("", mockNode);
        }
        for (String segment : segments) {
            if (!limits.shouldExtendPathSegment(segment, mockNode)) {
                return false;
            }
            limits.enterPathSegment(segment, mockNode);
        }
        return true;
    }
}
