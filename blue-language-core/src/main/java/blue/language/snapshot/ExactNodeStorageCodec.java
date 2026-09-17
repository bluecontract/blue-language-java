package blue.language.snapshot;

import blue.language.model.Node;
import blue.language.model.Schema;

import java.io.*;
import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * Versioned physical storage of every Node and Schema field, including supported
 * raw scalar types, exact UTF-16 units and null/empty distinctions. This is not
 * Blue wire, provider authentication or Java object deserialization.
 *
 * <p>The byte and nesting bounds are host operational limits. A bound failure
 * must abort the host publication; it must never become a semantic outcome.
 * Decoding accepts only complete, checksum-valid, canonically framed bytes.
 * The checksum detects byte damage; the host must authenticate its immutable
 * object reference separately before treating restored evidence as trusted.</p>
 *
 * <p>Only the explicitly encoded scalar and array-component classes are supported.
 * Host-specific Java values such as enums fail closed; decoding never loads their
 * classes. This transport restriction does not change Language value semantics.</p>
 */
public final class ExactNodeStorageCodec {
    /** Versioned physical format binding. */
    public static final String FORMAT = "blue-language/exact-node-storage/1";
    private final int maximumBytes;
    private final int maximumDepth;

    /**
     * Creates a codec with operational bounds.
     * @param maximumBytes maximum complete encoded bytes, at least 128
     * @param maximumDepth maximum nesting depth, from 1 through 256
     */
    public ExactNodeStorageCodec(int maximumBytes, int maximumDepth) {
        if (maximumBytes < 128 || maximumDepth < 1 || maximumDepth > 256)
            throw invalid("Invalid exact storage bounds (depth must be 1..256)");
        this.maximumBytes = maximumBytes;
        this.maximumDepth = maximumDepth;
    }

    /**
     * Encodes all mutable Node fields.
     * @param node exact detached input
     * @return complete checksummed bytes
     */
    public byte[] encode(final Node node) {
        Objects.requireNonNull(node, "node");
        return encodeEnvelope(FORMAT, out -> writeNode(out, node, 0));
    }

    /**
     * Restores a detached Node after complete physical verification.
     * @param bytes bytes authenticated by the enclosing immutable object store
     * @return detached exact Node
     */
    public Node decode(byte[] bytes) {
        Node value = decodeEnvelope(bytes, FORMAT, in -> readNode(in, 0));
        if (value == null || !Arrays.equals(bytes, encode(value))) throw invalid("Noncanonical exact node bytes");
        return value;
    }

    /**
     * Physical framing shared by Language-owned evidence codecs.
     * @param format exact versioned format binding
     * @param encoder field encoder
     * @return complete checksummed bytes
     */
    public byte[] encodeEnvelope(String format, Encoder encoder) {
        LimitedBuffer buffer = new LimitedBuffer(maximumBytes - 32);
        try {
            DataOutputStream out = new DataOutputStream(buffer);
            out.writeInt(0x42455331); // BES1
            writeText(out, format);
            encoder.write(out);
            byte[] payload = buffer.toByteArray();
            byte[] result = Arrays.copyOf(payload, payload.length + 32);
            System.arraycopy(digest(payload), 0, result, payload.length, 32);
            return result;
        } catch (IOException failure) { throw invalid("Cannot encode exact storage", failure); }
    }

    /**
     * Validates a bounded physical envelope and decodes its complete contents.
     * @param <T> decoded value type
     * @param bytes complete encoded envelope
     * @param format required format binding
     * @param decoder field decoder
     * @return decoded value
     */
    public <T> T decodeEnvelope(byte[] bytes, String format, Decoder<T> decoder) {
        if (bytes == null || bytes.length < 36 || bytes.length > maximumBytes)
            throw invalid("Missing, truncated or oversized exact storage");
        byte[] payload = Arrays.copyOf(bytes, bytes.length - 32);
        if (!MessageDigest.isEqual(digest(payload), Arrays.copyOfRange(bytes, payload.length, bytes.length)))
            throw invalid("Exact storage checksum mismatch");
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
            if (in.readInt() != 0x42455331 || !format.equals(readText(in))) throw invalid("Exact storage format mismatch");
            T result = decoder.read(in);
            if (in.available() != 0) throw invalid("Trailing exact storage bytes");
            return result;
        } catch (IOException failure) { throw invalid("Truncated exact storage", failure); }
    }

    /**
     * Writes a nullable Node within an enclosing bounded envelope.
     * @param out envelope output
     * @param node nullable node
     * @param depth current nesting depth
     * @throws IOException when output fails
     */
    public void writeNode(DataOutputStream out, Node node, int depth) throws IOException {
        depth(depth);
        out.writeBoolean(node != null);
        if (node == null) return;
        writeText(out, node.getName()); writeText(out, node.getDescription());
        writeNode(out, node.getType(), depth + 1); writeNode(out, node.getItemType(), depth + 1);
        writeNode(out, node.getKeyType(), depth + 1); writeNode(out, node.getValueType(), depth + 1);
        writeValue(out, node.getRawValue(), depth + 1); writeNodes(out, node.getItems(), depth + 1);
        Map<String, Node> properties = node.getProperties();
        out.writeInt(properties == null ? -1 : properties.size());
        if (properties != null) for (Map.Entry<String, Node> entry : properties.entrySet()) {
            writeText(out, Objects.requireNonNull(entry.getKey(), "property key")); writeNode(out, entry.getValue(), depth + 1);
        }
        writeNode(out, node.getContracts(), depth + 1); writeText(out, node.getBlueId());
        writeSchema(out, node.getSchema(), depth + 1); writeText(out, node.getMergePolicy());
        writeText(out, node.getPreviousBlueId()); out.writeBoolean(node.getPosition() != null);
        if (node.getPosition() != null) out.writeInt(node.getPosition());
        writeNode(out, node.getBlue(), depth + 1); out.writeBoolean(node.isInlineValue());
        out.writeBoolean(node.isPreprocessingTransformationConfiguration());
    }

    /**
     * Reads a nullable Node within an enclosing verified envelope.
     * @param in envelope input
     * @param depth current nesting depth
     * @return nullable detached node
     * @throws IOException when input is truncated
     */
    public Node readNode(DataInputStream in, int depth) throws IOException {
        depth(depth);
        if (!readBoolean(in)) return null;
        Node node = new Node().name(readText(in)).description(readText(in))
                .type(readNode(in, depth + 1)).itemType(readNode(in, depth + 1))
                .keyType(readNode(in, depth + 1)).valueType(readNode(in, depth + 1))
                .value(readValue(in, depth + 1)).items(readNodes(in, depth + 1));
        int count = count(in, true);
        if (count >= 0) {
            Map<String, Node> properties = new LinkedHashMap<>();
            for (int i = 0; i < count; i++) {
                String key = requiredText(in);
                if (properties.containsKey(key)) throw invalid("Duplicate Node property");
                properties.put(key, readNode(in, depth + 1));
            }
            node.properties(properties);
        }
        node.contracts(readNode(in, depth + 1)).blueId(readText(in)).schema(readSchema(in, depth + 1))
                .mergePolicy(readText(in)).previousBlueId(readText(in));
        if (readBoolean(in)) node.position(in.readInt());
        return node.blue(readNode(in, depth + 1)).inlineValue(readBoolean(in))
                .preprocessingTransformationConfiguration(readBoolean(in));
    }

    /**
     * Writes all nullable schema fields.
     * @param out envelope output
     * @param schema nullable schema
     * @param depth current nesting depth
     * @throws IOException when output fails
     */
    public void writeSchema(DataOutputStream out, Schema schema, int depth) throws IOException {
        depth(depth); out.writeBoolean(schema != null);
        if (schema == null) return;
        writeText(out, schema.getBlueId());
        for (Node keyword : Arrays.asList(schema.getRequired(), schema.getMinLength(), schema.getMaxLength(),
                schema.getMinimum(), schema.getMaximum(), schema.getExclusiveMinimum(), schema.getExclusiveMaximum(),
                schema.getMultipleOf(), schema.getMinItems(), schema.getMaxItems(), schema.getUniqueItems(),
                schema.getMinFields(), schema.getMaxFields())) writeNode(out, keyword, depth + 1);
        writeNodes(out, schema.getEnum(), depth + 1);
    }

    /**
     * Reads all nullable schema fields.
     * @param in envelope input
     * @param depth current nesting depth
     * @return nullable detached schema
     * @throws IOException when input is truncated
     */
    public Schema readSchema(DataInputStream in, int depth) throws IOException {
        depth(depth); if (!readBoolean(in)) return null;
        return new Schema().blueId(readText(in)).required(readNode(in, depth + 1)).minLength(readNode(in, depth + 1))
                .maxLength(readNode(in, depth + 1)).minimum(readNode(in, depth + 1)).maximum(readNode(in, depth + 1))
                .exclusiveMinimum(readNode(in, depth + 1)).exclusiveMaximum(readNode(in, depth + 1))
                .multipleOf(readNode(in, depth + 1)).minItems(readNode(in, depth + 1)).maxItems(readNode(in, depth + 1))
                .uniqueItems(readNode(in, depth + 1)).minFields(readNode(in, depth + 1)).maxFields(readNode(in, depth + 1))
                .enumValues(readNodes(in, depth + 1));
    }

    private void writeNodes(DataOutputStream out, List<Node> nodes, int depth) throws IOException {
        out.writeInt(nodes == null ? -1 : nodes.size());
        if (nodes != null) for (Node node : nodes) writeNode(out, node, depth);
    }

    private List<Node> readNodes(DataInputStream in, int depth) throws IOException {
        int size = count(in, true);
        if (size < 0) return null;
        List<Node> nodes = new ArrayList<>();
        for (int i = 0; i < size; i++) nodes.add(readNode(in, depth));
        return nodes;
    }

    /**
     * Writes an exact supported raw value without scalar normalization.
     * @param out envelope output
     * @param value nullable raw value
     * @param depth current nesting depth
     * @throws IOException when output fails
     */
    public void writeValue(DataOutputStream out, Object value, int depth) throws IOException {
        depth(depth);
        if (value == null) out.writeByte(0);
        else if (value instanceof String) { out.writeByte(1); writeText(out, (String) value); }
        else if (value instanceof Boolean) { out.writeByte(2); out.writeBoolean((Boolean) value); }
        else if (value instanceof BigInteger) { out.writeByte(3); writeText(out, value.toString()); }
        else if (value instanceof BigDecimal) { out.writeByte(4); writeText(out, value.toString()); }
        else if (value instanceof Byte) { out.writeByte(5); out.writeByte((Byte) value); }
        else if (value instanceof Short) { out.writeByte(6); out.writeShort((Short) value); }
        else if (value instanceof Integer) { out.writeByte(7); out.writeInt((Integer) value); }
        else if (value instanceof Long) { out.writeByte(8); out.writeLong((Long) value); }
        else if (value instanceof Float) { out.writeByte(9); out.writeInt(Float.floatToRawIntBits((Float) value)); }
        else if (value instanceof Double) { out.writeByte(10); out.writeLong(Double.doubleToRawLongBits((Double) value)); }
        else if (value instanceof Character) { out.writeByte(11); out.writeChar((Character) value); }
        else if (value instanceof List) {
            List<?> list = (List<?>) value; out.writeByte(12); out.writeInt(list.size());
            for (Object item : list) writeValue(out, item, depth + 1);
        } else if (value instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) value; out.writeByte(13); out.writeInt(map.size());
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String)) throw invalid("Non-text raw map key");
                writeText(out, (String) entry.getKey()); writeValue(out, entry.getValue(), depth + 1);
            }
        } else if (value.getClass().isArray()) {
            out.writeByte(14); writeText(out, arrayType(value.getClass().getComponentType()));
            int size = Array.getLength(value); out.writeInt(size);
            for (int i = 0; i < size; i++) writeValue(out, Array.get(value, i), depth + 1);
        } else throw invalid("Unsupported non-Blue Java payload: " + value.getClass().getName());
    }

    /**
     * Reads an exact raw value using a fixed type allowlist.
     * @param in envelope input
     * @param depth current nesting depth
     * @return nullable detached raw value
     * @throws IOException when input is truncated
     */
    public Object readValue(DataInputStream in, int depth) throws IOException {
        depth(depth);
        switch (in.readUnsignedByte()) {
            case 0: return null;
            case 1: return requiredText(in);
            case 2: return readBoolean(in);
            case 3: return new BigInteger(requiredText(in));
            case 4: return new BigDecimal(requiredText(in));
            case 5: return in.readByte();
            case 6: return in.readShort();
            case 7: return in.readInt();
            case 8: return in.readLong();
            case 9: return Float.intBitsToFloat(in.readInt());
            case 10: return Double.longBitsToDouble(in.readLong());
            case 11: return in.readChar();
            case 12: {
                int size = count(in, false); List<Object> list = new ArrayList<>();
                for (int i = 0; i < size; i++) list.add(readValue(in, depth + 1));
                return list;
            }
            case 13: {
                int size = count(in, false); Map<String, Object> map = new LinkedHashMap<>();
                for (int i = 0; i < size; i++) {
                    String key = requiredText(in);
                    if (map.containsKey(key)) throw invalid("Duplicate raw map key");
                    map.put(key, readValue(in, depth + 1));
                }
                return map;
            }
            case 14: {
                Class<?> type = arrayType(requiredText(in)); int size = count(in, false);
                Object array = Array.newInstance(type, size);
                for (int i = 0; i < size; i++) Array.set(array, i, readValue(in, depth + 1));
                return array;
            }
            default: throw invalid("Unknown raw value tag");
        }
    }

    /**
     * Enforces the configured operational nesting bound.
     * @param depth current nesting depth
     */
    public void depth(int depth) { if (depth > maximumDepth) throw invalid("Exact storage nesting bound exceeded"); }
    /**
     * Writes nullable exact UTF-16 units.
     * @param out envelope output
     * @param text nullable text
     * @throws IOException when output fails
     */
    public static void writeText(DataOutputStream out, String text) throws IOException {
        out.writeInt(text == null ? -1 : text.length());
        if (text != null) for (int i = 0; i < text.length(); i++) out.writeChar(text.charAt(i));
    }
    /**
     * Reads bounded nullable exact UTF-16 units.
     * @param in envelope input
     * @return nullable text
     * @throws IOException when input is truncated
     */
    public static String readText(DataInputStream in) throws IOException {
        int size = in.readInt(); if (size == -1) return null;
        if (size < 0 || size > in.available() / 2) throw invalid("Invalid exact text length");
        char[] chars = new char[size];
        for (int i = 0; i < size; i++) chars[i] = in.readChar();
        return new String(chars);
    }
    /**
     * Reads required exact text.
     * @param in envelope input
     * @return present text
     * @throws IOException when input is truncated
     */
    public static String requiredText(DataInputStream in) throws IOException {
        String text = readText(in); if (text == null) throw invalid("Missing exact text"); return text;
    }
    /**
     * Reads a collection count bounded by remaining bytes before allocation.
     * @param in envelope input
     * @param nullable whether minus one denotes absence
     * @return validated count
     * @throws IOException when input is truncated
     */
    public static int count(DataInputStream in, boolean nullable) throws IOException {
        int size = in.readInt();
        if (size < (nullable ? -1 : 0) || size > in.available()) throw invalid("Invalid exact collection length");
        return size;
    }
    /**
     * Reads one canonical Boolean byte.
     * @param in envelope input
     * @return Boolean value
     * @throws IOException when input is truncated
     */
    public static boolean readBoolean(DataInputStream in) throws IOException {
        int value = in.readUnsignedByte(); if (value > 1) throw invalid("Invalid exact Boolean"); return value == 1;
    }
    /**
     * Writes a length-prefixed byte sequence.
     * @param out envelope output
     * @param bytes required bytes
     * @throws IOException when output fails
     */
    public static void writeBytes(DataOutputStream out, byte[] bytes) throws IOException {
        out.writeInt(bytes.length); out.write(bytes);
    }
    /**
     * Reads a byte sequence bounded by the remaining envelope before allocation.
     * @param in envelope input
     * @return detached bytes
     * @throws IOException when input is truncated
     */
    public static byte[] readBytes(DataInputStream in) throws IOException {
        byte[] bytes = new byte[count(in, false)]; in.readFully(bytes); return bytes;
    }
    /**
     * Creates a physical storage validation failure.
     * @param message failure description
     * @return failure to abort the enclosing storage operation
     */
    public static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
    private static IllegalArgumentException invalid(String message, Throwable failure) {
        return new IllegalArgumentException(message, failure);
    }
    private static byte[] digest(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static String arrayType(Class<?> type) {
        if (type.isArray()) return "[" + arrayType(type.getComponentType());
        String name = type.getName(); arrayType(name); return name;
    }
    private static Class<?> arrayType(String name) {
        int dimensions = 0;
        while (dimensions < name.length() && name.charAt(dimensions) == '[') dimensions++;
        if (dimensions > 254) throw invalid("Raw array exceeds JVM dimension limit");
        Class<?> base;
        switch (name.substring(dimensions)) {
            case "byte": base = byte.class; break; case "short": base = short.class; break;
            case "int": base = int.class; break; case "long": base = long.class; break;
            case "float": base = float.class; break; case "double": base = double.class; break;
            case "boolean": base = boolean.class; break; case "char": base = char.class; break;
            case "java.lang.Object": base = Object.class; break; case "java.lang.String": base = String.class; break;
            case "java.lang.Byte": base = Byte.class; break; case "java.lang.Short": base = Short.class; break;
            case "java.lang.Integer": base = Integer.class; break; case "java.lang.Long": base = Long.class; break;
            case "java.lang.Float": base = Float.class; break; case "java.lang.Double": base = Double.class; break;
            case "java.lang.Boolean": base = Boolean.class; break; case "java.lang.Character": base = Character.class; break;
            case "java.math.BigInteger": base = BigInteger.class; break;
            case "java.math.BigDecimal": base = BigDecimal.class; break;
            default: throw invalid("Unsupported raw array component: " + name);
        }
        return dimensions == 0 ? base : Array.newInstance(base, new int[dimensions]).getClass();
    }
    /** Encodes fields inside a bounded checksummed envelope. */
    @FunctionalInterface public interface Encoder {
        /**
         * Writes fields.
         * @param out bounded output
         * @throws IOException when output fails
         */
        void write(DataOutputStream out) throws IOException;
    }
    /**
     * Decodes fields inside a verified checksummed envelope.
     * @param <T> decoded value type
     */
    @FunctionalInterface public interface Decoder<T> {
        /**
         * Reads fields.
         * @param in bounded input
         * @return decoded value
         * @throws IOException when input is truncated
         */
        T read(DataInputStream in) throws IOException;
    }
    private static final class LimitedBuffer extends ByteArrayOutputStream {
        private final int maximum;
        private LimitedBuffer(int maximum) { this.maximum = maximum; }
        private void check(int length) { if (length > maximum - count) throw invalid("Exact storage byte bound exceeded"); }
        @Override public synchronized void write(int value) { check(1); super.write(value); }
        @Override public synchronized void write(byte[] value, int offset, int length) {
            check(length); super.write(value, offset, length);
        }
    }
}
