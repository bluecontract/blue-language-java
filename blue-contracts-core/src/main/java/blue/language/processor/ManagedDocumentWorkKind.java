package blue.language.processor;

/** Closed processor-side work roles for one isolated managed document. */
public enum ManagedDocumentWorkKind {
    /** Already-selected direct external delivery. */
    EXTERNAL_DELIVERY,
    /** Initialization preflight and charge boundary. */
    INITIALIZATION,
    /** Already-created immutable Document Update delivery. */
    DOCUMENT_UPDATE,
    /** Already-selected local Triggered Event delivery. */
    TRIGGERED_EVENT,
    /** Delivery through a frozen containing occurrence. */
    EMBEDDED_EVENT,
    /** Already-created lifecycle delivery. */
    LIFECYCLE,
    /** Processor-managed containing-reference rewrite. */
    CONTAINING_REFERENCE_UPDATE
}
