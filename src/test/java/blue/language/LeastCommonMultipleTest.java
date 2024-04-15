package blue.language;

import blue.language.utils.LeastCommonMultiple;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class LeastCommonMultipleTest {

    @Test
    public void testLCM() {
        assertEquals(BigDecimal.valueOf(6), LeastCommonMultiple.lcm(BigDecimal.valueOf(2), BigDecimal.valueOf(3)));
        assertEquals(BigDecimal.valueOf(4), LeastCommonMultiple.lcm(BigDecimal.valueOf(2), BigDecimal.valueOf(4)));
        assertEquals(BigDecimal.valueOf(12), LeastCommonMultiple.lcm(BigDecimal.valueOf(4), BigDecimal.valueOf(6)));
        assertEquals(BigDecimal.valueOf(12), LeastCommonMultiple.lcm(BigDecimal.valueOf(4), BigDecimal.valueOf(3)));
        assertEquals(BigDecimal.valueOf(12), LeastCommonMultiple.lcm(BigDecimal.valueOf(-4), BigDecimal.valueOf(6)));
        assertEquals(BigDecimal.valueOf(1.2), LeastCommonMultiple.lcm(BigDecimal.valueOf(0.4), BigDecimal.valueOf(0.6)));
        assertEquals(BigDecimal.ZERO, LeastCommonMultiple.lcm(BigDecimal.valueOf(1), BigDecimal.valueOf(0)));
    }
}
