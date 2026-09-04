package blue.language.identity;

import blue.language.model.Node;
import blue.language.model.value.BlueNumbers;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import static blue.language.model.wire.BlueLanguageConstants.CORE_TYPE_BLUE_IDS;
import static blue.language.model.wire.BlueLanguageConstants.DOUBLE_TYPE_BLUE_ID;
import static blue.language.model.wire.BlueLanguageConstants.INTEGER_TYPE_BLUE_ID;

/**
 * Primitive numeric views for constraints, separate from exact scalar identity.
 * These operations never fetch content or change a node's type or raw payload.
 */
public final class ScalarConstraintPayload {

    private ScalarConstraintPayload() {
    }

    /**
     * Interprets canonical numeric spellings in a proven primitive domain.
     *
     * @param node scalar node to inspect
     * @param typeIdentities resolver-issued identities for materialized types
     * @return the exact Integer or canonical Double payload
     * @throws IllegalArgumentException when the payload has no numeric kind
     * @throws IllegalStateException when required type ancestry is unavailable
     */
    public static Number numericValue(
            Node node, CanonicalTypeIdentityLookup typeIdentities) {
        Object value = node.getValue();
        Node type = node.getType();
        Set<Node> visited = Collections.newSetFromMap(new IdentityHashMap<Node, Boolean>());
        while (type != null && visited.add(type)) {
            String typeId = type.isReferenceOnly() ? type.getBlueId()
                    : typeIdentities.requireCanonicalTypeBlueId(type);
            if (INTEGER_TYPE_BLUE_ID.equals(typeId) || DOUBLE_TYPE_BLUE_ID.equals(typeId)) {
                // Ancestry is established before interpreting quoted numeric
                // text; bare Text is never promoted to a numeric domain.
                value = new Node().type(new Node().blueId(typeId))
                        .value(node.getRawValue()).getValue();
                break;
            }
            if (CORE_TYPE_BLUE_IDS.contains(typeId)) {
                throw wrongKind();
            }
            if (type.isReferenceOnly()) {
                throw new IllegalStateException(
                        "Numeric schema validation requires completed type ancestry.");
            }
            type = type.getType();
        }
        if (!(value instanceof BigInteger) && !(value instanceof BigDecimal)) {
            throw wrongKind();
        }
        if (node.getType() == null && value instanceof BigDecimal) {
            return BlueNumbers.toCanonicalDoubleValue(value);
        }
        return (Number) value;
    }

    /**
     * Evaluates numeric divisibility without converting arbitrary Integers to
     * binary64. Double constraints retain exact binary64 rational semantics.
     *
     * @param value canonical numeric candidate from
     *              {@link #numericValue(Node, CanonicalTypeIdentityLookup)}
     * @param divisor canonical numeric constraint from
     *                {@link #numericValue(Node, CanonicalTypeIdentityLookup)}
     * @return whether the positive divisor divides the candidate exactly
     * @throws IllegalArgumentException when an argument is not a canonical
     *         numeric kind or the divisor is nonpositive
     */
    public static boolean isMultipleOf(Number value, Number divisor) {
        if (!(value instanceof BigInteger) && !(value instanceof BigDecimal)
                || !(divisor instanceof BigInteger) && !(divisor instanceof BigDecimal)) {
            throw wrongKind();
        }
        BigDecimal decimalDivisor = divisor instanceof BigInteger
                ? new BigDecimal((BigInteger) divisor)
                : (BigDecimal) divisor;
        if (decimalDivisor.signum() <= 0) {
            throw new IllegalArgumentException("multipleOf must be greater than zero");
        }
        if (value instanceof BigInteger) {
            BigDecimal exactDivisor = divisor instanceof BigInteger
                    ? decimalDivisor : new BigDecimal(divisor.doubleValue());
            return new BigDecimal((BigInteger) value)
                    .remainder(exactDivisor).signum() == 0;
        }
        return BlueNumbers.isExactBinary64Multiple(value, decimalDivisor);
    }

    private static IllegalArgumentException wrongKind() {
        return new IllegalArgumentException(
                "Numeric schema keyword applies to wrong kind; expected numeric scalar.");
    }
}
