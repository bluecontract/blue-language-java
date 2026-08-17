package blue.language.processor.closure;

import java.util.Objects;

/** Immutable admission cause with explicit nullable provenance. */
public final class AdmissionCause extends ProcessingCause {

    private final AdmissionKind admissionKind;
    private final String label;
    private final String triggeringEventBlueId;
    private final String parentTransitionIdentity;
    private final String policyIdentity;

    /**
     * Creates one closed admission cause.
     *
     * @param causeIdentity exact cause identity
     * @param admissionKind closed admission reason
     * @param label stable policy label
     * @param triggeringEventBlueId nullable triggering event BlueId
     * @param parentTransitionIdentity nullable parent transition identity
     * @param policyIdentity selected admission-policy identity
     */
    public AdmissionCause(
            String causeIdentity,
            AdmissionKind admissionKind,
            String label,
            String triggeringEventBlueId,
            String parentTransitionIdentity,
            String policyIdentity) {
        super(causeIdentity);
        this.admissionKind = Objects.requireNonNull(
                admissionKind, "admissionKind");
        this.label = ClosureValueSupport.requireNonEmptyText(label, "label");
        this.triggeringEventBlueId = triggeringEventBlueId == null
                ? null
                : ClosureValueSupport.requireBlueId(
                        triggeringEventBlueId, "triggeringEventBlueId");
        this.parentTransitionIdentity = parentTransitionIdentity == null
                ? null
                : ClosureValueSupport.requireSha256Identity(
                        parentTransitionIdentity,
                        "parentTransitionIdentity");
        this.policyIdentity = ClosureValueSupport.requireSha256Identity(
                policyIdentity, "policyIdentity");
    }

    /**
     * Returns the documented value.
     *
     * @return {@link Kind#ADMISSION}
     */
    @Override
    public Kind kind() {
        return Kind.ADMISSION;
    }

    /**
     * Returns the documented value.
     *
     * @return closed admission reason
     */
    public AdmissionKind admissionKind() {
        return admissionKind;
    }

    /**
     * Returns the documented value.
     *
     * @return stable admission label
     */
    public String label() {
        return label;
    }

    /**
     * Returns the documented value.
     *
     * @return triggering event BlueId, or {@code null}
     */
    public String triggeringEventBlueId() {
        return triggeringEventBlueId;
    }

    /**
     * Returns the documented value.
     *
     * @return parent transition identity, or {@code null}
     */
    public String parentTransitionIdentity() {
        return parentTransitionIdentity;
    }

    /**
     * Returns the documented value.
     *
     * @return selected admission-policy identity
     */
    public String policyIdentity() {
        return policyIdentity;
    }
}
