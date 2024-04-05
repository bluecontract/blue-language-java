package blue.language.utils;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 *  Calculates the least common multiple of two numbers
 */
public class LCM {
    private static BigDecimal gcd(BigDecimal a, BigDecimal b)
    {
        if (a.compareTo(b) < 0)
            return gcd(b, a);

        // base case
        if (b.abs().compareTo(BigDecimal.valueOf(0.001)) < 0)
            return a;

        else {
            return (gcd(b, a.subtract(a.divide(b).setScale(0, RoundingMode.FLOOR).multiply(b))));
        }
    }
    public static BigDecimal lcm(BigDecimal a, BigDecimal b) {
        if (BigDecimal.ZERO.equals(a) || BigDecimal.ZERO.equals(b)) {
            return BigDecimal.ZERO;
        }
        return a.divide(gcd(a.abs(), b.abs())).multiply(b).abs();
    }
}
