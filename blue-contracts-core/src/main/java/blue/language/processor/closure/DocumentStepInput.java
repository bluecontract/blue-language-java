package blue.language.processor.closure;

import blue.language.model.Node;
import blue.language.processor.FrozenJsonPatch;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** One exact isolated managed-document execution input. */
public final class DocumentStepInput {

    private final long stepOrdinal;
    private final ClosureWorkOccurrence work;
    private final ManagedDocumentSnapshot targetDocument;
    private final Node exactPayload;
    private final String matchingEventBlueId;
    private final Node occurrenceEvent;
    private final FrozenJsonPatch processorPatch;
    private final TentativeResolutionContext resolutionContext;

    /**
     * Creates a Root-scoped step for exactly one managed document.
     *
     * @param stepOrdinal contiguous invocation-local step ordinal
     * @param work exact accepted work occurrence
     * @param targetDocument latest exact target state
     * @param exactPayload exact work payload
     * @param matchingEventBlueId admitted identity of the semantic value that
     *        handlers match, or {@code null} for non-handler work
     * @param resolutionContext freshly reconstructed private resolver view
     */
    public DocumentStepInput(
            long stepOrdinal,
            ClosureWorkOccurrence work,
            ManagedDocumentSnapshot targetDocument,
            Node exactPayload,
            String matchingEventBlueId,
            TentativeResolutionContext resolutionContext) {
        this(stepOrdinal, work, targetDocument, exactPayload,
                matchingEventBlueId,
                null, null, resolutionContext);
    }

    /**
     * Creates a Root-scoped step with closed processor-managed evidence.
     *
     * @param stepOrdinal contiguous invocation-local step ordinal
     * @param work exact accepted work occurrence
     * @param targetDocument latest exact target state
     * @param exactPayload exact work payload or adapter wrapper
     * @param matchingEventBlueId admitted identity of the semantic value that
     *        handlers match, or {@code null} for non-handler work
     * @param occurrenceEvent originating semantic event for embedded work,
     *        otherwise {@code null}
     * @param processorPatch exact patch for containing-reference work,
     *        otherwise {@code null}
     * @param resolutionContext freshly reconstructed private resolver view
     */
    public DocumentStepInput(
            long stepOrdinal,
            ClosureWorkOccurrence work,
            ManagedDocumentSnapshot targetDocument,
            Node exactPayload,
            String matchingEventBlueId,
            Node occurrenceEvent,
            FrozenJsonPatch processorPatch,
            TentativeResolutionContext resolutionContext) {
        this.stepOrdinal = ClosureValueSupport.requireSafeInteger(
                stepOrdinal, "stepOrdinal");
        this.work = Objects.requireNonNull(work, "work");
        this.targetDocument = Objects.requireNonNull(
                targetDocument, "targetDocument");
        this.exactPayload = Objects.requireNonNull(
                exactPayload, "exactPayload").clone();
        this.matchingEventBlueId = matchingEventBlueId == null
                ? null
                : ClosureValueSupport.requireBlueId(
                        matchingEventBlueId,
                        "matchingEventBlueId");
        this.occurrenceEvent = occurrenceEvent != null
                ? occurrenceEvent.clone()
                : null;
        this.processorPatch = processorPatch;
        this.resolutionContext = Objects.requireNonNull(
                resolutionContext, "resolutionContext");
        validateManagedEvidenceShape();
        if (!this.work.targetDocumentId().equals(
                    this.targetDocument.documentId())
                || !this.work.targetDocumentId().equals(
                    this.resolutionContext.targetDocumentId())
                || !this.targetDocument.blueId().equals(
                    this.resolutionContext.targetBeforeBlueId())
                || !this.work.targetManagedScopeKey().isRoot()) {
            throw new IllegalArgumentException(
                    "Work, target document and resolution context disagree");
        }
    }

    /** Returns the contiguous step ordinal.
     * @return step ordinal */
    public long stepOrdinal() { return stepOrdinal; }

    /** Returns the exact accepted work.
     * @return work occurrence */
    public ClosureWorkOccurrence work() { return work; }

    /** Returns a defensive target-state copy.
     * @return target state */
    public ManagedDocumentSnapshot targetDocument() {
        return new ManagedDocumentSnapshot(
                targetDocument.documentId(),
                targetDocument.blueId(),
                targetDocument.document(),
                targetDocument.initialized(),
                targetDocument.terminated(),
                targetDocument.publicRoot(),
                targetDocument.epoch(),
                targetDocument.componentGeneration());
    }

    /** Returns a defensive exact payload copy.
     * @return work payload */
    public Node exactPayload() { return exactPayload.clone(); }

    /**
     * Returns the admission-proved identity used for handler matching.
     *
     * @return exact identity, including a cyclic-member identity, or
     *         {@code null} for work that invokes no handler
     */
    public String matchingEventBlueId() { return matchingEventBlueId; }

    /**
     * Returns the originating event behind an embedded adapter payload.
     *
     * @return defensive event copy, or {@code null} for other work kinds
     */
    public Node occurrenceEvent() {
        return occurrenceEvent != null ? occurrenceEvent.clone() : null;
    }

    /**
     * Returns the processor-managed containing-reference patch.
     *
     * @return immutable patch, or {@code null} for other work kinds
     */
    public FrozenJsonPatch processorPatch() { return processorPatch; }

    /** Returns the private exact resolver context.
     * @return resolution context */
    public TentativeResolutionContext resolutionContext() {
        return resolutionContext;
    }

    /** Returns the only execution scope.
     * @return Root scope address */
    public ScopeAddress executionScope() { return ScopeAddress.root(); }

    /**
     * Returns no reverse-containment values.
     * @return an empty immutable list
     */
    public List<DocumentId> ambientContainingDocumentIds() {
        return Collections.emptyList();
    }

    private void validateManagedEvidenceShape() {
        boolean embedded = work.kind() == WorkKind.EMBEDDED_EVENT;
        boolean containing = work.kind()
                == WorkKind.CONTAINING_REFERENCE_UPDATE;
        boolean handlerDelivery = work.kind() == WorkKind.EXTERNAL_DELIVERY
                || work.kind() == WorkKind.DOCUMENT_UPDATE
                || work.kind() == WorkKind.TRIGGERED_EVENT
                || work.kind() == WorkKind.EMBEDDED_EVENT
                || work.kind() == WorkKind.LIFECYCLE;
        if (embedded != (occurrenceEvent != null)) {
            throw new IllegalArgumentException(
                    "Only embedded-event work requires occurrenceEvent");
        }
        if (containing != (processorPatch != null)) {
            throw new IllegalArgumentException(
                    "Only containing-reference work requires processorPatch");
        }
        if (handlerDelivery != (matchingEventBlueId != null)) {
            throw new IllegalArgumentException(
                    "Handler work requires matchingEventBlueId and "
                            + "non-handler work forbids it");
        }
    }
}
