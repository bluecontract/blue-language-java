package blue.language.api;

import blue.language.provider.NodeProviderOutcome;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A fail-closed result for a demand-limited Language operation.
 *
 * @param <T> established or partial operation value type
 */
public final class BlueOperationResult<T> {

    private final BlueOperationOutcome outcome;
    private final T value;
    private final Set<String> outstandingBlueIds;
    private final NodeProviderOutcome providerOutcome;
    private final String reason;

    private BlueOperationResult(BlueOperationOutcome outcome,
                                T value,
                                Set<String> outstandingBlueIds,
                                NodeProviderOutcome providerOutcome,
                                String reason) {
        this.outcome = Objects.requireNonNull(outcome, "outcome");
        this.value = value;
        this.outstandingBlueIds = Collections.unmodifiableSet(
                new LinkedHashSet<>(outstandingBlueIds));
        this.providerOutcome = providerOutcome;
        this.reason = reason;
        if (outcome == BlueOperationOutcome.ESTABLISHED && value == null) {
            throw new IllegalArgumentException("An established result requires a value.");
        }
        if ((outcome == BlueOperationOutcome.ABSENT || outcome == BlueOperationOutcome.INVALID)
                && value != null) {
            throw new IllegalArgumentException(outcome + " results must not carry a value.");
        }
    }

    /**
     * Creates a successfully established result.
     *
     * @param value non-null established value
     * @param <T> result value type
     * @return established result
     * @throws IllegalArgumentException if {@code value} is {@code null}
     */
    public static <T> BlueOperationResult<T> established(T value) {
        return new BlueOperationResult<>(BlueOperationOutcome.ESTABLISHED, value,
                Collections.<String>emptySet(), null, null);
    }

    /**
     * Creates a complete result establishing semantic absence.
     *
     * @param reason optional human-readable explanation
     * @param <T> result value type
     * @return absent result
     */
    public static <T> BlueOperationResult<T> absent(String reason) {
        return new BlueOperationResult<>(BlueOperationOutcome.ABSENT, null,
                Collections.<String>emptySet(), null, reason);
    }

    /**
     * Creates a result that requires additional provider evidence or budget.
     *
     * @param partialValue optional safely established partial value
     * @param outstandingBlueIds identities whose content is still required
     * @param providerOutcome optional provider conclusion that prevented
     *                        completion
     * @param reason optional human-readable explanation
     * @param <T> result value type
     * @return incomplete result
     */
    public static <T> BlueOperationResult<T> incomplete(T partialValue,
                                                        Set<String> outstandingBlueIds,
                                                        NodeProviderOutcome providerOutcome,
                                                        String reason) {
        return new BlueOperationResult<>(BlueOperationOutcome.INCOMPLETE, partialValue,
                outstandingBlueIds == null
                        ? Collections.<String>emptySet()
                        : outstandingBlueIds,
                providerOutcome, reason);
    }

    /**
     * Creates a terminal result for invalid input or evidence.
     *
     * @param reason optional human-readable explanation
     * @param providerOutcome optional provider conclusion associated with the
     *                        invalid evidence
     * @param <T> result value type
     * @return invalid result
     */
    public static <T> BlueOperationResult<T> invalid(String reason,
                                                     NodeProviderOutcome providerOutcome) {
        return new BlueOperationResult<>(BlueOperationOutcome.INVALID, null,
                Collections.<String>emptySet(), providerOutcome, reason);
    }

    /**
     * Returns the operation's exhaustive semantic outcome.
     *
     * @return exhaustive operation outcome
     */
    public BlueOperationOutcome outcome() {
        return outcome;
    }

    /**
     * Returns any established or safely retained partial value.
     *
     * @return established or partial value, if one is available
     */
    public Optional<T> value() {
        return Optional.ofNullable(value);
    }

    /**
     * Returns the established value.
     *
     * @return non-null established value
     * @throws IllegalStateException when the outcome is not
     *                               {@link BlueOperationOutcome#ESTABLISHED}
     */
    public T requireEstablished() {
        if (outcome != BlueOperationOutcome.ESTABLISHED) {
            throw new IllegalStateException("Operation result is " + outcome
                    + (reason == null ? "" : ": " + reason));
        }
        return value;
    }

    /**
     * Returns identities whose content is still required.
     *
     * @return immutable outstanding identity set
     */
    public Set<String> outstandingBlueIds() {
        return outstandingBlueIds;
    }

    /**
     * Returns the provider conclusion associated with this result.
     *
     * @return provider conclusion, if any
     */
    public Optional<NodeProviderOutcome> providerOutcome() {
        return Optional.ofNullable(providerOutcome);
    }

    /**
     * Returns the optional human-readable explanation.
     *
     * @return explanation, if supplied
     */
    public Optional<String> reason() {
        return Optional.ofNullable(reason);
    }

    /**
     * Tests whether the operation established a value.
     *
     * @return whether the operation established a value
     */
    public boolean isEstablished() {
        return outcome == BlueOperationOutcome.ESTABLISHED;
    }

    /**
     * Tests whether the operation established semantic absence.
     *
     * @return whether the operation established semantic absence
     */
    public boolean isAbsent() {
        return outcome == BlueOperationOutcome.ABSENT;
    }

    /**
     * Tests whether no further evidence or budget is required.
     *
     * @return whether the result is complete, either established or absent
     */
    public boolean isComplete() {
        return outcome == BlueOperationOutcome.ESTABLISHED
                || outcome == BlueOperationOutcome.ABSENT;
    }
}
