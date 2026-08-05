package blue.language.processor.util;

import blue.language.processor.FailureCapture;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PointerUtilsTest {

    @Test
    void shouldDecodeEscapedSegmentsWhenSplittingJsonPointer() {
        // given
        String pointer = "/a~1b/c~0d/";

        // when
        List<String> segments = PointerUtils.splitPointer(pointer);

        // then
        assertEquals(Arrays.asList("a/b", "c~d", ""), segments);
    }

    @Test
    void shouldEncodeEscapedSegmentsWhenBuildingJsonPointer() {
        // given
        List<String> segments = Arrays.asList("a/b", "c~d", "");

        // when
        String pointer = PointerUtils.toPointer(segments);

        // then
        assertEquals("/a~1b/c~0d/", pointer);
    }

    @Test
    void shouldEscapeChildSegmentWhenAppendingJsonPointer() {
        // given
        String parent = "/a~1b";
        String child = "c~d";

        // when
        String pointer = PointerUtils.appendPointer(parent, child);

        // then
        assertEquals("/a~1b/c~0d", pointer);
    }

    @Test
    void shouldCompareDecodedSegmentsWhenResolvingAndRelativizing() {
        // given
        String scope = "/scope~1a";

        // when
        String resolved =
                PointerUtils.resolvePointer(scope, "/child~0b");
        String relativeChild =
                PointerUtils.relativizePointer(
                        scope, "/scope~1a/child~0b");
        String relativeSibling =
                PointerUtils.relativizePointer(
                        scope, "/scope~1ab/child");

        // then
        assertEquals("/scope~1a/child~0b", resolved);
        assertEquals("/child~0b", relativeChild);
        assertEquals("/scope~1ab/child", relativeSibling);
    }

    @Test
    void shouldEscapeLiteralSegmentsWhenJoiningRelativePointers() {
        // given
        String parent = "/a~1b";
        String child = "c~d";

        // when
        String pointer =
                PointerUtils.joinRelativePointers(parent, child);

        // then
        assertEquals("/a~1b/c~0d", pointer);
    }

    @Test
    void shouldVerifyDescendantChecksAreSegmentAware() {
        // given
        String ancestor = "/a";

        // when
        boolean sameIsDescendant =
                PointerUtils.descendantOrEqual("/a", ancestor);
        boolean childIsDescendant =
                PointerUtils.descendantOrEqual("/a/b", ancestor);
        boolean siblingPrefixIsDescendant =
                PointerUtils.descendantOrEqual("/ab", ancestor);
        boolean sameIsStrictlyInside =
                PointerUtils.strictlyInside("/a", ancestor);
        boolean childIsStrictlyInside =
                PointerUtils.strictlyInside("/a/b", ancestor);

        // then
        assertTrue(sameIsDescendant);
        assertTrue(childIsDescendant);
        assertFalse(siblingPrefixIsDescendant);
        assertFalse(sameIsStrictlyInside);
        assertTrue(childIsStrictlyInside);
    }

    @Test
    void shouldVerifyRuntimePointerValidationRejectsMalformedPointers() {
        // given
        List<String> invalidPointers =
                Arrays.asList("", "a", "/a/", "/a//b", "/a~2b");

        // when
        String root = validateRuntimePointer("/");
        String escaped =
                validateRuntimePointer("/a~1b/c~0d");
        List<Throwable> failures = invalidPointers.stream()
                .map(pointer -> FailureCapture
                        .<Throwable>captureFailure(
                        () -> validateRuntimePointer(pointer)))
                .collect(Collectors.toList());

        // then
        assertEquals("/", root);
        assertEquals("/a~1b/c~0d", escaped);
        failures.forEach(failure ->
                assertInstanceOf(
                        IllegalArgumentException.class, failure));
    }

    private static String validateRuntimePointer(String pointer) {
        return PointerUtils.assertValidRuntimePointer(pointer);
    }
}
