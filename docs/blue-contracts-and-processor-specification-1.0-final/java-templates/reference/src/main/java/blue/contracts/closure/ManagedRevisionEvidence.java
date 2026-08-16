package blue.contracts.closure;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** One exact consecutive managed revision and optional coordination order evidence. */
public final class ManagedRevisionEvidence {
    private final DocumentId documentId;
    private final long fromEpoch;
    private final long toEpoch;
    private final String beforeBlueId;
    private final String afterBlueId;
    private final Object afterDocument;
    private final String transitionIdentity;
    private final List<Object> sourceOrder;

    public ManagedRevisionEvidence(
            DocumentId documentId,
            long fromEpoch,
            long toEpoch,
            String beforeBlueId,
            String afterBlueId,
            Object afterDocument,
            String transitionIdentity) {
        this(
                documentId,
                fromEpoch,
                toEpoch,
                beforeBlueId,
                afterBlueId,
                afterDocument,
                transitionIdentity,
                null);
    }

    public ManagedRevisionEvidence(
            DocumentId documentId,
            long fromEpoch,
            long toEpoch,
            String beforeBlueId,
            String afterBlueId,
            Object afterDocument,
            String transitionIdentity,
            List<?> sourceOrder) {
        this.documentId = Objects.requireNonNull(documentId, "documentId");
        this.fromEpoch = CanonicalOrders.requireSafeInteger(fromEpoch, "fromEpoch");
        this.toEpoch = CanonicalOrders.requireSafeInteger(toEpoch, "toEpoch");
        if (this.fromEpoch == CanonicalOrders.MAX_SAFE_INTEGER
                || this.toEpoch != this.fromEpoch + 1L) {
            throw new IllegalArgumentException("epoch chain");
        }
        this.beforeBlueId = Objects.requireNonNull(beforeBlueId, "beforeBlueId");
        this.afterBlueId = Objects.requireNonNull(afterBlueId, "afterBlueId");
        this.afterDocument = Objects.requireNonNull(afterDocument, "afterDocument");
        this.transitionIdentity = Objects.requireNonNull(
                transitionIdentity, "transitionIdentity");
        if (sourceOrder == null) {
            this.sourceOrder = null;
        } else {
            ArrayList<Object> copy = new ArrayList<Object>(sourceOrder);
            if (copy.size() < 3) {
                throw new IllegalArgumentException("sourceOrder");
            }
            this.sourceOrder = Collections.unmodifiableList(copy);
        }
    }

    public DocumentId documentId() { return documentId; }
    public long fromEpoch() { return fromEpoch; }
    public long toEpoch() { return toEpoch; }
    public String beforeBlueId() { return beforeBlueId; }
    public String afterBlueId() { return afterBlueId; }
    public Object afterDocument() { return afterDocument; }
    public String transitionIdentity() { return transitionIdentity; }
    /** Optional canonical coordination tuple; null means the field is absent. */
    public List<Object> sourceOrder() { return sourceOrder; }
}
