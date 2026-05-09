package blue.language.utils;

import java.math.BigDecimal;
import java.math.BigInteger;

public final class BlueNumbers {

    private BlueNumbers() {
    }

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
            throw new IllegalArgumentException("Double value must be numeric or a numeric string: " + value);
        }

        if (!Double.isFinite(doubleValue)) {
            throw new IllegalArgumentException("Double value must be finite.");
        }
        return BigDecimal.valueOf(doubleValue);
    }
}
