package blue.language.processor;

import java.math.BigDecimal;
import java.math.BigInteger;

/** Contracts-owned projection of implicit Language identity members. */
final class CanonicalIdentityGasProjection {

    private CanonicalIdentityGasProjection() {
    }

    /**
     * Counts the implicit {@code type} helper-map member for an untyped
     * scalar value. Language identity inference makes that member identical
     * to the corresponding explicitly typed representation.
     */
    static long inferredScalarTypeMember(
            Object value,
            boolean explicitTypePresent) {
        if (value == null || explicitTypePresent) {
            return 0L;
        }
        return value instanceof String
                || value instanceof BigInteger
                || value instanceof BigDecimal
                || value instanceof Boolean
                ? 1L : 0L;
    }
}
