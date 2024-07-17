package blue.language.utils.limits;

import blue.language.utils.limits.PathLimits;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class PathLimitsTest {

    private PathLimits pathLimits;

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
        assertTrue(pathLimits.shouldProcessPathSegment("x"));
        pathLimits.enterPathSegment("x");
        assertTrue(pathLimits.shouldProcessPathSegment("a"));
        pathLimits.enterPathSegment("a");
        assertFalse(pathLimits.shouldProcessPathSegment("d"));
        pathLimits.exitPathSegment();
        assertTrue(pathLimits.shouldProcessPathSegment("y"));
        pathLimits.exitPathSegment();

        pathLimits.enterPathSegment("y");
        assertFalse(pathLimits.shouldProcessPathSegment("c"));
        pathLimits.exitPathSegment();

        pathLimits.enterPathSegment("a");
        pathLimits.enterPathSegment("b");
        assertTrue(pathLimits.shouldProcessPathSegment("d"));
        pathLimits.enterPathSegment("d");
        assertTrue(pathLimits.shouldProcessPathSegment("c"));
    }

    @Test
    public void testMaxDepth() {
        pathLimits.enterPathSegment("a");
        pathLimits.enterPathSegment("b");
        assertTrue(pathLimits.shouldProcessPathSegment("any"));
        pathLimits.enterPathSegment("any");
        assertTrue(pathLimits.shouldProcessPathSegment("c"));
        pathLimits.enterPathSegment("c");
        assertFalse(pathLimits.shouldProcessPathSegment("e"));
    }

    @Test
    public void testWildcardSingle() {
        pathLimits.enterPathSegment("a");
        pathLimits.enterPathSegment("b");
        assertTrue(pathLimits.shouldProcessPathSegment("any"));
        pathLimits.enterPathSegment("any");
        assertTrue(pathLimits.shouldProcessPathSegment("c"));
    }

    @Test
    public void testComplexPath() {
        pathLimits.enterPathSegment("a");
        pathLimits.enterPathSegment("b");
        assertTrue(pathLimits.shouldProcessPathSegment("c"));
        pathLimits.enterPathSegment("c");
        assertFalse(pathLimits.shouldProcessPathSegment("e"));
    }

    @Test
    public void testInvalidPath() {
        pathLimits.enterPathSegment("z");
        assertFalse(pathLimits.shouldProcessPathSegment("a"));
    }

    @Test
    public void testPathWithIndex() {
        pathLimits.enterPathSegment("d");
        assertTrue(pathLimits.shouldProcessPathSegment("0"));
        pathLimits.enterPathSegment("0");
        assertTrue(pathLimits.shouldProcessPathSegment("any"));
        pathLimits.exitPathSegment();
        assertFalse(pathLimits.shouldProcessPathSegment("1"));
    }

    @Test
    public void testMultipleWildcards() {
        pathLimits.enterPathSegment("e");
        assertTrue(pathLimits.shouldProcessPathSegment("0"));
        pathLimits.enterPathSegment("0");
        assertTrue(pathLimits.shouldProcessPathSegment("1"));
    }

    @Test
    public void testSpecificIndexPath() {
        pathLimits = new PathLimits.Builder()
                .addPath("/forX/d/0")
                .build();

        assertTrue(pathLimits.shouldProcessPathSegment("forX"));
        pathLimits.enterPathSegment("forX");

        assertTrue(pathLimits.shouldProcessPathSegment("d"));
        pathLimits.enterPathSegment("d");

        assertTrue(pathLimits.shouldProcessPathSegment("0"));
        pathLimits.enterPathSegment("0");

        assertFalse(pathLimits.shouldProcessPathSegment("any"));

        pathLimits.exitPathSegment();

        assertFalse(pathLimits.shouldProcessPathSegment("1"));
    }

    @Test
    public void testTwoLevelWildcard() {
        assertTrue(pathLimits.shouldProcessPathSegment("f"));
        pathLimits.enterPathSegment("f");

        assertTrue(pathLimits.shouldProcessPathSegment("anySegment"));
        pathLimits.enterPathSegment("anySegment");

        assertTrue(pathLimits.shouldProcessPathSegment("anotherSegment"));
        pathLimits.enterPathSegment("anotherSegment");

        assertFalse(pathLimits.shouldProcessPathSegment("tooDeep"));

        pathLimits.exitPathSegment();
        pathLimits.exitPathSegment();
        assertTrue(pathLimits.shouldProcessPathSegment("differentSegment"));
        pathLimits.enterPathSegment("differentSegment");

        assertTrue(pathLimits.shouldProcessPathSegment("lastSegment"));
        pathLimits.enterPathSegment("lastSegment");

        assertFalse(pathLimits.shouldProcessPathSegment("tooDeepAgain"));

        pathLimits.exitPathSegment();
        pathLimits.exitPathSegment();
        pathLimits.exitPathSegment();
        assertFalse(pathLimits.shouldProcessPathSegment("g"));
    }
}