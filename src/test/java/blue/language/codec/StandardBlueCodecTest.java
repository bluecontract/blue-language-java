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
    @Test
    void shouldPreserveQuotedNumericTextAcrossYamlRoundTrip() {
        // given
        String[] texts = {"7", "7.0", "-2", "1e3", "-2e-3", "1E+3",
                "12345678901234567890", "true", "null"};
        java.util.List<String> actual = new java.util.ArrayList<>();

        // when
        for (String text : texts) {
            Node original = codec.parseSource("\"" + text + "\"", BlueFormat.JSON);
            String yaml = codec.write(original, BlueFormat.YAML);
            actual.add((String) codec.parseSource(yaml, BlueFormat.YAML).getValue());
        }

        // then
        assertEquals(java.util.Arrays.asList(texts), actual);
    }

    @Test
    void shouldRejectNonStringYamlKeysBeforeJacksonCoercion() {
        // given
        String[] sources = {"1: x", "false: x", "null: x", "{1.5: x}",
                "outer: {1: x}", "? [a, b]\n: c", "? {a: b}\n: c"};
        java.util.List<Executable> parsing = new java.util.ArrayList<>();

        // when
        for (String yaml : sources) {
            parsing.add(() -> codec.parseSource(yaml, BlueFormat.YAML));
        }

        // then
        for (Executable operation : parsing) {
            assertThrows(RuntimeException.class, operation);
        }
    }

    @Test
    void shouldKeepQuotedNumericKeysAndJsonSchemaTextKeys() {
        // given
        String[] sources = {"\"1\": x", "'false': x", "yes: x", "no: x"};
        java.util.List<Node> parsed = new java.util.ArrayList<>();

        // when
        for (String yaml : sources) parsed.add(codec.parseSource(yaml, BlueFormat.YAML));

        // then
        for (Node node : parsed) {
            assertEquals(1, node.getProperties().size());
            assertEquals("x", node.getProperties().values().iterator().next().getValue());
        }
    }

}
