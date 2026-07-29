package blue.language.utils;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * Exact conversions from Jackson's arbitrary-precision scalar values to Java
 * primitive-wrapper and numeric types.
 */
public class TypeUtils {

    /**
     * Creates an exact scalar-conversion helper.
     */
    public TypeUtils() {
    }

    /**
     * Converts an integral BigInteger or BigDecimal to a range-checked Integer.
     *
     * @param obj arbitrary-precision integral value
     * @return exact Integer representation
     */
    public static Integer getIntegerFromObject(Object obj) {
        if (obj instanceof BigInteger) {
            BigInteger bigInt = (BigInteger) obj;
            if (bigInt.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) <= 0
                    && bigInt.compareTo(BigInteger.valueOf(Integer.MIN_VALUE)) >= 0) {
                return bigInt.intValue();
            } else {
                throw new ArithmeticException("BigInteger value is too large for an int");
            }
        } else if (obj instanceof BigDecimal) {
            BigDecimal bigDec = (BigDecimal) obj;
            if (bigDec.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) <= 0
                    && bigDec.compareTo(BigDecimal.valueOf(Integer.MIN_VALUE)) >= 0) {
                return bigDec.intValueExact();
            } else {
                throw new ArithmeticException("BigDecimal value is too large for an int");
            }
        } else {
            throw new IllegalArgumentException("Object is not a BigInteger or BigDecimal");
        }
    }

    /**
     * Converts an integral BigInteger or BigDecimal to a BigInteger.
     *
     * @param obj arbitrary-precision integral value
     * @return exact BigInteger representation
     */
    public static BigInteger getBigIntegerFromObject(Object obj) {
        if (obj instanceof BigInteger) {
            return (BigInteger) obj;
        } else if (obj instanceof BigDecimal) {
            return ((BigDecimal) obj).toBigIntegerExact();
        } else {
            throw new IllegalArgumentException("Object is not a BigInteger or BigDecimal");
        }
    }

    /**
     * Converts BigInteger or BigDecimal input without precision loss.
     *
     * @param obj arbitrary-precision numeric value
     * @return exact BigDecimal representation
     */
    public static BigDecimal getBigDecimalFromObject(Object obj) {
        if (obj instanceof BigInteger) {
            return new BigDecimal((BigInteger) obj);
        } else if (obj instanceof BigDecimal) {
            return (BigDecimal) obj;
        } else {
            throw new IllegalArgumentException("Object is not a BigInteger or BigDecimal");
        }
    }

    /**
     * Returns a Boolean input or rejects every other type.
     *
     * @param obj value expected to be a Boolean
     * @return the supplied Boolean value
     */
    public static Boolean getBooleanFromObject(Object obj) {
        if (obj instanceof Boolean)
            return (Boolean) obj;
        throw new IllegalArgumentException("Object is not a Boolean");
    }

}
