package blue.lang.utils;

import blue.lang.Node;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class JsonCanonicalizer {

    public static String canonicalize(Object object) {
        StringBuilder buffer = new StringBuilder();
        serialize(object, buffer);
        return buffer.toString();
    }

    private static void serialize(Object object, StringBuilder buffer) {
        if (object instanceof String || object instanceof Boolean || object instanceof BigDecimal || object instanceof BigInteger) {
            buffer.append(stringify(object));
        } else if (object instanceof List) {
            buffer.append('[');
            List<?> items = (List) object;
            for (int i = 0; i < items.size(); i++) {
                if (i > 0)
                    buffer.append(',');
                serialize(items.get(i), buffer);
            }
            buffer.append(']');
        } else {
            Map<String, Node> properties = new TreeMap<>((Map) object);
            buffer.append('{');
            boolean next = false;
            for (Map.Entry<String, Node> entry : properties.entrySet()) {
                if (next)
                    buffer.append(',');
                next = true;
                buffer.append(stringify(entry.getKey()));
                buffer.append(':');
                serialize(entry.getValue(), buffer);
            }
            buffer.append('}');
        }

    }

    private static String stringify(Object value) {
        if (value instanceof String)
            return "\"" + escape((String) value) + "\"";
        else return value.toString();
    }

    private static String escape(String str) {
        StringBuilder escaped = new StringBuilder();
        for (char c : str.toCharArray()) {
            switch (c) {
                case '\n':
                    escaped.append("\\n");
                    break;
                case '\b':
                    escaped.append("\\b");
                    break;
                case '\f':
                    escaped.append("\\f");
                    break;
                case '\r':
                    escaped.append("\\r");
                    break;
                case '\t':
                    escaped.append("\\t");
                    break;
                case '"':
                case '\\':
                    escaped.append('\\').append(c);
                    break;
                default:
                    if (c < 0x20) {
                        escaped.append("\\u");
                        for (int i = 0; i < 4; i++) {
                            int hex = (c >>> (12 - i * 4)) & 0xF;
                            escaped.append((char) (hex > 9 ? hex + 'a' - 10 : hex + '0'));
                        }
                    } else {
                        escaped.append(c);
                    }
            }
        }
        return escaped.toString();
    }

}