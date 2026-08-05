package blue.language.merge.processor;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

final class LeastCommonMultipleTest {

    @Test
    void shouldCalculateLeastCommonMultiple() {
        // given
        BigDecimal[][] inputs = {
                {BigDecimal.valueOf(2), BigDecimal.valueOf(3)},
                {BigDecimal.valueOf(2), BigDecimal.valueOf(4)},
                {BigDecimal.valueOf(4), BigDecimal.valueOf(6)},
                {BigDecimal.valueOf(4), BigDecimal.valueOf(3)},
                {BigDecimal.valueOf(-4), BigDecimal.valueOf(6)},
                {BigDecimal.valueOf(0.4), BigDecimal.valueOf(0.6)},
                {BigDecimal.ONE, BigDecimal.ZERO}
        };
        BigDecimal[] expected = {
                BigDecimal.valueOf(6),
                BigDecimal.valueOf(4),
                BigDecimal.valueOf(12),
                BigDecimal.valueOf(12),
                BigDecimal.valueOf(12),
                BigDecimal.valueOf(1.2),
                BigDecimal.ZERO
        };

        // when
        BigDecimal[] actual = new BigDecimal[inputs.length];
        for (int index = 0; index < inputs.length; index++) {
            actual[index] = LeastCommonMultiple.lcm(inputs[index][0], inputs[index][1]);
        }

        // then
        assertArrayEquals(expected, actual);
    }
}
