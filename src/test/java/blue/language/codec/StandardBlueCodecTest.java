package blue.language.codec;

import blue.language.model.wire.BlueLanguageConstants;

import blue.language.model.Node;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import static blue.language.model.wire.BlueLanguageConstants.TEXT_TYPE_BLUE_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class StandardBlueCodecTest {

    private final BlueCodec codec = new StandardBlueCodec();

    @Test
    void shouldParseSourceWithoutRunningPreprocessing() {
        // given
        String yaml = "type: Text\nvalue: hello";

        // when
        Node source = codec.parseSource(yaml, BlueFormat.YAML);

        // then
        assertEquals("Text", source.getType().getValue());
        assertEquals("hello", source.getValue());
    }

    @Test
    void shouldParseAndValidateDirectBlueIdInput() {
        // given
        String json = "{\"type\":{\"blueId\":\""
                + TEXT_TYPE_BLUE_ID + "\"},\"value\":\"hello\"}";

        // when
        Node exactInput = codec.parseBlueIdInput(json, BlueFormat.JSON);

        // then
        assertEquals(TEXT_TYPE_BLUE_ID, exactInput.getType().getBlueId());
        assertEquals("hello", exactInput.getValue());
    }

    @Test
    void shouldRejectSourceOnlyBlueIdInput() {
        // given
        String yaml = "type: Text\nvalue: hello";

        // when
        Executable parsing =
                () -> codec.parseBlueIdInput(yaml, BlueFormat.YAML);

        // then
        assertThrows(IllegalArgumentException.class, parsing);
    }

    @Test
    void shouldRoundTripNormalizedJson() {
        // given
        Node original = new Node().value("hello");

        // when
        String json = codec.write(original, BlueFormat.JSON);
        Node roundTrip = codec.parseSource(json, BlueFormat.JSON);

        // then
        assertEquals("hello", roundTrip.getValue());
        assertEquals(TEXT_TYPE_BLUE_ID,
                roundTrip.getType().getBlueId());
    }
}
