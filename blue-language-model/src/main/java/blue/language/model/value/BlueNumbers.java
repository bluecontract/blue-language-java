package blue.language.model.value;

import java.math.BigDecimal;
import java.math.BigInteger;

/** Numeric normalization and exact binary64 helpers owned by the model. */
public class BlueNumbers {

    /** Smallest integer represented exactly by every interoperable binary64 runtime. */
    public static final BigInteger MIN_INTEROPERABLE_INTEGER =
            BigInteger.valueOf(-9_007_199_254_740_991L);
    /** Largest integer represented exactly by every interoperable binary64 runtime. */
    public static final BigInteger MAX_INTEROPERABLE_INTEGER =
            BigInteger.valueOf(9_007_199_254_740_991L);

    /** Allows the legacy utility facade to inherit these operations. */
    protected BlueNumbers() {
    }

    /**
     * Converts a numeric value to the canonical decimal view of its binary64
     * representation.
     *
     * @param value number or numeric string to normalize
     * @return finite canonical decimal representation of the binary64 value
     * @throws IllegalArgumentException when {@code value} is not numeric or
     *         converts to a non-finite binary64 value
     */
    public static BigDecimal toCanonicalDoubleValue(Object value) {
        double doubleValue;
        if (value instanceof BigDecimal) {
            doubleValue = ((BigDecimal) value).doubleValue();
        } else if (value instanceof BigInteger) {
            doubleValue = ((BigInteger) value).doubleValue();
        } else if (value instanceof Number) {
            doubleValue = ((Number) value).doubleValue();
        } else if (value instanceof String) {
            doubleValue = Double.parseDouble((String) value);
        } else {
            throw new IllegalArgumentException(
                    "Double value must be numeric or a numeric string: "
                            + value);
        }
        if (!Double.isFinite(doubleValue)) {
            throw new IllegalArgumentException("Double value must be finite.");
        }
        return BigDecimal.valueOf(doubleValue);
    }

    /**
     * Tests whether one binary64 value is an exact integer multiple of another.
     * Both operands are compared as exact rationals after binary64 conversion.
     *
     * @param value numeric candidate value
     * @param multipleOf numeric divisor, or {@code null} to disable the test
     * @return {@code true} when the converted quotient is an exact integer or
     *         when {@code multipleOf} is {@code null}
     * @throws IllegalArgumentException when an operand is non-numeric or
     *         non-finite, or when {@code multipleOf} converts to zero
     */
    public static boolean isExactBinary64Multiple(
            Object value, BigDecimal multipleOf) {
        if (multipleOf == null) {
            return true;
        }
        double valueDouble = toDouble(value);
        double multipleDouble = toDouble(multipleOf);
        if (multipleDouble == 0.0d || !Double.isFinite(multipleDouble)) {
            throw new IllegalArgumentException(
                    "Double multipleOf must be finite and non-zero.");
        }
        Binary64Rational valueRational =
                Binary64Rational.fromDouble(valueDouble);
        Binary64Rational multipleRational =
                Binary64Rational.fromDouble(multipleDouble);
        return valueRational.dividedByIsInteger(multipleRational);
    }

    private static double toDouble(Object value) {
        double result;
        if (value instanceof BigDecimal) {
            result = ((BigDecimal) value).doubleValue();
        } else if (value instanceof BigInteger) {
            result = ((BigInteger) value).doubleValue();
        } else if (value instanceof Number) {
            result = ((Number) value).doubleValue();
        } else {
            throw new IllegalArgumentException(
                    "Double value must be numeric: " + value);
        }
        if (!Double.isFinite(result)) {
            throw new IllegalArgumentException("Double value must be finite.");
        }
        return result;
    }

    private static final class Binary64Rational {
        private final BigInteger numerator;
        private final BigInteger denominator;

        private Binary64Rational(
                BigInteger numerator, BigInteger denominator) {
            if (denominator.signum() <= 0) {
                throw new IllegalArgumentException(
                        "denominator must be positive");
            }
            BigInteger gcd = numerator.abs().gcd(denominator);
            this.numerator = numerator.divide(gcd);
            this.denominator = denominator.divide(gcd);
        }

        private static Binary64Rational fromDouble(double value) {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException(
                        "Double value must be finite.");
            }
            if (value == 0.0d) {
                return new Binary64Rational(
                        BigInteger.ZERO, BigInteger.ONE);
            }
            long bits = Double.doubleToLongBits(value);
            boolean negative = (bits & (1L << 63)) != 0;
            int exponentBits = (int) ((bits >>> 52) & 0x7ffL);
            long fraction = bits & 0x000f_ffff_ffff_ffffL;
            BigInteger significand;
            int exponent;
            if (exponentBits == 0) {
                significand = BigInteger.valueOf(fraction);
                exponent = -1074;
            } else {
                significand = BigInteger.valueOf(
                        (1L << 52) | fraction);
                exponent = exponentBits - 1023 - 52;
            }
            if (negative) {
                significand = significand.negate();
            }
            if (exponent >= 0) {
                return new Binary64Rational(
                        significand.shiftLeft(exponent), BigInteger.ONE);
            }
            return new Binary64Rational(
                    significand, BigInteger.ONE.shiftLeft(-exponent));
        }

        private boolean dividedByIsInteger(Binary64Rational divisor) {
            if (divisor.numerator.signum() == 0) {
                throw new IllegalArgumentException(
                        "Division by zero rational.");
            }
            BigInteger quotientNumerator =
                    numerator.multiply(divisor.denominator);
            BigInteger quotientDenominator =
                    denominator.multiply(divisor.numerator).abs();
            return quotientNumerator
                    .remainder(quotientDenominator).signum() == 0;
        }
    }
}
