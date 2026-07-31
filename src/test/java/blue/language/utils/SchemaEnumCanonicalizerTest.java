package blue.language.utils;

import blue.language.model.Node;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static blue.language.processor.FailureCapture.captureFailure;
import static blue.language.utils.Properties.DOUBLE_TYPE_BLUE_ID;
import static blue.language.utils.Properties.TEXT_TYPE_BLUE_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class SchemaEnumCanonicalizerTest {

    @Test
    void shouldSortPunctuationNumbersAndUnicodeByCanonicalUtf8Bytes() {
        // given
        List<Node> authoredPunctuation = Arrays.asList(
                scalar("CRU-LONG"),
                scalar("CRU"),
                scalar("Transfer_or_Adjust"),
                scalar("Transfer"));
        List<Node> authoredIntegers = Arrays.asList(
                scalar(BigInteger.ONE),
                scalar(BigInteger.TEN));
        List<Node> authoredUnicode = Arrays.asList(
                scalar("\uD800\uDC00"),
                scalar("\uE000"));

        // when
        List<Node> punctuation =
                SchemaEnumCanonicalizer.canonicalize(
                        authoredPunctuation);
        List<Node> integers =
                SchemaEnumCanonicalizer.canonicalize(
                        authoredIntegers);
        List<Node> unicode =
                SchemaEnumCanonicalizer.canonicalize(
                        authoredUnicode);

        // then
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
    void shouldNormalizeTypedIdentityDeduplicateAndNotMutateInput() {
        // given
        Node bareA = scalar("A");
        Node explicitA = scalar("A")
                .type(new Node().blueId(TEXT_TYPE_BLUE_ID));
        List<Node> source = Arrays.asList(
                scalar("B"),
                bareA,
                explicitA,
                scalar("B"));

        // when
        List<Node> canonical =
                SchemaEnumCanonicalizer.canonicalize(
                        source);

        // then
        assertEquals(Arrays.asList("A", "B"), stringValues(canonical));
        assertEquals(Arrays.asList("B", "A", "A", "B"), stringValues(source));
        assertEquals(4, source.size());
    }

    @Test
    void shouldKeepIntegerAndDoubleIdentityDistinct() {
        // given
        Node integer = scalar(BigInteger.ONE);
        Node doubleValue = scalar(new BigDecimal("1.0"))
                .type(new Node().blueId(DOUBLE_TYPE_BLUE_ID));

        // when
        String integerKey =
                SchemaEnumCanonicalizer.canonicalKey(integer);
        String doubleKey =
                SchemaEnumCanonicalizer.canonicalKey(
                        doubleValue);
        List<Node> canonical =
                SchemaEnumCanonicalizer.canonicalize(
                        Arrays.asList(integer, doubleValue));

        // then
        assertNotEquals(
                integerKey,
                doubleKey);
        assertEquals(
                2,
                canonical.size());
    }

    @Test
    void shouldRejectDeclarationMetadataInsteadOfSilentlyHashingIt() {
        // given
        Node invalid = scalar("A").name("label");

        // when
        Throwable failure =
                captureFailure(
                        () -> SchemaEnumCanonicalizer
                                .canonicalize(
                                        Arrays.asList(
                                                invalid)));

        // then
        assertInstanceOf(
                IllegalArgumentException.class,
                failure);
    }

    @Test
    void shouldCanonicalizeAndDeduplicatePureReferenceEntries() {
        // given
        Node referencedValue = scalar("referenced");
        String referencedBlueId =
                BlueIdCalculator.calculateBlueId(referencedValue);
        Node reference = new Node().blueId(referencedBlueId);
        List<Node> authored = Arrays.asList(
                scalar("inline"),
                reference,
                reference.clone());

        // when
        List<Node> canonical =
                SchemaEnumCanonicalizer.canonicalize(authored);

        // then
        assertEquals(2, canonical.size());
        assertEquals(
                1L,
                canonical.stream()
                        .filter(Node::isReferenceOnly)
                        .count());
        assertEquals(
                referencedBlueId,
                canonical.stream()
                        .filter(Node::isReferenceOnly)
                        .findFirst()
                        .get()
                        .getBlueId());
        assertEquals(3, authored.size());
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
