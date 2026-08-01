package blue.buildlogic.support;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.gradle.api.GradleException;

/** Minimal canonical JSON writer used for machine-readable build evidence. */
public final class DeterministicJson {

    private DeterministicJson() {}

    /** Encodes maps with lexicographically sorted string keys and appends one newline. */
    public static String write(Object value) {
        StringBuilder output = new StringBuilder();
        append(value, output);
        return output.append('\n').toString();
    }

    private static void append(Object value, StringBuilder output) {
        if (value == null) {
            output.append("null");
        } else if (value instanceof String || value instanceof Character) {
            appendString(value.toString(), output);
        } else if (value instanceof Boolean
                || value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long
                || value instanceof BigInteger
                || value instanceof BigDecimal) {
            output.append(value);
        } else if (value instanceof Float || value instanceof Double) {
            double number = ((Number) value).doubleValue();
            if (!Double.isFinite(number)) {
                throw new GradleException("Evidence JSON cannot contain a non-finite number");
            }
            output.append(value);
        } else if (value instanceof Map<?, ?>) {
            appendMap((Map<?, ?>) value, output);
        } else if (value instanceof Collection<?>) {
            appendCollection((Collection<?>) value, output);
        } else if (value.getClass().isArray()) {
            List<Object> items = new ArrayList<>();
            for (int index = 0; index < Array.getLength(value); index++) {
                items.add(Array.get(value, index));
            }
            appendCollection(items, output);
        } else {
            throw new GradleException(
                    "Unsupported evidence JSON value: " + value.getClass().getName());
        }
    }

    private static void appendMap(Map<?, ?> values, StringBuilder output) {
        List<Map.Entry<?, ?>> entries = new ArrayList<>(values.entrySet());
        for (Map.Entry<?, ?> entry : entries) {
            if (!(entry.getKey() instanceof String)) {
                throw new GradleException("Evidence JSON map keys must be strings");
            }
        }
        entries.sort(Comparator.comparing(entry -> (String) entry.getKey()));
        output.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> entry : entries) {
            if (!first) {
                output.append(',');
            }
            first = false;
            appendString((String) entry.getKey(), output);
            output.append(':');
            append(entry.getValue(), output);
        }
        output.append('}');
    }

    private static void appendCollection(Collection<?> values, StringBuilder output) {
        output.append('[');
        boolean first = true;
        for (Object value : values) {
            if (!first) {
                output.append(',');
            }
            first = false;
            append(value, output);
        }
        output.append(']');
    }

    private static void appendString(String value, StringBuilder output) {
        output.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"':
                    output.append("\\\"");
                    break;
                case '\\':
                    output.append("\\\\");
                    break;
                case '\b':
                    output.append("\\b");
                    break;
                case '\f':
                    output.append("\\f");
                    break;
                case '\n':
                    output.append("\\n");
                    break;
                case '\r':
                    output.append("\\r");
                    break;
                case '\t':
                    output.append("\\t");
                    break;
                default:
                    if (character < 0x20) {
                        output.append(String.format("\\u%04x", (int) character));
                    } else {
                        output.append(character);
                    }
            }
        }
        output.append('"');
    }
}
