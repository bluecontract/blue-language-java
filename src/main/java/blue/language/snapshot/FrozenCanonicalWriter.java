package blue.language.snapshot;

import blue.language.model.wire.SchemaPropertyConstants;

import blue.language.model.NodeWireForm;

import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.model.value.BlueNumbers;
import blue.language.model.wire.BlueLanguageConstants;
import blue.language.utils.SchemaEnumCanonicalizer;
import blue.language.utils.UncheckedObjectMapper;
import org.erdtman.jcs.NumberToJSON;
import org.erdtman.jcs.JsonCanonicalizer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static blue.language.model.wire.BlueLanguageConstants.*;
import static blue.language.model.wire.SchemaPropertyConstants.*;

/**
 * Writes the exact JCS byte representation of a frozen node's direct BlueId
 * input without first materializing that input as a complete map/list graph.
 *
 * <p>This class is deliberately stateless. A caller supplies the byte sink, so
 * the normal identity path can feed a {@link java.security.MessageDigest}
 * directly while tests can capture the bytes and compare them with the legacy
 * JSON/JCS pipeline.</p>
 */
public final class FrozenCanonicalWriter {

    private static final byte[] TRUE = ascii("true");
    private static final byte[] FALSE = ascii("false");
    private static final byte[] NULL = ascii("null");
    private static final int MAX_PLAIN_VALUE_DEPTH = 100;
    private static final int MAX_PLAIN_MAP_FIELDS = 256;
    private static final Class<?> SINGLETON_MAP_CLASS =
            Collections.singletonMap("key", BlueLanguageConstants.OBJECT_VALUE).getClass();
    private static final ThreadLocal<Set<String>> MAP_KEYS = new ThreadLocal<Set<String>>() {
        @Override
        protected Set<String> initialValue() {
            return new HashSet<>();
        }
    };

    private FrozenCanonicalWriter() {
    }

    /** Receives canonical bytes in encounter order. */
    interface CanonicalByteSink {
        void writeByte(int value);

        void write(byte[] bytes, int offset, int length);
    }

    /**
     * Writes a validated strict frozen node. Validation is performed by the
     * digester before this method is used on the production identity path.
     */
    static void write(FrozenNode node, CanonicalByteSink sink) {
        if (node == null) {
            throw new IllegalArgumentException("BlueId input must not contain null nodes. Path: /");
        }
        Context context = node.isListElementContext() ? Context.LIST_ELEMENT : Context.ROOT;
        int listIndex = node.isListElementContext() ? 0 : -1;
        writeNode(node, sink, context, listIndex, Mode.BLUE_ID_INPUT);
    }

    /** Streams the JCS form of {@code NodeWireForm.OFFICIAL}. */
    static void writeOfficial(FrozenNode node, CanonicalByteSink sink) {
        if (node == null) {
            throw new IllegalArgumentException("node must not be null");
        }
        writeNode(node, sink, Context.ROOT, -1, Mode.OFFICIAL);
    }

    /**
     * Computes the exact byte count of the official authored representation used by gas.
     *
     * @param node frozen node to measure, or {@code null}
     * @return canonical byte count, or zero for a null node
     */
    public static long officialCanonicalSize(FrozenNode node) {
        if (node == null) return 0L;
        CountingSink sink = new CountingSink();
        writeOfficial(node, sink);
        return sink.bytes;
    }

    /**
     * Returns the exact RFC 8785 byte representation used by the frozen identity
     * path for JSON-compatible scalar, map, and list values.
     *
     * <p>This is the allocation-friendly counterpart to serializing with
     * Jackson and parsing the result again with a JCS canonicalizer. Callers
     * that accept arbitrary Jackson-serializable objects should first use
     * {@link #supportsCanonicalValue(Object)} and retain their compatibility
     * fallback for unsupported values.</p>
     *
     * @param value JSON-compatible scalar, map, list, or supported array value
     * @return exact RFC 8785 representation of the value
     */
    public static byte[] canonicalValueBytes(Object value) {
        ByteArraySink sink = new ByteArraySink();
        writeCanonicalValue(value, sink);
        return sink.toByteArray();
    }

    static void writeCanonicalValue(Object value, CanonicalByteSink sink) {
        if (value == null) {
            writeBytes(sink, NULL);
            return;
        }
        if (value instanceof String) {
            writeString((String) value, sink);
            return;
        }
        if (value instanceof Character) {
            writeString(String.valueOf(value), sink);
            return;
        }
        if (value instanceof Boolean) {
            writeBytes(sink, Boolean.TRUE.equals(value) ? TRUE : FALSE);
            return;
        }
        if (value instanceof Enum) {
            // Enum wire values may be customized by Jackson annotations. Keep the
            // compatibility serializer as the source of truth instead of using name().
            writeLegacyCanonicalValue(value, sink);
            return;
        }
        if (value instanceof BigInteger) {
            BigInteger integer = (BigInteger) value;
            if (integer.compareTo(BlueNumbers.MIN_INTEROPERABLE_INTEGER) < 0
                    || integer.compareTo(BlueNumbers.MAX_INTEROPERABLE_INTEGER) > 0) {
                // UncheckedObjectMapper's registered BigInteger serializer uses
                // a JSON string outside the interoperable integer range.
                writeString(integer.toString(), sink);
            } else {
                writeNumber(integer.doubleValue(), sink);
            }
            return;
        }
        if (value instanceof BigDecimal) {
            writeNumber(((BigDecimal) value).doubleValue(), sink);
            return;
        }
        if (value instanceof Float) {
            // Jackson preserves the source float's shortest decimal spelling.
            // Widening the binary float directly would encode a different JSON number.
            writeNumber(Double.parseDouble(Float.toString((Float) value)), sink);
            return;
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof Double) {
            writeNumber(((Number) value).doubleValue(), sink);
            return;
        }
        if (value instanceof Map) {
            writeMap((Map<?, ?>) value, sink);
            return;
        }
        if (value instanceof List) {
            writeList((List<?>) value, sink);
            return;
        }
        if (value instanceof byte[]) {
            // Jackson's default byte-array serializer uses the standard padded
            // Base64 alphabet and emits a JSON string, not a numeric array.
            writeString(Base64.getEncoder().encodeToString((byte[]) value), sink);
            return;
        }
        if (value instanceof char[]) {
            // Jackson's char-array serializer likewise emits one JSON string.
            writeString(new String((char[]) value), sink);
            return;
        }
        if (value.getClass().isArray()) {
            writeLegacyCanonicalValue(value, sink);
            return;
        }
        throw new UnsupportedCanonicalValueException(value.getClass());
    }

    /**
     * Tests whether a value can use the allocation-friendly canonical writer.
     *
     * @param value value to inspect
     * @return {@code true} when the canonical writer supports the value directly
     */
    public static boolean supportsCanonicalValue(Object value) {
        return supportsCanonicalValue(value, 0);
    }

    private static boolean supportsCanonicalValue(Object value, int depth) {
        if (depth > MAX_PLAIN_VALUE_DEPTH) return false;
        if (value == null) return true;

        Class<?> type = value.getClass();
        if (type == String.class || type == Boolean.class
                || type == BigInteger.class
                || type == Byte.class || type == Short.class || type == Integer.class
                || type == Long.class) {
            return true;
        }
        if (type == BigDecimal.class || type == Float.class || type == Double.class) {
            return Double.isFinite(((Number) value).doubleValue());
        }

        // Only exact container implementations produced by the canonical helper-map
        // builders are admitted. Subclasses may carry Jackson annotations or custom
        // serializers that change their wire representation.
        boolean plainList = type == ArrayList.class;
        boolean plainMap = type == LinkedHashMap.class || type == TreeMap.class
                || type == SINGLETON_MAP_CLASS;
        if (!plainList && !plainMap) return false;
        if (plainList) {
            for (Object element : (List<?>) value) {
                if (!supportsCanonicalValue(element, depth + 1)) {
                    return false;
                }
            }
            return true;
        }
        Map<?, ?> map = (Map<?, ?>) value;
        if (map.size() > MAX_PLAIN_MAP_FIELDS) return false;
        if (type == TreeMap.class && !hasUniqueStringKeys(map)) return false;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() == null || entry.getKey().getClass() != String.class
                    || !supportsCanonicalValue(entry.getValue(), depth + 1)) {
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

    private enum Context {
        ROOT,
        OBJECT_FIELD,
        LIST_ELEMENT,
        METADATA
    }

    private enum Mode {
        BLUE_ID_INPUT,
        OFFICIAL
    }

    private static void writeNode(FrozenNode node,
                                  CanonicalByteSink sink,
                                  Context context,
                                  int listIndex,
                                  Mode mode) {
        if ((mode == Mode.OFFICIAL || context == Context.LIST_ELEMENT)
                && FrozenCanonicalDigester.isEmptyPlaceholder(node)) {
            sink.writeByte('{');
            writeString(LIST_CONTROL_EMPTY, sink);
            sink.writeByte(':');
            writeBytes(sink, TRUE);
            sink.writeByte('}');
            return;
        }

        if (node.isReferenceOnly()) {
            writeReference(node.getReferenceBlueId(), sink);
            return;
        }

        if (node.getPreviousBlueId() != null) {
            sink.writeByte('{');
            writeString(LIST_CONTROL_PREVIOUS, sink);
            sink.writeByte(':');
            writeReference(node.getPreviousBlueId(), sink);
            sink.writeByte('}');
            return;
        }

        List<FrozenNode> items = node.getItems();
        if (mode == Mode.BLUE_ID_INPUT && items != null
                && FrozenCanonicalDigester.isPayloadOnlyList(node)) {
            writeNodeList(items, sink, mode);
            return;
        }

        String[] keys = nodeInputKeys(node, mode);
        sink.writeByte('{');
        boolean first = true;
        String previous = null;
        for (String key : keys) {
            if (key.equals(previous)) {
                continue;
            }
            previous = key;
            if (!first) {
                sink.writeByte(',');
            }
            first = false;
            writeString(key, sink);
            sink.writeByte(':');
            writeNodeField(node, key, sink, mode);
        }
        sink.writeByte('}');
    }

    private static void writeNodeField(FrozenNode node,
                                       String key,
                                       CanonicalByteSink sink,
                                       Mode mode) {
        Map<String, FrozenNode> properties = node.getProperties();
        if (properties != null && properties.containsKey(key)) {
            writeNode(properties.get(key), sink, Context.OBJECT_FIELD, -1, mode);
            return;
        }
        if (OBJECT_NAME.equals(key)) {
            writeString(node.getName(), sink);
        } else if (OBJECT_DESCRIPTION.equals(key)) {
            writeString(node.getDescription(), sink);
        } else if (OBJECT_TYPE.equals(key)) {
            if (node.getType() != null) {
                writeNode(node.getType(), sink, Context.METADATA, -1, mode);
            } else {
                writeReference(FrozenCanonicalDigester.inferTypeBlueId(node.frozenValue()), sink);
            }
        } else if (OBJECT_ITEM_TYPE.equals(key)) {
            writeNode(node.getItemType(), sink, Context.METADATA, -1, mode);
        } else if (OBJECT_KEY_TYPE.equals(key)) {
            writeNode(node.getKeyType(), sink, Context.METADATA, -1, mode);
        } else if (OBJECT_VALUE_TYPE.equals(key)) {
            writeNode(node.getValueType(), sink, Context.METADATA, -1, mode);
        } else if (OBJECT_MERGE_POLICY.equals(key)) {
            writeString(node.getMergePolicy(), sink);
        } else if (OBJECT_VALUE.equals(key)) {
            String valueTypeBlueId = node.getType() != null
                    ? node.getType().getReferenceBlueId()
                    : FrozenCanonicalDigester.inferTypeBlueId(node.frozenValue());
            writeCanonicalValue(FrozenCanonicalDigester.handleValue(
                    node.frozenValue(), valueTypeBlueId), sink);
        } else if (OBJECT_ITEMS.equals(key)) {
            writeNodeList(node.getItems(), sink, mode);
        } else if (OBJECT_SCHEMA.equals(key)) {
            writeSchema(node.frozenSchemaView(), sink, mode);
        } else if (OBJECT_CONTRACTS.equals(key)) {
            writeNode(node.getContracts(), sink, Context.METADATA, -1, mode);
        } else if (LIST_CONTROL_POS.equals(key)) {
            writeCanonicalValue(BigInteger.valueOf(node.getPosition()), sink);
        } else if (OBJECT_BLUE.equals(key)) {
            writeNode(node.getBlue(), sink, Context.METADATA, -1, mode);
        } else {
            throw new IllegalStateException("Unknown frozen BlueId input field: " + key);
        }
    }

    private static String[] nodeInputKeys(FrozenNode node, Mode mode) {
        List<String> keys = new ArrayList<>();
        if (node.getName() != null) keys.add(OBJECT_NAME);
        if (node.getDescription() != null) keys.add(OBJECT_DESCRIPTION);
        if (node.getType() != null
                || node.frozenValue() != null
                && FrozenCanonicalDigester.inferTypeBlueId(node.frozenValue()) != null) {
            keys.add(OBJECT_TYPE);
        }
        if (node.getItemType() != null) keys.add(OBJECT_ITEM_TYPE);
        if (node.getKeyType() != null) keys.add(OBJECT_KEY_TYPE);
        if (node.getValueType() != null) keys.add(OBJECT_VALUE_TYPE);
        if (node.getMergePolicy() != null) keys.add(OBJECT_MERGE_POLICY);
        if (node.frozenValue() != null) keys.add(OBJECT_VALUE);
        if (node.getItems() != null) keys.add(OBJECT_ITEMS);
        if (node.frozenSchemaView() != null) keys.add(OBJECT_SCHEMA);
        if (node.getContracts() != null) keys.add(OBJECT_CONTRACTS);
        if (mode == Mode.OFFICIAL && node.getPosition() != null) keys.add(LIST_CONTROL_POS);
        if (mode == Mode.OFFICIAL && node.getBlue() != null) keys.add(OBJECT_BLUE);
        if (node.getProperties() != null) keys.addAll(node.getProperties().keySet());
        String[] sorted = keys.toArray(new String[0]);
        Arrays.sort(sorted);
        return sorted;
    }

    private static void writeNodeList(List<FrozenNode> nodes,
                                      CanonicalByteSink sink,
                                      Mode mode) {
        sink.writeByte('[');
        for (int index = 0; index < nodes.size(); index++) {
            if (index > 0) {
                sink.writeByte(',');
            }
            writeNode(nodes.get(index), sink, Context.LIST_ELEMENT, index, mode);
        }
        sink.writeByte(']');
    }

    private static void writeSchema(Schema schema,
                                    CanonicalByteSink sink,
                                    Mode mode) {
        List<String> keys = new ArrayList<>();
        if (schema.getRequired() != null && schema.getRequiredValue() != null) keys.add(KEY_REQUIRED);
        if (schema.getMinLength() != null && schema.getMinLength().getValue() != null) keys.add(KEY_MIN_LENGTH);
        if (schema.getMaxLength() != null && schema.getMaxLength().getValue() != null) keys.add(KEY_MAX_LENGTH);
        if (schema.getMinimum() != null) keys.add(KEY_MINIMUM);
        if (schema.getMaximum() != null) keys.add(KEY_MAXIMUM);
        if (schema.getExclusiveMinimum() != null) keys.add(KEY_EXCLUSIVE_MINIMUM);
        if (schema.getExclusiveMaximum() != null) keys.add(KEY_EXCLUSIVE_MAXIMUM);
        if (schema.getMultipleOf() != null) keys.add(KEY_MULTIPLE_OF);
        if (schema.getMinItems() != null && schema.getMinItems().getValue() != null) keys.add(KEY_MIN_ITEMS);
        if (schema.getMaxItems() != null && schema.getMaxItems().getValue() != null) keys.add(KEY_MAX_ITEMS);
        if (schema.getUniqueItems() != null && schema.getUniqueItemsValue() != null) keys.add(KEY_UNIQUE_ITEMS);
        if (schema.getMinFields() != null && schema.getMinFields().getValue() != null) keys.add(KEY_MIN_FIELDS);
        if (schema.getMaxFields() != null && schema.getMaxFields().getValue() != null) keys.add(KEY_MAX_FIELDS);
        if (schema.getEnum() != null) keys.add(KEY_ENUM);
        String[] sorted = keys.toArray(new String[0]);
        Arrays.sort(sorted);

        sink.writeByte('{');
        for (int index = 0; index < sorted.length; index++) {
            if (index > 0) sink.writeByte(',');
            String key = sorted[index];
            writeString(key, sink);
            sink.writeByte(':');
            writeSchemaField(schema, key, sink, mode);
        }
        sink.writeByte('}');
    }

    private static void writeSchemaField(Schema schema,
                                         String key,
                                         CanonicalByteSink sink,
                                         Mode mode) {
        if (KEY_REQUIRED.equals(key)) {
            writeCanonicalValue(schema.getRequiredValue(), sink);
        } else if (KEY_MIN_LENGTH.equals(key)) {
            writeCanonicalValue(schema.getMinLength().getValue(), sink);
        } else if (KEY_MAX_LENGTH.equals(key)) {
            writeCanonicalValue(schema.getMaxLength().getValue(), sink);
        } else if (KEY_MINIMUM.equals(key)) {
            writeSchemaNumeric(schema.getMinimum(), sink, mode);
        } else if (KEY_MAXIMUM.equals(key)) {
            writeSchemaNumeric(schema.getMaximum(), sink, mode);
        } else if (KEY_EXCLUSIVE_MINIMUM.equals(key)) {
            writeSchemaNumeric(schema.getExclusiveMinimum(), sink, mode);
        } else if (KEY_EXCLUSIVE_MAXIMUM.equals(key)) {
            writeSchemaNumeric(schema.getExclusiveMaximum(), sink, mode);
        } else if (KEY_MULTIPLE_OF.equals(key)) {
            writeSchemaNumeric(schema.getMultipleOf(), sink, mode);
        } else if (KEY_MIN_ITEMS.equals(key)) {
            writeCanonicalValue(schema.getMinItems().getValue(), sink);
        } else if (KEY_MAX_ITEMS.equals(key)) {
            writeCanonicalValue(schema.getMaxItems().getValue(), sink);
        } else if (KEY_UNIQUE_ITEMS.equals(key)) {
            writeCanonicalValue(schema.getUniqueItemsValue(), sink);
        } else if (KEY_MIN_FIELDS.equals(key)) {
            writeCanonicalValue(schema.getMinFields().getValue(), sink);
        } else if (KEY_MAX_FIELDS.equals(key)) {
            writeCanonicalValue(schema.getMaxFields().getValue(), sink);
        } else if (KEY_ENUM.equals(key)) {
            List<Node> enumValues = mode == Mode.BLUE_ID_INPUT
                    ? SchemaEnumCanonicalizer.canonicalize(schema.getEnum())
                    : schema.getEnum();
            sink.writeByte('[');
            for (int index = 0; index < enumValues.size(); index++) {
                if (index > 0) sink.writeByte(',');
                writeSchemaScalarOrNode(enumValues.get(index), sink, mode);
            }
            sink.writeByte(']');
        } else {
            throw new IllegalStateException("Unknown schema field: " + key);
        }
    }

    private static void writeSchemaNumeric(Node value,
                                           CanonicalByteSink sink,
                                           Mode mode) {
        writeSchemaScalarOrNode(value, sink, mode);
    }

    private static void writeSchemaScalarOrNode(Node value,
                                                CanonicalByteSink sink,
                                                Mode mode) {
        if (isPlainScalar(value)) {
            writeCanonicalValue(value.getValue(), sink);
        } else {
            writeNode(FrozenNode.fromNode(value), sink, Context.METADATA, -1, mode);
        }
    }

    static boolean isPlainScalar(Node node) {
        return node != null && node.getValue() != null
                && node.getName() == null && node.getDescription() == null
                && node.getType() == null && node.getItemType() == null
                && node.getKeyType() == null && node.getValueType() == null
                && node.getItems() == null && node.getProperties() == null
                && node.getContracts() == null && node.getBlueId() == null
                && node.getSchema() == null && node.getMergePolicy() == null
                && node.getPreviousBlueId() == null && node.getPosition() == null
                && node.getBlue() == null;
    }

    private static void writeReference(String blueId, CanonicalByteSink sink) {
        sink.writeByte('{');
        writeString(OBJECT_BLUE_ID, sink);
        sink.writeByte(':');
        writeString(blueId, sink);
        sink.writeByte('}');
    }

    private static void writeMap(Map<?, ?> map, CanonicalByteSink sink) {
        Map<String, Object> retainedFields = new TreeMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getValue() == null) {
                // Match the legacy mapper's NON_NULL map-value inclusion.
                continue;
            }
            Object key = entry.getKey();
            if (!(key instanceof String)) {
                throw new UnsupportedCanonicalValueException(key == null ? null : key.getClass());
            }
            String stringKey = (String) key;
            if (retainedFields.containsKey(stringKey)) {
                throw new UnsupportedCanonicalValueException(String.class);
            }
            retainedFields.put(stringKey, entry.getValue());
        }
        sink.writeByte('{');
        boolean first = true;
        for (Map.Entry<String, Object> entry : retainedFields.entrySet()) {
            if (!first) {
                sink.writeByte(',');
            }
            first = false;
            writeString(entry.getKey(), sink);
            sink.writeByte(':');
            writeCanonicalValue(entry.getValue(), sink);
        }
        sink.writeByte('}');
    }

    private static void writeList(List<?> list, CanonicalByteSink sink) {
        sink.writeByte('[');
        for (int index = 0; index < list.size(); index++) {
            if (index > 0) {
                sink.writeByte(',');
            }
            writeCanonicalValue(list.get(index), sink);
        }
        sink.writeByte(']');
    }

    private static void writeLegacyCanonicalValue(Object value, CanonicalByteSink sink) {
        try {
            byte[] json = UncheckedObjectMapper.JSON_MAPPER.writeValueAsBytes(value);
            byte[] wrapped = new byte[json.length + 2];
            wrapped[0] = '[';
            System.arraycopy(json, 0, wrapped, 1, json.length);
            wrapped[wrapped.length - 1] = ']';
            byte[] canonicalWrapped = new JsonCanonicalizer(wrapped).getEncodedUTF8();
            sink.write(canonicalWrapped, 1, canonicalWrapped.length - 2);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to canonicalize legacy raw value", exception);
        }
    }

    private static void writeString(String value, CanonicalByteSink sink) {
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
                            && Character.isLowSurrogate(value.charAt(index + 1))) {
                        int codePoint = Character.toCodePoint(current, value.charAt(++index));
                        writeUtf8CodePoint(codePoint, sink);
                    } else if (Character.isSurrogate(current)) {
                        // String.getBytes(UTF_8), used by JsonCanonicalizer 1.1,
                        // replaces an unpaired UTF-16 surrogate with '?'.
                        sink.writeByte('?');
                    } else {
                        writeUtf8CodePoint(current, sink);
                    }
            }
        }
        sink.writeByte('"');
    }

    private static void writeNumber(double value, CanonicalByteSink sink) {
        if (!Double.isFinite(value)) {
            throw new UnsupportedCanonicalValueException(Double.class);
        }
        try {
            writeBytes(sink, ascii(NumberToJSON.serializeNumber(value)));
        } catch (IOException exception) {
            throw new IllegalArgumentException("Problem when generating canonized json.", exception);
        }
    }

    private static void writeEscape(CanonicalByteSink sink, int escaped) {
        sink.writeByte('\\');
        sink.writeByte(escaped);
    }

    private static int hex(int nibble) {
        return nibble < 10 ? '0' + nibble : 'a' + nibble - 10;
    }

    private static void writeUtf8CodePoint(int codePoint, CanonicalByteSink sink) {
        if (codePoint <= 0x7f) {
            sink.writeByte(codePoint);
        } else if (codePoint <= 0x7ff) {
            sink.writeByte(0xc0 | (codePoint >>> 6));
            sink.writeByte(0x80 | (codePoint & 0x3f));
        } else if (codePoint <= 0xffff) {
            sink.writeByte(0xe0 | (codePoint >>> 12));
            sink.writeByte(0x80 | ((codePoint >>> 6) & 0x3f));
            sink.writeByte(0x80 | (codePoint & 0x3f));
        } else {
            sink.writeByte(0xf0 | (codePoint >>> 18));
            sink.writeByte(0x80 | ((codePoint >>> 12) & 0x3f));
            sink.writeByte(0x80 | ((codePoint >>> 6) & 0x3f));
            sink.writeByte(0x80 | (codePoint & 0x3f));
        }
    }

    private static byte[] ascii(String value) {
        byte[] bytes = new byte[value.length()];
        for (int index = 0; index < value.length(); index++) {
            bytes[index] = (byte) value.charAt(index);
        }
        return bytes;
    }

    private static void writeBytes(CanonicalByteSink sink, byte[] bytes) {
        sink.write(bytes, 0, bytes.length);
    }

    private static final class CountingSink implements CanonicalByteSink {
        private long bytes;

        @Override
        public void writeByte(int value) {
            bytes++;
        }

        @Override
        public void write(byte[] values, int offset, int length) {
            bytes += length;
        }
    }

    private static final class ByteArraySink implements CanonicalByteSink {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream(64);

        @Override
        public void writeByte(int value) {
            output.write(value);
        }

        @Override
        public void write(byte[] values, int offset, int length) {
            output.write(values, offset, length);
        }

        private byte[] toByteArray() {
            return output.toByteArray();
        }
    }

    static final class UnsupportedCanonicalValueException extends RuntimeException {
        UnsupportedCanonicalValueException(Class<?> type) {
            super(type == null ? "Unsupported null map key" : "Unsupported canonical value: " + type.getName());
        }
    }
}
