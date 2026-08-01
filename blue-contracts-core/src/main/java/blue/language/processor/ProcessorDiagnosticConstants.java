package blue.language.processor;

/**
 * Stable detail-field names emitted with processor diagnostics and failures.
 *
 * <p>Hosts may persist or project these details, so exception producers use a
 * shared vocabulary instead of repeating ad hoc string keys.</p>
 */
public final class ProcessorDiagnosticConstants {

    /** Admitted gas before a charge was rejected. */
    public static final String FIELD_ADMITTED_GAS = "admittedGas";
    /** Contract key associated with a diagnostic. */
    public static final String FIELD_CONTRACT_KEY = "contractKey";
    /** Gas counter associated with a diagnostic. */
    public static final String FIELD_COUNTER = "counter";
    /** Effective gas budget at the rejection boundary. */
    public static final String FIELD_EFFECTIVE_BUDGET = "effectiveBudget";
    /** Configured process gas limit. */
    public static final String FIELD_GAS_LIMIT = "gasLimit";
    /** Portable-limit threshold. */
    public static final String FIELD_LIMIT = "limit";
    /** Portable-limit name. */
    public static final String FIELD_LIMIT_NAME = "limitName";
    /** Gas namespace associated with a diagnostic. */
    public static final String FIELD_NAMESPACE = "namespace";
    /** Value observed by a portable-limit check. */
    public static final String FIELD_OBSERVED = "observed";
    /** Quantity requested by a gas charge. */
    public static final String FIELD_QUANTITY = "quantity";
    /** Scope path associated with a diagnostic. */
    public static final String FIELD_SCOPE_PATH = "scopePath";
    /** Unit weight associated with a gas charge. */
    public static final String FIELD_WEIGHT = "weight";

    private ProcessorDiagnosticConstants() {
    }
}
