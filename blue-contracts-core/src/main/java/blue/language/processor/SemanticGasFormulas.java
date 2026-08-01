package blue.language.processor;

import java.math.BigInteger;
import java.util.Objects;

/**
 * Overflow-safe arithmetic shared by the semantic gas formulas.
 *
 * <p>Keeping these pure calculations separate makes it explicit that they do
 * not admit gas or mutate invocation-local memoization.</p>
 */
final class SemanticGasFormulas {

    private SemanticGasFormulas() {
    }

    static long blocks(GasSchedule schedule, long codePoints) {
        requireNonNegative(codePoints, "codePointCount");
        return ceilingDivide(codePoints,
                Objects.requireNonNull(schedule, "schedule")
                        .formulaParameter(
                                GasScheduleConstants.FormulaParameter
                                        .TEXT_BLOCK_CODE_POINTS));
    }

    static long limbs(GasSchedule schedule, BigInteger magnitude) {
        GasSchedule exactSchedule = Objects.requireNonNull(schedule, "schedule");
        int bits = Objects.requireNonNull(magnitude, "magnitude")
                .abs()
                .bitLength();
        long radixBits = exactSchedule.formulaParameter(
                GasScheduleConstants.FormulaParameter.INTEGER_RADIX_BITS);
        return Math.max(
                exactSchedule.formulaParameter(
                        GasScheduleConstants.FormulaParameter
                                .INTEGER_MINIMUM_LIMBS),
                ceilingDivide(bits, radixBits));
    }

    static long ceilingDivide(long value, long divisor) {
        if (value == 0L) {
            return 0L;
        }
        return 1L + ((value - 1L) / divisor);
    }

    static long multiply(long left, long right, String label) {
        if (left != 0L && right > Long.MAX_VALUE / left) {
            throw new IllegalArgumentException(label + " exceeds long range");
        }
        return left * right;
    }

    static long checkedAdd(long left, long right, String label) {
        if (right > Long.MAX_VALUE - left) {
            throw new IllegalArgumentException(label + " exceeds long range");
        }
        return left + right;
    }

    static void requireIndex(long index,
                             long resultLength,
                             boolean insertion) {
        requireNonNegative(resultLength, "resultLength");
        requireNonNegative(index, "index");
        long upper = insertion ? resultLength : resultLength - 1L;
        if (resultLength == 0L || index > upper) {
            throw new IllegalArgumentException("index is outside result list");
        }
    }

    static void requirePositive(long value, String label) {
        if (value <= 0L) {
            throw new IllegalArgumentException(label + " must be positive");
        }
    }

    static void requireNonNegative(long value, String label) {
        if (value < 0L) {
            throw new IllegalArgumentException(label + " must be non-negative");
        }
    }

    static void requireKey(String value, String label) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(label + " must be non-empty");
        }
    }
}
