package blue.language.processor.closure;

import blue.language.processor.ProcessorDiagnostic;
import blue.language.processor.ProcessorStatus;
import blue.language.snapshot.FrozenNode;
import java.util.*;

/** Authenticated terminal producer disposition. It is not a successful source action program. */
public final class SourceOperationFailure {
    private final String invocationIdentity, causeIdentity, gasTraceIdentity, rejectedChargeIdentity;
    private final ProcessingCause.Kind causeKind;
    private final ExternalEventCause externalCause;
    private final ClosureEnvironment environment;
    private final ExecutionPolicy executionPolicy;
    private final ProcessorStatus status;
    private final Set<DocumentId> owned;
    private final List<SourceObservationProgram.SourceState> predecessors;
    private final long totalGas;
    private final ProcessorDiagnostic diagnostic;
    private final ManagedReactionContext managedReaction;
    private final Map<DocumentId, List<String>> originalAttachmentSelections;
    private final List<SameOriginGroupEvidence.SourceEvidence> interpretedSourceEvidence;

    private SourceOperationFailure(ClosureInvocationInput input, ClosureProcessResult result, Set<DocumentId> owned) {
        this(input.invocationIdentity(), input.cause().kind(), input.cause().causeIdentity(),
                input.cause() instanceof ExternalEventCause ? (ExternalEventCause) input.cause() : null,
                input.environment(), input.executionPolicy(), result.status(), owned, states(input, owned), result.totalGas(),
                result.gasTraceIdentity(), result.rejectedCharge() == null ? null : result.rejectedCharge().rejectedChargeIdentity(), result.diagnostic(),
                input.managedReaction().orElse(null));
        if (input.operation() != ClosureInvocationInput.Operation.PROCESS_CLOSURE || !result.rollbackToInput()
                || !input.invocationIdentity().equals(result.invocationIdentity()))
            throw new IllegalArgumentException("Source failure requires its actual rolled-back processing result");
        for (ComponentSnapshot component : input.snapshot().components()) {
            boolean touches = false;
            for (DocumentId member : component.orderedMemberDocumentIds()) if (owned.contains(member)) touches = true;
            if (touches && !owned.containsAll(component.orderedMemberDocumentIds()))
                throw new IllegalArgumentException("Source failure cannot split a pre-cut component");
        }
    }

    /** Derives failure authority from a completed owning-library attempt, never a Java exception. */
    public static SourceOperationFailure fromProcessClosure(ClosureInvocationInput input, ClosureProcessResult result,
                                                            Set<DocumentId> ownedDocumentIds) {
        return new SourceOperationFailure(Objects.requireNonNull(input), Objects.requireNonNull(result), Objects.requireNonNull(ownedDocumentIds));
    }

    /** Derives independently atomic failure authority directly from the interpreter's immutable group result. */
    public static SourceOperationFailure fromSameOrigin(SameOriginOperationResult group) {
        Objects.requireNonNull(group, "group");
        ClosureInvocationInput origin = group.origin();
        if (origin.operation() != ClosureInvocationInput.Operation.PROCESS_CLOSURE || origin.cause().kind() != ProcessingCause.Kind.EXTERNAL
                || !group.failure().isPresent() || group.sourceProgram().isPresent())
            throw new IllegalArgumentException("Source failure requires an actual failed external group");
        Map<DocumentId, ResultingDocument> results = new HashMap<>();
        for (ResultingDocument result : group.resultingDocuments()) results.put(result.documentId(), result);
        List<SourceObservationProgram.SourceState> states = new ArrayList<>();
        Set<DocumentId> beforeOwners = new HashSet<>();
        for (ManagedDocumentSnapshot before : group.predecessors()) {
            ResultingDocument after = results.get(before.documentId());
            if (!beforeOwners.add(before.documentId()) || after == null || !before.initialized()
                    || !before.blueId().equals(after.afterBlueId()) || before.epoch() != after.epoch()
                    || before.initialized() != after.initialized() || before.terminated() != after.terminated())
                throw new IllegalArgumentException("Failed atomic group changed its successful predecessor");
            states.add(new SourceObservationProgram.SourceState(before.documentId(), before.blueId(), before.epoch(),
                    before.initialized(), FrozenNode.fromResolvedNode(before.document())));
        }
        if (!beforeOwners.equals(group.ownedDocumentIds())) throw new IllegalArgumentException("Failed group predecessor ownership mismatch");
        SameOriginOperationResult.Failure failure = group.failure().get();
        String rejected = failure.rejectedCharge().isPresent() ? SameOriginRejectedChargeEvidence.fromOperation(group).identity() : null;
        return new SourceOperationFailure(group.operationIdentity(), origin.cause().kind(), origin.cause().causeIdentity(),
                (ExternalEventCause) origin.cause(), origin.environment(), origin.executionPolicy(), group.status(), group.ownedDocumentIds(),
                states, group.totalGas(), group.gasTraceIdentity(), rejected, failure.diagnostic(), origin.managedReaction().orElse(null),
                group.originalAttachmentSelections(), group.interpretedSourceEvidence());
    }

    /** Codec hook: callers must already possess the authenticated committed manifest identity. */
    SourceOperationFailure(String invocationIdentity, ProcessingCause.Kind causeKind, String causeIdentity,
            ExternalEventCause externalCause, ClosureEnvironment environment, ExecutionPolicy executionPolicy,
            ProcessorStatus status, Set<DocumentId> owned, List<SourceObservationProgram.SourceState> predecessors,
            long totalGas, String gasTraceIdentity, String rejectedChargeIdentity, ProcessorDiagnostic diagnostic) {
        this(invocationIdentity, causeKind, causeIdentity, externalCause, environment, executionPolicy, status, owned, predecessors,
                totalGas, gasTraceIdentity, rejectedChargeIdentity, diagnostic, null);
    }

    SourceOperationFailure(String invocationIdentity, ProcessingCause.Kind causeKind, String causeIdentity,
            ExternalEventCause externalCause, ClosureEnvironment environment, ExecutionPolicy executionPolicy,
            ProcessorStatus status, Set<DocumentId> owned, List<SourceObservationProgram.SourceState> predecessors,
            long totalGas, String gasTraceIdentity, String rejectedChargeIdentity, ProcessorDiagnostic diagnostic, ManagedReactionContext managedReaction) {
        this(invocationIdentity, causeKind, causeIdentity, externalCause, environment, executionPolicy, status, owned,
                predecessors, totalGas, gasTraceIdentity, rejectedChargeIdentity, diagnostic, managedReaction, null);
    }

    SourceOperationFailure(String invocationIdentity, ProcessingCause.Kind causeKind, String causeIdentity,
            ExternalEventCause externalCause, ClosureEnvironment environment, ExecutionPolicy executionPolicy,
            ProcessorStatus status, Set<DocumentId> owned, List<SourceObservationProgram.SourceState> predecessors,
            long totalGas, String gasTraceIdentity, String rejectedChargeIdentity, ProcessorDiagnostic diagnostic,
            ManagedReactionContext managedReaction, Map<DocumentId, List<String>> originalAttachmentSelections) {
        this(invocationIdentity, causeKind, causeIdentity, externalCause, environment, executionPolicy, status, owned,
                predecessors, totalGas, gasTraceIdentity, rejectedChargeIdentity, diagnostic, managedReaction,
                originalAttachmentSelections, Collections.emptyList());
    }

    SourceOperationFailure(String invocationIdentity, ProcessingCause.Kind causeKind, String causeIdentity,
            ExternalEventCause externalCause, ClosureEnvironment environment, ExecutionPolicy executionPolicy,
            ProcessorStatus status, Set<DocumentId> owned, List<SourceObservationProgram.SourceState> predecessors,
            long totalGas, String gasTraceIdentity, String rejectedChargeIdentity, ProcessorDiagnostic diagnostic,
            ManagedReactionContext managedReaction, Map<DocumentId, List<String>> originalAttachmentSelections,
            List<SameOriginGroupEvidence.SourceEvidence> interpretedSourceEvidence) {
        this.interpretedSourceEvidence = SameOriginGroupEvidence.canonicalSourceEvidence(interpretedSourceEvidence);
        this.originalAttachmentSelections = SameOriginAttachmentPolicy.freezeOriginalSelections(originalAttachmentSelections, owned);
        this.invocationIdentity = ClosureValueSupport.requireSha256Identity(invocationIdentity, "failedInvocation");
        this.causeIdentity = ClosureValueSupport.requireSha256Identity(causeIdentity, "failedCause");
        this.causeKind = Objects.requireNonNull(causeKind, "causeKind"); this.externalCause = externalCause;
        if (status != ProcessorStatus.GAS_LIMIT_EXCEEDED && status != ProcessorStatus.RUNTIME_FATAL)
            throw new IllegalArgumentException("Only recognized terminal source processing failures are consumable");
        if ((causeKind == ProcessingCause.Kind.EXTERNAL) != (externalCause != null)
                || externalCause != null && !externalCause.causeIdentity().equals(causeIdentity))
            throw new IllegalArgumentException("Failed source cause descriptor mismatch");
        this.status = status; this.environment = Objects.requireNonNull(environment); this.executionPolicy = Objects.requireNonNull(executionPolicy);
        this.owned = Collections.unmodifiableSet(new TreeSet<DocumentId>(owned));
        this.predecessors = Collections.unmodifiableList(new ArrayList<SourceObservationProgram.SourceState>(predecessors));
        Set<DocumentId> present = new HashSet<DocumentId>();
        for (SourceObservationProgram.SourceState state : predecessors) if (!present.add(state.documentId()))
            throw new IllegalArgumentException("Duplicate failed source predecessor");
        if (owned.isEmpty() || !present.containsAll(owned)) throw new IllegalArgumentException("Missing failed source ownership evidence");
        this.totalGas = ClosureValueSupport.requireSafeInteger(totalGas, "failedSourceGas");
        this.gasTraceIdentity = ClosureValueSupport.requireSha256Identity(gasTraceIdentity, "failedSourceTrace");
        this.rejectedChargeIdentity = rejectedChargeIdentity == null ? null
                : ClosureValueSupport.requireSha256Identity(rejectedChargeIdentity, "rejectedCharge");
        if ((status == ProcessorStatus.GAS_LIMIT_EXCEEDED) != (rejectedChargeIdentity != null))
            throw new IllegalArgumentException("Rejected charge belongs exactly to gas exhaustion");
        this.diagnostic = diagnostic;
        this.managedReaction = managedReaction;
    }

    public String invocationIdentity() { return invocationIdentity; }
    public ProcessingCause.Kind causeKind() { return causeKind; }
    public String causeIdentity() { return causeIdentity; }
    public ExternalEventCause externalCause() { return externalCause; }
    public ClosureEnvironment environment() { return environment; }
    public ExecutionPolicy executionPolicy() { return executionPolicy; }
    public ProcessorStatus status() { return status; }
    public Set<DocumentId> ownedDocumentIds() { return owned; }
    public List<SourceObservationProgram.SourceState> sourcePredecessors() { return predecessors; }
    public long totalGas() { return totalGas; }
    public String gasTraceIdentity() { return gasTraceIdentity; }
    public String rejectedChargeIdentity() { return rejectedChargeIdentity; }
    public ProcessorDiagnostic diagnostic() { return diagnostic; }
    public Optional<ManagedReactionContext> managedReaction() { return Optional.ofNullable(managedReaction); }
    /** Original seed choices, including unused choices and explicit empty; present for same-origin producers. */
    public Optional<Map<DocumentId, List<String>>> originalAttachmentSelections() { return Optional.ofNullable(originalAttachmentSelections); }
    public List<SameOriginGroupEvidence.SourceEvidence> interpretedSourceEvidence() { return interpretedSourceEvidence; }

    /** Checks a consumer's exact successful pins without creating or metering an invocation. */
    public void verifyObservationBasis(AffectedClosureSnapshot snapshot, Set<DocumentId> consumerOwned,
            Map<DocumentId, List<SourceObservationGap>> gaps) {
        Objects.requireNonNull(snapshot, "snapshot"); Objects.requireNonNull(consumerOwned, "consumerOwned");
        Objects.requireNonNull(gaps, "gaps");
        for (SourceObservationProgram.SourceState source : predecessors) {
            if (!owned.contains(source.documentId())) continue;
            ManagedDocumentSnapshot observed = snapshot.managedDocument(source.documentId());
            if (observed == null || consumerOwned.contains(source.documentId()))
                throw new IllegalArgumentException("Failed source operation has conflicting observation ownership");
            if (!source.blueId().equals(observed.blueId()) || source.epoch() != observed.epoch())
                SourceObservationGap.verifyContinuity(consumerOwned, observed, source,
                        gaps.containsKey(source.documentId()) ? gaps.get(source.documentId()) : Collections.<SourceObservationGap>emptyList());
        }
    }

    private static List<SourceObservationProgram.SourceState> states(ClosureInvocationInput input, Set<DocumentId> owned) {
        List<SourceObservationProgram.SourceState> states = new ArrayList<SourceObservationProgram.SourceState>();
        for (ManagedDocumentSnapshot state : input.snapshot().managedDocuments()) if (owned.contains(state.documentId()))
            states.add(new SourceObservationProgram.SourceState(state.documentId(), state.blueId(), state.epoch(),
                    state.initialized(), FrozenNode.fromResolvedNode(state.document())));
        return states;
    }
}
