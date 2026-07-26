package blue.language.processor.util;

import blue.language.model.Node;
import blue.language.snapshot.FrozenCanonicalWriter;
import blue.language.snapshot.FrozenNode;
import blue.language.utils.Base58Sha256Provider;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.NodeToBlueIdInput;
import blue.language.utils.NodeToMapListOrValue;
import blue.language.utils.UncheckedObjectMapper;
import org.erdtman.jcs.JsonCanonicalizer;

/**
 * Utility for producing canonical JSON sizes used in gas accounting.
 */
public final class NodeCanonicalizer {

    private NodeCanonicalizer() {
    }

    public static long canonicalSize(Node node) {
        if (node == null) {
            return 0L;
        }
        return canonicalSize(NodeToMapListOrValue.get(node));
    }

    /** Calculates the exact authored canonical size without materializing a mutable node. */
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
     */
    public static long directIdentityCanonicalSize(Node node) {
        if (node == null || node.isReferenceOnly()) {
            return 0L;
        }
        final long[] directBytes = {0L};
        final Base58Sha256Provider hash = new Base58Sha256Provider();
        BlueIdCalculator calculator = new BlueIdCalculator(value -> {
            directBytes[0] = canonicalSize(value);
            return hash.apply(value);
        });
        calculator.calculate(NodeToBlueIdInput.get(node));
        return directBytes[0];
    }

    private static long canonicalSize(Object canonical) {
        try {
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
