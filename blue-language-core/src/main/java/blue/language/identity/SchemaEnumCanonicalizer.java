package blue.language.identity;

import blue.language.codec.jackson.UncheckedObjectMapper;
import blue.language.model.Node;
import blue.language.model.Nodes;
import org.erdtman.jcs.JsonCanonicalizer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

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
     * <p>This evidence-free form accepts untyped scalars, scalars whose type
     * is a pure reference, and pure value references. Inline scalar types
     * must use {@link #canonicalizeResolved(List,
     * CanonicalTypeIdentityLookup)} after resolver completion.</p>
     *
     * @param values evidence-free enum values
     * @return independent normalized values, sorted and deduplicated
     */
    public static List<Node> canonicalize(List<Node> values) {
        return canonicalize(values, SchemaEnumCanonicalizer::normalized);
    }

    /**
     * Returns completed enum values in canonical identity order.
     *
     * <p>Expanded effective types are reduced through resolver-issued
     * identities. This method never hashes a completed type body.</p>
     *
     * @param values completed enum values
     * @param typeIdentities resolver-issued effective type identities
     * @return independent normalized values, sorted and deduplicated
     * @throws NullPointerException if {@code typeIdentities} is null
     * @throws IllegalArgumentException if {@code values} is null or contains
     *         an entry that is not valid scalar identity input
     * @throws IllegalStateException if required canonical type evidence is
     *         unavailable
     */
    public static List<Node> canonicalizeResolved(
            List<Node> values,
            CanonicalTypeIdentityLookup typeIdentities) {
        Objects.requireNonNull(typeIdentities, "typeIdentities");
        return canonicalize(
                values,
                value -> normalizedResolved(value, typeIdentities));
    }

    private static List<Node> canonicalize(
            List<Node> values,
            Function<Node, Node> normalizer) {
        if (values == null) {
            throw new IllegalArgumentException("Schema enum values must not be null.");
        }
        List<CanonicalValue> canonical = new ArrayList<>(values.size());
        for (Node value : values) {
            Node normalized = normalizer.apply(value);
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
     * Returns the collision-free canonical identity key used to normalize
     * enum declarations. Constraint membership is evaluated separately by
     * schema constraint membership; subtype overlap does not erase an
     * explicitly declared custom type from source identity.
     *
     * <p>This string is for equality only. Ordering always compares the
     * underlying unsigned UTF-8 bytes. Inline scalar types require
     * {@link #canonicalKeyResolved(Node,
     * CanonicalTypeIdentityLookup)}.</p>
     *
     * @param value enum scalar
     * @return RFC 8785 canonical JSON for the typed scalar identity
     */
    public static String canonicalKey(Node value) {
        return new String(
                canonicalBytes(normalized(value)),
                StandardCharsets.UTF_8);
    }

    /**
     * Returns the collision-free canonical identity key for a completed enum
     * scalar.
     *
     * @param value completed enum scalar
     * @param typeIdentities resolver-issued effective type identities
     * @return RFC 8785 canonical JSON for the typed scalar identity
     */
    static String canonicalKeyResolved(
            Node value,
            CanonicalTypeIdentityLookup typeIdentities) {
        Objects.requireNonNull(typeIdentities, "typeIdentities");
        return new String(
                canonicalBytes(
                        normalizedResolved(value, typeIdentities)),
                StandardCharsets.UTF_8);
    }

    private static Node normalized(Node value) {
        requireScalarIdentityShape(value);
        if (value.isReferenceOnly()) {
            return new Node().blueId(value.getBlueId());
        }
        return ScalarNodeIdentity.normalized(value);
    }

    private static Node normalizedResolved(
            Node value,
            CanonicalTypeIdentityLookup typeIdentities) {
        requireScalarIdentityShape(value);
        if (value.isReferenceOnly()) {
            return new Node().blueId(value.getBlueId());
        }
        return ScalarNodeIdentity.normalizedResolved(
                value, typeIdentities);
    }

    private static void requireScalarIdentityShape(Node value) {
        if (!Nodes.isSchemaEnumValue(value)) {
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
