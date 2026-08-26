package blue.language.processor.closure;

import blue.language.model.Node;

import java.util.Objects;
import java.util.Optional;

/** Exact authenticated transition for one contiguous managed-document epoch. */
public final class ManagedRevisionCause extends ProcessingCause {

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

    /**
     * Creates one contiguous authenticated managed revision.
     *
     * @param causeIdentity exact cause identity
     * @param targetOccurrenceIdentity stable target occurrence identity
     * @param childDocumentId revised child lineage
     * @param fromEpoch exact predecessor epoch
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
                null);
    }

    /**
     * Creates one contiguous managed revision authenticated by the complete
     * source transition receipt whose events must be delivered.
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
                sourceTransitionReceipt);
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
            ManagedDocumentTransitionReceipt sourceTransitionReceipt) {
        super(causeIdentity);
        this.targetOccurrenceIdentity =
                ClosureValueSupport.requireSha256Identity(
                        targetOccurrenceIdentity,
                        "targetOccurrenceIdentity");
        this.childDocumentId = Objects.requireNonNull(
                childDocumentId, "childDocumentId");
        this.fromEpoch = ClosureValueSupport.requireSafeInteger(
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
        if (sourceTransitionReceipt != null) {
            verifyTypedReceipt(sourceTransitionReceipt);
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
     * @return predecessor epoch
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
}
