package blue.language;

import blue.language.api.BlueCachePolicy;
import blue.language.api.BlueCacheStats;
import blue.language.api.BlueLanguageErrorCategory;
import blue.language.api.BlueLanguageErrorClassifier;
import blue.language.api.BlueOperationLimits;
import blue.language.api.BlueOperationOutcome;
import blue.language.api.BlueOperationResult;
import blue.language.api.BlueViewPath;
import blue.language.api.LanguageRuntimeAccess;
import blue.language.provider.NodeProvider;

import blue.language.model.Node;
import blue.language.model.Schema;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

final class NodeCloneTest {

    private static final int DEEP_GRAPH_LEVELS = 30_000;
    private static final String NEXT = "next";

    @Test
    void shouldCloneDeepNodeGraphWithoutSharingMutableNodes() {
        // given
        Node source = new Node();
        Node sourceLeaf = appendChain(source, DEEP_GRAPH_LEVELS);

        // when
        Node cloned = source.clone();
        Node clonedLeaf = descend(cloned, DEEP_GRAPH_LEVELS);
        clonedLeaf.name("changed");

        // then
        assertNotSame(source, cloned);
        assertNotSame(sourceLeaf, clonedLeaf);
        assertNull(sourceLeaf.getName());
        assertEquals("changed", clonedLeaf.getName());
    }

    @Test
    void shouldKeepHistoricallyIndependentCopiesForSharedAcyclicChildEdges() {
        // given
        Node shared = new Node().value("shared");
        Node source = new Node()
                .properties("left", shared)
                .properties("right", shared);

        // when
        Node cloned = source.clone();
        Node clonedLeft = cloned.getProperties().get("left");
        Node clonedRight = cloned.getProperties().get("right");
        clonedLeft.value("changed");

        // then
        assertNotSame(shared, clonedLeft);
        assertNotSame(clonedLeft, clonedRight);
        assertEquals("shared", shared.getValue());
        assertEquals("shared", clonedRight.getValue());
    }

    @Test
    void shouldRetainRootBackEdgesWhenCloningAndReplacing() {
        // given
        Node source = new Node();
        source.properties("self", source);
        Node receiver = new Node();

        // when
        Node cloned = source.clone();
        receiver.replaceWith(source);
        source.replaceWith(source);

        // then
        assertSame(cloned, cloned.getAsNode("/self"));
        assertSame(receiver, receiver.getAsNode("/self"));
        assertSame(source, source.getAsNode("/self"));
    }

    @Test
    void shouldPreserveNodeAndSchemaRuntimeSubclasses() {
        // given
        SpecialNode child = new SpecialNode("child");
        SpecialSchema schema = new SpecialSchema("schema");
        schema.required(true);
        SpecialNode source = new SpecialNode("root");
        source.properties("child", child).schema(schema);

        // when
        Node cloned = source.clone();

        // then
        assertEquals("root", assertInstanceOf(SpecialNode.class, cloned).marker);
        assertEquals("child", assertInstanceOf(
                SpecialNode.class, cloned.getAsNode("/child")).marker);
        assertEquals("schema", assertInstanceOf(
                SpecialSchema.class, cloned.getSchema()).marker);
    }

    private static Node appendChain(Node root, int levels) {
        Node current = root;
        for (int level = 0; level < levels; level++) {
            Node child = new Node();
            current.properties(NEXT, child);
            current = child;
        }
        return current;
    }

    private static Node descend(Node root, int levels) {
        Node current = root;
        for (int level = 0; level < levels; level++) {
            current = current.getProperties().get(NEXT);
        }
        return current;
    }

    private static final class SpecialNode extends Node {
        private final String marker;

        private SpecialNode(String marker) {
            this.marker = marker;
        }
    }

    private static final class SpecialSchema extends Schema {
        private final String marker;

        private SpecialSchema(String marker) {
            this.marker = marker;
        }
    }
}
