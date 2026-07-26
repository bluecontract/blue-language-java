package blue.language.processor;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Canonical Contracts 1.0 semantic-work formulas backed by one invocation's
 * shared {@link GasMeter}.
 *
 * <p>This object owns the run-local manifest and validation-proof memoization
 * required by §§13.7 and 13.11.  It never consults cross-invocation caches.</p>
 */
public final class SemanticGasMeter {

    private final GasMeter meter;
    private final Set<String> openedNodeManifests = new LinkedHashSet<>();
    private final Set<String> validationProofs = new LinkedHashSet<>();

    SemanticGasMeter(GasMeter meter) {
        this.meter = Objects.requireNonNull(meter, "meter");
    }

    /**
     * Charges the first semantic opening of an exact node manifest in this
     * invocation.  Returns {@code true} exactly for that first opening.
     */
    public boolean openNodeManifest(String nodeBlueId) {
        return openNodeManifest(nodeBlueId, GasChargeContext.empty());
    }

    public boolean openNodeManifest(String nodeBlueId, GasChargeContext context) {
        requireKey(nodeBlueId, "nodeBlueId");
        if (!openedNodeManifests.add(nodeBlueId)) {
            return false;
        }
        charge("nodeManifestOpened", 1L, context);
        return true;
    }

    public void objectMembersRead(long quantity, GasChargeContext context) {
        charge("objectMemberRead", quantity, context);
    }

    public void listItemsRead(long quantity, GasChargeContext context) {
        charge("listItemRead", quantity, context);
    }

    public void textCodePointsExamined(long codePointCount,
                                       GasChargeContext context) {
        charge("textBlockExamined", blocks(codePointCount), context);
    }

    public void textExamined(String text, GasChargeContext context) {
        Objects.requireNonNull(text, "text");
        textCodePointsExamined(text.codePointCount(0, text.length()), context);
    }

    public void textCodePointsConstructed(long codePointCount,
                                          GasChargeContext context) {
        charge("textBlockConstructed", blocks(codePointCount), context);
    }

    public void textConstructed(String text, GasChargeContext context) {
        Objects.requireNonNull(text, "text");
        textCodePointsConstructed(text.codePointCount(0, text.length()), context);
    }

    /**
     * Charges a lexicographic Text comparison and returns its result.
     * Comparison is by Unicode code point.
     */
    public int compareText(String left,
                           String right,
                           GasChargeContext context) {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        charge("scalarComparison", 1L, context);
        int leftOffset = 0;
        int rightOffset = 0;
        long read = 0L;
        int result = 0;
        while (leftOffset < left.length() && rightOffset < right.length()) {
            int leftCodePoint = left.codePointAt(leftOffset);
            int rightCodePoint = right.codePointAt(rightOffset);
            read++;
            if (leftCodePoint != rightCodePoint) {
                result = Integer.compare(leftCodePoint, rightCodePoint);
                break;
            }
            leftOffset += Character.charCount(leftCodePoint);
            rightOffset += Character.charCount(rightCodePoint);
        }
        if (result == 0) {
            result = Boolean.compare(leftOffset < left.length(), rightOffset < right.length());
        }
        long operandBlocks = blocks(read);
        charge("textBlockExamined", operandBlocks, context);
        charge("textBlockExamined", operandBlocks, context);
        return result;
    }

    public void scalarComparisons(long quantity, GasChargeContext context) {
        charge("scalarComparison", quantity, context);
    }

    public void integerOperation(IntegerOperation operation,
                                 long leftLimbs,
                                 long rightLimbs,
                                 GasChargeContext context) {
        Objects.requireNonNull(operation, "operation");
        requirePositive(leftLimbs, "leftLimbs");
        requirePositive(rightLimbs, "rightLimbs");
        charge("integerLimbOperation",
                operation.quantity(leftLimbs, rightLimbs),
                context);
    }

    public void integerOperation(String operation,
                                 long leftLimbs,
                                 long rightLimbs,
                                 GasChargeContext context) {
        integerOperation(IntegerOperation.fromWire(operation),
                leftLimbs,
                rightLimbs,
                context);
    }

    public void integerOperation(IntegerOperation operation,
                                 BigInteger leftMagnitude,
                                 BigInteger rightMagnitude,
                                 GasChargeContext context) {
        Objects.requireNonNull(leftMagnitude, "leftMagnitude");
        Objects.requireNonNull(rightMagnitude, "rightMagnitude");
        integerOperation(operation,
                limbs(leftMagnitude),
                limbs(rightMagnitude),
                context);
    }

    public void sortComparisons(long quantity, GasChargeContext context) {
        charge("sortComparison", quantity, context);
    }

    /**
     * Performs the normative stable bottom-up merge sort.  The sort charge is
     * admitted immediately before each comparator invocation; comparator-owned
     * content work can therefore append after that entry in canonical order.
     */
    public <T> List<T> stableBottomUpSort(List<T> input,
                                         Comparator<? super T> comparator,
                                         GasChargeContext context) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(comparator, "comparator");
        int size = input.size();
        if (size < 2) {
            return Collections.unmodifiableList(new ArrayList<>(input));
        }
        List<T> source = new ArrayList<>(input);
        List<T> target = new ArrayList<>(Collections.nCopies(size, (T) null));
        long configuredWidth = meter.schedule()
                .formulaParameter("sortingInitialRunWidth");
        if (configuredWidth > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "sortingInitialRunWidth exceeds supported list size");
        }
        for (int width = (int) configuredWidth;
             width < size;
             width = width > size / 2 ? size : width * 2) {
            for (int start = 0; start < size; start += width * 2) {
                int middle = Math.min(start + width, size);
                int end = Math.min(start + width * 2, size);
                int left = start;
                int right = middle;
                int out = start;
                while (left < middle && right < end) {
                    charge("sortComparison", 1L, context);
                    if (comparator.compare(source.get(left), source.get(right)) <= 0) {
                        target.set(out++, source.get(left++));
                    } else {
                        target.set(out++, source.get(right++));
                    }
                }
                while (left < middle) {
                    target.set(out++, source.get(left++));
                }
                while (right < end) {
                    target.set(out++, source.get(right++));
                }
            }
            List<T> swap = source;
            source = target;
            target = swap;
        }
        return Collections.unmodifiableList(new ArrayList<>(source));
    }

    public void typeEdgesFollowed(long quantity, GasChargeContext context) {
        charge("typeEdgeFollowed", quantity, context);
    }

    public void schemaPredicatesEvaluated(long quantity,
                                          GasChargeContext context) {
        charge("schemaPredicateEvaluated", quantity, context);
    }

    public void validationMembersExamined(long quantity,
                                          GasChargeContext context) {
        charge("validationMemberExamined", quantity, context);
    }

    /**
     * Records one logical use of a proof key.  The first use returns
     * {@code true} so the caller can perform and charge full validation.
     * Every later use returns {@code false} and charges proof reuse once.
     */
    public boolean useValidationProof(String proofKey,
                                      GasChargeContext context) {
        requireKey(proofKey, "proofKey");
        if (validationProofs.add(proofKey)) {
            return true;
        }
        charge("validationProofReused", 1L, context);
        return false;
    }

    public boolean useValidationProof(String nodeBlueId,
                                      String effectiveTypeBlueId,
                                      String effectiveConstraintIdentity,
                                      GasChargeContext context) {
        requireKey(nodeBlueId, "nodeBlueId");
        requireKey(effectiveTypeBlueId, "effectiveTypeBlueId");
        requireKey(effectiveConstraintIdentity, "effectiveConstraintIdentity");
        return useValidationProof(
                nodeBlueId + "\u0000"
                        + effectiveTypeBlueId + "\u0000"
                        + effectiveConstraintIdentity,
                context);
    }

    public void subtypeCandidatesTested(long quantity,
                                        GasChargeContext context) {
        charge("subtypeCandidateTested", quantity, context);
    }

    public void nodeIdentitiesEstablished(long quantity,
                                          GasChargeContext context) {
        charge("nodeIdentityEstablished", quantity, context);
    }

    public void objectMembersRebuilt(long quantity,
                                     GasChargeContext context) {
        charge("objectMemberRebuilt", quantity, context);
    }

    public void fullListIdentity(long resultLength,
                                 GasChargeContext context) {
        requireNonNegative(resultLength, "resultLength");
        charge("listFoldStepRecomputed", resultLength, context);
    }

    public void verifiedListAppend(long oldLength,
                                   long appendedCount,
                                   GasChargeContext context) {
        requireNonNegative(oldLength, "oldLength");
        requireNonNegative(appendedCount, "appendedCount");
        checkedAdd(oldLength, appendedCount, "list result length");
        charge("listFoldStepRecomputed", appendedCount, context);
    }

    public void listReplaceAt(long resultLength,
                              long index,
                              GasChargeContext context) {
        requireIndex(index, resultLength, false);
        charge("listFoldStepRecomputed", resultLength - index, context);
    }

    public void listInsertAt(long resultLength,
                             long index,
                             GasChargeContext context) {
        requireIndex(index, resultLength, true);
        charge("listFoldStepRecomputed", resultLength - index, context);
    }

    public void listRemoveAt(long resultLength,
                             long removedIndex,
                             GasChargeContext context) {
        requireNonNegative(resultLength, "resultLength");
        requireNonNegative(removedIndex, "removedIndex");
        if (removedIndex > resultLength) {
            throw new IllegalArgumentException("removedIndex exceeds result length");
        }
        charge("listFoldStepRecomputed", resultLength - removedIndex, context);
    }

    public void directIdentityInput(long canonicalUtf8Bytes,
                                    GasChargeContext context) {
        requireNonNegative(canonicalUtf8Bytes, "canonicalUtf8Bytes");
        long limit = meter.schedule().portableLimit("directCanonicalIdentityInputBytes");
        if (canonicalUtf8Bytes > limit) {
            throw new PortableLimitExceededException(
                    "directCanonicalIdentityInputBytes",
                    canonicalUtf8Bytes,
                    limit);
        }
        long withDomain = checkedAdd(
                canonicalUtf8Bytes,
                meter.schedule().formulaParameter("identityHashDomainBytes"),
                "direct identity hash input");
        charge("directIdentityHashBlock",
                ceilingDivide(withDomain,
                        meter.schedule().formulaParameter(
                                "identityHashBlockBytes")),
                context);
    }

    private void charge(String counter,
                        long quantity,
                        GasChargeContext context) {
        meter.charge("semantic",
                counter,
                quantity,
                context != null ? context : GasChargeContext.empty());
    }

    private long blocks(long codePoints) {
        requireNonNegative(codePoints, "codePointCount");
        return ceilingDivide(codePoints,
                meter.schedule().formulaParameter("textBlockCodePoints"));
    }

    private long limbs(BigInteger magnitude) {
        int bits = magnitude.abs().bitLength();
        long radixBits = meter.schedule()
                .formulaParameter("integerRadixBits");
        return Math.max(
                meter.schedule().formulaParameter("integerMinimumLimbs"),
                ceilingDivide(bits, radixBits));
    }

    private static long ceilingDivide(long value, long divisor) {
        if (value == 0L) {
            return 0L;
        }
        return 1L + ((value - 1L) / divisor);
    }

    private static long multiply(long left, long right, String label) {
        if (left != 0L && right > Long.MAX_VALUE / left) {
            throw new IllegalArgumentException(label + " exceeds long range");
        }
        return left * right;
    }

    private static long checkedAdd(long left, long right, String label) {
        if (right > Long.MAX_VALUE - left) {
            throw new IllegalArgumentException(label + " exceeds long range");
        }
        return left + right;
    }

    private static void requireIndex(long index,
                                     long resultLength,
                                     boolean insertion) {
        requireNonNegative(resultLength, "resultLength");
        requireNonNegative(index, "index");
        long upper = insertion ? resultLength : resultLength - 1L;
        if (resultLength == 0L || index > upper) {
            throw new IllegalArgumentException("index is outside result list");
        }
    }

    private static void requirePositive(long value, String label) {
        if (value <= 0L) {
            throw new IllegalArgumentException(label + " must be positive");
        }
    }

    private static void requireNonNegative(long value, String label) {
        if (value < 0L) {
            throw new IllegalArgumentException(label + " must be non-negative");
        }
    }

    private static void requireKey(String value, String label) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(label + " must be non-empty");
        }
    }

    public enum IntegerOperation {
        EQUALITY_OR_ORDERING {
            @Override
            long quantity(long left, long right) {
                return checkedAdd(left, right, "integer comparison quantity");
            }
        },
        ADDITION_OR_SUBTRACTION {
            @Override
            long quantity(long left, long right) {
                return checkedAdd(Math.max(left, right), 1L, "integer add/subtract quantity");
            }
        },
        MULTIPLICATION {
            @Override
            long quantity(long left, long right) {
                return multiply(left, right, "integer multiplication quantity");
            }
        },
        DIVISION_OR_REMAINDER {
            @Override
            long quantity(long left, long right) {
                return multiply(left, right, "integer division/remainder quantity");
            }
        },
        GCD_OR_MULTIPLE_OF {
            @Override
            long quantity(long left, long right) {
                return multiply(left, right, "integer gcd/multipleOf quantity");
            }
        },
        LCM {
            @Override
            long quantity(long left, long right) {
                long product = multiply(left, right, "integer lcm quantity");
                return checkedAdd(product, product, "integer lcm quantity");
            }
        };

        abstract long quantity(long left, long right);

        public static IntegerOperation fromWire(String operation) {
            if (operation == null || operation.isEmpty()) {
                throw new IllegalArgumentException("Integer operation must be non-empty");
            }
            String normalized = operation.trim()
                    .replace('-', '_')
                    .replace('/', '_')
                    .toUpperCase(Locale.ROOT);
            switch (normalized) {
                case "EQUALITY":
                case "ORDERING":
                case "EQUALITY_OR_ORDERING":
                    return EQUALITY_OR_ORDERING;
                case "ADDITION":
                case "SUBTRACTION":
                case "ADDITION_OR_SUBTRACTION":
                    return ADDITION_OR_SUBTRACTION;
                case "MULTIPLY":
                case "MULTIPLICATION":
                    return MULTIPLICATION;
                case "DIVISION":
                case "REMAINDER":
                case "DIVISION_OR_REMAINDER":
                    return DIVISION_OR_REMAINDER;
                case "GCD":
                case "MULTIPLEOF":
                case "MULTIPLE_OF":
                case "GCD_OR_MULTIPLE_OF":
                    return GCD_OR_MULTIPLE_OF;
                case "LCM":
                    return LCM;
                default:
                    throw new IllegalArgumentException(
                            "Unknown integer gas operation: " + operation);
            }
        }
    }
}
