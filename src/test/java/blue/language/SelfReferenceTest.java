package blue.language;

import blue.language.model.Node;
import blue.language.provider.BasicNodeProvider;
import blue.language.utils.NodeExtender;
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
        String aNodeBlueId = nodeProvider.getBlueIdByName("A");
        Node extended = aNode.clone();
        new NodeExtender(nodeProvider).extend(extended, PathLimits.withSinglePath("/x/x/x/x"));

        assertEquals(aNodeBlueId, extended.getAsNode("/x/type").getBlueId());
        assertEquals("A", extended.getAsText("/x/type/name"));
        assertEquals(aNodeBlueId, extended.getAsNode("/x/type/x/type").getBlueId());
        assertEquals("A", extended.getAsText("/x/type/x/type/name"));

    }

    @Test
    public void testTwoInterconnectedDocs() throws Exception {

        String ab = "- name: A\n" +
                    "  x:\n" +
                    "    type:\n" +
                    "      blueId: this#1\n" +
                    "  aVal:\n" +
                    "    schema:\n" +
                    "      maxLength: 4\n" +
                    "- name: B\n" +
                    "  y:\n" +
                    "    type:\n" +
                    "      blueId: this#0\n" +
                    "  bVal:\n" +
                    "    schema:\n" +
                    "      maxLength: 4\n" +
                    "  bConst: xyz";

        Node my = YAML_MAPPER.readValue(ab, Node.class);

        BasicNodeProvider nodeProvider = new BasicNodeProvider(my);

        Node aNode = nodeProvider.findNodeByName("A").orElseThrow(() -> new IllegalArgumentException("No A node found"));
        String aNodeBlueId = nodeProvider.getBlueIdByName("A");
        String bNodeBlueId = nodeProvider.getBlueIdByName("B");

        Node extendedA = aNode.clone();
        Node extendedB = nodeProvider.findNodeByName("B").orElseThrow(() -> new IllegalArgumentException("No B node found")).clone();
        new NodeExtender(nodeProvider).extend(extendedA, PathLimits.withSinglePath("/x/y/x/y"));
        new NodeExtender(nodeProvider).extend(extendedB, PathLimits.withSinglePath("/y/x/y/x"));

        assertEquals(bNodeBlueId, extendedA.getAsNode("/x/type").getBlueId());
        assertEquals("B", extendedA.getAsText("/x/type/name"));
        assertEquals(aNodeBlueId, extendedB.getAsNode("/y/type").getBlueId());
        assertEquals("A", extendedB.getAsText("/y/type/name"));
        assertEquals(aNodeBlueId, extendedA.getAsNode("/x/type/y/type").getBlueId());


        String instance = "name: Some\n" +
                          "a:\n" +
                          "  type:\n" +
                          "    blueId: " + aNodeBlueId + "\n" +
                          "  aVal: abcd\n" +
                          "  x:\n" +
                          "    bVal: abcd";

        Blue blue = new Blue(nodeProvider);
        Node result = blue.resolve(blue.preprocess(blue.yamlToNode(instance)), PathLimits.withSinglePath("/*/*/*"));
        assertEquals("xyz", result.getAsText("/a/x/bConst"));


        String errorInstance = "name: Some\n" +
                               "a:\n" +
                               "  type: \n" +
                               "    blueId: " + aNodeBlueId + "\n" +
                               "  aVal: abcd\n" +
                               "  x:\n" +
                               "    bVal: abcd\n" +
                               "    y:\n" +
                               "      aVal: TOO_LONG";

        assertThrows(IllegalArgumentException.class,
                () -> blue.resolve(blue.preprocess(blue.yamlToNode(errorInstance)), PathLimits.withSinglePath("/*/*/*/*")));
    }

}
