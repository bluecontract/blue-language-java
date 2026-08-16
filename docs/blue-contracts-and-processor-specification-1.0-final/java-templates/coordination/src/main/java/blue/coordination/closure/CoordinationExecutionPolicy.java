package blue.coordination.closure;

import blue.contracts.closure.DocumentId;
import blue.contracts.closure.ExecutionPolicy;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Exact Coordination selection of the Contracts release default or a lower host limit. */
public final class CoordinationExecutionPolicy {
    public static final long CONTRACTS_RELEASE_MAX = 100_000L;

    private final String identity;
    private final long sharedLimit;
    private final Map<DocumentId, Long> localLimits;
    private final String label;

    public CoordinationExecutionPolicy(
            String identity,
            long sharedLimit,
            Map<DocumentId, Long> localLimits,
            String label) {
        this.identity = Objects.requireNonNull(identity, "identity");
        if (sharedLimit < 0 || sharedLimit > CONTRACTS_RELEASE_MAX) {
            throw new IllegalArgumentException("sharedLimit must be within the Contracts release maximum");
        }
        this.sharedLimit = sharedLimit;
        Map<DocumentId, Long> copy = new LinkedHashMap<DocumentId, Long>();
        for (Map.Entry<DocumentId, Long> entry : localLimits.entrySet()) {
            long value = Objects.requireNonNull(entry.getValue(), "local limit").longValue();
            if (value < 0 || value > sharedLimit) {
                throw new IllegalArgumentException("local limit must lower the shared limit");
            }
            copy.put(entry.getKey(), Long.valueOf(value));
        }
        this.localLimits = Collections.unmodifiableMap(copy);
        this.label = Objects.requireNonNull(label, "label");
    }

    public ExecutionPolicy toContractsPolicy() {
        return new ExecutionPolicy(identity, sharedLimit, localLimits, label);
    }

    public String identity() {
        return identity;
    }

    public String label() {
        return label;
    }
}
