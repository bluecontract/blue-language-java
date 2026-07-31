package blue.language.utils;

import blue.language.model.Node;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static blue.language.utils.Properties.DOUBLE_TYPE_BLUE_ID;
import static blue.language.utils.Properties.TEXT_TYPE_BLUE_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SchemaEnumCanonicalizerTest {

    @Test
    void sortsPunctuationNumbersAndUnicodeByCanonicalUtf8Bytes() {
        List<Node> punctuation = SchemaEnumCanonicalizer.canonicalize(Arrays.asList(
                scalar("CRU-LONG"),
                scalar("CRU"),
                scalar("Transfer_or_Adjust"),
                scalar("Transfer")));
        List<Node> integers = SchemaEnumCanonicalizer.canonicalize(Arrays.asList(
                scalar(BigInteger.ONE),
                scalar(BigInteger.TEN)));
        List<Node> unicode = SchemaEnumCanonicalizer.canonicalize(Arrays.asList(
                scalar("\uD800\uDC00"),
                scalar("\uE000")));

        assertEquals(
                Arrays.asList("CRU", "CRU-LONG", "Transfer", "Transfer_or_Adjust"),
                stringValues(punctuation));
        assertEquals(
                Arrays.asList(BigInteger.TEN, BigInteger.ONE),
                Arrays.asList(
                        integers.get(0).getValue(),
                        integers.get(1).getValue()));
        assertEquals(
                Arrays.asList("\uE000", "\uD800\uDC00"),
                stringValues(unicode));
    }

    @Test
    void normalizesTypedIdentityDeduplicatesAndDoesNotMutateInput() {
        Node bareA = scalar("A");
        Node explicitA = scalar("A")
                .type(new Node().blueId(TEXT_TYPE_BLUE_ID));
        List<Node> source = Arrays.asList(
                scalar("B"),
                bareA,
                explicitA,
                scalar("B"));

        List<Node> canonical = SchemaEnumCanonicalizer.canonicalize(source);

        assertEquals(Arrays.asList("A", "B"), stringValues(canonical));
        assertEquals(Arrays.asList("B", "A", "A", "B"), stringValues(source));
        assertEquals(4, source.size());
    }

    @Test
    void keepsIntegerAndDoubleIdentityDistinct() {
        Node integer = scalar(BigInteger.ONE);
        Node doubleValue = scalar(new BigDecimal("1.0"))
                .type(new Node().blueId(DOUBLE_TYPE_BLUE_ID));

        assertNotEquals(
                SchemaEnumCanonicalizer.canonicalKey(integer),
                SchemaEnumCanonicalizer.canonicalKey(doubleValue));
        assertEquals(
                2,
                SchemaEnumCanonicalizer.canonicalize(
                        Arrays.asList(integer, doubleValue)).size());
    }

    @Test
    void rejectsDeclarationMetadataInsteadOfSilentlyHashingIt() {
        Node invalid = scalar("A").name("label");

        assertThrows(
                IllegalArgumentException.class,
                () -> SchemaEnumCanonicalizer.canonicalize(
                        Arrays.asList(invalid)));
    }

    private static Node scalar(Object value) {
        return new Node().value(value);
    }

    private static List<String> stringValues(List<Node> nodes) {
        return nodes.stream()
                .map(node -> String.valueOf(node.getValue()))
                .collect(Collectors.toList());
    }
}
