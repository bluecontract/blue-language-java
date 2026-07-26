package blue.language;

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

    public static <T> BlueOperationResult<T> established(T value) {
        return new BlueOperationResult<>(BlueOperationOutcome.ESTABLISHED, value,
                Collections.<String>emptySet(), null, null);
    }

    public static <T> BlueOperationResult<T> absent(String reason) {
        return new BlueOperationResult<>(BlueOperationOutcome.ABSENT, null,
                Collections.<String>emptySet(), null, reason);
    }

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

    public static <T> BlueOperationResult<T> invalid(String reason,
                                                     NodeProviderOutcome providerOutcome) {
        return new BlueOperationResult<>(BlueOperationOutcome.INVALID, null,
                Collections.<String>emptySet(), providerOutcome, reason);
    }

    public BlueOperationOutcome outcome() {
        return outcome;
    }

    public Optional<T> value() {
        return Optional.ofNullable(value);
    }

    public T requireEstablished() {
        if (outcome != BlueOperationOutcome.ESTABLISHED) {
            throw new IllegalStateException("Operation result is " + outcome
                    + (reason == null ? "" : ": " + reason));
        }
        return value;
    }

    public Set<String> outstandingBlueIds() {
        return outstandingBlueIds;
    }

    public Optional<NodeProviderOutcome> providerOutcome() {
        return Optional.ofNullable(providerOutcome);
    }

    public Optional<String> reason() {
        return Optional.ofNullable(reason);
    }

    public boolean isEstablished() {
        return outcome == BlueOperationOutcome.ESTABLISHED;
    }

    public boolean isAbsent() {
        return outcome == BlueOperationOutcome.ABSENT;
    }

    public boolean isComplete() {
        return outcome == BlueOperationOutcome.ESTABLISHED
                || outcome == BlueOperationOutcome.ABSENT;
    }
}
