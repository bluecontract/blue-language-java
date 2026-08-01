package blue.language;

import blue.language.api.BlueCachePolicy;
import blue.language.api.BlueCacheStats;
import blue.language.api.BlueLanguageErrorCategory;
import blue.language.api.BlueLanguageErrorClassifier;
import blue.language.api.BlueOperationLimits;
import blue.language.api.BlueOperationOutcome;
import blue.language.api.BlueOperationResult;
import blue.language.api.BlueViewPath;
import blue.language.api.LanguageRuntimeAccess;
import blue.language.provider.NodeProvider;

import blue.language.utils.LeastCommonMultiple;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

public class LeastCommonMultipleTest {

    @Test
    public void shouldCalculateLeastCommonMultiple() {
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
