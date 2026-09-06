package blue.language.processor.closure;

import blue.language.processor.ProcessorStatus;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A consumed managed-observation failure, derived from actual library evidence.
 * It proves that an offered successful source operation was not installed in
 * this consumer's successful view. It does not invent a successful import.
 */
public final class SourceObservationGap {
    private final DocumentId consumer;
    private final DocumentId source;
    private final String failedInvocationIdentity;
    private final String offeredSourceOperation;
    private final String observedBlueId;
    private final long observedEpoch;
    private final SourceObservationProgram.SourceState offeredBefore;
    private final SourceObservationProgram.SourceState offeredAfter;

    private SourceObservationGap(DocumentId consumer, DocumentId source,
                                 ClosureInvocationInput input, ClosureProcessResult failure,
                                 SourceObservationProgram program) {
        this.consumer = Objects.requireNonNull(consumer, "consumer");
        this.source = Objects.requireNonNull(source, "source");
        Objects.requireNonNull(input, "failedInvocation");
        Objects.requireNonNull(failure, "failedResult");
        Objects.requireNonNull(program, "offeredSourceProgram");
        if (consumer.equals(source) || !failure.rollbackToInput()
                || failure.status() != ProcessorStatus.GAS_LIMIT_EXCEEDED && failure.status() != ProcessorStatus.RUNTIME_FATAL
                || !input.invocationIdentity().equals(failure.invocationIdentity())
                || !input.cause().causeIdentity().equals(program.causeIdentity())
                || input.snapshot().managedDocument(consumer) == null
                || !program.ownedDocumentIds().contains(source)) {
            throw new IllegalArgumentException("Gap requires an exact terminal managed-observation failure");
        }
        ManagedDocumentSnapshot observed = input.snapshot().managedDocument(source);
        if (observed == null) throw new IllegalArgumentException("Failed observation has no exact source pin");
        this.observedBlueId = observed.blueId();
        this.observedEpoch = observed.epoch();
        this.failedInvocationIdentity = failure.invocationIdentity();
        this.offeredSourceOperation = program.invocationIdentity();
        this.offeredBefore = state(program.sourcePredecessors(), source);
        this.offeredAfter = state(program.sourceResults(), source);
    }

    /** Only the managed lane's gas/recognized-runtime failures consume this offer. */
    public static SourceObservationGap fromManagedFailure(DocumentId consumer, DocumentId source,
            ClosureInvocationInput failedInvocation, ClosureProcessResult failedResult,
            SourceObservationProgram offeredSourceProgram) {
        return new SourceObservationGap(consumer, source, failedInvocation, failedResult, offeredSourceProgram);
    }

    /**
     * Reconstitutes a gap from authenticated committed managed-failure evidence.
     * The caller must authenticate the terminal receipt, its owned consumer, the
     * observed source pin and consumed source operation before calling this hook.
     * An arbitrary self-hashed record is not execution authority.
     */
    public static SourceObservationGap fromAuthenticatedManagedFailure(DocumentId consumer, DocumentId source,
            String failedInvocationIdentity, String causeIdentity, ProcessorStatus status,
            String observedBlueId, long observedEpoch, SourceObservationProgram offeredSourceProgram) {
        return new SourceObservationGap(consumer, source, failedInvocationIdentity, causeIdentity,
                status, observedBlueId, observedEpoch, offeredSourceProgram);
    }

    private SourceObservationGap(DocumentId consumer, DocumentId source, String failedInvocationIdentity,
            String causeIdentity, ProcessorStatus status, String observedBlueId, long observedEpoch,
            SourceObservationProgram program) {
        this.consumer = Objects.requireNonNull(consumer, "consumer");
        this.source = Objects.requireNonNull(source, "source");
        Objects.requireNonNull(program, "offeredSourceProgram");
        if (consumer.equals(source) || status != ProcessorStatus.GAS_LIMIT_EXCEEDED && status != ProcessorStatus.RUNTIME_FATAL
                || !program.causeIdentity().equals(causeIdentity) || !program.ownedDocumentIds().contains(source))
            throw new IllegalArgumentException("Gap requires an authenticated terminal managed-observation failure");
        this.failedInvocationIdentity = ClosureValueSupport.requireSha256Identity(failedInvocationIdentity, "failedInvocationIdentity");
        this.observedBlueId = ClosureValueSupport.requireBlueId(observedBlueId, "observedBlueId");
        this.observedEpoch = ClosureValueSupport.requireSafeInteger(observedEpoch, "observedEpoch");
        this.offeredSourceOperation = program.invocationIdentity();
        this.offeredBefore = state(program.sourcePredecessors(), source);
        this.offeredAfter = state(program.sourceResults(), source);
    }

    public DocumentId consumer() { return consumer; }
    public DocumentId source() { return source; }
    public String failedInvocationIdentity() { return failedInvocationIdentity; }
    public String offeredSourceOperation() { return offeredSourceOperation; }
    public String observedBlueId() { return observedBlueId; }
    public long observedEpoch() { return observedEpoch; }
    public SourceObservationProgram.SourceState offeredBefore() { return offeredBefore; }
    public SourceObservationProgram.SourceState offeredAfter() { return offeredAfter; }

    static void verifyContinuity(Set<DocumentId> owned, ManagedDocumentSnapshot observed,
                                 SourceObservationProgram.SourceState requiredBefore,
                                 List<SourceObservationGap> gaps) {
        String expectedBlueId = observed.blueId();
        long expectedEpoch = observed.epoch();
        for (SourceObservationGap gap : gaps) {
            if (owned == null || !owned.contains(gap.consumer)
                    || !observed.documentId().equals(gap.source)
                    || !observed.blueId().equals(gap.observedBlueId) || observed.epoch() != gap.observedEpoch
                    || !expectedBlueId.equals(gap.offeredBefore.blueId()) || expectedEpoch != gap.offeredBefore.epoch()) {
                throw new IllegalArgumentException("Managed failure gap does not continue the exact observed source pin");
            }
            expectedBlueId = gap.offeredAfter.blueId();
            expectedEpoch = gap.offeredAfter.epoch();
        }
        if (gaps.isEmpty() || !expectedBlueId.equals(requiredBefore.blueId()) || expectedEpoch != requiredBefore.epoch()) {
            throw new IllegalArgumentException("Missing lawfully consumed source gap before alignment");
        }
    }

    private static SourceObservationProgram.SourceState state(List<SourceObservationProgram.SourceState> states, DocumentId id) {
        for (SourceObservationProgram.SourceState state : states) if (state.documentId().equals(id)) return state;
        throw new IllegalArgumentException("Source program is missing owned lineage evidence");
    }
}
