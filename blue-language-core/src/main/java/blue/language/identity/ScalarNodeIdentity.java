package blue.language.identity;

import blue.language.codec.jackson.UncheckedObjectMapper;
import blue.language.model.Node;
import blue.language.model.NodeIdentities;

import java.util.Objects;

/**
 * Canonical identity of a scalar Blue node.
 *
 * <p>Scalar equality is defined by the effective scalar type and canonical
 * scalar value. Declaration metadata such as {@code name},
 * {@code description}, and {@code schema} is deliberately excluded. Exact
 * authored input and completed resolver output are separate trust domains:
 * inline types require resolver-issued identity evidence in both cases.</p>
 */
public final class ScalarNodeIdentity {

    private ScalarNodeIdentity() {
    }

    /**
     * Builds the minimal canonical node used for evidence-free scalar
     * identity.
     *
     * <p>This evidence-free form accepts only an absent type or a pure type
     * reference. Inline type syntax must first be completed by a resolver and
     * passed to {@link #normalizedResolved(Node,
     * CanonicalTypeIdentityLookup)}. Recursively hashing inline type syntax
     * would make nested inline ancestry representation-dependent.</p>
     *
     * @param node untyped or pure-reference-typed scalar node to normalize
     * @return new node containing only the scalar value and effective type
     */
    public static Node normalized(Node node) {
        requireScalarValue(node);

        Node normalized = new Node().value(node.getValue());
        Node type = node.getType();
        if (type != null) {
            if (!type.isReferenceOnly()) {
                throw new IllegalStateException(
                        "Resolver-issued canonical type identity evidence is "
                                + "required for an inline scalar type");
            }
            normalized.type(new Node().blueId(type.getBlueId()));
        }
        return normalized;
    }

    /**
     * Builds the minimal canonical node used for a completed scalar's
     * effective identity.
     *
     * <p>Pure type references retain their declared BlueId. Every expanded
     * or inline completed type requires resolver-issued evidence, including a
     * materialized type that happens to carry a BlueId. Falling back to a raw
     * hash of the completed body would make identity depend on whether the
     * type was authored inline or by reference.</p>
     *
     * @param node completed scalar node to normalize
     * @param typeIdentities resolver-issued effective type identities
     * @return new node containing only the scalar value and canonical type
     * @throws IllegalArgumentException if {@code node} is null or has no
     *         scalar value
     * @throws NullPointerException if a materialized effective type is present
     *         and {@code typeIdentities} is null
     * @throws IllegalStateException if required canonical type evidence is
     *         unavailable
     */
    public static Node normalizedResolved(
            Node node,
            CanonicalTypeIdentityLookup typeIdentities) {
        requireScalarValue(node);

        Node normalized = new Node().value(node.getValue());
        Node type = node.getType();
        if (type == null) {
            return normalized;
        }

        String typeBlueId;
        if (type.isReferenceOnly()) {
            typeBlueId = type.getBlueId();
        } else {
            typeBlueId = Objects.requireNonNull(
                    typeIdentities,
                    "typeIdentities")
                    .requireCanonicalTypeBlueId(type);
        }
        return normalized.type(new Node().blueId(typeBlueId));
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
     * Calculates the canonical identity of a completed scalar.
     *
     * @param node completed scalar node
     * @param typeIdentities resolver-issued effective type identities
     * @return canonical scalar BlueId
     * @throws IllegalArgumentException if {@code node} is null or has no
     *         scalar value
     * @throws NullPointerException if a materialized effective type is present
     *         and {@code typeIdentities} is null
     * @throws IllegalStateException if required canonical type evidence is
     *         unavailable
     */
    public static String resolvedBlueId(
            Node node,
            CanonicalTypeIdentityLookup typeIdentities) {
        return NodeIdentities.calculate(
                normalizedResolved(node, typeIdentities));
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

    private static void requireScalarValue(Node node) {
        if (node == null || node.getValue() == null) {
            throw new IllegalArgumentException(
                    "Scalar identity requires a scalar value.");
        }
    }
}
