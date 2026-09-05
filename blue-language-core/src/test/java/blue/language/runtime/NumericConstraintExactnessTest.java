package blue.language.runtime;

import blue.language.model.Node;
import blue.language.model.Schema;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;

import static blue.language.model.wire.BlueLanguageConstants.*;
import static org.junit.jupiter.api.Assertions.*;

final class NumericConstraintExactnessTest {
    @Test
    void shouldCompareBinary64PayloadAndBoundsByExactValue() {
        // given
        try (BlueLanguage language = BlueLanguage.builder().build()) {
            Node exact = integer("99999999999999991611392");
            Node approximate = decimal("1e23");
            Node doubleCandidate = approximate.clone().schema(new Schema().minimum(exact).maximum(exact));
            Node integerCandidate = exact.clone().schema(new Schema().minimum(approximate).maximum(approximate));
            // when
            Node doublePattern = new Node().schema(new Schema().minimum(exact).maximum(exact));
            Node integerPattern = new Node().schema(new Schema().minimum(approximate).maximum(approximate));
            // then
            assertDoesNotThrow(() -> language.resolution().resolve(doubleCandidate));
            assertDoesNotThrow(() -> language.resolution().resolve(integerCandidate));
            assertTrue(language.matching().matches(approximate, doublePattern));
            assertTrue(language.matching().matches(exact, integerPattern));
        }
    }

    @Test
    void shouldNotRoundLargeIntegerDivisorToBinary64() {
        // given
        try (BlueLanguage language = BlueLanguage.builder().build()) {
            Node value = decimal("9007199254740992");
            Node pattern = new Node().schema(new Schema().multipleOf(integer("9007199254740993")));
            // when
            Node constrained = value.clone().schema(pattern.getSchema().clone());
            // then
            assertThrows(IllegalArgumentException.class, () -> language.resolution().resolve(constrained));
            assertFalse(language.matching().matches(value, pattern));
        }
    }

    @Test
    void shouldRejectCrossedInclusiveExclusiveBoundsWithoutPayload() {
        // given
        try (BlueLanguage language = BlueLanguage.builder().build()) {
            Node five = integer("5");
            Node left = new Node().type(new Node().blueId(INTEGER_TYPE_BLUE_ID))
                    .schema(new Schema().minimum(five).exclusiveMaximum(five));
            Node right = new Node().type(new Node().blueId(INTEGER_TYPE_BLUE_ID))
                    .schema(new Schema().exclusiveMinimum(five).maximum(five));
            // when
            Node nested = new Node().properties("value", left.clone());
            // then
            assertThrows(IllegalArgumentException.class, () -> language.resolution().resolveDefinition(left));
            assertThrows(IllegalArgumentException.class, () -> language.resolution().resolveDefinition(right));
            assertThrows(IllegalArgumentException.class, () -> language.resolution().resolveDefinition(nested));
        }
    }

    @Test
    void shouldIntersectDyadicMultiplesWithoutDecimalTolerance() {
        // given
        try (BlueLanguage language = BlueLanguage.builder().build()) {
            Node parent = new Node().schema(new Schema().multipleOf(decimal("0.1")));
            Node child = new Node().type(parent).schema(new Schema().multipleOf(decimal("0.3")));
            BigInteger numerator = new BigInteger("19471113219505603967277331424215");
            // when
            Node prepared = language.resolution().resolveDefinition(child);
            // then
            assertEquals(numerator, prepared.getSchema().getMultipleOf().getValue());
            assertThrows(IllegalArgumentException.class, () -> language.resolution().resolve(child.clone().value(new BigDecimal("0.3"))));
            assertDoesNotThrow(() -> language.resolution().resolve(child.clone().value(numerator)));
            assertDoesNotThrow(() -> language.resolution().resolve(child.clone().value(numerator.multiply(BigInteger.valueOf(2)))));
            assertDoesNotThrow(() -> language.resolution().resolve(child.clone().value(BigDecimal.ZERO)));
        }
    }

    private static Node integer(String value) {
        return new Node().type(new Node().blueId(INTEGER_TYPE_BLUE_ID)).value(new BigInteger(value));
    }

    private static Node decimal(String value) {
        return new Node().type(new Node().blueId(DOUBLE_TYPE_BLUE_ID)).value(new BigDecimal(value));
    }
}
