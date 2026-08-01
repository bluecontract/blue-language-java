package blue.language.model.value;

import java.math.BigDecimal;
import java.math.BigInteger;

/** Exact conversions for arbitrary-precision model scalar values. */
public class ScalarValues {

    /** Allows the legacy utility facade to inherit these operations. */
    protected ScalarValues() {
    }

    /**
     * Converts an arbitrary-precision integer or exact decimal to an int.
     *
     * @param value {@link BigInteger} or {@link BigDecimal} to convert
     * @return the exact 32-bit integer value
     * @throws IllegalArgumentException when {@code value} has another type
     * @throws ArithmeticException when the value is fractional or outside the
     *         32-bit signed integer range
     */
    public static Integer getIntegerFromObject(Object value) {
        if (value instanceof BigInteger) {
            BigInteger integer = (BigInteger) value;
            if (integer.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) <= 0
                    && integer.compareTo(
                            BigInteger.valueOf(Integer.MIN_VALUE)) >= 0) {
                return integer.intValue();
            }
            throw new ArithmeticException(
                    "BigInteger value is too large for an int");
        }
        if (value instanceof BigDecimal) {
            BigDecimal decimal = (BigDecimal) value;
            if (decimal.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) <= 0
                    && decimal.compareTo(
                            BigDecimal.valueOf(Integer.MIN_VALUE)) >= 0) {
                return decimal.intValueExact();
            }
            throw new ArithmeticException(
                    "BigDecimal value is too large for an int");
        }
        throw new IllegalArgumentException(
                "Object is not a BigInteger or BigDecimal");
    }

    /**
     * Converts an arbitrary-precision integer or exact decimal to an integer.
     *
     * @param value {@link BigInteger} or {@link BigDecimal} to convert
     * @return the supplied integer or the decimal's exact integer value
     * @throws IllegalArgumentException when {@code value} has another type
     * @throws ArithmeticException when a decimal value has a fractional part
     */
    public static BigInteger getBigIntegerFromObject(Object value) {
        if (value instanceof BigInteger) {
            return (BigInteger) value;
        }
        if (value instanceof BigDecimal) {
            return ((BigDecimal) value).toBigIntegerExact();
        }
        throw new IllegalArgumentException(
                "Object is not a BigInteger or BigDecimal");
    }

    /**
     * Converts an arbitrary-precision integer or decimal to a decimal.
     *
     * @param value {@link BigInteger} or {@link BigDecimal} to convert
     * @return an exact arbitrary-precision decimal value
     * @throws IllegalArgumentException when {@code value} has another type
     */
    public static BigDecimal getBigDecimalFromObject(Object value) {
        if (value instanceof BigInteger) {
            return new BigDecimal((BigInteger) value);
        }
        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        }
        throw new IllegalArgumentException(
                "Object is not a BigInteger or BigDecimal");
    }

    /**
     * Requires and returns a Boolean scalar.
     *
     * @param value candidate Boolean value
     * @return the supplied Boolean
     * @throws IllegalArgumentException when {@code value} is not a Boolean
     */
    public static Boolean getBooleanFromObject(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        throw new IllegalArgumentException("Object is not a Boolean");
    }
}
