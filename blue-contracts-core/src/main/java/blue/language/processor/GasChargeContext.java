package blue.language.processor;

/**
 * Immutable deterministic attribution attached to a gas trace entry.
 *
 * <p>Scope, contract, and logical path are optional because some kernel work
 * is global. The reason is always non-null, and the shared empty value is safe
 * to reuse because the class has no mutable state.</p>
 */
public final class GasChargeContext {

    private static final GasChargeContext EMPTY =
            new GasChargeContext(null, null, null, "unspecified");

    private final String scopePath;
    private final String contractKey;
    private final String logicalPath;
    private final String reason;

    private GasChargeContext(String scopePath,
                             String contractKey,
                             String logicalPath,
                             String reason) {
        this.scopePath = scopePath;
        this.contractKey = contractKey;
        this.logicalPath = logicalPath;
        this.reason = reason != null ? reason : "unspecified";
    }

    /**
     * Returns the attribution used for global work with no semantic owner.
     *
     * @return shared attribution with no scope, contract, or path
     */
    public static GasChargeContext empty() {
        return EMPTY;
    }

    /**
     * Creates a complete immutable charge attribution.
     *
     * @param scopePath optional scope attribution
     * @param contractKey optional contract attribution
     * @param logicalPath optional logical path attribution
     * @param reason deterministic charge reason, or {@code null}
     * @return immutable attribution context
     */
    public static GasChargeContext of(String scopePath,
                                      String contractKey,
                                      String logicalPath,
                                      String reason) {
        return new GasChargeContext(scopePath, contractKey, logicalPath, reason);
    }

    /**
     * Creates an attribution containing only a deterministic reason.
     *
     * @param reason deterministic charge reason
     * @return reason-only context
     */
    public static GasChargeContext reason(String reason) {
        return of(null, null, null, reason);
    }

    /**
     * Returns the semantic scope charged for the work.
     *
     * @return attributed scope, or {@code null}
     */
    public String scopePath() {
        return scopePath;
    }

    /**
     * Returns the contract charged for the work.
     *
     * @return attributed contract key, or {@code null}
     */
    public String contractKey() {
        return contractKey;
    }

    /**
     * Returns the logical document path charged for the work.
     *
     * @return attributed logical path, or {@code null}
     */
    public String logicalPath() {
        return logicalPath;
    }

    /**
     * Returns the deterministic reason recorded in the gas trace.
     *
     * @return non-null deterministic reason
     */
    public String reason() {
        return reason;
    }
}
