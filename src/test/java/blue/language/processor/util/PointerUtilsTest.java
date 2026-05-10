package blue.language.processor.util;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PointerUtilsTest {

    @Test
    void splitAndJoinUseJsonPointerEscaping() {
        assertEquals(Arrays.asList("a/b", "c~d", ""), PointerUtils.splitPointer("/a~1b/c~0d/"));
        assertEquals("/a~1b/c~0d/", PointerUtils.toPointer(Arrays.asList("a/b", "c~d", "")));
        assertEquals("/a~1b/c~0d", PointerUtils.appendPointer("/a~1b", "c~d"));
    }

    @Test
    void resolveAndRelativizeCompareDecodedSegments() {
        assertEquals("/scope~1a/child~0b", PointerUtils.resolvePointer("/scope~1a", "/child~0b"));
        assertEquals("/child~0b", PointerUtils.relativizePointer("/scope~1a", "/scope~1a/child~0b"));
        assertEquals("/scope~1ab/child", PointerUtils.relativizePointer("/scope~1a", "/scope~1ab/child"));
    }

    @Test
    void joinRelativePointersEscapesLiteralSegments() {
        assertEquals("/a~1b/c~0d", PointerUtils.joinRelativePointers("/a~1b", "c~d"));
    }
}
