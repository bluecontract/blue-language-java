package blue.language.utils.limits;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.preprocess.provider.BasicNodeProvider;
import blue.language.graph.NodeExpander;
import blue.language.matching.NodeTypeMatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static blue.language.identity.DirectBlueIdCalculator.calculateBlueId;
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
    public void shouldIgnoreConfiguredPropertiesWithinMatchingType() {
        // given
        Node nodeWithType =
                new Node().type(new Node().blueId(typeBlueId));

        // when
        List<Boolean> atRoot =
                expansionDecisions(nodeWithType, "x", "y", "z");
        typeSpecificPropertyFilter.enterPathSegment("", nodeWithType);
        List<Boolean> insideTarget =
                expansionDecisions(nodeWithType, "x", "y", "z");
        typeSpecificPropertyFilter.enterPathSegment("x", nodeWithType);
        List<Boolean> insideTargetChild =
                expansionDecisions(
                        nodeWithType,
                        "nestedX",
                        "y",
                        "nestedZ");
        typeSpecificPropertyFilter.exitPathSegment();
        typeSpecificPropertyFilter.exitPathSegment();
        List<Boolean> afterExit =
                expansionDecisions(nodeWithType, "x", "y", "z");
        boolean unrelatedTypeDecision =
                typeSpecificPropertyFilter.shouldExpandPathSegment(
                        "otherProperty", mockNode);

        // then
        assertEquals(Arrays.asList(true, true, true), atRoot);
        assertEquals(Arrays.asList(true, false, true), insideTarget);
        assertEquals(
                Arrays.asList(true, false, true),
                insideTargetChild);
        assertEquals(Arrays.asList(true, true, true), afterExit);
        assertTrue(unrelatedTypeDecision);
    }

    @Test
    public void shouldSkipIgnoredPropertiesOnlyWithinMatchingNestedStructures() throws Exception {
        // given
        Node validExpansionNode1 = new Node().name("ValidExpansion1");
        Node validExpansionNode2 = new Node().name("ValidExpansion2");

        String validBlueId1 = calculateBlueId(validExpansionNode1);
        String validBlueId2 = calculateBlueId(validExpansionNode2);

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

        BasicNodeProvider nodeProvider = new BasicNodeProvider(
                typeNode, validExpansionNode1, validExpansionNode2);
        Blue blue = new Blue(nodeProvider);

        Node complexNode = blue.yamlToNode(complexYaml);

        NodeExpander nodeExpander = new NodeExpander(nodeProvider);
        // when
        nodeExpander.expand(complexNode, typeSpecificPropertyFilter);

        // then
        assertNull(complexNode.getAsNode("/a/b/c/y").getName(),
                "Expansion should not occur for matching type");
        assertNull(complexNode.getAsNode("/a/l/0/y/name").getName(),
                "Expansion should not occur for matching type in list");
        assertEquals("ValidExpansion1", complexNode.get("/a/l/1/y/name"),
                "Expansion should occur for non-matching type in list");
        assertEquals("ValidExpansion2", complexNode.get("/a/d/y/name"),
                "Expansion should occur for non-matching type");
    }

    @Test
    public void shouldMatchTypeWhileFilteringConfiguredProperties() throws Exception {
        // given
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
        // when
        boolean result = matcher.matchesType(instanceNode, typeNode, typeSpecificPropertyFilter);

        // then
        assertTrue(result);
    }

    @Test
    public void shouldSkipNonTargetType() {
        // given
        Node nonTargetNode =
                new Node().type(
                        new Node().blueId(
                                "different-blue-id"));

        // when
        List<Boolean> decisions =
                expansionDecisions(nonTargetNode, "x", "y", "z");

        // then
        assertEquals(Arrays.asList(true, true, true), decisions);
    }

    private List<Boolean> expansionDecisions(
            Node node,
            String first,
            String second,
            String third) {
        return Arrays.asList(
                typeSpecificPropertyFilter.shouldExpandPathSegment(
                        first, node),
                typeSpecificPropertyFilter.shouldExpandPathSegment(
                        second, node),
                typeSpecificPropertyFilter.shouldExpandPathSegment(
                        third, node));
    }
}
