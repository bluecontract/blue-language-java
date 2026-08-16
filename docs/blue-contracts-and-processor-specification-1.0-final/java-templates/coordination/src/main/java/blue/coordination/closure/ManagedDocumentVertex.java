package blue.coordination.closure;

import blue.contracts.closure.CanonicalOrders;
import blue.contracts.closure.DocumentId;
import java.util.Objects;

/** Current exact host view of one managed document. */
public final class ManagedDocumentVertex {
    private final DocumentId documentId;
    private final long epoch;
    private final String blueId;
    private final Object exactDocument;

    public ManagedDocumentVertex(DocumentId documentId, long epoch, String blueId, Object exactDocument) {
        this.documentId = Objects.requireNonNull(documentId, "documentId");
        this.epoch = CanonicalOrders.requireSafeInteger(epoch, "epoch");
        this.blueId = Objects.requireNonNull(blueId, "blueId");
        this.exactDocument = Objects.requireNonNull(exactDocument, "exactDocument");
    }

    public DocumentId documentId() {
        return documentId;
    }

    public long epoch() {
        return epoch;
    }

    public String blueId() {
        return blueId;
    }

    public Object exactDocument() {
        return exactDocument;
    }
}
