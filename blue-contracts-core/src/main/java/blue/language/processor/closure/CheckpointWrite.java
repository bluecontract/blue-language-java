package blue.language.processor.closure;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** One complete Root-scoped checkpoint mutation receipt. */
public final class CheckpointWrite {

    private final long checkpointWriteOrdinal;
    private final ManagedScopeKey targetManagedScopeKey;
    private final String targetManagedScopeIdentity;
    private final String rawChannelKey;
    private final State before;
    private final State after;

    /**
     * Creates one closed checkpoint write.
     *
     * @param checkpointWriteOrdinal contiguous canonical ordinal
     * @param targetManagedScopeKey exact target Root key
     * @param targetManagedScopeIdentity asserted Root-key identity
     * @param rawChannelKey exact raw Channel key
     * @param before complete before state, or {@code null}
     * @param after complete after state, or {@code null}
     */
    public CheckpointWrite(
            long checkpointWriteOrdinal,
            ManagedScopeKey targetManagedScopeKey,
            String targetManagedScopeIdentity,
            String rawChannelKey,
            State before,
            State after) {
        this.checkpointWriteOrdinal =
                ClosureValueSupport.requireSafeInteger(
                        checkpointWriteOrdinal, "checkpointWriteOrdinal");
        ManagedScopeKey target = Objects.requireNonNull(
                targetManagedScopeKey, "targetManagedScopeKey");
        if (!target.isRoot()) {
            throw new IllegalArgumentException(
                    "Contracts 1.0 closure checkpoints target only Root");
        }
        this.targetManagedScopeKey = target;
        String asserted = ClosureValueSupport.requireSha256Identity(
                targetManagedScopeIdentity, "targetManagedScopeIdentity");
        String computed = ClosureIdentityService.INSTANCE
                .managedScopeKeyIdentity(target);
        if (!asserted.equals(computed)) {
            throw new IllegalArgumentException(
                    "targetManagedScopeIdentity does not identify target Root");
        }
        this.targetManagedScopeIdentity = asserted;
        this.rawChannelKey = ClosureValueSupport.requireNonEmptyText(
                rawChannelKey, "rawChannelKey");
        this.before = before;
        this.after = after;
        if (before == null && after == null) {
            throw new IllegalArgumentException(
                    "Checkpoint write cannot have two absent sides");
        }
        if (before != null && after != null && before.sameState(after)) {
            throw new IllegalArgumentException(
                    "Checkpoint write cannot retain identical state");
        }
    }

    /**
     * Returns contiguous canonical checkpoint-write ordinal.
     *
     * @return contiguous canonical checkpoint-write ordinal
     */
    public long checkpointWriteOrdinal() {
        return checkpointWriteOrdinal;
    }

    /**
     * Returns exact target Root managed-scope identity.
     *
     * @return exact target Root managed-scope identity
     */
    public String targetManagedScopeIdentity() {
        return targetManagedScopeIdentity;
    }

    /**
     * Returns the verified target key for sparse owned-effect evidence
     * reconciliation. The normative result wire shape exposes its identity,
     * not this redundant constructor witness.
     *
     * @return exact target Root key
     */
    public ManagedScopeKey targetManagedScopeKey() {
        return targetManagedScopeKey;
    }

    /**
     * Returns exact raw Channel key.
     *
     * @return exact raw Channel key
     */
    public String rawChannelKey() {
        return rawChannelKey;
    }

    /**
     * Returns whether the before side is present.
     *
     * @return whether the before side is present
     */
    public boolean beforePresent() {
        return before != null;
    }

    /**
     * Returns exact before domain BlueId, or {@code null}.
     *
     * @return exact before domain BlueId, or {@code null}
     */
    public String beforeDomainBlueId() {
        return before == null ? null : before.domainBlueId();
    }

    /**
     * Returns complete before domain value, or {@code null}.
     *
     * @return complete before domain value, or {@code null}
     */
    public CheckpointDomainValue beforeDomainValue() {
        return before == null ? null : before.domainValue();
    }

    /**
     * Returns exact before subject BlueId, or {@code null}.
     *
     * @return exact before subject BlueId, or {@code null}
     */
    public String beforeSubjectBlueId() {
        return before == null ? null : before.subjectBlueId();
    }

    /**
     * Returns whether the after side is present.
     *
     * @return whether the after side is present
     */
    public boolean afterPresent() {
        return after != null;
    }

    /**
     * Returns exact after domain BlueId, or {@code null}.
     *
     * @return exact after domain BlueId, or {@code null}
     */
    public String afterDomainBlueId() {
        return after == null ? null : after.domainBlueId();
    }

    /**
     * Returns complete after domain value, or {@code null}.
     *
     * @return complete after domain value, or {@code null}
     */
    public CheckpointDomainValue afterDomainValue() {
        return after == null ? null : after.domainValue();
    }

    /**
     * Returns exact after subject BlueId, or {@code null}.
     *
     * @return exact after subject BlueId, or {@code null}
     */
    public String afterSubjectBlueId() {
        return after == null ? null : after.subjectBlueId();
    }

    Map<String, Object> identityValue() {
        LinkedHashMap<String, Object> value =
                new LinkedHashMap<String, Object>();
        value.put("checkpointWriteOrdinal",
                Long.valueOf(checkpointWriteOrdinal));
        value.put("targetManagedScopeIdentity", targetManagedScopeIdentity);
        value.put("rawChannelKey", rawChannelKey);
        value.put("beforePresent", Boolean.valueOf(beforePresent()));
        value.put("beforeDomainBlueId", beforeDomainBlueId());
        value.put("beforeDomainValue", before == null
                ? null : before.domainValue().identityValue());
        value.put("beforeSubjectBlueId", beforeSubjectBlueId());
        value.put("afterPresent", Boolean.valueOf(afterPresent()));
        value.put("afterDomainBlueId", afterDomainBlueId());
        value.put("afterDomainValue", after == null
                ? null : after.domainValue().identityValue());
        value.put("afterSubjectBlueId", afterSubjectBlueId());
        return value;
    }

    /** Complete present side of a checkpoint receipt. */
    public static final class State {
        private final String domainBlueId;
        private final CheckpointDomainValue domainValue;
        private final String subjectBlueId;

        /**
         * Creates one present checkpoint state and verifies its direct BlueId.
         *
         * @param domainBlueId asserted exact domain identity
         * @param domainValue complete exact domain value
         * @param subjectBlueId exact subject identity
         */
        public State(
                String domainBlueId,
                CheckpointDomainValue domainValue,
                String subjectBlueId) {
            this.domainBlueId = ClosureValueSupport.requireBlueId(
                    domainBlueId, "domainBlueId");
            this.domainValue = Objects.requireNonNull(
                    domainValue, "domainValue");
            this.subjectBlueId = ClosureValueSupport.requireBlueId(
                    subjectBlueId, "subjectBlueId");
            if (!this.domainBlueId.equals(this.domainValue.blueId())) {
                throw new IllegalArgumentException(
                        "domainBlueId does not identify domainValue");
            }
        }

        /**
         * Returns exact domain BlueId.
         *
         * @return exact domain BlueId
         */
        public String domainBlueId() {
            return domainBlueId;
        }

        /**
         * Returns complete immutable domain value.
         *
         * @return complete immutable domain value
         */
        public CheckpointDomainValue domainValue() {
            return domainValue;
        }

        /**
         * Returns exact subject BlueId.
         *
         * @return exact subject BlueId
         */
        public String subjectBlueId() {
            return subjectBlueId;
        }

        private boolean sameState(State other) {
            return domainBlueId.equals(other.domainBlueId)
                    && subjectBlueId.equals(other.subjectBlueId)
                    && domainValue.sameValue(other.domainValue);
        }
    }
}
