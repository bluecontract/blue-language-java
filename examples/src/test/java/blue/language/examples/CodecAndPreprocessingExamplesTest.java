package blue.language.examples;

import blue.language.model.Node;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static blue.language.model.wire.BlueLanguageConstants.TEXT_TYPE_BLUE_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CodecAndPreprocessingExamplesTest {

    @Test
    void shouldParseAndSerializeWithoutChangingSourceIdentity() {
        // given
        String expectedValue = "hello";

        // when
        ParseAndSerializeExample.Result result =
                ParseAndSerializeExample.run();

        // then
        assertEquals(expectedValue, result.getValue());
        assertFalse(result.getBlueId().isEmpty());
        assertTrue(result.getJson().contains(expectedValue));
        assertTrue(result.getYaml().contains(expectedValue));
    }

    @Test
    void shouldCalculateReleasedDirectBlueIdFromEquivalentInputs() {
        // given
        String expectedBlueId = DirectBlueIdExample.INTEGER_ONE_BLUE_ID;

        // when
        DirectBlueIdExample.Result result = DirectBlueIdExample.run();

        // then
        assertEquals(expectedBlueId, result.getInlineBlueId());
        assertEquals(expectedBlueId, result.getWrappedBlueId());
    }

    @Test
    void shouldCalculateSourceDocumentBlueIdThroughCanonicalInput() {
        // given
        String expectedTypeBlueId = TEXT_TYPE_BLUE_ID;

        // when
        SourceDocumentBlueIdExample.Result result =
                SourceDocumentBlueIdExample.run();

        // then
        Node canonical = result.getCanonical();
        assertNull(canonical.getBlue());
        assertEquals(expectedTypeBlueId, canonical.getType().getBlueId());
        assertEquals(result.getDirectBlueId(), result.getSourceBlueId());
    }

    @Test
    void shouldApplyImportsAndTransformationsInDeclarationOrder() {
        // given
        java.util.List<String> expectedOrder =
                Arrays.asList("first", "second");

        // when
        PreprocessingDirectiveExample.Result result =
                PreprocessingDirectiveExample.run();

        // then
        assertEquals(expectedOrder, result.getExecutionOrder());
        assertEquals("start-first-second",
                result.getPreprocessed().getValue());
        assertEquals(TEXT_TYPE_BLUE_ID,
                result.getPreprocessed().getType().getBlueId());
        assertNull(result.getPreprocessed().getBlue());
        assertTrue(result.getSource().getBlue() != null);
    }
}
