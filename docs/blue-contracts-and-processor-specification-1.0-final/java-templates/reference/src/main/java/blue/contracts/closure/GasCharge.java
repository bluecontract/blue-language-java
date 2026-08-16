package blue.contracts.closure;

import java.util.Objects;

/** One admitted canonical gas-trace entry in the complete closed schema shape. */
public final class GasCharge {
    /** Closed namespace values serialized by the normative gas-trace schema. */
    public enum Namespace {
        PROCESSOR("processor"),
        SEMANTIC("semantic"),
        RUNTIME("runtime");

        private final String serializedValue;

        Namespace(String serializedValue) {
            this.serializedValue = serializedValue;
        }

        public String serializedValue() {
            return serializedValue;
        }
    }

    /**
     * Semantically applicable trace context. Null fields are omitted by a
     * serializer; safe-integer fields are retained exactly when non-null.
     */
    public static final class Context {
        private final DocumentId documentId;
        private final String scopePath;
        private final Long activationGeneration;
        private final Long componentGeneration;
        private final String contractKey;
        private final String logicalPath;
        private final String workOccurrenceId;
        private final String reason;

        public Context(
                DocumentId documentId,
                String scopePath,
                Long activationGeneration,
                Long componentGeneration,
                String contractKey,
                String logicalPath,
                String workOccurrenceId,
                String reason) {
            this.documentId = documentId;
            this.scopePath = scopePath;
            this.activationGeneration = safeNullable(
                    activationGeneration, "activationGeneration");
            this.componentGeneration = safeNullable(
                    componentGeneration, "componentGeneration");
            this.contractKey = contractKey;
            this.logicalPath = logicalPath;
            this.workOccurrenceId = workOccurrenceId;
            this.reason = reason;
        }

        public static Context invocation(String reason) {
            return new Context(null, null, null, null, null, null, null, reason);
        }

        public DocumentId documentId() { return documentId; }
        public String scopePath() { return scopePath; }
        public Long activationGeneration() { return activationGeneration; }
        public Long componentGeneration() { return componentGeneration; }
        public String contractKey() { return contractKey; }
        public String logicalPath() { return logicalPath; }
        public String workOccurrenceId() { return workOccurrenceId; }
        public String reason() { return reason; }

        private static Long safeNullable(Long value, String field) {
            return value == null ? null : Long.valueOf(
                    CanonicalOrders.requireSafeInteger(value.longValue(), field));
        }
    }

    private final long sequence;
    private final Namespace namespace;
    private final String counter;
    private final long quantity;
    private final long weight;
    private final long subtotal;
    private final Context context;

    public GasCharge(
            long sequence,
            Namespace namespace,
            String counter,
            long quantity,
            long weight,
            Context context) {
        this.sequence = CanonicalOrders.requireSafeInteger(sequence, "sequence");
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        this.counter = Objects.requireNonNull(counter, "counter");
        this.quantity = CanonicalOrders.requireSafeInteger(quantity, "quantity");
        this.weight = CanonicalOrders.requireSafeInteger(weight, "weight");
        this.subtotal = CanonicalOrders.requireSafeInteger(
                Math.multiplyExact(quantity, weight), "subtotal");
        this.context = Objects.requireNonNull(context, "context");
    }

    public long sequence() { return sequence; }
    public Namespace namespace() { return namespace; }
    public String counter() { return counter; }
    public long quantity() { return quantity; }
    public long weight() { return weight; }
    public long subtotal() { return subtotal; }
    public Context context() { return context; }
    public DocumentId documentId() { return context.documentId(); }
    public String scopePath() { return context.scopePath(); }
    public Long activationGeneration() { return context.activationGeneration(); }
    public Long componentGeneration() { return context.componentGeneration(); }
    public String contractKey() { return context.contractKey(); }
    public String logicalPath() { return context.logicalPath(); }
    public String workOccurrenceId() { return context.workOccurrenceId(); }
    public String reason() { return context.reason(); }
}
