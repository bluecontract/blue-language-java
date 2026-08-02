package blue.language.snapshot;

import blue.language.identity.CanonicalJsonValueWriter;
import blue.language.model.NodeWireForm;

import blue.language.model.Node;
import blue.language.model.Schema;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

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

    private FrozenCanonicalWriter() {
    }

    /** Receives canonical bytes in encounter order. */
    interface CanonicalByteSink
            extends CanonicalJsonValueWriter.ByteSink {
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
        return CanonicalJsonValueWriter.write(value);
    }

    static void writeCanonicalValue(Object value, CanonicalByteSink sink) {
        CanonicalJsonValueWriter.write(value, sink);
    }

    /**
     * Tests whether a value can use the allocation-friendly canonical writer.
     *
     * @param value value to inspect
     * @return {@code true} when the canonical writer supports the value directly
     */
    public static boolean supportsCanonicalValue(Object value) {
        return CanonicalJsonValueWriter.supports(value);
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
            sink.writeByte('[');
            for (int index = 0; index < schema.getEnum().size(); index++) {
                if (index > 0) sink.writeByte(',');
                writeSchemaScalarOrNode(schema.getEnum().get(index), sink, mode);
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

}
