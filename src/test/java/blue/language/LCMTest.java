package blue.language;

import blue.language.utils.LCM;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class LCMTest {

    @Test
    public void testLCM() {
        assertEquals(BigDecimal.valueOf(6), LCM.lcm(BigDecimal.valueOf(2), BigDecimal.valueOf(3)));
        assertEquals(BigDecimal.valueOf(4), LCM.lcm(BigDecimal.valueOf(2), BigDecimal.valueOf(4)));
        assertEquals(BigDecimal.valueOf(12), LCM.lcm(BigDecimal.valueOf(4), BigDecimal.valueOf(6)));
        assertEquals(BigDecimal.valueOf(12), LCM.lcm(BigDecimal.valueOf(-4), BigDecimal.valueOf(6)));
        assertEquals(BigDecimal.valueOf(1.2), LCM.lcm(BigDecimal.valueOf(0.4), BigDecimal.valueOf(0.6)));
        assertEquals(BigDecimal.ZERO, LCM.lcm(BigDecimal.valueOf(1), BigDecimal.valueOf(0)));
    }
}
