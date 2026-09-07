package blue.language.processor.closure;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One closed affected-closure invocation input.
 *
 * <p>Cryptographic identities are asserted here as typed evidence and are
 * recomputed by an authoritative {@link ClosureProcessor}; callers do not
 * inject identity factories into this value.</p>
 */
public final class ClosureInvocationInput {

    /** Closed closure-operation discriminator. */
    public enum Operation {
        /** Execute external or managed-revision processing. */
        PROCESS_CLOSURE("process-closure"),
        /** Validate and admit one closed closure state. */
        ADMIT_CLOSURE("admit-closure");

        private final String wireValue;

        Operation(String wireValue) {
            this.wireValue = wireValue;
        }

        /**
         * Returns the documented value.
         *
         * @return stable operation wire value
         */
        public String wireValue() {
            return wireValue;
        }
    }

    private final Operation operation;
    private final String invocationIdentity;
    private final AffectedClosureSnapshot snapshot;
    private final ProcessingCause cause;
    private final AdmissionCandidate admissionCandidate;
    private final String admissionCandidateIdentity;
    private final List<DirectLogicalDelivery> directDeliveries;
    private final String directDeliverySnapshotIdentity;
    private final ExecutionPolicy executionPolicy;
    private final ClosureEnvironment environment;

    private ClosureInvocationInput(
            Operation operation,
            String invocationIdentity,
            AffectedClosureSnapshot snapshot,
            ProcessingCause cause,
            AdmissionCandidate admissionCandidate,
            String admissionCandidateIdentity,
            List<DirectLogicalDelivery> directDeliveries,
            String directDeliverySnapshotIdentity,
            ExecutionPolicy executionPolicy,
            ClosureEnvironment environment) {
        this.operation = Objects.requireNonNull(operation, "operation");
        this.invocationIdentity = ClosureValueSupport.requireSha256Identity(
                invocationIdentity, "invocationIdentity");
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.cause = Objects.requireNonNull(cause, "cause");
        if ((admissionCandidate == null)
                != (admissionCandidateIdentity == null)) {
            throw new IllegalArgumentException(
                    "Admission candidate and identity must be present together");
        }
        this.admissionCandidate = admissionCandidate;
        this.admissionCandidateIdentity = admissionCandidateIdentity == null
                ? null
                : ClosureValueSupport.requireSha256Identity(
                        admissionCandidateIdentity,
                        "admissionCandidateIdentity");
        this.directDeliveries = immutableCanonicalDeliveries(directDeliveries);
        this.directDeliverySnapshotIdentity =
                ClosureValueSupport.requireSha256Identity(
                        directDeliverySnapshotIdentity,
                        "directDeliverySnapshotIdentity");
        this.executionPolicy = Objects.requireNonNull(
                executionPolicy, "executionPolicy");
        this.environment = Objects.requireNonNull(environment, "environment");
        validateOperationShape();
        validateSnapshotReferences();
    }

    /**
     * Builds external or managed-revision processing with one closed input.
     *
     * @param invocationIdentity asserted invocation identity
     * @param snapshot authoritative closure state
     * @param cause external or managed-revision cause
     * @param directDeliveries canonical direct-delivery sequence
     * @param directDeliverySnapshotIdentity asserted delivery identity
     * @param executionPolicy invocation-owned execution policy
     * @param environment complete runtime and policy identity snapshot
     * @return immutable closed invocation
     */
    public static ClosureInvocationInput processClosure(
            String invocationIdentity,
            AffectedClosureSnapshot snapshot,
            ProcessingCause cause,
            List<DirectLogicalDelivery> directDeliveries,
            String directDeliverySnapshotIdentity,
            ExecutionPolicy executionPolicy,
            ClosureEnvironment environment) {
        return new ClosureInvocationInput(
                Operation.PROCESS_CLOSURE,
                invocationIdentity,
                snapshot,
                cause,
                null,
                null,
                directDeliveries,
                directDeliverySnapshotIdentity,
                executionPolicy,
                environment);
    }

    /**
     * Builds admission input with no direct-delivery sequence.
     *
     * <p>This factory constructs immutable invocation evidence only; it does
     * not execute admission. Conforming execution uses
     * {@link BlueClosureContracts#admitClosureWithLifecycleQueue(
     * ClosureInvocationInput)}.</p>
     *
     * @param invocationIdentity asserted invocation identity
     * @param snapshot authoritative closure state
     * @param cause admission cause
     * @param admissionCandidate nullable typed invalid-evidence candidate
     * @param admissionCandidateIdentity nullable candidate identity
     * @param emptyDirectDeliverySnapshotIdentity asserted empty-list identity
     * @param executionPolicy invocation-owned execution policy
     * @param environment complete runtime and policy identity snapshot
     * @return immutable closed admission
     */
    public static ClosureInvocationInput admitClosure(
            String invocationIdentity,
            AffectedClosureSnapshot snapshot,
            AdmissionCause cause,
            AdmissionCandidate admissionCandidate,
            String admissionCandidateIdentity,
            String emptyDirectDeliverySnapshotIdentity,
            ExecutionPolicy executionPolicy,
            ClosureEnvironment environment) {
        return new ClosureInvocationInput(
                Operation.ADMIT_CLOSURE,
                invocationIdentity,
                snapshot,
                cause,
                admissionCandidate,
                admissionCandidateIdentity,
                Collections.<DirectLogicalDelivery>emptyList(),
                emptyDirectDeliverySnapshotIdentity,
                executionPolicy,
                environment);
    }

    /**
     * Returns the documented value.
     *
     * @return closed operation discriminator
     */
    public Operation operation() {
        return operation;
    }

    /**
     * Returns the documented value.
     *
     * @return asserted complete invocation identity
     */
    public String invocationIdentity() {
        return invocationIdentity;
    }

    /**
     * Returns the documented value.
     *
     * @return authoritative state-only closure snapshot
     */
    public AffectedClosureSnapshot snapshot() {
        return snapshot;
    }

    /**
     * Returns the documented value.
     *
     * @return sole invocation cause
     */
    public ProcessingCause cause() {
        return cause;
    }

    /**
     * Returns the documented value.
     *
     * @return nullable typed admission candidate
     */
    public AdmissionCandidate admissionCandidate() {
        return admissionCandidate;
    }

    /**
     * Returns the documented value.
     *
     * @return nullable admission-candidate identity
     */
    public String admissionCandidateIdentity() {
        return admissionCandidateIdentity;
    }

    /**
     * Returns the documented value.
     *
     * @return immutable canonical direct-delivery sequence
     */
    public List<DirectLogicalDelivery> directDeliveries() {
        return directDeliveries;
    }

    /**
     * Returns the documented value.
     *
     * @return asserted complete direct-delivery-sequence identity
     */
    public String directDeliverySnapshotIdentity() {
        return directDeliverySnapshotIdentity;
    }

    /**
     * Returns the documented value.
     *
     * @return invocation-owned execution policy
     */
    public ExecutionPolicy executionPolicy() {
        return executionPolicy;
    }

    /**
     * Returns the documented value.
     *
     * @return complete runtime and policy identity snapshot
     */
    public ClosureEnvironment environment() {
        return environment;
    }

    /** Internal execution view owned by a verified resolution-bound retry. */
    ClosureInvocationInput withInvocationIdentity(String selectedIdentity) {
        return new ClosureInvocationInput(
                operation,
                selectedIdentity,
                snapshot,
                cause,
                admissionCandidate,
                admissionCandidateIdentity,
                directDeliveries,
                directDeliverySnapshotIdentity,
                executionPolicy,
                environment);
    }

    private void validateOperationShape() {
        if (operation == Operation.ADMIT_CLOSURE) {
            if (cause.kind() != ProcessingCause.Kind.ADMISSION
                    || !directDeliveries.isEmpty()) {
                throw new IllegalArgumentException(
                        "Admission requires an admission cause and no direct deliveries");
            }
            return;
        }
        if (cause.kind() == ProcessingCause.Kind.ADMISSION
                || admissionCandidate != null) {
            throw new IllegalArgumentException(
                    "Processing requires an external or managed-revision cause");
        }
        if (cause instanceof ManagedHistoryStep
                && !directDeliveries.isEmpty()) {
            throw new IllegalArgumentException(
                    "Managed-revision processing has no direct deliveries");
        }
    }

    private void validateSnapshotReferences() {
        for (DirectLogicalDelivery delivery : directDeliveries) {
            if (!snapshot.contains(delivery.targetDocumentId())) {
                throw new IllegalArgumentException(
                        "Direct delivery target is outside the affected closure");
            }
        }
        for (Map.Entry<DocumentId, Long> localLimit
                : executionPolicy.localLimits().entrySet()) {
            if (!snapshot.contains(localLimit.getKey())) {
                throw new IllegalArgumentException(
                        "Local execution cap targets a document outside the closure");
            }
        }
        if (cause instanceof ManagedHistoryStep) {
            validateManagedRevision((ManagedHistoryStep) cause);
        }
        if (cause instanceof ExternalEventCause
                && !((ExternalEventCause) cause)
                        .externalOrderPolicyIdentity()
                        .equals(environment.externalOrderPolicyIdentity())) {
            throw new IllegalArgumentException(
                    "External cause and environment order policies disagree");
        }
    }

    private void validateManagedRevision(ManagedHistoryStep revision) {
        if (!snapshot.contains(revision.childDocumentId())) {
            throw new IllegalArgumentException(
                    "Managed-revision child is outside the affected closure");
        }
        ManagedOccurrenceBinding target = null;
        for (ManagedOccurrenceBinding occurrence : snapshot.occurrences()) {
            if (occurrence.occurrenceIdentity().equals(
                    revision.targetOccurrenceIdentity())) {
                target = occurrence;
                break;
            }
        }
        if (target == null
                || !target.targetDocumentId().equals(
                    revision.childDocumentId())) {
            throw new IllegalArgumentException(
                    "Managed-revision cause does not name its target occurrence");
        }
        if (target.active()
                || target.pendingHistoricalEpoch() == null
                || target.pendingHistoricalEpoch().longValue()
                != revision.fromEpoch()
                || !target.expectedTargetBlueId().equals(
                        revision.beforeBlueId())) {
            throw new IllegalArgumentException(
                    "Managed-revision cause does not match the inactive "
                            + "occurrence history cursor");
        }
        ManagedRepresentationCursor cursor = target.pendingRepresentationCursor();
        if (revision instanceof ManagedRepresentationCause) {
            // A completed intermediate chain stays inactive only for its numbered successor.
            if (cursor != null && cursor.positionIdentity().equals(cursor.targetPositionIdentity())) {
                throw new IllegalArgumentException("Representation chain already reached its frozen target");
            }
            ManagedRepresentationCause representation = (ManagedRepresentationCause) revision;
            ManagedRepresentationTransition bridge = representation.transition();
            String predecessor = cursor == null ? bridge.anchorReceiptIdentity() : cursor.positionIdentity();
            if (!predecessor.equals(bridge.predecessorPositionIdentity())
                    || (cursor != null && (!cursor.anchorReceiptIdentity().equals(bridge.anchorReceiptIdentity())
                    || !cursor.targetPositionIdentity().equals(representation.targetPositionIdentity())
                    || !Objects.equals(cursor.nextRevisionReceiptIdentity(), representation.nextRevisionReceiptIdentity())))) {
                throw new IllegalArgumentException("Representation cause changed its exact historical position or frozen target");
            }
        } else if (cursor != null && (!cursor.positionIdentity().equals(cursor.targetPositionIdentity())
                || !revision.sourceRevisionReceiptIdentity().equals(cursor.nextRevisionReceiptIdentity()))) {
            throw new IllegalArgumentException("Numbered revision lacks its completed exact representation predecessor");
        }
        ManagedDocumentSnapshot child = snapshot.managedDocument(
                revision.childDocumentId());
        if (revision.toEpoch() > child.epoch()) {
            throw new IllegalArgumentException(
                    "Managed-revision cause is ahead of the child durable epoch");
        }
    }

    private static List<DirectLogicalDelivery> immutableCanonicalDeliveries(
            List<DirectLogicalDelivery> values) {
        ArrayList<DirectLogicalDelivery> copy = new ArrayList<DirectLogicalDelivery>(
                Objects.requireNonNull(values, "directDeliveries"));
        for (int index = 0; index < copy.size(); index++) {
            Objects.requireNonNull(copy.get(index), "direct delivery");
            if (index > 0
                    && copy.get(index - 1).compareTo(copy.get(index)) >= 0) {
                throw new IllegalArgumentException(
                        "Direct deliveries are not in canonical order");
            }
        }
        return Collections.unmodifiableList(copy);
    }
}
