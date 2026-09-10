package blue.language.processor.closure;

import blue.language.identity.BlueIds;
import blue.language.model.Node;
import blue.language.provider.CyclicSetProof;

import java.util.Objects;
import java.util.Optional;

/**
 * Exact authenticated transition for one contiguous managed-document epoch.
 *
 * <p>A cyclic successor carries its complete historical cyclic-set proof as
 * invocation evidence.  The proof is required exactly for a cyclic-member
 * {@code afterBlueId}; an ordinary successor has no proof.</p>
 */
public final class ManagedRevisionCause extends ProcessingCause implements ManagedHistoryStep {

    private final String targetOccurrenceIdentity;
    private final DocumentId childDocumentId;
    private final long fromEpoch;
    private final long toEpoch;
    private final String beforeBlueId;
    private final String afterBlueId;
    private final Node afterDocument;
    private final String originalSourceCauseIdentity;
    private final String sourceRevisionReceiptIdentity;
    private final ManagedDocumentTransitionReceipt sourceTransitionReceipt;
    private final CyclicSetProof afterCyclicProof;
    private final ManagedRepresentationCause successorRepresentationCause;

    /**
     * Creates one contiguous authenticated managed revision.
     *
     * @param causeIdentity exact cause identity
     * @param targetOccurrenceIdentity stable target occurrence identity
     * @param childDocumentId revised child lineage
     * @param fromEpoch exact predecessor epoch; {@code -1} denotes the
     *     authored pre-initialization value before epoch zero
     * @param toEpoch exact successor epoch
     * @param beforeBlueId exact predecessor BlueId
     * @param afterBlueId exact successor BlueId
     * @param afterDocument exact successor document
     * @param originalSourceCauseIdentity originating cause identity
     * @param sourceRevisionReceiptIdentity authenticated receipt identity
     */
    public ManagedRevisionCause(
            String causeIdentity,
            String targetOccurrenceIdentity,
            DocumentId childDocumentId,
            long fromEpoch,
            long toEpoch,
            String beforeBlueId,
            String afterBlueId,
            Node afterDocument,
            String originalSourceCauseIdentity,
            String sourceRevisionReceiptIdentity) {
        this(
                causeIdentity,
                targetOccurrenceIdentity,
                childDocumentId,
                fromEpoch,
                toEpoch,
                beforeBlueId,
                afterBlueId,
                afterDocument,
                originalSourceCauseIdentity,
                sourceRevisionReceiptIdentity,
                null,
                null);
    }

    /**
     * Creates one contiguous authenticated managed revision whose successor
     * may be a cyclic-set member.
     *
     * <p>The proof is required exactly when {@code afterBlueId} is a cyclic
     * member identity.  It is immutable invocation evidence and does not alter
     * the established managed-revision cause identity.</p>
     *
     * @param causeIdentity exact cause identity
     * @param targetOccurrenceIdentity stable target occurrence identity
     * @param childDocumentId revised child lineage
     * @param fromEpoch exact predecessor revision cursor
     * @param toEpoch exact successor revision cursor
     * @param beforeBlueId exact predecessor BlueId
     * @param afterBlueId exact successor BlueId
     * @param afterDocument exact successor document
     * @param originalSourceCauseIdentity originating cause identity
     * @param sourceRevisionReceiptIdentity authenticated receipt identity
     * @param afterCyclicProof complete successor cyclic-set proof, or
     *     {@code null} exactly for an ordinary successor
     */
    public ManagedRevisionCause(
            String causeIdentity,
            String targetOccurrenceIdentity,
            DocumentId childDocumentId,
            long fromEpoch,
            long toEpoch,
            String beforeBlueId,
            String afterBlueId,
            Node afterDocument,
            String originalSourceCauseIdentity,
            String sourceRevisionReceiptIdentity,
            CyclicSetProof afterCyclicProof) {
        this(
                causeIdentity,
                targetOccurrenceIdentity,
                childDocumentId,
                fromEpoch,
                toEpoch,
                beforeBlueId,
                afterBlueId,
                afterDocument,
                originalSourceCauseIdentity,
                sourceRevisionReceiptIdentity,
                null,
                afterCyclicProof);
    }

    /**
     * Creates one contiguous managed revision authenticated by the complete
     * source transition receipt whose events must be delivered.
     *
     * @param causeIdentity exact cause identity
     * @param targetOccurrenceIdentity stable target occurrence identity
     * @param childDocumentId revised child lineage
     * @param fromEpoch exact predecessor revision cursor; {@code -1} denotes
     *     the authored pre-initialization value before epoch zero
     * @param toEpoch exact successor revision cursor
     * @param beforeBlueId exact predecessor BlueId
     * @param afterBlueId exact successor BlueId
     * @param afterDocument exact successor document
     * @param originalSourceCauseIdentity originating cause identity
     * @param sourceTransitionReceipt complete authenticated source transition
     */
    public ManagedRevisionCause(
            String causeIdentity,
            String targetOccurrenceIdentity,
            DocumentId childDocumentId,
            long fromEpoch,
            long toEpoch,
            String beforeBlueId,
            String afterBlueId,
            Node afterDocument,
            String originalSourceCauseIdentity,
            ManagedDocumentTransitionReceipt sourceTransitionReceipt) {
        this(
                causeIdentity,
                targetOccurrenceIdentity,
                childDocumentId,
                fromEpoch,
                toEpoch,
                beforeBlueId,
                afterBlueId,
                afterDocument,
                originalSourceCauseIdentity,
                Objects.requireNonNull(
                        sourceTransitionReceipt,
                        "sourceTransitionReceipt")
                        .transitionReceiptIdentity(),
                sourceTransitionReceipt,
                null);
    }

    /**
     * Creates one typed-receipt managed revision whose successor may be a
     * cyclic-set member.
     *
     * @param causeIdentity exact cause identity
     * @param targetOccurrenceIdentity stable target occurrence identity
     * @param childDocumentId revised child lineage
     * @param fromEpoch exact predecessor revision cursor
     * @param toEpoch exact successor revision cursor
     * @param beforeBlueId exact predecessor BlueId
     * @param afterBlueId exact successor BlueId
     * @param afterDocument exact successor document
     * @param originalSourceCauseIdentity originating cause identity
     * @param sourceTransitionReceipt complete authenticated source transition
     * @param afterCyclicProof complete successor cyclic-set proof, or
     *     {@code null} exactly for an ordinary successor
     */
    public ManagedRevisionCause(
            String causeIdentity,
            String targetOccurrenceIdentity,
            DocumentId childDocumentId,
            long fromEpoch,
            long toEpoch,
            String beforeBlueId,
            String afterBlueId,
            Node afterDocument,
            String originalSourceCauseIdentity,
            ManagedDocumentTransitionReceipt sourceTransitionReceipt,
            CyclicSetProof afterCyclicProof) {
        this(
                causeIdentity,
                targetOccurrenceIdentity,
                childDocumentId,
                fromEpoch,
                toEpoch,
                beforeBlueId,
                afterBlueId,
                afterDocument,
                originalSourceCauseIdentity,
                Objects.requireNonNull(
                        sourceTransitionReceipt,
                        "sourceTransitionReceipt")
                        .transitionReceiptIdentity(),
                sourceTransitionReceipt,
                afterCyclicProof);
    }

    private ManagedRevisionCause(
            String causeIdentity,
            String targetOccurrenceIdentity,
            DocumentId childDocumentId,
            long fromEpoch,
            long toEpoch,
            String beforeBlueId,
            String afterBlueId,
            Node afterDocument,
            String originalSourceCauseIdentity,
            String sourceRevisionReceiptIdentity,
            ManagedDocumentTransitionReceipt sourceTransitionReceipt,
            CyclicSetProof afterCyclicProof) {
        this(causeIdentity, targetOccurrenceIdentity, childDocumentId, fromEpoch, toEpoch,
                beforeBlueId, afterBlueId, afterDocument, originalSourceCauseIdentity,
                sourceRevisionReceiptIdentity, sourceTransitionReceipt, afterCyclicProof, null);
    }

    private ManagedRevisionCause(String causeIdentity, String targetOccurrenceIdentity,
            DocumentId childDocumentId, long fromEpoch, long toEpoch, String beforeBlueId,
            String afterBlueId, Node afterDocument, String originalSourceCauseIdentity,
            String sourceRevisionReceiptIdentity, ManagedDocumentTransitionReceipt sourceTransitionReceipt,
            CyclicSetProof afterCyclicProof, ManagedRepresentationCause successorRepresentationCause) {
        super(causeIdentity);
        this.targetOccurrenceIdentity =
                ClosureValueSupport.requireSha256Identity(
                        targetOccurrenceIdentity,
                        "targetOccurrenceIdentity");
        this.childDocumentId = Objects.requireNonNull(
                childDocumentId, "childDocumentId");
        this.fromEpoch = ClosureValueSupport.requireManagedEpochCursor(
                fromEpoch, "fromEpoch");
        this.toEpoch = ClosureValueSupport.requireSafeInteger(
                toEpoch, "toEpoch");
        if (fromEpoch == ClosureValueSupport.MAX_SAFE_INTEGER
                || toEpoch != fromEpoch + 1L) {
            throw new IllegalArgumentException(
                    "toEpoch must equal fromEpoch + 1");
        }
        this.beforeBlueId = ClosureValueSupport.requireBlueId(
                beforeBlueId, "beforeBlueId");
        this.afterBlueId = ClosureValueSupport.requireBlueId(
                afterBlueId, "afterBlueId");
        this.afterDocument = Objects.requireNonNull(
                afterDocument, "afterDocument").clone();
        this.originalSourceCauseIdentity =
                ClosureValueSupport.requireSha256Identity(
                        originalSourceCauseIdentity,
                        "originalSourceCauseIdentity");
        this.sourceRevisionReceiptIdentity =
                ClosureValueSupport.requireSha256Identity(
                        sourceRevisionReceiptIdentity,
                        "sourceRevisionReceiptIdentity");
        this.sourceTransitionReceipt = sourceTransitionReceipt;
        this.successorRepresentationCause = successorRepresentationCause;
        boolean cyclicAfter = BlueIds.hasCyclicMemberSeparator(
                this.afterBlueId);
        if (cyclicAfter) {
            BlueIds.requireBlueIdOrCyclicMember(
                    this.afterBlueId, "afterBlueId");
        }
        if (cyclicAfter != (afterCyclicProof != null)) {
            throw new IllegalArgumentException(
                    "A cyclic successor requires exactly one complete cyclic-set proof");
        }
        this.afterCyclicProof = copyProof(afterCyclicProof);
        if (sourceTransitionReceipt != null) {
            verifyTypedReceipt(sourceTransitionReceipt);
        }
        verifySuccessorRepresentationCause();
    }

    /**
     * Binds the first future step of a host-authenticated terminal representation tail.
     * This numbered cause still executes exactly one numbered revision.
     * The host must authenticate the entire captured prefix and its frozen source view.
     * @param successor complete first representation cause, used only as future evidence
     * @return a new numbered cause with an identity-bound successor goal
     */
    public ManagedRevisionCause withSuccessorRepresentationCause(ManagedRepresentationCause successor) {
        if (successorRepresentationCause != null) {
            throw new IllegalStateException("A numbered successor goal is already frozen");
        }
        ManagedRepresentationCause selected = Objects.requireNonNull(successor, "successor");
        String identity = ClosureIdentityService.INSTANCE.managedRevisionCauseWithRepresentationSuccessorIdentity(
                targetOccurrenceIdentity, childDocumentId, fromEpoch, toEpoch, beforeBlueId,
                afterBlueId, originalSourceCauseIdentity, sourceRevisionReceiptIdentity, selected.causeIdentity());
        return new ManagedRevisionCause(identity, targetOccurrenceIdentity, childDocumentId, fromEpoch, toEpoch,
                beforeBlueId, afterBlueId, afterDocument, originalSourceCauseIdentity,
                sourceRevisionReceiptIdentity, sourceTransitionReceipt, afterCyclicProof, selected);
    }

    /**
     * Returns the first future representation step without executing or consuming it.
     * @return optional complete successor evidence; empty for unchanged ordinary causes
     */
    public Optional<ManagedRepresentationCause> successorRepresentationCause() {
        return Optional.ofNullable(successorRepresentationCause);
    }

    void verifySuccessorRepresentationCause() {
        if (successorRepresentationCause == null) return;
        ManagedRepresentationCause successor = successorRepresentationCause;
        ManagedRepresentationTransition first = successor.transition();
        if (sourceTransitionReceipt == null
                || !successor.causeIdentity().equals(successor.recomputedIdentity())
                || !targetOccurrenceIdentity.equals(successor.targetOccurrenceIdentity())
                || !childDocumentId.equals(successor.childDocumentId())
                || successor.fromEpoch() != toEpoch || successor.toEpoch() != toEpoch
                || !afterBlueId.equals(successor.beforeBlueId())
                || !first.anchorReceiptIdentity().equals(first.predecessorPositionIdentity())
                || first.anchorReceiptIdentity().equals(successor.targetPositionIdentity())
                || successor.nextRevisionReceiptIdentity() != null) {
            throw new IllegalArgumentException("Numbered successor is not the authenticated first terminal-tail position");
        }
    }

    /**
     * Returns the documented value.
     *
     * @return {@link Kind#MANAGED_REVISION}
     */
    @Override
    public Kind kind() {
        return Kind.MANAGED_REVISION;
    }

    /**
     * Returns the documented value.
     *
     * @return target occurrence identity
     */
    public String targetOccurrenceIdentity() {
        return targetOccurrenceIdentity;
    }

    /**
     * Returns the documented value.
     *
     * @return revised child lineage
     */
    public DocumentId childDocumentId() {
        return childDocumentId;
    }

    /**
     * Returns the documented value.
     *
     * @return predecessor epoch, with {@code -1} denoting the authored
     *     pre-initialization value
     */
    public long fromEpoch() {
        return fromEpoch;
    }

    /**
     * Returns the documented value.
     *
     * @return successor epoch
     */
    public long toEpoch() {
        return toEpoch;
    }

    /**
     * Returns the documented value.
     *
     * @return predecessor BlueId
     */
    public String beforeBlueId() {
        return beforeBlueId;
    }

    /**
     * Returns the documented value.
     *
     * @return successor BlueId
     */
    public String afterBlueId() {
        return afterBlueId;
    }

    /**
     * Returns the documented value.
     *
     * @return defensive copy of the exact successor document
     */
    public Node afterDocument() {
        return afterDocument.clone();
    }

    /**
     * Returns the documented value.
     *
     * @return originating source-cause identity
     */
    public String originalSourceCauseIdentity() {
        return originalSourceCauseIdentity;
    }

    /**
     * Returns the documented value.
     *
     * @return authenticated source-revision-receipt identity
     */
    public String sourceRevisionReceiptIdentity() {
        return sourceRevisionReceiptIdentity;
    }

    /**
     * Returns the complete authenticated source transition when supplied.
     * Legacy state-only causes return an empty value.
     *
     * @return optional complete source transition receipt
     */
    public Optional<ManagedDocumentTransitionReceipt>
            sourceTransitionReceipt() {
        return Optional.ofNullable(sourceTransitionReceipt);
    }

    /**
     * Returns the complete cyclic proof for a cyclic successor.
     *
     * @return defensive optional successor proof
     */
    public Optional<CyclicSetProof> afterCyclicProof() {
        return Optional.ofNullable(copyProof(afterCyclicProof));
    }

    private void verifyTypedReceipt(
            ManagedDocumentTransitionReceipt receipt) {
        if (!sourceRevisionReceiptIdentity.equals(
                    receipt.transitionReceiptIdentity())
                || !childDocumentId.equals(receipt.documentId())
                || !beforeBlueId.equals(receipt.beforeBlueId())
                || !afterBlueId.equals(receipt.afterBlueId())
                || !originalSourceCauseIdentity.equals(
                    receipt.originalCauseIdentity())) {
            throw new IllegalArgumentException(
                    "Complete source transition receipt disagrees with managed revision");
        }
    }

    private static CyclicSetProof copyProof(CyclicSetProof proof) {
        return proof == null ? null
                : CyclicSetProof.fromDeclaredPlaceholderSet(
                        proof.declaredPlaceholderSet());
    }
}
