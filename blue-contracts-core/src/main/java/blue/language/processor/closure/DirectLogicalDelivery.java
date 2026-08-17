package blue.language.processor.closure;

import java.util.Objects;

/** One exact direct logical delivery in canonical snapshot order. */
public final class DirectLogicalDelivery
        implements Comparable<DirectLogicalDelivery> {

    private final ManagedScopeKey targetScope;
    private final String channelKey;
    private final String logicalDeliveryKey;
    private final long rawOccurrenceOrder;

    /**
     * Creates one direct delivery to an independently managed document Root.
     *
     * @param targetScope Root-scoped managed target
     * @param channelKey raw channel key
     * @param logicalDeliveryKey logical duplicate-group key
     * @param rawOccurrenceOrder stable raw occurrence order
     */
    public DirectLogicalDelivery(
            ManagedScopeKey targetScope,
            String channelKey,
            String logicalDeliveryKey,
            long rawOccurrenceOrder) {
        this.targetScope = Objects.requireNonNull(targetScope, "targetScope");
        if (!targetScope.isRoot()) {
            throw new IllegalArgumentException(
                    "Closure 1.0 direct delivery must target a document Root");
        }
        this.channelKey = ClosureValueSupport.requireNonEmptyText(
                channelKey, "channelKey");
        this.logicalDeliveryKey = ClosureValueSupport.requireNonEmptyText(
                logicalDeliveryKey, "logicalDeliveryKey");
        this.rawOccurrenceOrder = ClosureValueSupport.requireSafeInteger(
                rawOccurrenceOrder, "rawOccurrenceOrder");
    }

    /**
     * Returns the documented value.
     *
     * @return Root-scoped managed target
     */
    public ManagedScopeKey targetScope() {
        return targetScope;
    }

    /**
     * Returns the documented value.
     *
     * @return target managed-document lineage
     */
    public DocumentId targetDocumentId() {
        return targetScope.documentId();
    }

    /**
     * Returns the documented value.
     *
     * @return raw channel key
     */
    public String channelKey() {
        return channelKey;
    }

    /**
     * Returns the documented value.
     *
     * @return logical duplicate-group key
     */
    public String logicalDeliveryKey() {
        return logicalDeliveryKey;
    }

    /**
     * Returns the documented value.
     *
     * @return stable raw occurrence order
     */
    public long rawOccurrenceOrder() {
        return rawOccurrenceOrder;
    }

    /**
     * Compares exact deliveries in canonical snapshot order.
     *
     * @param other delivery to compare
     * @return canonical ordering result
     */
    @Override
    public int compareTo(DirectLogicalDelivery other) {
        int order = Long.compare(rawOccurrenceOrder,
                other.rawOccurrenceOrder);
        if (order != 0) {
            return order;
        }
        order = targetScope.compareTo(other.targetScope);
        if (order != 0) {
            return order;
        }
        order = ClosureValueSupport.comparePortableText(
                channelKey, other.channelKey);
        return order != 0
                ? order
                : ClosureValueSupport.comparePortableText(
                        logicalDeliveryKey, other.logicalDeliveryKey);
    }
}
