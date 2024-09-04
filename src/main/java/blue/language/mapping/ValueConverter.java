package blue.language.mapping;

import blue.language.model.Node;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static blue.language.utils.Properties.*;

public class ValueConverter {

    public static Object convertValue(Node node, Class<?> targetClass) {
        if (node == null || node.getValue() == null) {
            if (targetClass.isPrimitive()) {
                return getDefaultPrimitiveValue(targetClass);
            }
            return null;
        }

        String typeBlueId = node.getType() != null ? node.getType().getBlueId() : null;
        Object value = node.getValue();

        if (TEXT_TYPE_BLUE_ID.equals(typeBlueId)) {
            return convertFromString((String) value, targetClass);
        } else if (DOUBLE_TYPE_BLUE_ID.equals(typeBlueId) || value instanceof BigDecimal) {
            return convertFromBigDecimal((BigDecimal) value, targetClass);
        } else if (INTEGER_TYPE_BLUE_ID.equals(typeBlueId) || value instanceof BigInteger) {
            return convertFromBigInteger((BigInteger) value, targetClass);
        } else if (BOOLEAN_TYPE_BLUE_ID.equals(typeBlueId) || value instanceof Boolean) {
            return convertFromBoolean((Boolean) value, targetClass);
        }

        return convertFromString((String) value, targetClass);
    }

    private static Object convertFromString(String value, Class<?> targetClass) {
        if (targetClass == String.class || targetClass == Object.class) return value;
        if (targetClass == char.class || targetClass == Character.class) return value.charAt(0);
        if (targetClass.isEnum()) return Enum.valueOf((Class<Enum>) targetClass, value);

        if (targetClass == int.class || targetClass == Integer.class) return Integer.parseInt(value);
        if (targetClass == long.class || targetClass == Long.class) return Long.parseLong(value);
        if (targetClass == double.class || targetClass == Double.class) return Double.parseDouble(value);
        if (targetClass == float.class || targetClass == Float.class) return Float.parseFloat(value);
        if (targetClass == short.class || targetClass == Short.class) return Short.parseShort(value);
        if (targetClass == byte.class || targetClass == Byte.class) return Byte.parseByte(value);
        if (targetClass == BigInteger.class) return new BigInteger(value);
        if (targetClass == BigDecimal.class) return new BigDecimal(value);
        if (targetClass == AtomicInteger.class) return new AtomicInteger(Integer.parseInt(value));
        if (targetClass == AtomicLong.class) return new AtomicLong(Long.parseLong(value));

        if (targetClass == boolean.class || targetClass == Boolean.class) return Boolean.parseBoolean(value);

        throw new IllegalArgumentException("Cannot convert String to " + targetClass);
    }

    private static Object convertFromBigDecimal(BigDecimal value, Class<?> targetClass) {
        if (targetClass == BigDecimal.class) return value;
        if (targetClass == double.class || targetClass == Double.class) return value.doubleValue();
        if (targetClass == float.class || targetClass == Float.class) return value.floatValue();
        if (targetClass == AtomicInteger.class) return new AtomicInteger(value.intValue());
        if (targetClass == AtomicLong.class) return new AtomicLong(value.longValue());
        if (targetClass == Number.class) return value;
        if (targetClass == String.class) return value.toPlainString();
        throw new IllegalArgumentException("Cannot convert BigDecimal to " + targetClass);
    }

    private static Object convertFromBigInteger(BigInteger value, Class<?> targetClass) {
        if (targetClass == BigInteger.class) return value;
        if (targetClass == int.class || targetClass == Integer.class) return value.intValue();
        if (targetClass == long.class || targetClass == Long.class) return value.longValue();
        if (targetClass == short.class || targetClass == Short.class) return value.shortValue();
        if (targetClass == byte.class || targetClass == Byte.class) return value.byteValue();
        if (targetClass == AtomicInteger.class) return new AtomicInteger(value.intValue());
        if (targetClass == AtomicLong.class) return new AtomicLong(value.longValue());
        if (targetClass == Number.class) return value;
        if (targetClass == String.class) return value.toString();
        throw new IllegalArgumentException("Cannot convert BigInteger to " + targetClass);
    }

    private static Object convertFromBoolean(Boolean value, Class<?> targetClass) {
        if (targetClass == boolean.class || targetClass == Boolean.class) return value;
        if (targetClass == String.class) return value.toString();
        throw new IllegalArgumentException("Cannot convert Boolean to " + targetClass);
    }

    public static boolean isSupportedType(Class<?> targetClass) {
        return targetClass == String.class ||
               targetClass == Character.class ||
               targetClass == Boolean.class ||
               Number.class.isAssignableFrom(targetClass) ||
               targetClass == AtomicInteger.class ||
               targetClass == AtomicLong.class ||
               targetClass.isPrimitive();
    }

    public static Object getDefaultPrimitiveValue(Class<?> targetClass) {
        if (targetClass == int.class) return 0;
        if (targetClass == long.class) return 0L;
        if (targetClass == double.class) return 0.0;
        if (targetClass == float.class) return 0.0f;
        if (targetClass == boolean.class) return false;
        if (targetClass == byte.class) return (byte) 0;
        if (targetClass == short.class) return (short) 0;
        if (targetClass == char.class) return '\u0000';
        throw new IllegalArgumentException("Unsupported primitive type: " + targetClass);
    }
}