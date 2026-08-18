package blue.language.processor.closure;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** One admitted charge in the complete affected-closure gas trace. */
public final class GasTraceEntry {

    /** Closed gas namespace. */
    public enum Namespace {
        /** Processor-orchestration gas. */
        PROCESSOR("processor"),
        /** Portable semantic gas. */
        SEMANTIC("semantic"),
        /** Runtime child-ledger gas. */
        RUNTIME("runtime");

        private final String wireValue;

        Namespace(String wireValue) {
            this.wireValue = wireValue;
        }

        /**
         * Returns exact lowercase serialized value.
         *
         * @return exact lowercase serialized value
         */
        public String wireValue() {
            return wireValue;
        }
    }

    private final long sequence;
    private final Namespace namespace;
    private final String counter;
    private final long quantity;
    private final long weight;
    private final long subtotal;
    private final DocumentId documentId;
    private final String scopePath;
    private final Long activationGeneration;
    private final Long componentGeneration;
    private final String contractKey;
    private final String logicalPath;
    private final String workOccurrenceId;
    private final String reason;

    /**
     * Creates one complete admitted gas entry.  Nullable context fields are
     * serialized only when semantically applicable.
     *
     * @param sequence contiguous owning-ledger sequence
     * @param namespace closed schedule namespace
     * @param counter exact non-empty schedule counter
     * @param quantity admitted positive quantity
     * @param weight exact non-negative unit weight
     * @param subtotal asserted exact product
     * @param documentId optional owning document
     * @param scopePath optional Root path paired with activation generation
     * @param activationGeneration optional Root generation zero
     * @param componentGeneration optional component generation
     * @param contractKey optional exact contract key
     * @param logicalPath optional exact logical path
     * @param workOccurrenceId optional work occurrence identity
     * @param reason optional deterministic reason
     */
    public GasTraceEntry(
            long sequence,
            Namespace namespace,
            String counter,
            long quantity,
            long weight,
            long subtotal,
            DocumentId documentId,
            String scopePath,
            Long activationGeneration,
            Long componentGeneration,
            String contractKey,
            String logicalPath,
            String workOccurrenceId,
            String reason) {
        this.sequence = ClosureValueSupport.requireSafeInteger(
                sequence, "sequence");
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        this.counter = ClosureValueSupport.requireNonEmptyText(
                counter, "counter");
        this.quantity = ClosureValueSupport.requirePositiveSafeInteger(
                quantity, "quantity");
        this.weight = ClosureValueSupport.requireSafeInteger(weight, "weight");
        this.subtotal = ClosureValueSupport.requireSafeInteger(
                subtotal, "subtotal");
        long product;
        try {
            product = Math.multiplyExact(quantity, weight);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(
                    "Gas charge multiplication overflow", exception);
        }
        if (product != subtotal) {
            throw new IllegalArgumentException(
                    "subtotal must equal quantity multiplied by weight");
        }
        this.documentId = documentId;
        this.scopePath = scopePath == null ? null
                : ClosureValueSupport.requireAbsolutePointer(
                        scopePath, "scopePath");
        this.activationGeneration = safeNullable(
                activationGeneration, "activationGeneration");
        if ((this.scopePath == null) != (this.activationGeneration == null)) {
            throw new IllegalArgumentException(
                    "scopePath and activationGeneration must appear together");
        }
        if (this.scopePath != null
                && (!"/".equals(this.scopePath)
                || this.activationGeneration.longValue() != 0L)) {
            throw new IllegalArgumentException(
                    "Contracts 1.0 closure gas scope context is Root-only");
        }
        this.componentGeneration = safeNullable(
                componentGeneration, "componentGeneration");
        this.contractKey = nullableText(contractKey, "contractKey");
        this.logicalPath = nullableText(logicalPath, "logicalPath");
        this.workOccurrenceId = workOccurrenceId == null ? null
                : ClosureValueSupport.requireSha256Identity(
                        workOccurrenceId, "workOccurrenceId");
        this.reason = nullableText(reason, "reason");
    }

    /**
     * Returns contiguous gas trace sequence.
     *
     * @return contiguous gas trace sequence
     */
    public long sequence() {
        return sequence;
    }

    /**
     * Returns closed schedule namespace.
     *
     * @return closed schedule namespace
     */
    public Namespace namespace() {
        return namespace;
    }

    /**
     * Returns exact schedule counter.
     *
     * @return exact schedule counter
     */
    public String counter() {
        return counter;
    }

    /**
     * Returns admitted quantity.
     *
     * @return admitted quantity
     */
    public long quantity() {
        return quantity;
    }

    /**
     * Returns exact unit weight.
     *
     * @return exact unit weight
     */
    public long weight() {
        return weight;
    }

    /**
     * Returns exact admitted subtotal.
     *
     * @return exact admitted subtotal
     */
    public long subtotal() {
        return subtotal;
    }

    /**
     * Returns optional owning document.
     *
     * @return optional owning document
     */
    public DocumentId documentId() {
        return documentId;
    }

    /**
     * Returns optional literal Root scope.
     *
     * @return optional literal Root scope
     */
    public String scopePath() {
        return scopePath;
    }

    /**
     * Returns optional Root generation zero.
     *
     * @return optional Root generation zero
     */
    public Long activationGeneration() {
        return activationGeneration;
    }

    /**
     * Returns optional component generation.
     *
     * @return optional component generation
     */
    public Long componentGeneration() {
        return componentGeneration;
    }

    /**
     * Returns optional exact contract key.
     *
     * @return optional exact contract key
     */
    public String contractKey() {
        return contractKey;
    }

    /**
     * Returns optional exact logical path.
     *
     * @return optional exact logical path
     */
    public String logicalPath() {
        return logicalPath;
    }

    /**
     * Returns optional work occurrence identity.
     *
     * @return optional work occurrence identity
     */
    public String workOccurrenceId() {
        return workOccurrenceId;
    }

    /**
     * Returns optional deterministic reason.
     *
     * @return optional deterministic reason
     */
    public String reason() {
        return reason;
    }

    Map<String, Object> identityValue() {
        LinkedHashMap<String, Object> value =
                new LinkedHashMap<String, Object>();
        value.put("sequence", Long.valueOf(sequence));
        value.put("namespace", namespace.wireValue());
        value.put("counter", counter);
        value.put("quantity", Long.valueOf(quantity));
        value.put("weight", Long.valueOf(weight));
        value.put("subtotal", Long.valueOf(subtotal));
        putOptional(value, "documentId",
                documentId == null ? null : documentId.value());
        putOptional(value, "scopePath", scopePath);
        putOptional(value, "activationGeneration", activationGeneration);
        putOptional(value, "componentGeneration", componentGeneration);
        putOptional(value, "contractKey", contractKey);
        putOptional(value, "logicalPath", logicalPath);
        putOptional(value, "workOccurrenceId", workOccurrenceId);
        putOptional(value, "reason", reason);
        return value;
    }

    private static Long safeNullable(Long value, String field) {
        return value == null ? null : Long.valueOf(
                ClosureValueSupport.requireSafeInteger(
                        value.longValue(), field));
    }

    private static String nullableText(String value, String field) {
        return value == null ? null
                : ClosureValueSupport.requirePortableText(value, field);
    }

    private static void putOptional(
            Map<String, Object> value,
            String key,
            Object item) {
        if (item != null) {
            value.put(key, item);
        }
    }
}
