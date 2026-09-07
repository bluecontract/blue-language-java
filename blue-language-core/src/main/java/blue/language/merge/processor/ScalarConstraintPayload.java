package blue.language.merge.processor;

import blue.language.identity.*;

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
final class ScalarConstraintPayload {

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
    static Number numericValue(
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
    static boolean isMultipleOf(Number value, Number divisor) {
        BigDecimal exactDivisor = exact(divisor);
        if (exactDivisor.signum() <= 0) {
            throw new IllegalArgumentException("multipleOf must be greater than zero");
        }
        return exact(value).remainder(exactDivisor).signum() == 0;
    }

    // BigDecimal payloads are canonical binary64 views. Its shortest decimal
    // spelling is not the numeric value used by schema arithmetic.
    static BigDecimal exact(Number value) {
        if (value instanceof BigInteger) {
            return new BigDecimal((BigInteger) value);
        }
        if (value instanceof BigDecimal) {
            return new BigDecimal(value.doubleValue());
        }
        throw wrongKind();
    }

    static int compare(Node left, Node right, CanonicalTypeIdentityLookup identities) {
        return exact(numericValue(left, identities))
                .compareTo(exact(numericValue(right, identities)));
    }

    static Number leastCommonMultiple(Number left, Number right) {
        Fraction a = new Fraction(exact(left));
        Fraction b = new Fraction(exact(right));
        if (a.numerator.signum() <= 0 || b.numerator.signum() <= 0) {
            throw new IllegalArgumentException("multipleOf must be greater than zero");
        }
        BigInteger numerator = a.numerator.divide(a.numerator.gcd(b.numerator))
                .multiply(b.numerator);
        BigInteger denominator = a.denominator.gcd(b.denominator);
        BigInteger divisor = numerator.gcd(denominator);
        numerator = numerator.divide(divisor);
        denominator = denominator.divide(divisor);
        if (left instanceof BigInteger && right instanceof BigInteger) {
            return numerator;
        }
        BigDecimal rational = new BigDecimal(numerator).divide(new BigDecimal(denominator));
        double candidate = rational.doubleValue();
        if (Double.isFinite(candidate) && new BigDecimal(candidate).compareTo(rational) == 0) {
            return BigDecimal.valueOf(candidate);
        }
        // An unrepresentable dyadic LCM has no nonzero binary64 multiples.
        // Its Integer members are precisely the multiples of the reduced
        // numerator. This is equivalent over Integer and finite Double values.
        return numerator;
    }

    private static final class Fraction {
        private final BigInteger numerator;
        private final BigInteger denominator;

        private Fraction(BigDecimal value) {
            BigInteger unscaled = value.unscaledValue();
            BigInteger scale = BigInteger.TEN.pow(Math.max(0, value.scale()));
            if (value.scale() < 0) {
                unscaled = unscaled.multiply(BigInteger.TEN.pow(-value.scale()));
            }
            BigInteger gcd = unscaled.gcd(scale);
            numerator = unscaled.divide(gcd);
            denominator = scale.divide(gcd);
        }
    }

    private static IllegalArgumentException wrongKind() {
        return new IllegalArgumentException(
                "Numeric schema keyword applies to wrong kind; expected numeric scalar.");
    }
}
