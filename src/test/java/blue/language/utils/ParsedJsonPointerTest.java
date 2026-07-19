package blue.language.utils;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParsedJsonPointerTest {

    @Test
    void canonicalizesAndDecodesOnce() {
        ParsedJsonPointer pointer = ParsedJsonPointer.parse("a/~0key/~1value");

        assertEquals("/a/~0key/~1value", pointer.pointer());
        assertEquals(Arrays.asList("a", "~key", "/value"), pointer.segments());
        assertEquals("/value", pointer.leaf());
        assertEquals(3, pointer.depth());
        assertThrows(UnsupportedOperationException.class, () -> pointer.segments().add("x"));
    }

    @Test
    void rootAndParentUseHistoricalRootSpelling() {
        ParsedJsonPointer root = ParsedJsonPointer.parse("");

        assertEquals("/", root.pointer());
        assertTrue(root.isRoot());
        assertSame(root, root.parent());
        assertEquals("/a", root.append("a").pointer());
        assertEquals("/", ParsedJsonPointer.parse("/a").parent().pointer());
    }

    @Test
    void ancestorAndOverlapCompareDecodedSegmentsNotStringPrefixes() {
        ParsedJsonPointer a = ParsedJsonPointer.parse("/a");
        ParsedJsonPointer child = ParsedJsonPointer.parse("/a/b");
        ParsedJsonPointer siblingPrefix = ParsedJsonPointer.parse("/ab");

        assertTrue(a.isAncestorOfOrEqual(a));
        assertTrue(a.isAncestorOfOrEqual(child));
        assertTrue(a.overlaps(child));
        assertFalse(a.isAncestorOfOrEqual(siblingPrefix));
        assertFalse(a.overlaps(siblingPrefix));
    }

    @Test
    void classifiesArrayLeavesWithoutThrowing() {
        assertEquals(12, ParsedJsonPointer.parse("/rows/12").arrayIndex());
        assertEquals(-1, ParsedJsonPointer.parse("/rows/-").arrayIndex());
        assertTrue(ParsedJsonPointer.parse("/rows/-").isAppend());
        assertTrue(ParsedJsonPointer.parse("/rows/12").hasArrayIndexLeaf());
        assertFalse(ParsedJsonPointer.parse("/rows/nope").hasArrayIndexLeaf());
        assertEquals(-1, ParsedJsonPointer.parse("/rows/999999999999999999").arrayIndex());
    }
}
