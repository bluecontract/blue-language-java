package blue.language.utils.limits;

import blue.language.model.Node;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NodeToPathLimitsConverterTest {

    @Test
    void testEmptyNode() {
        Node node = new Node();
        PathLimits limits = NodeToPathLimitsConverter.convert(node);
        assertTrue(limits.shouldProcessPathSegment("/"));
        assertFalse(limits.shouldProcessPathSegment("/anyOtherPath"));
    }

    @Test
    void testNodeWithSingleProperty() {
        Node node = new Node().properties("prop", new Node());
        PathLimits limits = NodeToPathLimitsConverter.convert(node);
        assertTrue(limits.shouldProcessPathSegment("/prop"));
        assertFalse(limits.shouldProcessPathSegment("/anyOtherPath"));
    }

    @Test
    void testNodeWithNestedProperties() {
        Node node = new Node().properties(
                "prop1", new Node().properties("nested", new Node()),
                "prop2", new Node()
        );
        PathLimits limits = NodeToPathLimitsConverter.convert(node);
        assertTrue(limits.shouldProcessPathSegment("/prop1"));
        assertTrue(limits.shouldProcessPathSegment("/prop1/nested"));
        assertTrue(limits.shouldProcessPathSegment("/prop2"));
        assertFalse(limits.shouldProcessPathSegment("/prop1/nonexistent"));
    }

    @Test
    void testNodeWithItems() {
        Node node = new Node().items(new Node(), new Node().properties("itemProp", new Node()));
        PathLimits limits = NodeToPathLimitsConverter.convert(node);
        assertTrue(limits.shouldProcessPathSegment("/0"));
        assertTrue(limits.shouldProcessPathSegment("/1"));
        assertTrue(limits.shouldProcessPathSegment("/1/itemProp"));
        assertFalse(limits.shouldProcessPathSegment("/2"));
    }

    @Test
    void testComplexNode() {
        Node node = new Node().properties(
                "prop1", new Node().items(new Node(), new Node().properties("nestedItemProp", new Node())),
                "prop2", new Node().properties("nestedProp", new Node())
        );
        PathLimits limits = NodeToPathLimitsConverter.convert(node);
        assertTrue(limits.shouldProcessPathSegment("/prop1"));
        assertTrue(limits.shouldProcessPathSegment("/prop1/0"));
        assertTrue(limits.shouldProcessPathSegment("/prop1/1"));
        assertTrue(limits.shouldProcessPathSegment("/prop1/1/nestedItemProp"));
        assertTrue(limits.shouldProcessPathSegment("/prop2"));
        assertTrue(limits.shouldProcessPathSegment("/prop2/nestedProp"));
        assertFalse(limits.shouldProcessPathSegment("/prop2/nestedProp/xyz"));
        assertFalse(limits.shouldProcessPathSegment("/nonexistent"));
    }

    @Test
    void testNullNode() {
        PathLimits limits = NodeToPathLimitsConverter.convert(null);
        assertFalse(limits.shouldProcessPathSegment("/"));
        assertFalse(limits.shouldProcessPathSegment("/anyPath"));
    }
}