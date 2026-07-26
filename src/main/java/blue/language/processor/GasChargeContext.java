package blue.language.processor;

/**
 * Optional deterministic context attached to a gas trace entry.
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

    public static GasChargeContext empty() {
        return EMPTY;
    }

    public static GasChargeContext of(String scopePath,
                                      String contractKey,
                                      String logicalPath,
                                      String reason) {
        return new GasChargeContext(scopePath, contractKey, logicalPath, reason);
    }

    public static GasChargeContext reason(String reason) {
        return of(null, null, null, reason);
    }

    public String scopePath() {
        return scopePath;
    }

    public String contractKey() {
        return contractKey;
    }

    public String logicalPath() {
        return logicalPath;
    }

    public String reason() {
        return reason;
    }
}
