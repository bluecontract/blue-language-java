package blue.language.utils;

import java.math.BigDecimal;
import java.math.RoundingMode;

public class LeastCommonMultiple {
    private static BigDecimal gcd(BigDecimal a, BigDecimal b) {
        if (a.compareTo(b) < 0)
            return gcd(b, a);

        // base case
        if (b.abs().compareTo(BigDecimal.valueOf(0.001)) < 0)
            return a;

        else {
            a = a.setScale(10, RoundingMode.UNNECESSARY);
            b = b.setScale(10, RoundingMode.UNNECESSARY);
            return (gcd(b, a.subtract(a.divide(b, RoundingMode.DOWN).setScale(0, RoundingMode.FLOOR).multiply(b))));
        }
    }

    public static BigDecimal lcm(BigDecimal a, BigDecimal b) {
        if (BigDecimal.ZERO.equals(a) || BigDecimal.ZERO.equals(b)) {
            return BigDecimal.ZERO;
        }
        return a.divide(gcd(a.abs(), b.abs())).multiply(b).abs();
    }
}
