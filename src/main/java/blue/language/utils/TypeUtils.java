package blue.language.utils;

import java.math.BigDecimal;
import java.math.BigInteger;

public class TypeUtils {

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

    public static BigDecimal getBigDecimalFromObject(Object obj) {
        if (obj instanceof BigInteger) {
            return new BigDecimal((BigInteger) obj);
        } else if (obj instanceof BigDecimal) {
            return (BigDecimal) obj;
        } else {
            throw new IllegalArgumentException("Object is not a BigInteger or BigDecimal");
        }
    }

    public static Boolean getBooleanFromObject(Object obj) {
        if (obj instanceof Boolean)
            return (Boolean) obj;
        throw new IllegalArgumentException("Object is not a Boolean");
    }

}
