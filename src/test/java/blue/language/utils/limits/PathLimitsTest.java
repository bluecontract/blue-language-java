package blue.language.utils.limits;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.provider.BasicNodeProvider;
import blue.language.utils.NodeTypeMatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static blue.language.utils.BlueIdCalculator.calculateBlueId;
import static blue.language.utils.UncheckedObjectMapper.YAML_MAPPER;
import static org.junit.jupiter.api.Assertions.*;

public class PathLimitsTest {

    private PathLimits pathLimits;
    private final Node mockNode = new Node();

    @BeforeEach
    public void setup() {
        pathLimits = new PathLimits.Builder()
                .addPath("/x/*")
                .addPath("/y")
                .addPath("/a/b/*/c")
                .addPath("/d/0/*")
                .addPath("/e/*/*")
                .addPath("/forX/d/0")
                .addPath("/f/*/*")
                .setMaxDepth(4)
                .build();
    }

    @Test
    public void shouldProcessPathSegmentWithinConfiguredLimits() {
        // given

        // when
        boolean rootIncludesX =
                pathLimits.shouldExtendPathSegment("x", mockNode);
        pathLimits.enterPathSegment("x");
        boolean xIncludesA =
                pathLimits.shouldExtendPathSegment("a", mockNode);
        pathLimits.enterPathSegment("a");
        boolean xaIncludesD =
                pathLimits.shouldExtendPathSegment("d", mockNode);
        pathLimits.exitPathSegment();
        boolean xIncludesY =
                pathLimits.shouldExtendPathSegment("y", mockNode);
        pathLimits.exitPathSegment();
        pathLimits.enterPathSegment("y");
        boolean yIncludesC =
                pathLimits.shouldExtendPathSegment("c", mockNode);
        pathLimits.exitPathSegment();
        pathLimits.enterPathSegment("a");
        pathLimits.enterPathSegment("b");
        boolean abIncludesD =
                pathLimits.shouldExtendPathSegment("d", mockNode);
        pathLimits.enterPathSegment("d");
        boolean abdIncludesC =
                pathLimits.shouldExtendPathSegment("c", mockNode);

        // then
        assertTrue(rootIncludesX);
        assertTrue(xIncludesA);
        assertFalse(xaIncludesD);
        assertTrue(xIncludesY);
        assertFalse(yIncludesC);
        assertTrue(abIncludesD);
        assertTrue(abdIncludesC);
    }

    @Test
    public void shouldEnforceMaximumDepth() {
        // given
        pathLimits.enterPathSegment("a");
        // when
        pathLimits.enterPathSegment("b");
        boolean depthTwoIncludesAny =
                pathLimits.shouldExtendPathSegment("any", mockNode);
        pathLimits.enterPathSegment("any");
        boolean depthThreeIncludesC =
                pathLimits.shouldExtendPathSegment("c", mockNode);
        pathLimits.enterPathSegment("c");
        boolean depthFourIncludesE =
                pathLimits.shouldExtendPathSegment("e", mockNode);

        // then
        assertTrue(depthTwoIncludesAny);
        assertTrue(depthThreeIncludesC);
        assertFalse(depthFourIncludesE);
    }

    @Test
    public void shouldMatchSingleWildcard() {
        // given
        pathLimits.enterPathSegment("a");
        // when
        pathLimits.enterPathSegment("b");
        boolean includesAny =
                pathLimits.shouldExtendPathSegment("any", mockNode);
        pathLimits.enterPathSegment("any");
        boolean wildcardIncludesC =
                pathLimits.shouldExtendPathSegment("c", mockNode);

        // then
        assertTrue(includesAny);
        assertTrue(wildcardIncludesC);
    }

    @Test
    public void shouldMatchComplexPath() {
        // given
        pathLimits.enterPathSegment("a");
        // when
        pathLimits.enterPathSegment("b");
        boolean includesC =
                pathLimits.shouldExtendPathSegment("c", mockNode);
        pathLimits.enterPathSegment("c");
        boolean includesE =
                pathLimits.shouldExtendPathSegment("e", mockNode);

        // then
        assertTrue(includesC);
        assertFalse(includesE);
    }

    @Test
    public void shouldRejectInvalidPath() {
        // given
        String invalidRootSegment = "z";
        String candidateChildSegment = "a";

        // when
        pathLimits.enterPathSegment(invalidRootSegment);
        boolean candidateChildIncluded =
                pathLimits.shouldExtendPathSegment(candidateChildSegment, mockNode);

        // then
        assertFalse(candidateChildIncluded);
    }

    @Test
    public void shouldMatchPathWithIndex() {
        // given
        PathLimits limits = pathLimits;

        // when
        limits.enterPathSegment("d");
        boolean includesZero =
                limits.shouldExtendPathSegment("0", mockNode);
        limits.enterPathSegment("0");
        boolean zeroIncludesAny =
                limits.shouldExtendPathSegment("any", mockNode);
        limits.exitPathSegment();
        boolean includesOne =
                limits.shouldExtendPathSegment("1", mockNode);

        // then
        assertTrue(includesZero);
        assertTrue(zeroIncludesAny);
        assertFalse(includesOne);
    }

    @Test
    public void shouldMatchMultipleWildcards() {
        // given
        PathLimits limits = pathLimits;

        // when
        limits.enterPathSegment("e");
        boolean includesZero =
                limits.shouldExtendPathSegment("0", mockNode);
        limits.enterPathSegment("0");
        boolean zeroIncludesOne =
                limits.shouldExtendPathSegment("1", mockNode);

        // then
        assertTrue(includesZero);
        assertTrue(zeroIncludesOne);
    }

    @Test
    public void shouldMatchSpecificIndexPath() {
        // given
        pathLimits = new PathLimits.Builder()
                .addPath("/forX/d/0")
                .build();

        // when
        boolean rootIncludesForX =
                pathLimits.shouldExtendPathSegment("forX", mockNode);
        pathLimits.enterPathSegment("forX");
        boolean forXIncludesD =
                pathLimits.shouldExtendPathSegment("d", mockNode);
        pathLimits.enterPathSegment("d");
        boolean dIncludesZero =
                pathLimits.shouldExtendPathSegment("0", mockNode);
        pathLimits.enterPathSegment("0");
        boolean zeroIncludesAny =
                pathLimits.shouldExtendPathSegment("any", mockNode);
        pathLimits.exitPathSegment();
        boolean dIncludesOne =
                pathLimits.shouldExtendPathSegment("1", mockNode);

        // then
        assertTrue(rootIncludesForX);
        assertTrue(forXIncludesD);
        assertTrue(dIncludesZero);
        assertFalse(zeroIncludesAny);
        assertFalse(dIncludesOne);
    }

    @Test
    public void shouldMatchEscapedJsonPointerSegments() {
        // given
        pathLimits = new PathLimits.Builder()
                .addPath("/x/a~1b/c~0d")
                .build();

        // when
        boolean rootIncludesX =
                pathLimits.shouldExtendPathSegment("x", mockNode);
        pathLimits.enterPathSegment("x");
        boolean xIncludesDecodedSlash =
                pathLimits.shouldExtendPathSegment("a/b", mockNode);
        boolean xIncludesEncodedSlash =
                pathLimits.shouldExtendPathSegment("a~1b", mockNode);
        pathLimits.enterPathSegment("a/b");
        boolean slashIncludesDecodedTilde =
                pathLimits.shouldExtendPathSegment("c~d", mockNode);
        boolean slashIncludesSlash =
                pathLimits.shouldExtendPathSegment("c/d", mockNode);

        // then
        assertTrue(rootIncludesX);
        assertTrue(xIncludesDecodedSlash);
        assertFalse(xIncludesEncodedSlash);
        assertTrue(slashIncludesDecodedTilde);
        assertFalse(slashIncludesSlash);
    }

    @Test
    public void shouldMatchTwoLevelWildcard() {
        // given

        // when
        boolean rootIncludesF =
                pathLimits.shouldExtendPathSegment("f", mockNode);
        pathLimits.enterPathSegment("f");
        boolean fIncludesAny =
                pathLimits.shouldExtendPathSegment("anySegment", mockNode);
        pathLimits.enterPathSegment("anySegment");
        boolean firstWildcardIncludesAnother =
                pathLimits.shouldExtendPathSegment(
                        "anotherSegment", mockNode);
        pathLimits.enterPathSegment("anotherSegment");
        boolean secondWildcardIncludesTooDeep =
                pathLimits.shouldExtendPathSegment("tooDeep", mockNode);
        pathLimits.exitPathSegment();
        pathLimits.exitPathSegment();
        boolean fIncludesDifferent =
                pathLimits.shouldExtendPathSegment(
                        "differentSegment", mockNode);
        pathLimits.enterPathSegment("differentSegment");
        boolean differentIncludesLast =
                pathLimits.shouldExtendPathSegment(
                        "lastSegment", mockNode);
        pathLimits.enterPathSegment("lastSegment");
        boolean lastIncludesTooDeep =
                pathLimits.shouldExtendPathSegment(
                        "tooDeepAgain", mockNode);
        pathLimits.exitPathSegment();
        pathLimits.exitPathSegment();
        pathLimits.exitPathSegment();
        boolean rootIncludesG =
                pathLimits.shouldExtendPathSegment("g", mockNode);

        // then
        assertTrue(rootIncludesF);
        assertTrue(fIncludesAny);
        assertTrue(firstWildcardIncludesAnother);
        assertFalse(secondWildcardIncludesTooDeep);
        assertTrue(fIncludesDifferent);
        assertTrue(differentIncludesLast);
        assertFalse(lastIncludesTooDeep);
        assertFalse(rootIncludesG);
    }

    @Test
    public void shouldIncludeSchemaAndBlueIdMetadata() throws Exception {
        // given
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        Blue blue = new Blue(nodeProvider);

        String a = "name: A\n" +
                   "x:\n" +
                   "  description: aa\n" +
                   "  schema:\n" +
                   "    maxLength: 4\n" +
                   "y:\n" +
                   "  schema:\n" +
                   "    maxLength: 4";
        Node aNode = blue.yamlToNode(a);
        nodeProvider.addSingleNodes(aNode);
        String referencedBlueId = calculateBlueId(new Node().value("some-blue-id"));

        String b = "name: B\n" +
                   "type:\n" +
                   "  blueId: " + calculateBlueId(aNode) + "\n" +
                   "x:\n" +
                   "  blueId: " + referencedBlueId + "\n" +
                   "y: abcd";
        Node bNode = blue.yamlToNode(b);
        nodeProvider.addSingleNodes(bNode);

        String bInst = "name: B Inst\n" +
                       "type:\n" +
                       "  blueId: " + calculateBlueId(bNode) + "\n" +
                       "x:\n" +
                       "  blueId: " + referencedBlueId + "\n" +
                       "y: abcd";
        Node bInstNode = blue.yamlToNode(bInst);
        nodeProvider.addSingleNodes(bInstNode);

        String typeBlueId = calculateBlueId(bNode);
        Set<String> ignoredProperties = new HashSet<>(Collections.singletonList("x"));
        Limits globalLimits = new TypeSpecificPropertyFilter(typeBlueId, ignoredProperties);

        // when
        boolean result =
                new NodeTypeMatcher(blue)
                        .matchesType(bInstNode, bNode, globalLimits);

        // then
        assertTrue(result);
    }

}
