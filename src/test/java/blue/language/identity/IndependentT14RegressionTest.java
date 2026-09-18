package blue.language.identity;

import blue.language.codec.BlueFormat;
import blue.language.codec.StandardBlueCodec;
import blue.language.model.Node;
import blue.language.codec.jackson.UncheckedObjectMapper.JsonException;
import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static blue.language.codec.jackson.UncheckedObjectMapper.JSON_MAPPER;
import static blue.language.codec.jackson.UncheckedObjectMapper.YAML_MAPPER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndependentT14RegressionTest {

    private final StandardBlueCodec codec = new StandardBlueCodec();

    @Test
    void shouldPreserveQuotedPunctuation() {
        // given
        String yaml = "'hello !literal &literal'";

        // when
        Node node = codec.parseSource(yaml, BlueFormat.YAML);

        // then
        assertEquals("hello !literal &literal", node.getValue());
    }

    @Test
    void shouldIgnoreCommentPunctuation() {
        // given
        String yaml = "hello # !literal &literal";

        // when
        Node node = codec.parseSource(yaml, BlueFormat.YAML);

        // then
        assertEquals("hello", node.getValue());
    }

    @Test
    void shouldRejectFlowAnchorsAndAliases() {
        // given
        String yaml = "[&x 1,*x]";

        // when
        Supplier<Node> parse = () -> codec.parseSource(yaml, BlueFormat.YAML);

        // then
        assertThrows(JsonException.class, parse::get);
    }

    @Test
    void shouldAdmitOrdinaryPortableInput() {
        // given
        String yaml = "'hello'";

        // when
        Node node = codec.parseSource(yaml, BlueFormat.YAML);

        // then
        assertEquals("hello", node.getValue());
    }

    @ParameterizedTest
    @MethodSource("literalScalars")
    void shouldPreserveLiteralTextAndIdentity(String yaml, String expected) {
        // given
        Node expectedNode = new Node().value(expected);

        // when
        Node actual = codec.parseSource(yaml, BlueFormat.YAML);

        // then
        assertEquals(expected, actual.getValue());
        assertEquals(DirectBlueIdCalculator.calculateBlueId(expectedNode),
                DirectBlueIdCalculator.calculateBlueId(actual));
    }

    static Stream<Arguments> literalScalars() {
        return Stream.of(
                Arguments.of("\"hello !tag &anchor *alias <<\"", "hello !tag &anchor *alias <<"),
                Arguments.of("'hello !tag &anchor *alias <<'", "hello !tag &anchor *alias <<"),
                Arguments.of("'it''s !tag &anchor'", "it's !tag &anchor"),
                Arguments.of("\"escaped \\\" !tag &anchor \\\"\"", "escaped \" !tag &anchor \""),
                Arguments.of("\"\\u0021tag \\u0026anchor\"", "!tag &anchor"),
                Arguments.of("'hello\n  !tag &anchor'", "hello !tag &anchor"),
                Arguments.of("plain !tag &anchor *alias <<", "plain !tag &anchor *alias <<"),
                Arguments.of("|\n  !tag &anchor *alias <<\n", "!tag &anchor *alias <<\n"),
                Arguments.of("|-\n  !tag\n  &anchor\n", "!tag\n&anchor"),
                Arguments.of("|+\n  !tag &anchor\n\n", "!tag &anchor\n\n"),
                Arguments.of(">\n  !tag\n  &anchor\n", "!tag &anchor\n"),
                Arguments.of(">-\n  !tag\n  &anchor\n", "!tag &anchor"),
                Arguments.of("|2-\n  !tag\n    &anchor\n", "!tag\n  &anchor"),
                Arguments.of("|\r\n  !tag &anchor\r\n", "!tag &anchor\n"),
                Arguments.of("hello # !tag &anchor *alias <<", "hello"),
                Arguments.of("# !tag &anchor *alias <<\nhello", "hello"),
                Arguments.of("<<", "<<"),
                Arguments.of("'yes'", "yes"),
                Arguments.of("'off'", "off"),
                Arguments.of("'012'", "012"),
                Arguments.of("2026-09-16", "2026-09-16"));
    }

    @ParameterizedTest
    @MethodSource("forbiddenSyntax")
    void shouldRejectForbiddenTokensBeforeNodeConstruction(String yaml, String reason) {
        // given
        List<Supplier<Node>> parsers = Arrays.asList(
                () -> codec.parseSource(yaml, BlueFormat.YAML),
                () -> codec.parseBlueIdInput(yaml, BlueFormat.YAML));

        // when
        Stream<Supplier<Node>> attempts = parsers.stream();

        // then
        attempts.forEach(parse -> {
            JsonException failure = assertThrows(JsonException.class, parse::get, yaml);
            assertTrue(failure.getMessage().contains(reason), failure.getMessage());
        });
    }

    static Stream<Arguments> forbiddenSyntax() {
        Stream<Arguments> references = Stream.of(
                "&x text", "*missing", "[&x 1,*x]", "[*missing]",
                "[&x 1]", "[&x [1]]", "[&x {a: 1}]", "{a: &x 1,b: *x}",
                "{a: *missing}", "{&x key: text}", "{*missing: text}",
                "a: &x text\nb: *x", "- &x text\n- *x", "&x {a: 1}",
                "a: &x\n  b: text", "a: [&x [*x]]", "[&é text]")
                .map(yaml -> Arguments.of(yaml, "anchors and aliases"));
        Stream<Arguments> tags = Stream.of(
                "!custom text", "[!custom text]", "!custom {a: 1}",
                "!custom [a]", "{!custom key: text}", "!<tag:example.org,2026:test> text",
                "%TAG !e! tag:example.org,2026:\n---\n!e!test text",
                "!!binary SGVsbG8=", "!!set {a: null}", "!!omap [{a: 1}]",
                "!!str text")
                .map(yaml -> Arguments.of(yaml, "YAML tags"));
        Stream<Arguments> merges = Stream.of(
                "<<: {a: 1}", "{<<: {a: 1}}", "a: {<<: [{b: 1}, {c: 2}]}",
                "a:\n  <<:\n    b: 1", "[ {<<: {a: 1}} ]", "{? << : {a: 1}}")
                .map(yaml -> Arguments.of(yaml, "merge keys"));
        return Stream.concat(Stream.concat(references, tags), merges);
    }

    @Test
    void shouldTreatQuotedMergeKeysAndMergeValuesAsOrdinaryText() {
        // given
        String yaml = "'<<': first\nnested: {\"<<\": second}\ntext: <<\nlist: [<<]";

        // when
        Node node = codec.parseSource(yaml, BlueFormat.YAML);
        Node roundTrip = codec.parseSource(codec.write(node, BlueFormat.YAML), BlueFormat.YAML);

        // then
        assertEquals("first", node.getProperties().get("<<").getValue());
        assertEquals("second", node.getProperties().get("nested").getProperties().get("<<").getValue());
        assertEquals("<<", node.getProperties().get("text").getValue());
        assertEquals("<<", node.getProperties().get("list").getItems().get(0).getValue());
        assertEquals(DirectBlueIdCalculator.calculateBlueId(node),
                DirectBlueIdCalculator.calculateBlueId(roundTrip));
    }

    @Test
    void shouldKeepDifferentBlockScalarBytesIdentityBearing() {
        // given
        String stripped = "|-\n  !tag &anchor\n";
        String clipped = "|\n  !tag &anchor\n";

        // when
        Node withoutNewline = codec.parseSource(stripped, BlueFormat.YAML);
        Node withNewline = codec.parseSource(clipped, BlueFormat.YAML);

        // then
        assertNotEquals(DirectBlueIdCalculator.calculateBlueId(withoutNewline),
                DirectBlueIdCalculator.calculateBlueId(withNewline));
    }

    @ParameterizedTest
    @MethodSource("invalidObjectKeys")
    void shouldRetainDuplicateAndNonStringKeyRejection(String yaml) {
        // given
        BlueFormat format = BlueFormat.YAML;

        // when
        Supplier<Node> parse = () -> codec.parseSource(yaml, format);

        // then
        assertThrows(JsonException.class, parse::get);
    }

    static Stream<String> invalidObjectKeys() {
        return Stream.of("x: 1\nx: 2", "{x: 1, x: 2}", "1: text", "true: text",
                "null: text", "1e2: text", "? [a, b]\n: text", "? {a: b}\n: text");
    }

    @Test
    void shouldKeepQuotedAndJsonSchemaStringKeysLegal() {
        // given
        String yaml = "yes: first\noff: second\n'1': third\n'null': fourth";

        // when
        Node actual = codec.parseSource(yaml, BlueFormat.YAML);

        // then
        assertEquals(4, actual.getProperties().size());
        assertEquals("first", actual.getProperties().get("yes").getValue());
        assertEquals("third", actual.getProperties().get("1").getValue());
    }

    @Test
    void shouldLeaveJsonPunctuationAndKeyHandlingUnchanged() {
        // given
        String json = "{\"<<\":\"!tag &anchor *alias\"}";

        // when
        Node actual = codec.parseSource(json, BlueFormat.JSON);

        // then
        assertEquals("!tag &anchor *alias", actual.getProperties().get("<<").getValue());
        assertThrows(JsonException.class,
                () -> codec.parseSource("{\"x\":1,\"x\":2}", BlueFormat.JSON));
    }

    @Test
    void shouldUseTokenScreeningAcrossGuardedMapperEntryPoints() {
        // given
        String valid = "'hello !tag &anchor'";
        String invalid = "[&x 1,*x]";

        // when
        List<Object> accepted = Arrays.asList(
                YAML_MAPPER.readValue(valid, Object.class),
                YAML_MAPPER.readValue(valid, new TypeReference<Object>() { }),
                YAML_MAPPER.readValue(valid, YAML_MAPPER.constructType(Object.class)),
                YAML_MAPPER.readValue(utf8(valid), Object.class),
                YAML_MAPPER.readValue(utf8(valid), new TypeReference<Object>() { }),
                YAML_MAPPER.readTree(valid).textValue());

        // then
        accepted.forEach(value -> assertEquals("hello !tag &anchor", value));
        assertThrows(JsonException.class, () -> YAML_MAPPER.readValue(invalid, Object.class));
        assertThrows(JsonException.class,
                () -> YAML_MAPPER.readValue(invalid, new TypeReference<Object>() { }));
        assertThrows(JsonException.class,
                () -> YAML_MAPPER.readValue(invalid, YAML_MAPPER.constructType(Object.class)));
        assertThrows(JsonException.class, () -> YAML_MAPPER.readValue(utf8(invalid), Object.class));
        assertThrows(JsonException.class,
                () -> YAML_MAPPER.readValue(utf8(invalid), new TypeReference<Object>() { }));
        assertThrows(JsonException.class, () -> YAML_MAPPER.readTree(invalid));
    }

    @Test
    void shouldPreserveRawYamlInsideAJsonTestEnvelope() {
        // given
        String raw = "[&x 1,*x]";
        String envelope = JSON_MAPPER.writeValueAsString(raw);

        // when
        String restored = JSON_MAPPER.readValue(envelope, String.class);

        // then
        assertEquals(raw, restored);
        assertThrows(JsonException.class, () -> codec.parseSource(restored, BlueFormat.YAML));
    }

    private static ByteArrayInputStream utf8(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }
}
