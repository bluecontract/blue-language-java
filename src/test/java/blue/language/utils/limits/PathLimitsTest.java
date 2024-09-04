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
    public void testShouldProcessPathSegment() {
        assertTrue(pathLimits.shouldExtendPathSegment("x", mockNode));
        pathLimits.enterPathSegment("x");
        assertTrue(pathLimits.shouldExtendPathSegment("a", mockNode));
        pathLimits.enterPathSegment("a");
        assertFalse(pathLimits.shouldExtendPathSegment("d", mockNode));
        pathLimits.exitPathSegment();
        assertTrue(pathLimits.shouldExtendPathSegment("y", mockNode));
        pathLimits.exitPathSegment();

        pathLimits.enterPathSegment("y");
        assertFalse(pathLimits.shouldExtendPathSegment("c", mockNode));
        pathLimits.exitPathSegment();

        pathLimits.enterPathSegment("a");
        pathLimits.enterPathSegment("b");
        assertTrue(pathLimits.shouldExtendPathSegment("d", mockNode));
        pathLimits.enterPathSegment("d");
        assertTrue(pathLimits.shouldExtendPathSegment("c", mockNode));
    }

    @Test
    public void testMaxDepth() {
        pathLimits.enterPathSegment("a");
        pathLimits.enterPathSegment("b");
        assertTrue(pathLimits.shouldExtendPathSegment("any", mockNode));
        pathLimits.enterPathSegment("any");
        assertTrue(pathLimits.shouldExtendPathSegment("c", mockNode));
        pathLimits.enterPathSegment("c");
        assertFalse(pathLimits.shouldExtendPathSegment("e", mockNode));
    }

    @Test
    public void testWildcardSingle() {
        pathLimits.enterPathSegment("a");
        pathLimits.enterPathSegment("b");
        assertTrue(pathLimits.shouldExtendPathSegment("any", mockNode));
        pathLimits.enterPathSegment("any");
        assertTrue(pathLimits.shouldExtendPathSegment("c", mockNode));
    }

    @Test
    public void testComplexPath() {
        pathLimits.enterPathSegment("a");
        pathLimits.enterPathSegment("b");
        assertTrue(pathLimits.shouldExtendPathSegment("c", mockNode));
        pathLimits.enterPathSegment("c");
        assertFalse(pathLimits.shouldExtendPathSegment("e", mockNode));
    }

    @Test
    public void testInvalidPath() {
        pathLimits.enterPathSegment("z");
        assertFalse(pathLimits.shouldExtendPathSegment("a", mockNode));
    }

    @Test
    public void testPathWithIndex() {
        pathLimits.enterPathSegment("d");
        assertTrue(pathLimits.shouldExtendPathSegment("0", mockNode));
        pathLimits.enterPathSegment("0");
        assertTrue(pathLimits.shouldExtendPathSegment("any", mockNode));
        pathLimits.exitPathSegment();
        assertFalse(pathLimits.shouldExtendPathSegment("1", mockNode));
    }

    @Test
    public void testMultipleWildcards() {
        pathLimits.enterPathSegment("e");
        assertTrue(pathLimits.shouldExtendPathSegment("0", mockNode));
        pathLimits.enterPathSegment("0");
        assertTrue(pathLimits.shouldExtendPathSegment("1", mockNode));
    }

    @Test
    public void testSpecificIndexPath() {
        pathLimits = new PathLimits.Builder()
                .addPath("/forX/d/0")
                .build();

        assertTrue(pathLimits.shouldExtendPathSegment("forX", mockNode));
        pathLimits.enterPathSegment("forX");

        assertTrue(pathLimits.shouldExtendPathSegment("d", mockNode));
        pathLimits.enterPathSegment("d");

        assertTrue(pathLimits.shouldExtendPathSegment("0", mockNode));
        pathLimits.enterPathSegment("0");

        assertFalse(pathLimits.shouldExtendPathSegment("any", mockNode));

        pathLimits.exitPathSegment();

        assertFalse(pathLimits.shouldExtendPathSegment("1", mockNode));
    }

    @Test
    public void testTwoLevelWildcard() {
        assertTrue(pathLimits.shouldExtendPathSegment("f", mockNode));
        pathLimits.enterPathSegment("f");

        assertTrue(pathLimits.shouldExtendPathSegment("anySegment", mockNode));
        pathLimits.enterPathSegment("anySegment");

        assertTrue(pathLimits.shouldExtendPathSegment("anotherSegment", mockNode));
        pathLimits.enterPathSegment("anotherSegment");

        assertFalse(pathLimits.shouldExtendPathSegment("tooDeep", mockNode));

        pathLimits.exitPathSegment();
        pathLimits.exitPathSegment();
        assertTrue(pathLimits.shouldExtendPathSegment("differentSegment", mockNode));
        pathLimits.enterPathSegment("differentSegment");

        assertTrue(pathLimits.shouldExtendPathSegment("lastSegment", mockNode));
        pathLimits.enterPathSegment("lastSegment");

        assertFalse(pathLimits.shouldExtendPathSegment("tooDeepAgain", mockNode));

        pathLimits.exitPathSegment();
        pathLimits.exitPathSegment();
        pathLimits.exitPathSegment();
        assertFalse(pathLimits.shouldExtendPathSegment("g", mockNode));
    }

    @Test
    public void testConstraintsAndBlueId() throws Exception {
        String a = "name: A\n" +
                   "x:\n" +
                   "  description: aa\n" +
                   "  constraints:\n" +
                   "    maxLength: 4\n" +
                   "y:\n" +
                   "  constraints:\n" +
                   "    maxLength: 4";
        Node aNode = YAML_MAPPER.readValue(a, Node.class);

        String b = "name: B\n" +
                   "type:\n" +
                   "  blueId: " + calculateBlueId(aNode) + "\n" +
                   "x:\n" +
                   "  blueId: some-blue-id\n" +
                   "y: abcd";
        Node bNode = YAML_MAPPER.readValue(b, Node.class);

        String bInst = "name: B Inst\n" +
                       "type:\n" +
                       "  blueId: " + calculateBlueId(bNode) + "\n" +
                       "x:\n" +
                       "  blueId: some-blue-id\n" +
                       "y: abcd";
        Node bInstNode = YAML_MAPPER.readValue(bInst, Node.class);

        BasicNodeProvider nodeProvider = new BasicNodeProvider(aNode, bNode, bInstNode);
        Blue blue = new Blue(nodeProvider);

        String typeBlueId = calculateBlueId(bNode);
        Set<String> ignoredProperties = new HashSet<>(Collections.singletonList("x"));
        Limits globalLimits = new TypeSpecificPropertyFilter(typeBlueId, ignoredProperties);

        boolean result = new NodeTypeMatcher(blue).matchesType(bInstNode, bNode, globalLimits);

        if (!result) {
            System.out.println("bInstNode: \n" + YAML_MAPPER.writeValueAsString(bInstNode));
            System.out.println("bNode: \n" + YAML_MAPPER.writeValueAsString(bNode));
        }

        assertTrue(result);
    }

}