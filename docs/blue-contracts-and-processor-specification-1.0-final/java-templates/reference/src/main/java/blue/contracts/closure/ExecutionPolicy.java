package blue.contracts.closure;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

/** Exact shared gas policy, canonical per-document caps, and identity-bound label. */
public final class ExecutionPolicy {
    private final String identity;
    private final long sharedLimit;
    private final SortedMap<DocumentId, Long> localLimits;
    private final String label;

    public ExecutionPolicy(
            String identity,
            long sharedLimit,
            Map<DocumentId, Long> localLimits,
            String label) {
        this.identity = Objects.requireNonNull(identity, "identity");
        this.sharedLimit = CanonicalOrders.requireSafeInteger(sharedLimit, "sharedLimit");
        TreeMap<DocumentId, Long> copy = new TreeMap<DocumentId, Long>(
                CanonicalOrders.DOCUMENT_ID);
        for (Map.Entry<DocumentId, Long> entry
                : Objects.requireNonNull(localLimits, "localLimits").entrySet()) {
            copy.put(
                    Objects.requireNonNull(entry.getKey(), "local documentId"),
                    Long.valueOf(CanonicalOrders.requireSafeInteger(
                            Objects.requireNonNull(entry.getValue(), "local limit").longValue(),
                            "localLimit")));
        }
        this.localLimits = Collections.unmodifiableSortedMap(copy);
        this.label = CanonicalOrders.requireNfc(label, "label");
        if (this.label.isEmpty()) {
            throw new IllegalArgumentException("label");
        }
    }

    public String identity() { return identity; }
    public long sharedLimit() { return sharedLimit; }
    public Map<DocumentId, Long> localLimits() { return localLimits; }
    public Long localLimit(DocumentId documentId) { return localLimits.get(documentId); }
    public String label() { return label; }
}
