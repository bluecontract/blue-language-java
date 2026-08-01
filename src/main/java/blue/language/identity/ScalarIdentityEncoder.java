package blue.language.identity;

import blue.language.utils.BlueNumbers;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;

import static blue.language.utils.Properties.BOOLEAN_TYPE_BLUE_ID;
import static blue.language.utils.Properties.DOUBLE_TYPE_BLUE_ID;
import static blue.language.utils.Properties.INTEGER_TYPE_BLUE_ID;
import static blue.language.utils.Properties.OBJECT_BLUE_ID;
import static blue.language.utils.Properties.OBJECT_TYPE;
import static blue.language.utils.Properties.OBJECT_VALUE;
import static blue.language.utils.Properties.TEXT_TYPE_BLUE_ID;

/** Encodes scalar-node sugar as its explicit typed identity representation. */
public final class ScalarIdentityEncoder {

    /** Creates the stateless scalar encoder. */
    public ScalarIdentityEncoder() {
    }

    /**
     * Encodes a supported Java scalar as an explicit Blue scalar node.
     *
     * @param value Text, Integer, Double, or Boolean value
     * @return canonical typed scalar map
     */
    public Map<String, Object> encode(Object value) {
        String typeBlueId;
        Object canonicalValue = value;
        if (value instanceof String) {
            typeBlueId = TEXT_TYPE_BLUE_ID;
        } else if (value instanceof Boolean) {
            typeBlueId = BOOLEAN_TYPE_BLUE_ID;
        } else if (value instanceof BigDecimal
                || value instanceof Float
                || value instanceof Double) {
            typeBlueId = DOUBLE_TYPE_BLUE_ID;
            canonicalValue = BlueNumbers.toCanonicalDoubleValue(value);
        } else if (value instanceof Number) {
            typeBlueId = INTEGER_TYPE_BLUE_ID;
            BigInteger integer = value instanceof BigInteger
                    ? (BigInteger) value
                    : BigInteger.valueOf(((Number) value).longValue());
            canonicalValue = integer.compareTo(
                    BlueNumbers.MIN_INTEROPERABLE_INTEGER) < 0
                    || integer.compareTo(
                    BlueNumbers.MAX_INTEROPERABLE_INTEGER) > 0
                    ? integer.toString()
                    : integer;
        } else {
            throw new IllegalArgumentException(
                    "Blue scalar must be Text, Integer, Double, or Boolean.");
        }

        Map<String, Object> type = new LinkedHashMap<>();
        type.put(OBJECT_BLUE_ID, typeBlueId);
        Map<String, Object> scalar = new LinkedHashMap<>();
        scalar.put(OBJECT_TYPE, type);
        scalar.put(OBJECT_VALUE, canonicalValue);
        return scalar;
    }
}
