package blue.language.processor.util;

import blue.language.model.Node;
import blue.language.snapshot.FrozenCanonicalWriter;
import blue.language.snapshot.FrozenNode;
import blue.language.identity.Base58Sha256Provider;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.identity.NodeToBlueIdInput;
import blue.language.model.NodeWireForm;
import blue.language.codec.jackson.UncheckedObjectMapper;
import org.erdtman.jcs.JsonCanonicalizer;

/**
 * Utility for producing canonical JSON sizes used in gas accounting.
 */
public final class NodeCanonicalizer {

    private NodeCanonicalizer() {
    }

    /**
     * Returns the JCS byte length of a mutable node's authored wire value.
     *
     * @param node authored node, or {@code null}
     * @return zero when {@code node} is {@code null}
     */
    public static long canonicalSize(Node node) {
        if (node == null) {
            return 0L;
        }
        return canonicalSize(NodeWireForm.get(node));
    }

    /**
     * Calculates exact authored canonical size without materializing a mutable node.
     *
     * @param node strict canonical frozen node, or {@code null}
     * @return canonical authored byte length
     * @throws IllegalArgumentException when the frozen value is a resolved view
     */
    public static long canonicalFrozenSize(FrozenNode node) {
        if (node == null) {
            return 0L;
        }
        if (!node.isStrictCanonical()) {
            throw new IllegalArgumentException("Gas accounting requires an authored canonical frozen value");
        }
        return FrozenCanonicalWriter.officialCanonicalSize(node);
    }

    /**
     * Returns the exact canonical byte size of this node's direct BlueId
     * helper map. Child content is represented by its bounded BlueId.
     *
     * @param node source node, or {@code null}
     * @return direct identity-input byte length, or zero for a reference
     */
    public static long directIdentityCanonicalSize(Node node) {
        if (node == null || node.isReferenceOnly()) {
            return 0L;
        }
        return directIdentityCanonicalSize(
                NodeToBlueIdInput.get(node));
    }

    /**
     * Returns the exact direct helper-map byte size while admitting only the
     * invocation-local placeholder syntax used by Language cyclic-set
     * finalization.
     *
     * @param node cyclic finalization input, or {@code null}
     * @return direct identity-input byte length, or zero for a reference
     */
    public static long directIdentityCanonicalSizeAllowingCyclicPlaceholders(
            Node node) {
        if (node == null || node.isReferenceOnly()) {
            return 0L;
        }
        return directIdentityCanonicalSize(
                NodeToBlueIdInput.getAllowingCyclicPlaceholders(node));
    }

    private static long directIdentityCanonicalSize(Object input) {
        final long[] directBytes = {0L};
        final Base58Sha256Provider hash = new Base58Sha256Provider();
        DirectBlueIdCalculator calculator = new DirectBlueIdCalculator(value -> {
            directBytes[0] = canonicalSize(value);
            return hash.apply(value);
        });
        calculator.directBlueIdFromCanonicalInput(
                input);
        return directBytes[0];
    }

    private static long canonicalSize(Object canonical) {
        try {
            if (FrozenCanonicalWriter.supportsCanonicalValue(canonical)) {
                return FrozenCanonicalWriter.canonicalValueBytes(canonical).length;
            }
            byte[] json =
                    UncheckedObjectMapper.JSON_MAPPER
                            .writeValueAsBytes(canonical);
            if (canonical instanceof String
                    || canonical instanceof Number
                    || canonical instanceof Boolean
                    || canonical == null) {
                byte[] wrapped = new byte[json.length + 2];
                wrapped[0] = '[';
                System.arraycopy(
                        json, 0, wrapped, 1, json.length);
                wrapped[wrapped.length - 1] = ']';
                return new JsonCanonicalizer(wrapped)
                        .getEncodedUTF8().length - 2L;
            }
            return new JsonCanonicalizer(json)
                    .getEncodedUTF8().length;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to canonicalize node", ex);
        }
    }
}
