package blue.language.merge.processor;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Decimal greatest/least-common-multiple helper used when combining numeric
 * schema constraints.
 */
final class LeastCommonMultiple {

    private static final BigDecimal GCD_ZERO_TOLERANCE = BigDecimal.valueOf(0.001);
    private static final int GCD_SCALE = 10;

    /**
     * Creates a decimal least-common-multiple helper.
     */
    LeastCommonMultiple() {
    }

    private static BigDecimal gcd(BigDecimal a, BigDecimal b) {
        if (a.compareTo(b) < 0)
            return gcd(b, a);

        // base case
        if (b.abs().compareTo(GCD_ZERO_TOLERANCE) < 0)
            return a;

        else {
            a = a.setScale(GCD_SCALE, RoundingMode.UNNECESSARY);
            b = b.setScale(GCD_SCALE, RoundingMode.UNNECESSARY);
            return (gcd(b, a.subtract(a.divide(b, RoundingMode.DOWN).setScale(0, RoundingMode.FLOOR).multiply(b))));
        }
    }

    /**
     * Returns the non-negative decimal least common multiple of two values.
     *
     * @param a first decimal value
     * @param b second decimal value
     * @return non-negative decimal least common multiple
     */
    static BigDecimal lcm(BigDecimal a, BigDecimal b) {
        if (BigDecimal.ZERO.equals(a) || BigDecimal.ZERO.equals(b)) {
            return BigDecimal.ZERO;
        }
        return a.divide(gcd(a.abs(), b.abs())).multiply(b).abs();
    }
}
