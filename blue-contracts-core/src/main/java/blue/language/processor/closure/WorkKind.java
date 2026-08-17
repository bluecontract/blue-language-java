package blue.language.processor.closure;

/** Closed Contracts 1.0 work kinds. */
public enum WorkKind {
    /** Frozen direct external delivery. */
    EXTERNAL_DELIVERY,
    /** Processor-managed document initialization. */
    INITIALIZATION,
    /** Delivery of one immutable Document Update. */
    DOCUMENT_UPDATE,
    /** Delivery to a local Triggered Event Channel. */
    TRIGGERED_EVENT,
    /** Delivery through a frozen containing occurrence. */
    EMBEDDED_EVENT,
    /** Processor-generated lifecycle delivery. */
    LIFECYCLE,
    /** Processor-managed exact containing-reference rewrite. */
    CONTAINING_REFERENCE_UPDATE
}
