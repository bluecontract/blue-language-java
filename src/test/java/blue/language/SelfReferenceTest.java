package blue.language;

import blue.language.model.Node;
import blue.language.provider.BasicNodeProvider;
import blue.language.utils.NodeExtender;
import blue.language.utils.Types;
import blue.language.utils.limits.PathLimits;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static blue.language.utils.UncheckedObjectMapper.YAML_MAPPER;
import static org.junit.jupiter.api.Assertions.*;

public class SelfReferenceTest {

    @Test
    public void testSingleDoc() throws Exception {

        String a = "name: A\n" +
                   "x:\n" +
                   "  type:\n" +
                   "    blueId: this";

        Map<String, Node> nodes = Stream.of(a)
                .map(doc -> YAML_MAPPER.readValue(doc, Node.class))
                .collect(Collectors.toMap(Node::getName, node -> node));
        BasicNodeProvider nodeProvider = new BasicNodeProvider(nodes.values());

        Node aNode = nodeProvider.findNodeByName("A").orElseThrow(() -> new IllegalArgumentException("No A node found"));
        Node extended = aNode.clone();
        new NodeExtender(nodeProvider).extend(extended, PathLimits.withSinglePath("/x/x/x/x"));

        assertTrue(Types.isSubtype(extended, extended.getAsNode("/x/type"), nodeProvider));
        assertTrue(Types.isSubtype(aNode, extended.getAsNode("/x/type"), nodeProvider));
        assertTrue(Types.isSubtype(extended.getAsNode("/x/type"), aNode, nodeProvider));
        assertTrue(Types.isSubtype(extended.getAsNode("/x/type/x/type/x/type/x/type"), aNode, nodeProvider));

    }

    @Test
    public void testTwoInterconnectedDocs() throws Exception {
        String ab = "- name: A\n" +
                    "  x:\n" +
                    "    type:\n" +
                    "      blueId: this#1\n" +
                    "  aVal:\n" +
                    "    constraints:\n" +
                    "      maxLength: 4\n" +
                    "- name: B\n" +
                    "  y:\n" +
                    "    type:\n" +
                    "      blueId: this#0\n" +
                    "  bVal:\n" +
                    "    constraints:\n" +
                    "      maxLength: 4\n" +
                    "  bConst: xyz";

        Node my = YAML_MAPPER.readValue(ab, Node.class);

        BasicNodeProvider nodeProvider = new BasicNodeProvider(my);

        Node aNode = nodeProvider.findNodeByName("A").orElseThrow(() -> new IllegalArgumentException("No A node found"));
        Node bNode = nodeProvider.findNodeByName("B").orElseThrow(() -> new IllegalArgumentException("No B node found"));
        String aNodeBlueId = aNode.getAsText("/blueId");
        String bNodeBlueId = bNode.getAsText("/blueId");

        Node extendedA = aNode.clone();
        Node extendedB = bNode.clone();
        new NodeExtender(nodeProvider).extend(extendedA, PathLimits.withSinglePath("/x/y/x/y"));
        new NodeExtender(nodeProvider).extend(extendedB, PathLimits.withSinglePath("/y/x/y/x"));

        assertTrue(Types.isSubtype(extendedA, extendedB.getAsNode("/y/type"), nodeProvider));
        assertTrue(Types.isSubtype(extendedB, extendedA.getAsNode("/x/type/y/type/x/type"), nodeProvider));


        String instance = "name: Some\n" +
                          "a:\n" +
                          "  type: " + aNodeBlueId + "\n" +
                          "  aVal: abcd\n" +
                          "  x:\n" +
                          "    bVal: abcd";

        Blue blue = new Blue(nodeProvider);
        Node result = blue.resolve(blue.yamlToNode(instance), PathLimits.withSinglePath("/*/*/*"));
        assertEquals("xyz", result.getAsText("/a/x/bConst"));


        String errorInstance = "name: Some\n" +
                               "a:\n" +
                               "  type: " + aNodeBlueId + "\n" +
                               "  aVal: abcd\n" +
                               "  x:\n" +
                               "    bVal: abcd\n" +
                               "    y:\n" +
                               "      aVal: TOO_LONG";

        assertThrows(IllegalArgumentException.class,
                () -> blue.resolve(blue.yamlToNode(errorInstance), PathLimits.withSinglePath("/*/*/*/*")));
    }

}