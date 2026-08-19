package blue.language.processor;

import java.util.Objects;

/** Exact one-entry add, replace, or removal applied by checkpoint settlement. */
public final class ManagedCheckpointMutation {

    /** Closed checkpoint-entry mutation operation. */
    public enum Operation {
        /** An absent raw-key entry became present. */
        ADD,
        /** A present raw-key entry changed domain or subject. */
        REPLACE,
        /** A present raw-key entry was retired. */
        REMOVE
    }

    private final Operation operation;
    private final String rawChannelKey;
    private final ManagedCheckpointState beforeState;
    private final ManagedCheckpointState afterState;
    private final FrozenJsonPatch processorPatch;

    ManagedCheckpointMutation(
            Operation operation,
            String rawChannelKey,
            ManagedCheckpointState beforeState,
            ManagedCheckpointState afterState,
            FrozenJsonPatch processorPatch) {
        this.operation = Objects.requireNonNull(operation, "operation");
        if (rawChannelKey == null || rawChannelKey.isEmpty()) {
            throw new IllegalArgumentException(
                    "rawChannelKey must be non-empty");
        }
        this.rawChannelKey = rawChannelKey;
        this.beforeState = Objects.requireNonNull(
                beforeState, "beforeState");
        this.afterState = Objects.requireNonNull(
                afterState, "afterState");
        this.processorPatch = Objects.requireNonNull(
                processorPatch, "processorPatch");
    }

    /**
     * Returns the closed entry operation.
     *
     * @return add, replace, or remove
     */
    public Operation operation() { return operation; }

    /**
     * Returns the exact raw Root Channel key.
     *
     * @return non-empty raw key
     */
    public String rawChannelKey() { return rawChannelKey; }

    /**
     * Returns the complete exact side before settlement.
     *
     * @return immutable before side
     */
    public ManagedCheckpointState beforeState() { return beforeState; }

    /**
     * Returns the complete exact side after settlement.
     *
     * @return immutable after side
     */
    public ManagedCheckpointState afterState() { return afterState; }

    /**
     * Returns the exact processor-owned direct entry mutation.
     *
     * @return immutable patch evidence
     */
    public FrozenJsonPatch processorPatch() { return processorPatch; }
}
