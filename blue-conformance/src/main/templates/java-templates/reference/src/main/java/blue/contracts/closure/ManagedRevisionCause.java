package blue.contracts.closure;

import java.util.Objects;

/**
 * Closed cause for exactly one authenticated contiguous child revision.
 *
 * <p>This is an invocation cause, not a batch resource and not a queued
 * historical work kind. The processor seeds one
 * {@link WorkKind#CONTAINING_REFERENCE_UPDATE} from it.</p>
 */
public final class ManagedRevisionCause extends ProcessingCause {
    public interface ExactNodeIdentityFactory {
        String blueId(Object exactNode);
    }

    public interface SourceRevisionReceiptIdentityFactory {
        String identity(
                DocumentId childDocumentId,
                long fromEpoch,
                long toEpoch,
                String beforeBlueId,
                String afterBlueId,
                String originalSourceCauseIdentity);
    }

    public interface CauseIdentityFactory {
        String identity(
                String targetOccurrenceIdentity,
                DocumentId childDocumentId,
                long fromEpoch,
                long toEpoch,
                String beforeBlueId,
                String afterBlueId,
                String originalSourceCauseIdentity,
                String sourceRevisionReceiptIdentity);
    }

    private final String causeIdentity;
    private final String targetOccurrenceIdentity;
    private final DocumentId childDocumentId;
    private final long fromEpoch;
    private final long toEpoch;
    private final String beforeBlueId;
    private final String afterBlueId;
    private final Object afterDocument;
    private final String originalSourceCauseIdentity;
    private final String sourceRevisionReceiptIdentity;

    public ManagedRevisionCause(
            String causeIdentity,
            String targetOccurrenceIdentity,
            DocumentId childDocumentId,
            long fromEpoch,
            long toEpoch,
            String beforeBlueId,
            String afterBlueId,
            Object afterDocument,
            String originalSourceCauseIdentity,
            String sourceRevisionReceiptIdentity,
            ExactNodeIdentityFactory exactNodeIdentityFactory,
            SourceRevisionReceiptIdentityFactory receiptIdentityFactory,
            CauseIdentityFactory causeIdentityFactory) {
        this.targetOccurrenceIdentity = Objects.requireNonNull(
                targetOccurrenceIdentity, "targetOccurrenceIdentity");
        this.childDocumentId = Objects.requireNonNull(
                childDocumentId, "childDocumentId");
        this.fromEpoch = CanonicalOrders.requireSafeInteger(fromEpoch, "fromEpoch");
        this.toEpoch = CanonicalOrders.requireSafeInteger(toEpoch, "toEpoch");
        if (this.fromEpoch == CanonicalOrders.MAX_SAFE_INTEGER
                || this.toEpoch != this.fromEpoch + 1L) {
            throw new IllegalArgumentException("toEpoch must equal fromEpoch + 1");
        }
        this.beforeBlueId = Objects.requireNonNull(beforeBlueId, "beforeBlueId");
        this.afterBlueId = Objects.requireNonNull(afterBlueId, "afterBlueId");
        this.afterDocument = Objects.requireNonNull(afterDocument, "afterDocument");
        String recomputedAfterBlueId = Objects.requireNonNull(
                Objects.requireNonNull(
                        exactNodeIdentityFactory, "exactNodeIdentityFactory")
                        .blueId(this.afterDocument),
                "recomputed afterBlueId");
        if (!this.afterBlueId.equals(recomputedAfterBlueId)) {
            throw new IllegalArgumentException("afterDocument/afterBlueId mismatch");
        }
        this.originalSourceCauseIdentity = Objects.requireNonNull(
                originalSourceCauseIdentity, "originalSourceCauseIdentity");
        String recomputedReceiptIdentity = Objects.requireNonNull(
                Objects.requireNonNull(
                        receiptIdentityFactory, "receiptIdentityFactory")
                        .identity(
                                this.childDocumentId,
                                this.fromEpoch,
                                this.toEpoch,
                                this.beforeBlueId,
                                this.afterBlueId,
                                this.originalSourceCauseIdentity),
                "recomputed sourceRevisionReceiptIdentity");
        if (!recomputedReceiptIdentity.equals(Objects.requireNonNull(
                sourceRevisionReceiptIdentity,
                "sourceRevisionReceiptIdentity"))) {
            throw new IllegalArgumentException(
                    "sourceRevisionReceiptIdentity mismatch");
        }
        this.sourceRevisionReceiptIdentity = recomputedReceiptIdentity;
        String recomputedCauseIdentity = Objects.requireNonNull(
                Objects.requireNonNull(causeIdentityFactory, "causeIdentityFactory")
                        .identity(
                                this.targetOccurrenceIdentity,
                                this.childDocumentId,
                                this.fromEpoch,
                                this.toEpoch,
                                this.beforeBlueId,
                                this.afterBlueId,
                                this.originalSourceCauseIdentity,
                                this.sourceRevisionReceiptIdentity),
                "recomputed causeIdentity");
        if (!recomputedCauseIdentity.equals(Objects.requireNonNull(
                causeIdentity, "causeIdentity"))) {
            throw new IllegalArgumentException("causeIdentity mismatch");
        }
        this.causeIdentity = recomputedCauseIdentity;
    }

    @Override
    public String kind() { return "managed-revision"; }

    @Override
    public String causeIdentity() { return causeIdentity; }

    public String targetOccurrenceIdentity() { return targetOccurrenceIdentity; }
    public DocumentId childDocumentId() { return childDocumentId; }
    public long fromEpoch() { return fromEpoch; }
    public long toEpoch() { return toEpoch; }
    public String beforeBlueId() { return beforeBlueId; }
    public String afterBlueId() { return afterBlueId; }
    public Object afterDocument() { return afterDocument; }
    public String originalSourceCauseIdentity() {
        return originalSourceCauseIdentity;
    }
    public String sourceRevisionReceiptIdentity() {
        return sourceRevisionReceiptIdentity;
    }
}
