package blue.language.resolve;

import blue.language.Blue;
import blue.language.identity.CanonicalTypeIdentityEvidence;
import blue.language.identity.CanonicalTypeIdentityLookup;
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
import java.util.Optional;
import java.util.Set;

import static blue.language.identity.DirectBlueIdCalculator.calculateBlueId;
import static blue.language.codec.jackson.UncheckedObjectMapper.YAML_MAPPER;
import static org.junit.jupiter.api.Assertions.*;

public class TypeSpecificPropertyFilterTest {

    private ResolutionLimits typeSpecificPropertyFilter;
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
        typeSpecificPropertyFilter = ResolutionLimits
                .filteringPropertiesForType(typeBlueId, ignoredProperties);
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
        ResolutionLimits matchingFilter = ResolutionLimits
                .filteringPropertiesForType(
                        typeBlueId,
                        Collections.singleton("y"),
                        canonicalEvidenceFor(typeBlueId, typeNode));
        // when
        boolean result = matcher.matchesType(
                instanceNode, typeNode, matchingFilter);

        // then
        assertTrue(result);
    }

    @Test
    public void shouldSkipNonTargetType() {
        // given
        Node nonTargetNode =
                new Node().type(
                        new Node().blueId(
                                calculateBlueId(
                                        new Node().name("Different Type"))));

        // when
        List<Boolean> decisions =
                expansionDecisions(nonTargetNode, "x", "y", "z");

        // then
        assertEquals(Arrays.asList(true, true, true), decisions);
    }

    @Test
    public void shouldTreatInlineAndReferenceTypeRepresentationsEqually() {
        // given
        CanonicalTypeIdentityLookup evidence =
                new CanonicalTypeIdentityLookup() {
                    @Override
                    public boolean hasCompleteCoverage() {
                        return true;
                    }

                    @Override
                    public Optional<CanonicalTypeIdentityEvidence>
                    findCanonicalTypeIdentityEvidence(Node completedType) {
                        return typeBlueId.equals(
                                calculateBlueId(completedType))
                                ? Optional.of(
                                CanonicalTypeIdentityEvidence.identityOnly(
                                        typeBlueId))
                                : Optional.<CanonicalTypeIdentityEvidence>
                                empty();
                    }

                    @Override
                    public Optional<CanonicalTypeIdentityEvidence>
                    findCanonicalTypeIdentityEvidence(
                            Node completedType,
                            Node authoredTypeSource) {
                        return findCanonicalTypeIdentityEvidence(completedType);
                    }
                };
        ResolutionLimits inlineFilter = ResolutionLimits
                .filteringPropertiesForType(
                        typeBlueId,
                        Collections.singleton("y"),
                        evidence);
        ResolutionLimits referenceFilter = ResolutionLimits
                .filteringPropertiesForType(
                        typeBlueId,
                        Collections.singleton("y"));
        Node inline = new Node().type(typeNode.clone());
        Node reference = new Node().type(new Node().blueId(typeBlueId));

        // when
        inlineFilter.enterPathSegment("", inline);
        referenceFilter.enterPathSegment("", reference);
        boolean inlineDecision = inlineFilter.shouldExpandPathSegment(
                "y", inline);
        boolean referenceDecision = referenceFilter.shouldExpandPathSegment(
                "y", reference);
        inlineFilter.exitPathSegment();
        referenceFilter.exitPathSegment();

        // then
        assertFalse(inlineDecision);
        assertEquals(referenceDecision, inlineDecision);
    }

    @Test
    public void shouldFailClosedForInlineTypeWithoutCanonicalEvidence() {
        // given
        Node inline = new Node().type(typeNode.clone());

        // when
        Runnable enterInlineType = () ->
                typeSpecificPropertyFilter.enterPathSegment("", inline);

        // then
        assertThrows(
                IllegalStateException.class,
                enterInlineType::run);
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

    private CanonicalTypeIdentityLookup canonicalEvidenceFor(
            String canonicalBlueId,
            Node canonicalType) {
        return new CanonicalTypeIdentityLookup() {
            @Override
            public boolean hasCompleteCoverage() {
                return true;
            }

            @Override
            public Optional<CanonicalTypeIdentityEvidence>
            findCanonicalTypeIdentityEvidence(Node completedType) {
                if (completedType.isReferenceOnly()) {
                    return Optional.of(
                            CanonicalTypeIdentityEvidence.referenceSource(
                                    completedType.getBlueId()));
                }
                if (canonicalBlueId.equals(completedType.getBlueId())
                        || canonicalType.getName().equals(
                        completedType.getName())) {
                    return Optional.of(
                            CanonicalTypeIdentityEvidence.identityOnly(
                                    canonicalBlueId));
                }
                return Optional.empty();
            }

            @Override
            public Optional<CanonicalTypeIdentityEvidence>
            findCanonicalTypeIdentityEvidence(
                    Node completedType,
                    Node authoredTypeSource) {
                return findCanonicalTypeIdentityEvidence(completedType);
            }
        };
    }
}
