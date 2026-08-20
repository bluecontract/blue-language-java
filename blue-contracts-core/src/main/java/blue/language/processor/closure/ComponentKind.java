package blue.language.processor.closure;

/** Structural kind of one affected-closure component. */
public enum ComponentKind {
    /** Singleton component without a self-cycle. */
    ACYCLIC,
    /** One or more members covered by complete cyclic-set proof. */
    CYCLIC
}
