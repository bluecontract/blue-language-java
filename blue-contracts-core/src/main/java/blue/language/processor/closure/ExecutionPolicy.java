package blue.language.processor.closure;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Immutable identity-bound shared allowance and optional document-local caps. */
public final class ExecutionPolicy {

    private final String identity;
    private final long sharedLimit;
    private final Map<DocumentId, Long> localLimits;
    private final String label;

    /**
     * Creates exact invocation-owned execution-policy evidence.
     *
     * @param identity execution-policy identity
     * @param sharedLimit one closure-wide allowance
     * @param localLimits optional per-document caps
     * @param label stable policy label
     */
    public ExecutionPolicy(
            String identity,
            long sharedLimit,
            Map<DocumentId, Long> localLimits,
            String label) {
        this.identity = ClosureValueSupport.requireSha256Identity(
                identity, "executionPolicyIdentity");
        this.sharedLimit = ClosureValueSupport.requireSafeInteger(
                sharedLimit, "sharedLimit");
        TreeMap<DocumentId, Long> sorted = new TreeMap<DocumentId, Long>();
        for (Map.Entry<DocumentId, Long> entry
                : Objects.requireNonNull(localLimits, "localLimits").entrySet()) {
            DocumentId documentId = Objects.requireNonNull(
                    entry.getKey(), "local limit documentId");
            long limit = ClosureValueSupport.requireSafeInteger(
                    Objects.requireNonNull(
                            entry.getValue(), "local limit").longValue(),
                    "localLimit");
            sorted.put(documentId, Long.valueOf(limit));
        }
        this.localLimits = Collections.unmodifiableMap(
                new LinkedHashMap<DocumentId, Long>(sorted));
        this.label = ClosureValueSupport.requireNonEmptyText(label, "label");
    }

    /**
     * Returns the documented value.
     *
     * @return execution-policy identity
     */
    public String identity() {
        return identity;
    }

    /**
     * Returns the documented value.
     *
     * @return one closure-wide allowance
     */
    public long sharedLimit() {
        return sharedLimit;
    }

    /**
     * Returns the documented value.
     *
     * @return immutable canonical per-document caps
     */
    public Map<DocumentId, Long> localLimits() {
        return localLimits;
    }

    /**
     * Looks up a document-local cap.
     *
     * @param documentId managed document lineage
     * @return local cap, or {@code null}
     */
    public Long localLimit(DocumentId documentId) {
        return localLimits.get(Objects.requireNonNull(documentId, "documentId"));
    }

    /**
     * Returns the documented value.
     *
     * @return stable policy label
     */
    public String label() {
        return label;
    }
}
