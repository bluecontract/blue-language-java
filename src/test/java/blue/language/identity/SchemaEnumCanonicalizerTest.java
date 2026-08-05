package blue.language.identity;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.wire.BlueLanguageConstants;

import blue.language.model.Node;
import blue.language.model.Schema;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static blue.language.processor.FailureCapture.captureFailure;
import static blue.language.model.wire.BlueLanguageConstants.DOUBLE_TYPE_BLUE_ID;
import static blue.language.model.wire.BlueLanguageConstants.TEXT_TYPE_BLUE_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class SchemaEnumCanonicalizerTest {

    @Test
    void shouldIgnoreEnumAuthoringOrderAndDuplicatesForDirectBlueIds() {
        // given
        Node authoredSchema = schemaNode("add", "replace", "remove");
        Node duplicateSchema = schemaNode(
                "remove", "add", "replace", "add");
        Node canonicalSchema = schemaNode("add", "remove", "replace");
        Node authoredContainer = enclosingNode(
                "add", "replace", "remove");
        Node duplicateContainer = enclosingNode(
                "remove", "add", "replace", "add");
        Node canonicalContainer = enclosingNode(
                "add", "remove", "replace");

        // when
        String authoredSchemaBlueId =
                DirectBlueIdCalculator.calculateBlueId(authoredSchema);
        String duplicateSchemaBlueId =
                DirectBlueIdCalculator.calculateBlueId(duplicateSchema);
        String canonicalSchemaBlueId =
                DirectBlueIdCalculator.calculateBlueId(canonicalSchema);
        String authoredContainerBlueId =
                DirectBlueIdCalculator.calculateBlueId(authoredContainer);
        String duplicateContainerBlueId =
                DirectBlueIdCalculator.calculateBlueId(duplicateContainer);
        String canonicalContainerBlueId =
                DirectBlueIdCalculator.calculateBlueId(canonicalContainer);

        // then
        assertEquals(canonicalSchemaBlueId, authoredSchemaBlueId);
        assertEquals(canonicalSchemaBlueId, duplicateSchemaBlueId);
        assertEquals(canonicalContainerBlueId, authoredContainerBlueId);
        assertEquals(canonicalContainerBlueId, duplicateContainerBlueId);
    }

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
                DirectBlueIdCalculator.calculateBlueId(referencedValue);
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

    private static Node schemaNode(String... values) {
        return new Node().schema(enumSchema(values));
    }

    private static Node enclosingNode(String... values) {
        return new Node()
                .name("Operation")
                .schema(enumSchema(values))
                .value("add");
    }

    private static Schema enumSchema(String... values) {
        return new Schema().enumValues(
                Arrays.stream(values)
                        .map(SchemaEnumCanonicalizerTest::scalar)
                        .collect(Collectors.toList()));
    }

    private static List<String> stringValues(List<Node> nodes) {
        return nodes.stream()
                .map(node -> String.valueOf(node.getValue()))
                .collect(Collectors.toList());
    }
}
