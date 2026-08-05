package blue.language.identity;

import blue.language.model.value.BlueNumbers;
import org.erdtman.jcs.JsonCanonicalizer;
import org.erdtman.jcs.NumberToJSON;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static blue.language.codec.jackson.UncheckedObjectMapper.JSON_MAPPER;

/**
 * Writes deterministic RFC 8785 bytes for normalized identity values without
 * depending on snapshots or runtime composition.
 */
public final class CanonicalJsonValueWriter {

    private static final byte[] TRUE = ascii("true");
    private static final byte[] FALSE = ascii("false");
    private static final byte[] NULL = ascii("null");
    private static final int MAX_PLAIN_VALUE_DEPTH = 100;
    private static final int MAX_PLAIN_MAP_FIELDS = 256;
    private static final Class<?> SINGLETON_MAP_CLASS =
            Collections.singletonMap(Boolean.TRUE, Boolean.TRUE).getClass();
    private static final ThreadLocal<Set<String>> MAP_KEYS =
            new ThreadLocal<Set<String>>() {
                @Override
                protected Set<String> initialValue() {
                    return new HashSet<>();
                }
            };

    private CanonicalJsonValueWriter() {
    }

    /**
     * Returns exact canonical bytes for one supported identity value.
     *
     * @param value normalized identity value, which may be {@code null}
     * @return RFC 8785 canonical bytes
     * @throws UnsupportedCanonicalValueException if the value has no supported
     *         wire-equivalent representation
     * @throws IllegalStateException if legacy-compatible serialization fails
     */
    public static byte[] write(Object value) {
        ByteArraySink sink = new ByteArraySink();
        write(value, sink);
        return sink.toByteArray();
    }

    /**
     * Streams exact canonical bytes to a caller-owned sink.
     *
     * @param value normalized identity value, which may be {@code null}
     * @param sink caller-owned destination receiving bytes in encounter order
     * @throws NullPointerException if {@code sink} is {@code null}
     * @throws UnsupportedCanonicalValueException if the value has no supported
     *         wire-equivalent representation
     * @throws IllegalStateException if legacy-compatible serialization fails
     */
    public static void write(Object value, ByteSink sink) {
        if (sink == null) {
            throw new NullPointerException("sink");
        }
        writeValue(value, sink);
    }

    /** Receives canonical bytes in encounter order. */
    public interface ByteSink {

        /**
         * Writes one canonical byte.
         *
         * @param value byte value; only the low eight bits are significant
         */
        void writeByte(int value);

        /**
         * Writes a contiguous canonical byte range.
         *
         * @param bytes source byte array
         * @param offset zero-based source offset
         * @param length number of bytes to write
         */
        void write(byte[] bytes, int offset, int length);
    }

    /**
     * Tests whether the allocation-light writer preserves Jackson semantics.
     *
     * @param value candidate normalized identity value
     * @return {@code true} when the allocation-light path is wire-equivalent
     */
    public static boolean supports(Object value) {
        return supports(value, 0);
    }

    private static boolean supports(Object value, int depth) {
        if (depth > MAX_PLAIN_VALUE_DEPTH) {
            return false;
        }
        if (value == null) {
            return true;
        }
        Class<?> type = value.getClass();
        if (type == String.class || type == Boolean.class
                || type == BigInteger.class
                || type == Byte.class || type == Short.class
                || type == Integer.class || type == Long.class) {
            return true;
        }
        if (type == BigDecimal.class || type == Float.class
                || type == Double.class) {
            return Double.isFinite(((Number) value).doubleValue());
        }
        boolean plainList = type == ArrayList.class;
        boolean plainMap = type == LinkedHashMap.class
                || type == TreeMap.class
                || type == SINGLETON_MAP_CLASS;
        if (!plainList && !plainMap) {
            return false;
        }
        if (plainList) {
            for (Object element : (List<?>) value) {
                if (!supports(element, depth + 1)) {
                    return false;
                }
            }
            return true;
        }
        Map<?, ?> map = (Map<?, ?>) value;
        if (map.size() > MAX_PLAIN_MAP_FIELDS
                || type == TreeMap.class && !hasUniqueStringKeys(map)) {
            return false;
        }
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() == null
                    || entry.getKey().getClass() != String.class
                    || !supports(entry.getValue(), depth + 1)) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasUniqueStringKeys(Map<?, ?> map) {
        Set<String> keys = MAP_KEYS.get();
        keys.clear();
        try {
            for (Object key : map.keySet()) {
                if (!(key instanceof String) || !keys.add((String) key)) {
                    return false;
                }
            }
            return true;
        } finally {
            keys.clear();
        }
    }

    private static void writeValue(Object value, ByteSink sink) {
        if (value == null) {
            writeBytes(sink, NULL);
        } else if (value instanceof String) {
            writeString((String) value, sink);
        } else if (value instanceof Character) {
            writeString(String.valueOf(value), sink);
        } else if (value instanceof Boolean) {
            writeBytes(sink, Boolean.TRUE.equals(value) ? TRUE : FALSE);
        } else if (value instanceof Enum) {
            writeLegacyValue(value, sink);
        } else if (value instanceof BigInteger) {
            writeInteger((BigInteger) value, sink);
        } else if (value instanceof BigDecimal) {
            writeNumber(((BigDecimal) value).doubleValue(), sink);
        } else if (value instanceof Float) {
            writeNumber(Double.parseDouble(Float.toString((Float) value)), sink);
        } else if (value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long
                || value instanceof Double) {
            writeNumber(((Number) value).doubleValue(), sink);
        } else if (value instanceof Map) {
            writeMap((Map<?, ?>) value, sink);
        } else if (value instanceof List) {
            writeList((List<?>) value, sink);
        } else if (value instanceof byte[]) {
            writeString(Base64.getEncoder().encodeToString((byte[]) value),
                    sink);
        } else if (value instanceof char[]) {
            writeString(new String((char[]) value), sink);
        } else if (value.getClass().isArray()) {
            writeLegacyValue(value, sink);
        } else {
            throw unsupported(value.getClass());
        }
    }

    private static void writeInteger(
            BigInteger integer, ByteSink sink) {
        if (integer.compareTo(BlueNumbers.MIN_INTEROPERABLE_INTEGER) < 0
                || integer.compareTo(
                BlueNumbers.MAX_INTEROPERABLE_INTEGER) > 0) {
            writeString(integer.toString(), sink);
        } else {
            writeNumber(integer.doubleValue(), sink);
        }
    }

    private static void writeMap(Map<?, ?> map, ByteSink sink) {
        Map<String, Object> retained = new TreeMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            Object key = entry.getKey();
            if (!(key instanceof String)) {
                throw unsupported(key == null ? null : key.getClass());
            }
            if (retained.put((String) key, entry.getValue()) != null) {
                throw unsupported(String.class);
            }
        }
        sink.writeByte('{');
        boolean first = true;
        for (Map.Entry<String, Object> entry : retained.entrySet()) {
            if (!first) {
                sink.writeByte(',');
            }
            first = false;
            writeString(entry.getKey(), sink);
            sink.writeByte(':');
            writeValue(entry.getValue(), sink);
        }
        sink.writeByte('}');
    }

    private static void writeList(List<?> list, ByteSink sink) {
        sink.writeByte('[');
        for (int index = 0; index < list.size(); index++) {
            if (index > 0) {
                sink.writeByte(',');
            }
            writeValue(list.get(index), sink);
        }
        sink.writeByte(']');
    }

    private static void writeLegacyValue(
            Object value, ByteSink sink) {
        try {
            byte[] json = JSON_MAPPER.writeValueAsBytes(value);
            byte[] wrapped = new byte[json.length + 2];
            wrapped[0] = '[';
            System.arraycopy(json, 0, wrapped, 1, json.length);
            wrapped[wrapped.length - 1] = ']';
            byte[] canonical =
                    new JsonCanonicalizer(wrapped).getEncodedUTF8();
            sink.write(canonical, 1, canonical.length - 2);
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Failed to canonicalize legacy raw value", exception);
        }
    }

    private static void writeString(String value, ByteSink sink) {
        sink.writeByte('"');
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            switch (current) {
                case '\b':
                    writeEscape(sink, 'b');
                    break;
                case '\t':
                    writeEscape(sink, 't');
                    break;
                case '\n':
                    writeEscape(sink, 'n');
                    break;
                case '\f':
                    writeEscape(sink, 'f');
                    break;
                case '\r':
                    writeEscape(sink, 'r');
                    break;
                case '"':
                case '\\':
                    writeEscape(sink, current);
                    break;
                default:
                    if (current < 0x20) {
                        sink.writeByte('\\');
                        sink.writeByte('u');
                        sink.writeByte('0');
                        sink.writeByte('0');
                        sink.writeByte(hex((current >>> 4) & 0x0f));
                        sink.writeByte(hex(current & 0x0f));
                    } else if (Character.isHighSurrogate(current)
                            && index + 1 < value.length()
                            && Character.isLowSurrogate(
                            value.charAt(index + 1))) {
                        writeUtf8CodePoint(Character.toCodePoint(
                                current, value.charAt(++index)), sink);
                    } else if (Character.isSurrogate(current)) {
                        sink.writeByte('?');
                    } else {
                        writeUtf8CodePoint(current, sink);
                    }
            }
        }
        sink.writeByte('"');
    }

    private static void writeNumber(double value, ByteSink sink) {
        if (!Double.isFinite(value)) {
            throw unsupported(Double.class);
        }
        try {
            writeBytes(sink, ascii(
                    NumberToJSON.serializeNumber(value)));
        } catch (IOException exception) {
            throw new IllegalArgumentException(
                    "Problem when generating canonized json.", exception);
        }
    }

    private static void writeEscape(ByteSink sink, int escaped) {
        sink.writeByte('\\');
        sink.writeByte(escaped);
    }

    private static int hex(int nibble) {
        return nibble < 10 ? '0' + nibble : 'a' + nibble - 10;
    }

    private static void writeUtf8CodePoint(
            int codePoint, ByteSink sink) {
        if (codePoint <= 0x7f) {
            sink.writeByte(codePoint);
        } else if (codePoint <= 0x7ff) {
            sink.writeByte(0xc0 | codePoint >>> 6);
            sink.writeByte(0x80 | codePoint & 0x3f);
        } else if (codePoint <= 0xffff) {
            sink.writeByte(0xe0 | codePoint >>> 12);
            sink.writeByte(0x80 | codePoint >>> 6 & 0x3f);
            sink.writeByte(0x80 | codePoint & 0x3f);
        } else {
            sink.writeByte(0xf0 | codePoint >>> 18);
            sink.writeByte(0x80 | codePoint >>> 12 & 0x3f);
            sink.writeByte(0x80 | codePoint >>> 6 & 0x3f);
            sink.writeByte(0x80 | codePoint & 0x3f);
        }
    }

    private static byte[] ascii(String value) {
        byte[] bytes = new byte[value.length()];
        for (int index = 0; index < value.length(); index++) {
            bytes[index] = (byte) value.charAt(index);
        }
        return bytes;
    }

    private static void writeBytes(ByteSink sink, byte[] bytes) {
        sink.write(bytes, 0, bytes.length);
    }

    private static UnsupportedCanonicalValueException unsupported(
            Class<?> type) {
        return new UnsupportedCanonicalValueException(type);
    }

    private static final class ByteArraySink implements ByteSink {
        private final ByteArrayOutputStream output =
                new ByteArrayOutputStream(64);

        @Override
        public void writeByte(int value) {
            output.write(value);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            output.write(bytes, offset, length);
        }

        private byte[] toByteArray() {
            return output.toByteArray();
        }
    }

    /** Signals that the optimized writer cannot preserve wire semantics. */
    public static final class UnsupportedCanonicalValueException
            extends RuntimeException {

        /**
         * Creates an exception for the unsupported runtime type.
         *
         * @param type unsupported runtime type, or {@code null} for a null map key
         */
        public UnsupportedCanonicalValueException(Class<?> type) {
            super(type == null
                    ? "Unsupported null map key"
                    : "Unsupported canonical value: " + type.getName());
        }
    }
}
