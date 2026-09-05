package blue.language.merge.processor;

import blue.language.identity.*;

import blue.language.model.Node;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import static blue.language.model.wire.BlueLanguageConstants.*;

/**
 * Scalar enum membership, separate from exact scalar identity.
 *
 * <p>A scalar entry restricts the canonical payload and its declared scalar
 * domain. An absent type denotes the payload's core scalar type. A candidate
 * may specialize that domain; an opaque value reference instead denotes one
 * exact scalar identity. This operation never changes either input and never
 * fetches content. Completed custom ancestry needs resolver-issued evidence.</p>
 */
final class EnumConstraintMembership {

    private EnumConstraintMembership() {
    }

    /**
     * Tests a completed scalar against one enum restriction.
     *
     * @param candidate completed scalar value
     * @param entry scalar-domain restriction or exact value reference
     * @param identities resolver-issued identities for materialized types
     * @return whether the candidate satisfies the restriction
     * @throws IllegalStateException when required type ancestry is unavailable
     */
    static boolean matches(
            Node candidate, Node entry, CanonicalTypeIdentityLookup identities) {
        if (candidate == null || candidate.getValue() == null) {
            return false;
        }
        if (entry.isReferenceOnly()) {
            BlueIdReferenceValidator.validate(entry);
            return entry.getBlueId().equals(
                    ScalarNodeIdentity.resolvedBlueId(candidate, identities));
        }
        if (ScalarNodeIdentity.resolvedBlueId(candidate, identities).equals(
                ScalarNodeIdentity.resolvedBlueId(entry, identities))) {
            return true;
        }
        String required = domainBlueId(entry, identities);
        Node type = candidate.getType();
        if (type == null) {
            String primitive = primitiveBlueId(candidate.getValue());
            return required.equals(primitive)
                    && samePayloadInDomain(candidate, entry, primitive);
        }
        boolean domainMatched = false;
        Set<Node> visited = Collections.newSetFromMap(
                new IdentityHashMap<Node, Boolean>());
        while (type != null && visited.add(type)) {
            String actual = typeBlueId(type, identities);
            if (required.equals(actual)) {
                domainMatched = true;
            }
            if (CORE_TYPE_BLUE_ID_TO_NAME_MAP.containsKey(actual)) {
                return domainMatched && BASIC_TYPE_BLUE_IDS.contains(actual)
                        && samePayloadInDomain(candidate, entry, actual);
            }
            if (type.isReferenceOnly()) {
                throw new IllegalStateException(
                        "Enum membership requires completed scalar type ancestry.");
            }
            type = type.getType();
        }
        return false;
    }

    /**
     * Compares canonical primitive payloads without changing declared types.
     * Integer, Double, Text and Boolean remain distinct domains.
     *
     * @param left first scalar
     * @param right second scalar
     * @return whether primitive kinds and canonical payloads agree
     */
    static boolean samePayload(Node left, Node right) {
        if (left.getValue() == null || right.getValue() == null) {
            return false;
        }
        return SchemaEnumCanonicalizer.canonicalKey(
                new Node().value(left.getValue())).equals(
                SchemaEnumCanonicalizer.canonicalKey(
                        new Node().value(right.getValue())));
    }

    private static boolean samePayloadInDomain(Node left, Node right, String domain) {
        Node leftPrimitive = new Node().type(new Node().blueId(domain))
                .value(left.getRawValue());
        Node rightPrimitive = new Node().type(new Node().blueId(domain))
                .value(right.getRawValue());
        // Normalize canonical numeric/Boolean spellings only after their
        // primitive domain has been proved. A quoted bare Text remains Text.
        if (!domain.equals(primitiveBlueId(leftPrimitive.getValue()))
                || !domain.equals(primitiveBlueId(rightPrimitive.getValue()))) {
            return false;
        }
        return SchemaEnumCanonicalizer.canonicalKey(leftPrimitive).equals(
                SchemaEnumCanonicalizer.canonicalKey(rightPrimitive));
    }

    private static String domainBlueId(
            Node node, CanonicalTypeIdentityLookup identities) {
        Node type = node.getType();
        return type == null ? primitiveBlueId(node.getValue())
                : typeBlueId(type, identities);
    }

    private static String typeBlueId(
            Node type, CanonicalTypeIdentityLookup identities) {
        if (type.isReferenceOnly()) {
            BlueIdReferenceValidator.validate(type);
            return type.getBlueId();
        }
        return identities.requireCanonicalTypeBlueId(type);
    }

    private static String primitiveBlueId(Object value) {
        if (value instanceof String) {
            return TEXT_TYPE_BLUE_ID;
        }
        if (value instanceof BigInteger) {
            return INTEGER_TYPE_BLUE_ID;
        }
        if (value instanceof BigDecimal) {
            return DOUBLE_TYPE_BLUE_ID;
        }
        if (value instanceof Boolean) {
            return BOOLEAN_TYPE_BLUE_ID;
        }
        throw new IllegalArgumentException("Enum membership requires a scalar payload.");
    }
}
