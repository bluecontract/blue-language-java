package blue.language.utils.limits;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.provider.BasicNodeProvider;
import blue.language.utils.NodeExtender;
import blue.language.utils.NodeTypeMatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static blue.language.utils.BlueIdCalculator.calculateBlueId;
import static blue.language.utils.UncheckedObjectMapper.YAML_MAPPER;
import static org.junit.jupiter.api.Assertions.*;

public class TypeSpecificPropertyFilterTest {

    private TypeSpecificPropertyFilter typeSpecificPropertyFilter;
    private final Node mockNode = new Node();
    private Node typeNode;
    private String typeBlueId;

    @BeforeEach
    public void setup() throws Exception {
        String typeYaml = "name: TypeA\n" +
                          "x:\n" +
                          "  description: Property X\n" +
                          "y:\n" +
                          "  description: Property Y\n" +
                          "z:\n" +
                          "  description: Property Z";
        typeNode = new Blue().yamlToNode(typeYaml);
        typeBlueId = calculateBlueId(typeNode);

        Set<String> ignoredProperties = new HashSet<>(Collections.singletonList("y"));
        typeSpecificPropertyFilter = new TypeSpecificPropertyFilter(typeBlueId, ignoredProperties);
    }

    @Test
    public void testShouldProcessPathSegment() {
        Node nodeWithType = new Node();
        nodeWithType.type(new Node().blueId(typeBlueId));

        // Root level, should process all
        assertTrue(typeSpecificPropertyFilter.shouldExtendPathSegment("x", nodeWithType));
        assertTrue(typeSpecificPropertyFilter.shouldExtendPathSegment("y", nodeWithType));
        assertTrue(typeSpecificPropertyFilter.shouldExtendPathSegment("z", nodeWithType));

        typeSpecificPropertyFilter.enterPathSegment("", nodeWithType); // Enter root node

        // Now we're in the target type, should not process "y"
        assertTrue(typeSpecificPropertyFilter.shouldExtendPathSegment("x", nodeWithType));
        assertFalse(typeSpecificPropertyFilter.shouldExtendPathSegment("y", nodeWithType));
        assertTrue(typeSpecificPropertyFilter.shouldExtendPathSegment("z", nodeWithType));

        typeSpecificPropertyFilter.enterPathSegment("x", nodeWithType);

        // Still in target type, behavior should be the same
        assertTrue(typeSpecificPropertyFilter.shouldExtendPathSegment("nestedX", nodeWithType));
        assertFalse(typeSpecificPropertyFilter.shouldExtendPathSegment("y", nodeWithType));
        assertTrue(typeSpecificPropertyFilter.shouldExtendPathSegment("nestedZ", nodeWithType));

        typeSpecificPropertyFilter.exitPathSegment(); // Exit x
        typeSpecificPropertyFilter.exitPathSegment(); // Exit root

        // Back at root level, should process all again
        assertTrue(typeSpecificPropertyFilter.shouldExtendPathSegment("x", nodeWithType));
        assertTrue(typeSpecificPropertyFilter.shouldExtendPathSegment("y", nodeWithType));
        assertTrue(typeSpecificPropertyFilter.shouldExtendPathSegment("z", nodeWithType));

        // This should be true for a non-target type
        assertTrue(typeSpecificPropertyFilter.shouldExtendPathSegment("otherProperty", mockNode));
    }

    @Test
    public void testComplexNestedStructure() throws Exception {
        Node validExtensionNode1 = new Node().name("ValidExtension1");
        Node validExtensionNode2 = new Node().name("ValidExtension2");

        String validBlueId1 = calculateBlueId(validExtensionNode1);
        String validBlueId2 = calculateBlueId(validExtensionNode2);

        String complexYaml = "a:\n" +
                             "  b:\n" +
                             "    c:\n" +
                             "      type:\n" +
                             "        blueId: " + typeBlueId + "\n" +
                             "      y:\n" +
                             "        blueId: invalid-blue-id1\n" +
                             "  l:\n" +
                             "    - type:\n" +
                             "        blueId: " + typeBlueId + "\n" +
                             "      y:\n" +
                             "        blueId: invalid-blue-id2\n" +
                             "    - y:\n" +
                             "        blueId: " + validBlueId1 + "\n" +
                             "  d:\n" +
                             "    y:\n" +
                             "      blueId: " + validBlueId2;

        BasicNodeProvider nodeProvider = new BasicNodeProvider(typeNode, validExtensionNode1, validExtensionNode2);
        Blue blue = new Blue(nodeProvider);

        Node complexNode = blue.yamlToNode(complexYaml);

        NodeExtender nodeExtender = new NodeExtender(nodeProvider);
        nodeExtender.extend(complexNode, typeSpecificPropertyFilter);

        assertNull(complexNode.getAsNode("/a/b/c/y").getName(), "Extension should not occur for matching type");
        assertNull(complexNode.getAsNode("/a/l/0/y/name").getName(), "Extension should not occur for matching type in list");
        assertEquals("ValidExtension1", complexNode.get("/a/l/1/y/name"), "Extension should occur for non-matching type in list");
        assertEquals("ValidExtension2", complexNode.get("/a/d/y/name"), "Extension should occur for non-matching type");
    }

    @Test
    public void testWithNodeTypeMatcher() throws Exception {
        String instanceYaml = "name: InstanceA\n" +
                              "type:\n" +
                              "  blueId: " + typeBlueId + "\n" +
                              "x: valueX\n" +
                              "y: valueY\n" +
                              "z: valueZ";
        Node instanceNode = YAML_MAPPER.readValue(instanceYaml, Node.class);

        String typeYaml = "name: TypeA\n" +
                          "x:\n" +
                          "  description: Property X\n" +
                          "y:\n" +
                          "  description: Property Y\n" +
                          "z:\n" +
                          "  description: Property Z";
        Node typeNode = YAML_MAPPER.readValue(typeYaml, Node.class);

        BasicNodeProvider nodeProvider = new BasicNodeProvider(typeNode, instanceNode);
        Blue blue = new Blue(nodeProvider);

        NodeTypeMatcher matcher = new NodeTypeMatcher(blue);
        boolean result = matcher.matchesType(instanceNode, typeNode, typeSpecificPropertyFilter);

        assertTrue(result);
    }

    @Test
    public void testNonTargetType() {
        Node nonTargetNode = new Node();
        nonTargetNode.type(new Node().blueId("different-blue-id"));

        // For non-target types, all properties should be processed, including the ignored ones
        assertTrue(typeSpecificPropertyFilter.shouldExtendPathSegment("x", nonTargetNode));
        assertTrue(typeSpecificPropertyFilter.shouldExtendPathSegment("y", nonTargetNode));
        assertTrue(typeSpecificPropertyFilter.shouldExtendPathSegment("z", nonTargetNode));
    }

}