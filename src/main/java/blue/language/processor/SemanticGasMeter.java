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
     *
     * @param nodeBlueId non-empty exact node identity
     * @return {@code true} only for the first opening in this invocation
     * @throws IllegalArgumentException if {@code nodeBlueId} is empty or
     *         {@code null}
     * @throws GasLimitExceededException if the first opening exceeds budget
     */
    public boolean openNodeManifest(String nodeBlueId) {
        return openNodeManifest(nodeBlueId, GasChargeContext.empty());
    }

    /**
     * Charges the first opening with deterministic trace attribution.
     *
     * @param nodeBlueId non-empty exact node identity
     * @param context charge attribution, or {@code null}
     * @return {@code true} only for the first opening in this invocation
     * @throws IllegalArgumentException if {@code nodeBlueId} is empty or
     *         {@code null}
     * @throws GasLimitExceededException if the first opening exceeds budget
     */
    public boolean openNodeManifest(String nodeBlueId, GasChargeContext context) {
        requireKey(nodeBlueId, "nodeBlueId");
        if (!openedNodeManifests.add(nodeBlueId)) {
            return false;
        }
        charge(
                GasScheduleConstants
                        .SemanticCounter.NODE_MANIFEST_OPENED,
                1L,
                context);
        return true;
    }

    /**
     * Charges direct object-member reads.
     *
     * @param quantity non-negative member count
     * @param context charge attribution, or {@code null}
     * @throws IllegalArgumentException if {@code quantity} is negative
     * @throws GasLimitExceededException if budget is insufficient
     */
    public void objectMembersRead(long quantity, GasChargeContext context) {
        charge(
                GasScheduleConstants.SemanticCounter.OBJECT_MEMBER_READ,
                quantity,
                context);
    }

    /**
     * Charges direct list-item reads.
     *
     * @param quantity non-negative item count
     * @param context charge attribution, or {@code null}
     * @throws IllegalArgumentException if {@code quantity} is negative
     * @throws GasLimitExceededException if budget is insufficient
     */
    public void listItemsRead(long quantity, GasChargeContext context) {
        charge(
                GasScheduleConstants.SemanticCounter.LIST_ITEM_READ,
                quantity,
                context);
    }

    /**
     * Charges text examination by logical Unicode code-point blocks.
     *
     * @param codePointCount non-negative examined code-point count
     * @param context charge attribution, or {@code null}
     * @throws IllegalArgumentException if {@code codePointCount} is negative
     * @throws GasLimitExceededException if budget is insufficient
     */
    public void textCodePointsExamined(long codePointCount,
                                       GasChargeContext context) {
        charge(
                GasScheduleConstants.SemanticCounter.TEXT_BLOCK_EXAMINED,
                blocks(codePointCount),
                context);
    }

    /**
     * Charges examination of an exact Java string.
     *
     * @param text non-null examined text
     * @param context charge attribution, or {@code null}
     * @throws NullPointerException if {@code text} is {@code null}
     * @throws GasLimitExceededException if budget is insufficient
     */
    public void textExamined(String text, GasChargeContext context) {
        Objects.requireNonNull(text, "text");
        textCodePointsExamined(text.codePointCount(0, text.length()), context);
    }

    /**
     * Charges text construction by logical Unicode code-point blocks.
     *
     * @param codePointCount non-negative constructed code-point count
     * @param context charge attribution, or {@code null}
     * @throws IllegalArgumentException if {@code codePointCount} is negative
     * @throws GasLimitExceededException if budget is insufficient
     */
    public void textCodePointsConstructed(long codePointCount,
                                          GasChargeContext context) {
        charge(
                GasScheduleConstants
                        .SemanticCounter.TEXT_BLOCK_CONSTRUCTED,
                blocks(codePointCount),
                context);
    }

    /**
     * Charges construction of an exact Java string.
     *
     * @param text non-null constructed text
     * @param context charge attribution, or {@code null}
     * @throws NullPointerException if {@code text} is {@code null}
     * @throws GasLimitExceededException if budget is insufficient
     */
    public void textConstructed(String text, GasChargeContext context) {
        Objects.requireNonNull(text, "text");
        textCodePointsConstructed(text.codePointCount(0, text.length()), context);
    }

    /**
     * Charges a lexicographic Text comparison and returns its result.
     * Comparison is by Unicode code point.
     *
     * @param left non-null left operand
     * @param right non-null right operand
     * @param context charge attribution, or {@code null}
     * @return negative, zero, or positive according to code-point ordering
     * @throws NullPointerException if either operand is {@code null}
     * @throws GasLimitExceededException if budget is insufficient
     */
    public int compareText(String left,
                           String right,
                           GasChargeContext context) {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        charge(
                GasScheduleConstants.SemanticCounter.SCALAR_COMPARISON,
                1L,
                context);
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
        charge(
                GasScheduleConstants.SemanticCounter.TEXT_BLOCK_EXAMINED,
                operandBlocks,
                context);
        charge(
                GasScheduleConstants.SemanticCounter.TEXT_BLOCK_EXAMINED,
                operandBlocks,
                context);
        return result;
    }

    /**
     * Charges scalar comparisons already counted by a caller.
     *
     * @param quantity non-negative comparison count
     * @param context charge attribution, or {@code null}
     * @throws IllegalArgumentException if {@code quantity} is negative
     * @throws GasLimitExceededException if budget is insufficient
     */
    public void scalarComparisons(long quantity, GasChargeContext context) {
        charge(
                GasScheduleConstants.SemanticCounter.SCALAR_COMPARISON,
                quantity,
                context);
    }

    /**
     * Charges an integer operation from explicit logical limb counts.
     *
     * @param operation integer formula category
     * @param leftLimbs positive left operand limb count
     * @param rightLimbs positive right operand limb count
     * @param context charge attribution, or {@code null}
     * @throws NullPointerException if {@code operation} is {@code null}
     * @throws IllegalArgumentException if a limb count or calculated quantity
     *         is invalid
     * @throws GasLimitExceededException if budget is insufficient
     */
    public void integerOperation(IntegerOperation operation,
                                 long leftLimbs,
                                 long rightLimbs,
                                 GasChargeContext context) {
        Objects.requireNonNull(operation, "operation");
        requirePositive(leftLimbs, "leftLimbs");
        requirePositive(rightLimbs, "rightLimbs");
        charge(
                GasScheduleConstants
                        .SemanticCounter.INTEGER_LIMB_OPERATION,
                operation.quantity(leftLimbs, rightLimbs),
                context);
    }

    /**
     * Charges an integer operation selected by its wire name.
     *
     * @param operation stable operation name
     * @param leftLimbs positive left operand limb count
     * @param rightLimbs positive right operand limb count
     * @param context charge attribution, or {@code null}
     * @throws IllegalArgumentException if the name, limb counts, or calculated
     *         quantity is invalid
     * @throws GasLimitExceededException if budget is insufficient
     */
    public void integerOperation(String operation,
                                 long leftLimbs,
                                 long rightLimbs,
                                 GasChargeContext context) {
        integerOperation(IntegerOperation.fromWire(operation),
                leftLimbs,
                rightLimbs,
                context);
    }

    /**
     * Charges an integer operation from exact operand magnitudes.
     *
     * @param operation integer formula category
     * @param leftMagnitude non-null left magnitude
     * @param rightMagnitude non-null right magnitude
     * @param context charge attribution, or {@code null}
     * @throws NullPointerException if an operation or magnitude is
     *         {@code null}
     * @throws IllegalArgumentException if the calculated quantity overflows
     * @throws GasLimitExceededException if budget is insufficient
     */
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

    /**
     * Charges construction of one exact Integer by its logical limb count.
     *
     * @param magnitude non-null constructed magnitude
     * @param context charge attribution, or {@code null}
     * @throws NullPointerException if {@code magnitude} is {@code null}
     * @throws GasLimitExceededException if budget is insufficient
     */
    public void integerConstructed(BigInteger magnitude,
                                   GasChargeContext context) {
        Objects.requireNonNull(magnitude, "magnitude");
        charge(
                GasScheduleConstants
                        .SemanticCounter.INTEGER_LIMB_OPERATION,
                limbs(magnitude),
                context);
    }

    GasSchedule schedule() {
        return meter.schedule();
    }

    /**
     * Charges stable-sort comparator invocations.
     *
     * @param quantity non-negative comparison count
     * @param context charge attribution, or {@code null}
     * @throws IllegalArgumentException if {@code quantity} is negative
     * @throws GasLimitExceededException if budget is insufficient
     */
    public void sortComparisons(long quantity, GasChargeContext context) {
        charge(
                GasScheduleConstants.SemanticCounter.SORT_COMPARISON,
                quantity,
                context);
    }

    /**
     * Performs the normative stable bottom-up merge sort.  The sort charge is
     * admitted immediately before each comparator invocation; comparator-owned
     * content work can therefore append after that entry in canonical order.
     *
     * @param <T> sorted element type
     * @param input source elements copied before sorting
     * @param comparator stable ordering comparator
     * @param context charge attribution, or {@code null}
     * @return immutable stably sorted copy
     * @throws NullPointerException if {@code input} or {@code comparator} is
     *         {@code null}
     * @throws IllegalArgumentException if the configured run width is
     *         unsupported
     * @throws GasLimitExceededException if budget is insufficient
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
                .formulaParameter(
                        GasScheduleConstants.FormulaParameter
                                .SORTING_INITIAL_RUN_WIDTH);
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
                    charge(
                            GasScheduleConstants
                                    .SemanticCounter.SORT_COMPARISON,
                            1L,
                            context);
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

    /**
     * Charges followed edges in an effective type lineage.
     *
     * @param quantity non-negative edge count
     * @param context charge attribution, or {@code null}
     * @throws IllegalArgumentException if {@code quantity} is negative
     * @throws GasLimitExceededException if budget is insufficient
     */
    public void typeEdgesFollowed(long quantity, GasChargeContext context) {
        charge(
                GasScheduleConstants.SemanticCounter.TYPE_EDGE_FOLLOWED,
                quantity,
                context);
    }

    /**
     * Charges evaluated schema predicates.
     *
     * @param quantity non-negative predicate count
     * @param context charge attribution, or {@code null}
     * @throws IllegalArgumentException if {@code quantity} is negative
     * @throws GasLimitExceededException if budget is insufficient
     */
    public void schemaPredicatesEvaluated(long quantity,
                                          GasChargeContext context) {
        charge(
                GasScheduleConstants
                        .SemanticCounter.SCHEMA_PREDICATE_EVALUATED,
                quantity,
                context);
    }

    /**
     * Charges members examined during conformance validation.
     *
     * @param quantity non-negative examined-member count
     * @param context charge attribution, or {@code null}
     * @throws IllegalArgumentException if {@code quantity} is negative
     * @throws GasLimitExceededException if budget is insufficient
     */
    public void validationMembersExamined(long quantity,
                                          GasChargeContext context) {
        charge(
                GasScheduleConstants
                        .SemanticCounter.VALIDATION_MEMBER_EXAMINED,
                quantity,
                context);
    }

    /**
     * Records one logical use of a proof key.  The first use returns
     * {@code true} so the caller can perform and charge full validation.
     * Every later use returns {@code false} and charges proof reuse once.
     *
     * @param proofKey non-empty invocation-local proof identity
     * @param context charge attribution, or {@code null}
     * @return {@code true} for first use, otherwise {@code false}
     * @throws IllegalArgumentException if {@code proofKey} is empty or
     *         {@code null}
     * @throws GasLimitExceededException if a reuse charge exceeds budget
     */
    public boolean useValidationProof(String proofKey,
                                      GasChargeContext context) {
        requireKey(proofKey, "proofKey");
        if (validationProofs.add(proofKey)) {
            return true;
        }
        charge(
                GasScheduleConstants
                        .SemanticCounter.VALIDATION_PROOF_REUSED,
                1L,
                context);
        return false;
    }

    /**
     * Uses the canonical tuple identifying one validation proof.
     *
     * @param nodeBlueId non-empty node identity
     * @param effectiveTypeBlueId non-empty effective type identity
     * @param effectiveConstraintIdentity non-empty constraint identity
     * @param context charge attribution, or {@code null}
     * @return {@code true} for first use, otherwise {@code false}
     * @throws IllegalArgumentException if any identity is empty or
     *         {@code null}
     * @throws GasLimitExceededException if a reuse charge exceeds budget
     */
    public boolean useValidationProof(String nodeBlueId,
                                      String effectiveTypeBlueId,
                                      String effectiveConstraintIdentity,
                                      GasChargeContext context) {
        requireKey(nodeBlueId, "nodeBlueId");
        requireKey(effectiveTypeBlueId, "effectiveTypeBlueId");
        requireKey(effectiveConstraintIdentity, "effectiveConstraintIdentity");
        return useValidationProof(
                nodeBlueId
                        + ProcessorIdentityConstants.SELECTOR_COMPONENT_DELIMITER
                        + effectiveTypeBlueId
                        + ProcessorIdentityConstants.SELECTOR_COMPONENT_DELIMITER
                        + effectiveConstraintIdentity,
                context);
    }

    /**
     * Charges subtype candidates tested by a conformance operation.
     *
     * @param quantity non-negative candidate count
     * @param context charge attribution, or {@code null}
     * @throws IllegalArgumentException if {@code quantity} is negative
     * @throws GasLimitExceededException if budget is insufficient
     */
    public void subtypeCandidatesTested(long quantity,
                                        GasChargeContext context) {
        charge(
                GasScheduleConstants
                        .SemanticCounter.SUBTYPE_CANDIDATE_TESTED,
                quantity,
                context);
    }

    /**
     * Charges semantic node identities established.
     *
     * @param quantity non-negative identity count
     * @param context charge attribution, or {@code null}
     * @throws IllegalArgumentException if {@code quantity} is negative
     * @throws GasLimitExceededException if budget is insufficient
     */
    public void nodeIdentitiesEstablished(long quantity,
                                          GasChargeContext context) {
        charge(
                GasScheduleConstants
                        .SemanticCounter.NODE_IDENTITY_ESTABLISHED,
                quantity,
                context);
    }

    /**
     * Charges object members rebuilt for identity propagation.
     *
     * @param quantity non-negative rebuilt-member count
     * @param context charge attribution, or {@code null}
     * @throws IllegalArgumentException if {@code quantity} is negative
     * @throws GasLimitExceededException if budget is insufficient
     */
    public void objectMembersRebuilt(long quantity,
                                     GasChargeContext context) {
        charge(
                GasScheduleConstants
                        .SemanticCounter.OBJECT_MEMBER_REBUILT,
                quantity,
                context);
    }

    /**
     * Charges all fold steps required for a full list identity.
     *
     * @param resultLength non-negative result list length
     * @param context charge attribution, or {@code null}
     * @throws IllegalArgumentException if {@code resultLength} is negative
     * @throws GasLimitExceededException if budget is insufficient
     */
    public void fullListIdentity(long resultLength,
                                 GasChargeContext context) {
        requireNonNegative(resultLength, "resultLength");
        charge(
                GasScheduleConstants
                        .SemanticCounter.LIST_FOLD_STEP_RECOMPUTED,
                resultLength,
                context);
    }

    /**
     * Charges fold steps for a verified append.
     *
     * @param oldLength non-negative prior list length
     * @param appendedCount non-negative appended item count
     * @param context charge attribution, or {@code null}
     * @throws IllegalArgumentException if a length is negative or overflows
     * @throws GasLimitExceededException if budget is insufficient
     */
    public void verifiedListAppend(long oldLength,
                                   long appendedCount,
                                   GasChargeContext context) {
        requireNonNegative(oldLength, "oldLength");
        requireNonNegative(appendedCount, "appendedCount");
        checkedAdd(oldLength, appendedCount, "list result length");
        charge(
                GasScheduleConstants
                        .SemanticCounter.LIST_FOLD_STEP_RECOMPUTED,
                appendedCount,
                context);
    }

    /**
     * Charges fold recomputation after replacing one list item.
     *
     * @param resultLength positive result list length
     * @param index valid replaced index
     * @param context charge attribution, or {@code null}
     * @throws IllegalArgumentException if the length or index is invalid
     * @throws GasLimitExceededException if budget is insufficient
     */
    public void listReplaceAt(long resultLength,
                              long index,
                              GasChargeContext context) {
        requireIndex(index, resultLength, false);
        charge(
                GasScheduleConstants
                        .SemanticCounter.LIST_FOLD_STEP_RECOMPUTED,
                resultLength - index,
                context);
    }

    /**
     * Charges fold recomputation after inserting one list item.
     *
     * @param resultLength positive result list length
     * @param index valid insertion index in the result
     * @param context charge attribution, or {@code null}
     * @throws IllegalArgumentException if the length or index is invalid
     * @throws GasLimitExceededException if budget is insufficient
     */
    public void listInsertAt(long resultLength,
                             long index,
                             GasChargeContext context) {
        requireIndex(index, resultLength, true);
        charge(
                GasScheduleConstants
                        .SemanticCounter.LIST_FOLD_STEP_RECOMPUTED,
                resultLength - index,
                context);
    }

    /**
     * Charges fold recomputation after removing one list item.
     *
     * @param resultLength non-negative post-removal list length
     * @param removedIndex non-negative removed index not exceeding the result
     *        length
     * @param context charge attribution, or {@code null}
     * @throws IllegalArgumentException if the length or index is invalid
     * @throws GasLimitExceededException if budget is insufficient
     */
    public void listRemoveAt(long resultLength,
                             long removedIndex,
                             GasChargeContext context) {
        requireNonNegative(resultLength, "resultLength");
        requireNonNegative(removedIndex, "removedIndex");
        if (removedIndex > resultLength) {
            throw new IllegalArgumentException("removedIndex exceeds result length");
        }
        charge(
                GasScheduleConstants
                        .SemanticCounter.LIST_FOLD_STEP_RECOMPUTED,
                resultLength - removedIndex,
                context);
    }

    /**
     * Charges canonical bytes hashed for a direct node identity.
     *
     * @param canonicalUtf8Bytes non-negative direct canonical input size
     * @param context charge attribution, or {@code null}
     * @throws IllegalArgumentException if the size is negative or arithmetic
     *         overflows
     * @throws PortableLimitExceededException if the portable direct-input
     *         limit is exceeded
     * @throws GasLimitExceededException if budget is insufficient
     */
    public void directIdentityInput(long canonicalUtf8Bytes,
                                    GasChargeContext context) {
        requireNonNegative(canonicalUtf8Bytes, "canonicalUtf8Bytes");
        long limit = meter.schedule().portableLimit(
                GasScheduleConstants.PortableLimit
                        .DIRECT_CANONICAL_IDENTITY_INPUT_BYTES);
        if (canonicalUtf8Bytes > limit) {
            throw new PortableLimitExceededException(
                    GasScheduleConstants.PortableLimit
                            .DIRECT_CANONICAL_IDENTITY_INPUT_BYTES,
                    canonicalUtf8Bytes,
                    limit);
        }
        long withDomain = checkedAdd(
                canonicalUtf8Bytes,
                meter.schedule().formulaParameter(
                        GasScheduleConstants.FormulaParameter
                                .IDENTITY_HASH_DOMAIN_BYTES),
                "direct identity hash input");
        charge(
                GasScheduleConstants
                        .SemanticCounter.DIRECT_IDENTITY_HASH_BLOCK,
                ceilingDivide(withDomain,
                        meter.schedule().formulaParameter(
                                GasScheduleConstants.FormulaParameter
                                        .IDENTITY_HASH_BLOCK_BYTES)),
                context);
    }

    private void charge(String counter,
                        long quantity,
                        GasChargeContext context) {
        meter.charge(
                GasScheduleConstants.Namespace.SEMANTIC,
                counter,
                quantity,
                context != null ? context : GasChargeContext.empty());
    }

    private long blocks(long codePoints) {
        requireNonNegative(codePoints, "codePointCount");
        return ceilingDivide(codePoints,
                meter.schedule().formulaParameter(
                        GasScheduleConstants.FormulaParameter
                                .TEXT_BLOCK_CODE_POINTS));
    }

    private long limbs(BigInteger magnitude) {
        int bits = magnitude.abs().bitLength();
        long radixBits = meter.schedule()
                .formulaParameter(
                        GasScheduleConstants.FormulaParameter
                                .INTEGER_RADIX_BITS);
        return Math.max(
                meter.schedule().formulaParameter(
                        GasScheduleConstants.FormulaParameter
                                .INTEGER_MINIMUM_LIMBS),
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

    /** Formula categories for logical integer work. */
    public enum IntegerOperation {
        /** Equality and ordering comparisons. */
        EQUALITY_OR_ORDERING {
            @Override
            long quantity(long left, long right) {
                return checkedAdd(left, right, "integer comparison quantity");
            }
        },
        /** Addition and subtraction. */
        ADDITION_OR_SUBTRACTION {
            @Override
            long quantity(long left, long right) {
                return checkedAdd(Math.max(left, right), 1L, "integer add/subtract quantity");
            }
        },
        /** Multiplication. */
        MULTIPLICATION {
            @Override
            long quantity(long left, long right) {
                return multiply(left, right, "integer multiplication quantity");
            }
        },
        /** Division and remainder. */
        DIVISION_OR_REMAINDER {
            @Override
            long quantity(long left, long right) {
                return multiply(left, right, "integer division/remainder quantity");
            }
        },
        /** Greatest-common-divisor and multiple-of checks. */
        GCD_OR_MULTIPLE_OF {
            @Override
            long quantity(long left, long right) {
                return multiply(left, right, "integer gcd/multipleOf quantity");
            }
        },
        /** Least-common-multiple calculation. */
        LCM {
            @Override
            long quantity(long left, long right) {
                long product = multiply(left, right, "integer lcm quantity");
                return checkedAdd(product, product, "integer lcm quantity");
            }
        };

        abstract long quantity(long left, long right);

        /**
         * Parses a stable integer-operation name.
         *
         * @param operation non-empty wire operation name
         * @return matching formula category
         * @throws IllegalArgumentException if {@code operation} is empty,
         *         {@code null}, or unknown
         */
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
