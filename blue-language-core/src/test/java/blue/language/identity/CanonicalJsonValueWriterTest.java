package blue.language.identity;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CanonicalJsonValueWriterTest {

    @Test
    void shouldOrderObjectNamesByUtf16CodeUnits() {
        // given
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("\uE000", "bmp");
        value.put("\uD83D\uDE00", "supplementary");

        // when
        String canonical = canonical(value);

        // then
        assertEquals(
                "{\"\uD83D\uDE00\":\"supplementary\",\"\uE000\":\"bmp\"}",
                canonical);
    }

    @Test
    void shouldPreserveNormalizationAndUseJcsStringEscapes() {
        // given
        String controls = "\u0000\b\t\n\f\r\"\\/";

        // when
        String canonical = canonical(Arrays.asList(
                "\u00E9",
                "e\u0301",
                controls));

        // then
        assertEquals(
                "[\"\u00E9\",\"e\u0301\",\"\\u0000\\b\\t\\n\\f\\r\\\"\\\\/\"]",
                canonical);
    }

    @Test
    void shouldUseEcmascriptNumbersAtSafeIntegerAndExponentBoundaries() {
        // given
        Object value = Arrays.asList(
                new BigInteger("-9007199254740991"),
                new BigInteger("9007199254740991"),
                -0.0d,
                1.0e-7d,
                1.0e-6d,
                1.0e20d,
                1.0e21d,
                333333333.33333329d);

        // when / then
        assertEquals(
                "[-9007199254740991,9007199254740991,0,1e-7,0.000001,100000000000000000000,1e+21,333333333.3333333]",
                canonical(value));
    }

    @Test
    void shouldRejectUnpairedSurrogatesInValuesAndNames() {
        // given
        Map<String, Object> invalidName = new LinkedHashMap<>();
        invalidName.put("\uDC00", "value");

        // when / then
        assertThrows(
                IllegalArgumentException.class,
                () -> CanonicalJsonValueWriter.write("\uD800"));
        assertThrows(
                IllegalArgumentException.class,
                () -> CanonicalJsonValueWriter.write(invalidName));
    }

    private static String canonical(Object value) {
        return new String(
                CanonicalJsonValueWriter.write(value),
                StandardCharsets.UTF_8);
    }
}
