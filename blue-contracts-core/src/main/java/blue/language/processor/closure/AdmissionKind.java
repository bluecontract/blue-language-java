package blue.language.processor.closure;

/** Closed reason for affected-closure admission. */
public enum AdmissionKind {
    /** Directly admitted public or private managed Root. */
    TOP_LEVEL_ADMISSION,
    /** Managed lineage activated by authored embedded content. */
    EMBEDDED_ACTIVATION,
    /** Previously managed state imported under an explicit policy. */
    IMPORTED_STATE_ADMISSION
}
