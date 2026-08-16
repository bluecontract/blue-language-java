package blue.contracts.closure;

import java.util.Objects;

/** Immutable admission cause; nullable evidence is represented explicitly. */
public final class AdmissionCause extends ProcessingCause {
    private final String causeIdentity;
    private final AdmissionKind admissionKind;
    private final String label;
    private final String triggeringEventBlueId;
    private final String parentTransitionIdentity;
    private final String policyIdentity;

    public AdmissionCause(
            String causeIdentity,
            AdmissionKind admissionKind,
            String label,
            String triggeringEventBlueId,
            String parentTransitionIdentity,
            String policyIdentity) {
        this.causeIdentity = Objects.requireNonNull(causeIdentity, "causeIdentity");
        this.admissionKind = Objects.requireNonNull(admissionKind, "admissionKind");
        this.label = Objects.requireNonNull(label, "label");
        this.triggeringEventBlueId = triggeringEventBlueId;
        this.parentTransitionIdentity = parentTransitionIdentity;
        this.policyIdentity = Objects.requireNonNull(policyIdentity, "policyIdentity");
    }

    @Override
    public String causeIdentity() {
        return causeIdentity;
    }

    @Override
    public String kind() {
        return "admission";
    }

    public AdmissionKind admissionKind() {
        return admissionKind;
    }

    public String label() {
        return label;
    }

    public String triggeringEventBlueId() {
        return triggeringEventBlueId;
    }

    public String parentTransitionIdentity() {
        return parentTransitionIdentity;
    }

    public String policyIdentity() {
        return policyIdentity;
    }
}
