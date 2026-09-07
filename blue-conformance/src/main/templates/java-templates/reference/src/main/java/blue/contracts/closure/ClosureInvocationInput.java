package blue.contracts.closure;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * One complete operation-specific closure invocation.
 *
 * <p>A serializer flattens this wrapper and its state snapshot to the closed
 * normative invocation shape. State and invocation-only evidence each have
 * one owner, so a caller cannot supply conflicting parallel copies.</p>
 */
public final class ClosureInvocationInput {
    public enum Operation {
        PROCESS_CLOSURE("process-closure"),
        ADMIT_CLOSURE("admit-closure");

        private final String identityValue;

        Operation(String identityValue) {
            this.identityValue = identityValue;
        }

        public String identityValue() {
            return identityValue;
        }
    }

    /** Recomputes the optional closed admission-candidate identity. */
    public interface AdmissionCandidateIdentityFactory {
        String identity(AdmissionCandidate candidate);
    }

    /** Recomputes the closed invocation identity from normative input only. */
    public interface InvocationIdentityFactory {
        String identity(
                Operation operation,
                AffectedClosureSnapshot state,
                ProcessingCause cause,
                String admissionCandidateIdentity,
                String directDeliverySnapshotIdentity,
                ExecutionPolicy gasPolicy,
                AffectedClosureSnapshot.EnvironmentEvidence environment);
    }

    private final Operation operation;
    private final String invocationIdentity;
    private final AffectedClosureSnapshot state;
    private final ProcessingCause cause;
    private final AdmissionCandidate admissionCandidate;
    private final String admissionCandidateIdentity;
    private final List<DirectLogicalDelivery> directDeliveries;
    private final String directDeliverySnapshotIdentity;
    private final ExecutionPolicy gasPolicy;
    private final AffectedClosureSnapshot.EnvironmentEvidence environment;

    private ClosureInvocationInput(
            Operation operation,
            String invocationIdentity,
            AffectedClosureSnapshot state,
            ProcessingCause cause,
            AdmissionCandidate admissionCandidate,
            String admissionCandidateIdentity,
            List<DirectLogicalDelivery> directDeliveries,
            String directDeliverySnapshotIdentity,
            DirectLogicalDelivery.SnapshotIdentityFactory
                    directDeliverySnapshotIdentityFactory,
            ExecutionPolicy gasPolicy,
            AffectedClosureSnapshot.EnvironmentEvidence environment,
            AdmissionCandidateIdentityFactory candidateIdentityFactory,
            InvocationIdentityFactory invocationIdentityFactory) {
        this.operation = Objects.requireNonNull(operation, "operation");
        this.state = Objects.requireNonNull(state, "state");
        this.cause = Objects.requireNonNull(cause, "cause");
        this.directDeliveries = immutableCanonicalDirectDeliveries(directDeliveries);
        String recomputedDeliverySnapshotIdentity = Objects.requireNonNull(
                Objects.requireNonNull(
                        directDeliverySnapshotIdentityFactory,
                        "directDeliverySnapshotIdentityFactory")
                        .identity(this.directDeliveries),
                "recomputed directDeliverySnapshotIdentity");
        if (!recomputedDeliverySnapshotIdentity.equals(Objects.requireNonNull(
                directDeliverySnapshotIdentity, "directDeliverySnapshotIdentity"))) {
            throw new IllegalArgumentException(
                    "directDeliverySnapshotIdentity mismatch");
        }
        this.directDeliverySnapshotIdentity = recomputedDeliverySnapshotIdentity;
        this.gasPolicy = Objects.requireNonNull(gasPolicy, "gasPolicy");
        this.environment = Objects.requireNonNull(environment, "environment");
        if ((admissionCandidate == null) != (admissionCandidateIdentity == null)) {
            throw new IllegalArgumentException("admissionCandidateIdentity");
        }
        this.admissionCandidate = admissionCandidate;
        if (admissionCandidate == null) {
            this.admissionCandidateIdentity = null;
        } else {
            String recomputedCandidateIdentity = Objects.requireNonNull(
                    Objects.requireNonNull(
                            candidateIdentityFactory, "candidateIdentityFactory")
                            .identity(admissionCandidate),
                    "recomputed admissionCandidateIdentity");
            if (!recomputedCandidateIdentity.equals(admissionCandidateIdentity)) {
                throw new IllegalArgumentException(
                        "admissionCandidateIdentity mismatch");
            }
            this.admissionCandidateIdentity = recomputedCandidateIdentity;
        }
        String recomputedInvocationIdentity = Objects.requireNonNull(
                Objects.requireNonNull(
                        invocationIdentityFactory, "invocationIdentityFactory")
                        .identity(
                                this.operation,
                                this.state,
                                this.cause,
                                this.admissionCandidateIdentity,
                                this.directDeliverySnapshotIdentity,
                                this.gasPolicy,
                                this.environment),
                "recomputed invocationIdentity");
        if (!recomputedInvocationIdentity.equals(Objects.requireNonNull(
                invocationIdentity, "invocationIdentity"))) {
            throw new IllegalArgumentException("invocationIdentity mismatch");
        }
        this.invocationIdentity = recomputedInvocationIdentity;
    }

    public static ClosureInvocationInput processClosure(
            String invocationIdentity,
            AffectedClosureSnapshot state,
            ExternalEventCause cause,
            List<DirectLogicalDelivery> directDeliveries,
            String directDeliverySnapshotIdentity,
            DirectLogicalDelivery.SnapshotIdentityFactory
                    directDeliverySnapshotIdentityFactory,
            ExecutionPolicy gasPolicy,
            AffectedClosureSnapshot.EnvironmentEvidence environment,
            InvocationIdentityFactory invocationIdentityFactory) {
        if (!Objects.requireNonNull(cause, "cause")
                .externalOrderPolicyIdentity().equals(
                        Objects.requireNonNull(environment, "environment")
                                .externalOrderPolicy().identity())) {
            throw new IllegalArgumentException("externalOrderPolicyIdentity mismatch");
        }
        return new ClosureInvocationInput(
                Operation.PROCESS_CLOSURE,
                invocationIdentity,
                state,
                cause,
                null,
                null,
                directDeliveries,
                directDeliverySnapshotIdentity,
                directDeliverySnapshotIdentityFactory,
                gasPolicy,
                environment,
                null,
                invocationIdentityFactory);
    }

    /**
     * Builds one independently metered/committed managed-revision invocation.
     * It has no external direct deliveries and carries no transition list.
     */
    public static ClosureInvocationInput processManagedRevision(
            String invocationIdentity,
            AffectedClosureSnapshot state,
            ManagedRevisionCause cause,
            String emptyDirectDeliverySnapshotIdentity,
            DirectLogicalDelivery.SnapshotIdentityFactory
                    directDeliverySnapshotIdentityFactory,
            ExecutionPolicy gasPolicy,
            AffectedClosureSnapshot.EnvironmentEvidence environment,
            InvocationIdentityFactory invocationIdentityFactory) {
        return new ClosureInvocationInput(
                Operation.PROCESS_CLOSURE,
                invocationIdentity,
                state,
                Objects.requireNonNull(cause, "cause"),
                null,
                null,
                Collections.<DirectLogicalDelivery>emptyList(),
                emptyDirectDeliverySnapshotIdentity,
                directDeliverySnapshotIdentityFactory,
                gasPolicy,
                environment,
                null,
                invocationIdentityFactory);
    }

    public static ClosureInvocationInput admitClosure(
            String invocationIdentity,
            AffectedClosureSnapshot state,
            AdmissionCause cause,
            AdmissionCandidate admissionCandidate,
            String admissionCandidateIdentity,
            String emptyDirectDeliverySnapshotIdentity,
            DirectLogicalDelivery.SnapshotIdentityFactory
                    directDeliverySnapshotIdentityFactory,
            ExecutionPolicy gasPolicy,
            AffectedClosureSnapshot.EnvironmentEvidence environment,
            AdmissionCandidateIdentityFactory candidateIdentityFactory,
            InvocationIdentityFactory invocationIdentityFactory) {
        return new ClosureInvocationInput(
                Operation.ADMIT_CLOSURE,
                invocationIdentity,
                state,
                Objects.requireNonNull(cause, "cause"),
                admissionCandidate,
                admissionCandidateIdentity,
                Collections.<DirectLogicalDelivery>emptyList(),
                emptyDirectDeliverySnapshotIdentity,
                directDeliverySnapshotIdentityFactory,
                gasPolicy,
                environment,
                candidateIdentityFactory,
                invocationIdentityFactory);
    }

    public Operation operation() { return operation; }
    public String invocationIdentity() { return invocationIdentity; }
    public AffectedClosureSnapshot state() { return state; }
    public ProcessingCause cause() { return cause; }
    public AdmissionCandidate admissionCandidate() { return admissionCandidate; }
    public String admissionCandidateIdentity() { return admissionCandidateIdentity; }
    public List<DirectLogicalDelivery> directDeliveries() { return directDeliveries; }
    public String directDeliverySnapshotIdentity() {
        return directDeliverySnapshotIdentity;
    }
    public ExecutionPolicy gasPolicy() { return gasPolicy; }
    public AffectedClosureSnapshot.EnvironmentEvidence environment() {
        return environment;
    }

    private static List<DirectLogicalDelivery> immutableCanonicalDirectDeliveries(
            List<DirectLogicalDelivery> values) {
        ArrayList<DirectLogicalDelivery> copy = copy(values, "directDeliveries");
        for (int index = 1; index < copy.size(); index++) {
            if (copy.get(index - 1).compareTo(copy.get(index)) >= 0) {
                throw new IllegalArgumentException(
                        "directDeliveries not in canonical order");
            }
        }
        return Collections.unmodifiableList(copy);
    }

    private static <T> ArrayList<T> copy(List<T> values, String field) {
        ArrayList<T> copy = new ArrayList<T>(Objects.requireNonNull(values, field));
        for (T value : copy) {
            Objects.requireNonNull(value, field + " item");
        }
        return copy;
    }
}
