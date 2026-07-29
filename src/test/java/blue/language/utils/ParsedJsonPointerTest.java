package blue.language.utils;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static blue.language.processor.FailureCapture.captureFailure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParsedJsonPointerTest {

    @Test
    void shouldCanonicalizeAndDecodeOnce() {
        // given
        String nonCanonicalPointer = "a/~0key/~1value";

        // when
        ParsedJsonPointer pointer = ParsedJsonPointer.parse(nonCanonicalPointer);
        Throwable mutationFailure =
                captureFailure(() -> pointer.segments().add("x"));

        // then
        assertEquals("/a/~0key/~1value", pointer.pointer());
        assertEquals(Arrays.asList("a", "~key", "/value"), pointer.segments());
        assertEquals("/value", pointer.leaf());
        assertEquals(3, pointer.depth());
        assertInstanceOf(UnsupportedOperationException.class, mutationFailure);
    }

    @Test
    void shouldRootAndParentUseHistoricalRootSpelling() {
        // given
        String emptyPointer = "";

        // when
        ParsedJsonPointer root = ParsedJsonPointer.parse(emptyPointer);
        ParsedJsonPointer rootParent = root.parent();
        ParsedJsonPointer appended = root.append("a");
        ParsedJsonPointer childParent = ParsedJsonPointer.parse("/a").parent();

        // then
        assertEquals("/", root.pointer());
        assertTrue(root.isRoot());
        assertSame(root, rootParent);
        assertEquals("/a", appended.pointer());
        assertEquals("/", childParent.pointer());
    }

    @Test
    void shouldAncestorAndOverlapCompareDecodedSegmentsNotStringPrefixes() {
        // given
        String ancestorPointer = "/a";
        String childPointer = "/a/b";
        String siblingPrefixPointer = "/ab";

        // when
        ParsedJsonPointer ancestor = ParsedJsonPointer.parse(ancestorPointer);
        ParsedJsonPointer child = ParsedJsonPointer.parse(childPointer);
        ParsedJsonPointer siblingPrefix = ParsedJsonPointer.parse(siblingPrefixPointer);
        boolean includesSelf = ancestor.isAncestorOfOrEqual(ancestor);
        boolean includesChild = ancestor.isAncestorOfOrEqual(child);
        boolean overlapsChild = ancestor.overlaps(child);
        boolean includesSiblingPrefix = ancestor.isAncestorOfOrEqual(siblingPrefix);
        boolean overlapsSiblingPrefix = ancestor.overlaps(siblingPrefix);

        // then
        assertTrue(includesSelf);
        assertTrue(includesChild);
        assertTrue(overlapsChild);
        assertFalse(includesSiblingPrefix);
        assertFalse(overlapsSiblingPrefix);
    }

    @Test
    void shouldClassifyArrayLeavesWithoutThrowing() {
        // given
        String numericPointer = "/rows/12";
        String appendPointer = "/rows/-";
        String propertyPointer = "/rows/nope";
        String overflowingIndexPointer = "/rows/999999999999999999";

        // when
        ParsedJsonPointer numeric = ParsedJsonPointer.parse(numericPointer);
        ParsedJsonPointer append = ParsedJsonPointer.parse(appendPointer);
        ParsedJsonPointer property = ParsedJsonPointer.parse(propertyPointer);
        ParsedJsonPointer overflowingIndex =
                ParsedJsonPointer.parse(overflowingIndexPointer);
        int numericIndex = numeric.arrayIndex();
        int appendIndex = append.arrayIndex();
        boolean isAppend = append.isAppend();
        boolean numericHasArrayIndex = numeric.hasArrayIndexLeaf();
        boolean propertyHasArrayIndex = property.hasArrayIndexLeaf();
        int overflowingIndexValue = overflowingIndex.arrayIndex();

        // then
        assertEquals(12, numericIndex);
        assertEquals(-1, appendIndex);
        assertTrue(isAppend);
        assertTrue(numericHasArrayIndex);
        assertFalse(propertyHasArrayIndex);
        assertEquals(-1, overflowingIndexValue);
    }
}
