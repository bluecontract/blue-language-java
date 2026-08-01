package blue.language.utils;

import blue.language.model.Node;
import org.erdtman.jcs.JsonCanonicalizer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Canonicalizes schema enum values for Source Document identity.
 *
 * <p>Schema enums are sets: declaration order and duplicate spellings do not
 * contribute to a BlueId. Values are reduced to scalar identity, ordered by
 * unsigned lexicographic RFC 8785 bytes, and deduplicated without mutating the
 * authored schema.</p>
 */
public final class SchemaEnumCanonicalizer {

    private SchemaEnumCanonicalizer() {
    }

    /**
     * Returns normalized enum values in canonical identity order.
     *
     * @param values authored enum values
     * @return independent normalized values, sorted and deduplicated
     */
    public static List<Node> canonicalize(List<Node> values) {
        if (values == null) {
            throw new IllegalArgumentException("Schema enum values must not be null.");
        }
        List<CanonicalValue> canonical = new ArrayList<>(values.size());
        for (Node value : values) {
            Node normalized = normalized(value);
            canonical.add(new CanonicalValue(canonicalBytes(normalized), normalized));
        }
        canonical.sort(Comparator.comparing(
                CanonicalValue::bytes,
                SchemaEnumCanonicalizer::compareUnsigned));

        List<Node> result = new ArrayList<>(canonical.size());
        byte[] previous = null;
        for (CanonicalValue value : canonical) {
            if (previous == null || !Arrays.equals(previous, value.bytes())) {
                result.add(value.node());
                previous = value.bytes();
            }
        }
        return result;
    }

    /**
     * Returns the collision-free canonical identity key used for enum set
     * membership.
     *
     * <p>This string is for equality only. Ordering always compares the
     * underlying unsigned UTF-8 bytes.</p>
     *
     * @param value enum scalar
     * @return RFC 8785 canonical JSON for the typed scalar identity
     */
    public static String canonicalKey(Node value) {
        return new String(
                canonicalBytes(normalized(value)),
                StandardCharsets.UTF_8);
    }

    private static Node normalized(Node value) {
        requireScalarIdentityShape(value);
        if (value.isReferenceOnly()) {
            return new Node().blueId(value.getBlueId());
        }
        return ScalarNodeIdentity.normalized(value);
    }

    private static void requireScalarIdentityShape(Node value) {
        if (value != null && value.isReferenceOnly()) {
            return;
        }
        if (value == null
                || value.getValue() == null
                || value.getName() != null
                || value.getDescription() != null
                || value.getItemType() != null
                || value.getKeyType() != null
                || value.getValueType() != null
                || value.getItems() != null
                || value.getProperties() != null
                || value.getContracts() != null
                || value.getBlueId() != null
                || value.getSchema() != null
                || value.getMergePolicy() != null
                || value.getPreviousBlueId() != null
                || value.getPosition() != null
                || value.getBlue() != null) {
            throw new IllegalArgumentException(
                    "Schema enum entries must be scalar values, explicit "
                            + "type/value scalar nodes, or pure references.");
        }
    }

    private static byte[] canonicalBytes(Node value) {
        try {
            Object identityInput = NodeToBlueIdInput.get(value);
            String json = UncheckedObjectMapper.JSON_MAPPER.writeValueAsString(identityInput);
            return new JsonCanonicalizer(json).getEncodedUTF8();
        } catch (IOException exception) {
            throw new IllegalArgumentException(
                    "Schema enum value cannot be represented as canonical JSON.",
                    exception);
        }
    }

    private static int compareUnsigned(byte[] left, byte[] right) {
        int commonLength = Math.min(left.length, right.length);
        for (int index = 0; index < commonLength; index++) {
            int comparison = Integer.compare(
                    left[index] & 0xff,
                    right[index] & 0xff);
            if (comparison != 0) {
                return comparison;
            }
        }
        return Integer.compare(left.length, right.length);
    }

    private static final class CanonicalValue {
        private final byte[] bytes;
        private final Node node;

        private CanonicalValue(byte[] bytes, Node node) {
            this.bytes = bytes;
            this.node = node;
        }

        private byte[] bytes() {
            return bytes;
        }

        private Node node() {
            return node;
        }
    }
}
