package blue.language.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CancellationException;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static blue.language.processor.FailureCapture.captureFailure;

class SchemaKeywordValidationTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("keywords")
    void shouldRejectMalformedScalarKindsAtTheOwningSchemaGetter(String keyword,
            BiConsumer<Schema, Node> setter, Function<Schema, Object> getter, Object valid) {
        // given
        Schema schema = new Schema();
        for (Node invalid : Arrays.asList(new Node().value("bogus"), new Node(),
                new Node().items(Collections.singletonList(new Node().value(true))),
                new Node().properties("unexpected", new Node().value(true)))) {
            setter.accept(schema, invalid);

            // when
            Throwable failure = captureFailure(() -> getter.apply(schema));

            // then
            assertInstanceOf(InvalidNodeStructureException.class, failure);
            assertTrue(failure.getMessage().contains(keyword));
            assertNull(failure.getCause(), "The pure validation owns the failure classification");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("counts")
    void shouldRejectFractionalCountsWithoutNarrowingLegalIntegers(String keyword,
            BiConsumer<Schema, Node> setter, Function<Schema, Object> getter, Object valid) {
        // given
        Schema schema = new Schema();
        setter.accept(schema, new Node().value(new BigDecimal("0.5")));
        BigInteger huge = BigInteger.TEN.pow(100);

        // when
        Throwable failure = captureFailure(() -> getter.apply(schema));
        setter.accept(schema, new Node().value(new BigDecimal(huge).setScale(2)));
        Object accepted = getter.apply(schema);

        // then
        assertInstanceOf(InvalidNodeStructureException.class, failure);
        assertTrue(failure.getMessage().contains(keyword));
        assertEquals(huge, accepted);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("keywords")
    void shouldPreserveLegalScalarValuesAndExactKeywordMetadata(String keyword,
            BiConsumer<Schema, Node> setter, Function<Schema, Object> getter, Object valid) {
        // given
        Schema schema = new Schema();
        Node exact = new Node().value(valid).description("retained keyword declaration");
        setter.accept(schema, exact);

        // when
        Object accepted = getter.apply(schema);

        // then
        assertEquals(valid, accepted, keyword);
        assertEquals("retained keyword declaration", exact.getDescription());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("counts")
    void shouldPreserveIntegerRepresentationFailuresAsOperational(String keyword,
            BiConsumer<Schema, Node> setter, Function<Schema, Object> getter, Object valid) {
        // given
        BigDecimal oversized = new BigDecimal(BigInteger.ONE, Integer.MIN_VALUE);
        Schema schema = new Schema();
        setter.accept(schema, new Node().value(oversized));

        // when
        Throwable conversionFailure = captureFailure(oversized::toBigIntegerExact);
        Throwable schemaFailure = captureFailure(() -> getter.apply(schema));

        // then
        assertInstanceOf(ArithmeticException.class, conversionFailure);
        assertInstanceOf(ArithmeticException.class, schemaFailure);
        assertEquals(conversionFailure.getMessage(), schemaFailure.getMessage(), keyword);
    }

    @Test
    void shouldPreserveUnknownKeywordAccessFaults() {
        // given
        for (RuntimeException fault : Arrays.asList(new IllegalArgumentException("host fault"),
                new ArithmeticException("host arithmetic"), new CancellationException("cancelled"))) {
            Node faulty = new Node() {
                @Override public Object getValue() { throw fault; }
            };
            Schema schema = new Schema().required(faulty).minItems(faulty).minimum(faulty);

            // when
            Throwable booleanFailure = captureFailure(schema::getRequiredValue);
            Throwable countFailure = captureFailure(schema::getMinItemsExact);
            Throwable numericFailure = captureFailure(schema::getMinimumValue);

            // then
            assertSame(fault, booleanFailure);
            assertSame(fault, countFailure);
            assertSame(fault, numericFailure);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("keywords")
    void shouldKeepUnresolvedReferenceAndTypeSuppliedKeywordValuesOperational(String keyword,
            BiConsumer<Schema, Node> setter, Function<Schema, Object> getter, Object valid) {
        // given
        String exactId = blue.language.identity.DirectBlueIdCalculator.calculateBlueId(new Node().value(valid));
        Schema schema = new Schema();
        for (Node unresolved : Arrays.asList(new Node().blueId(exactId),
                new Node().type(new Node().blueId(exactId)))) {
            setter.accept(schema, unresolved);

            // when
            Throwable failure = captureFailure(() -> getter.apply(schema));

            // then
            assertEquals(IllegalArgumentException.class, failure.getClass(), keyword);
            assertTrue(failure.getMessage().contains("resolved scalar"));
        }
    }

    @Test
    void shouldRejectMixedSchemaReferencesButPreservePureReferencesAndConverterFaults() {
        // given
        String exactId = blue.language.identity.DirectBlueIdCalculator.calculateBlueId(new Node().value("schema"));
        Schema pure = new Schema().blueId(exactId);
        Schema mixed = new Schema().blueId(exactId).required(true);
        IllegalArgumentException fault = new IllegalArgumentException("wire converter fault");
        Schema decorated = new Schema().minimum(new Node().value(BigDecimal.ONE).description("numeric declaration"));

        // when
        Object wireReference = SchemaWireForm.get(pure, node -> { throw new AssertionError("No reference expansion"); });
        Throwable mixedFailure = captureFailure(() -> SchemaWireForm.get(mixed, node -> node));
        Throwable converterFailure = captureFailure(() -> SchemaWireForm.get(decorated, node -> { throw fault; }));

        // then
        assertEquals(Collections.singletonMap("blueId", exactId), wireReference);
        assertInstanceOf(InvalidNodeStructureException.class, mixedFailure);
        assertTrue(mixedFailure.getMessage().contains("pure reference"));
        assertSame(fault, converterFailure);
    }

    static Stream<Arguments> keywords() {
        return Stream.concat(Stream.of(
                keyword("required", Schema::required, Schema::getRequiredValue, true),
                keyword("uniqueItems", Schema::uniqueItems, Schema::getUniqueItemsValue, false),
                keyword("minimum", Schema::minimum, Schema::getMinimumValue, new BigDecimal("0.5")),
                keyword("maximum", Schema::maximum, Schema::getMaximumValue, BigDecimal.ONE),
                keyword("exclusiveMinimum", Schema::exclusiveMinimum, Schema::getExclusiveMinimumValue, BigDecimal.ZERO),
                keyword("exclusiveMaximum", Schema::exclusiveMaximum, Schema::getExclusiveMaximumValue, BigDecimal.TEN),
                keyword("multipleOf", Schema::multipleOf, Schema::getMultipleOfValue, new BigDecimal("0.1"))), counts());
    }

    static Stream<Arguments> counts() {
        return Stream.of(
                keyword("minLength", Schema::minLength, Schema::getMinLengthExact, BigInteger.ZERO),
                keyword("maxLength", Schema::maxLength, Schema::getMaxLengthExact, BigInteger.TEN),
                keyword("minItems", Schema::minItems, Schema::getMinItemsExact, BigInteger.ZERO),
                keyword("maxItems", Schema::maxItems, Schema::getMaxItemsExact, BigInteger.TEN),
                keyword("minFields", Schema::minFields, Schema::getMinFieldsExact, BigInteger.ZERO),
                keyword("maxFields", Schema::maxFields, Schema::getMaxFieldsExact, BigInteger.TEN));
    }

    private static Arguments keyword(String name, BiConsumer<Schema, Node> setter,
            Function<Schema, Object> getter, Object valid) {
        return Arguments.of(name, setter, getter, valid);
    }
}
