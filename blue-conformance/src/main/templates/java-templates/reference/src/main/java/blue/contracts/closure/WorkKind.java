package blue.contracts.closure;

/** Closed queue kinds. ManagedRevisionCause seeds CONTAINING_REFERENCE_UPDATE. */
public enum WorkKind {
    EXTERNAL_DELIVERY,
    INITIALIZATION,
    DOCUMENT_UPDATE,
    TRIGGERED_EVENT,
    EMBEDDED_EVENT,
    LIFECYCLE,
    CONTAINING_REFERENCE_UPDATE
}
