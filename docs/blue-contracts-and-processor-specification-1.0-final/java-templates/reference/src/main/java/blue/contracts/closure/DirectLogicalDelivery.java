package blue.contracts.closure;

import java.util.List;
import java.util.Objects;

/** One exact direct logical delivery in canonical snapshot order. */
public final class DirectLogicalDelivery
        implements Comparable<DirectLogicalDelivery> {
    /** Recomputes the exact directDeliverySnapshotIdentity defined by the registry. */
    public interface SnapshotIdentityFactory {
        String identity(List<DirectLogicalDelivery> directDeliveries);
    }

    private final DocumentId targetDocumentId;
    private final String scopePath;
    private final long activationGeneration;
    private final String channelKey;
    private final String logicalDeliveryKey;
    private final long rawOccurrenceOrder;

    public DirectLogicalDelivery(
            DocumentId targetDocumentId,
            String scopePath,
            long activationGeneration,
            String channelKey,
            String logicalDeliveryKey,
            long rawOccurrenceOrder) {
        this.targetDocumentId = Objects.requireNonNull(
                targetDocumentId, "targetDocumentId");
        this.scopePath = CanonicalOrders.requireNfc(
                Objects.requireNonNull(scopePath, "scopePath"), "scopePath");
        this.activationGeneration = CanonicalOrders.requireSafeInteger(
                activationGeneration, "activationGeneration");
        if (!"/".equals(this.scopePath) || this.activationGeneration != 0L) {
            throw new IllegalArgumentException(
                    "Contracts 1.0 closure direct delivery must target Root");
        }
        this.channelKey = CanonicalOrders.requireNfc(
                Objects.requireNonNull(channelKey, "channelKey"), "channelKey");
        this.logicalDeliveryKey = CanonicalOrders.requireNfc(
                Objects.requireNonNull(logicalDeliveryKey, "logicalDeliveryKey"),
                "logicalDeliveryKey");
        this.rawOccurrenceOrder = CanonicalOrders.requireSafeInteger(
                rawOccurrenceOrder, "rawOccurrenceOrder");
    }

    public DocumentId targetDocumentId() { return targetDocumentId; }
    public String scopePath() { return scopePath; }
    public long activationGeneration() { return activationGeneration; }
    public String channelKey() { return channelKey; }
    public String logicalDeliveryKey() { return logicalDeliveryKey; }
    public long rawOccurrenceOrder() { return rawOccurrenceOrder; }

    @Override
    public int compareTo(DirectLogicalDelivery other) {
        int order = Long.compare(rawOccurrenceOrder, other.rawOccurrenceOrder);
        if (order != 0) return order;
        order = targetDocumentId.compareTo(other.targetDocumentId);
        if (order != 0) return order;
        order = CanonicalOrders.compareUnicodeScalars(
                scopePath, other.scopePath);
        if (order != 0) return order;
        order = Long.compare(activationGeneration, other.activationGeneration);
        if (order != 0) return order;
        order = CanonicalOrders.compareUnicodeScalars(
                channelKey, other.channelKey);
        if (order != 0) return order;
        return CanonicalOrders.compareUnicodeScalars(
                logicalDeliveryKey, other.logicalDeliveryKey);
    }
}
