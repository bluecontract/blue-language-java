package blue.language.identity;

import blue.language.codec.jackson.UncheckedObjectMapper;
import blue.language.model.Node;
import blue.language.model.NodeIdentities;

/**
 * Canonical identity of a scalar Blue node.
 *
 * <p>Scalar equality is defined by the effective scalar type and canonical
 * scalar value. Declaration metadata such as {@code name},
 * {@code description}, and {@code schema} is deliberately excluded.</p>
 */
public final class ScalarNodeIdentity {

    private ScalarNodeIdentity() {
    }

    /**
     * Builds the minimal canonical node used for scalar identity.
     *
     * @param node scalar node to normalize
     * @return new node containing only the scalar value and effective type
     */
    public static Node normalized(Node node) {
        if (node == null || node.getValue() == null) {
            throw new IllegalArgumentException(
                    "Scalar identity requires a scalar value.");
        }

        Node normalized = new Node().value(node.getValue());
        Node type = node.getType();
        if (type != null) {
            String typeBlueId = type.getBlueId() != null
                    ? type.getBlueId()
                    : NodeIdentities.calculate(type);
            normalized.type(new Node().blueId(typeBlueId));
        }
        return normalized;
    }

    /**
     * Calculates the canonical BlueId of a scalar node.
     *
     * @param node scalar node to identify
     * @return canonical scalar BlueId
     */
    public static String blueId(Node node) {
        return NodeIdentities.calculate(normalized(node));
    }

    /**
     * Serializes the canonical scalar identity input as JSON.
     *
     * @param node scalar node to serialize
     * @return canonical scalar identity JSON
     */
    public static String canonicalJson(Node node) {
        return UncheckedObjectMapper.JSON_MAPPER.writeValueAsString(
                NodeToBlueIdInput.get(normalized(node)));
    }
}
