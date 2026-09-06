package blue.language.processor.closure;

import blue.language.processor.GasChargeContext;
import java.util.*;

/** Exact ordinary rejection data, distinct from a rejected atomic union. No execution authority is created. */
public final class SameOriginRejectedChargeEvidence {
    private final String identity;
    private final Map<String, Object> value;

    private SameOriginRejectedChargeEvidence(String expectedIdentity, Map<String, Object> supplied) {
        Map<String, Object> copy = new TreeMap<>(Objects.requireNonNull(supplied));
        Object context = copy.get("context");
        if (!(context instanceof Map)) throw new IllegalArgumentException("Rejected charge requires exact context");
        Map<String, Object> copiedContext = new TreeMap<>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) context).entrySet()) {
            if (!(entry.getKey() instanceof String)) throw new IllegalArgumentException("Rejected context key must be text");
            copiedContext.put((String) entry.getKey(), entry.getValue());
        }
        copy.put("context", Collections.unmodifiableMap(copiedContext));
        identity = ClosureIdentityService.INSTANCE.identity(ClosureIdentityService.Constructor.SAME_ORIGIN_REJECTED_CHARGE, copy);
        if (expectedIdentity != null && !identity.equals(expectedIdentity)) throw new IllegalArgumentException("Rejected charge identity mismatch");
        value = Collections.unmodifiableMap(copy);
    }

    public static SameOriginRejectedChargeEvidence fromOperation(SameOriginOperationResult result) {
        SameOriginOperationResult.Failure failure = result.failure().orElseThrow(() -> new IllegalArgumentException("Successful group has no rejection"));
        SameOriginOperationResult.ChargeRejection charge = failure.rejectedCharge().orElseThrow(() -> new IllegalArgumentException("No ordinary rejected charge"));
        GasChargeContext context = charge.chargeContext();
        return new SameOriginRejectedChargeEvidence(null, map("operationIdentity", result.operationIdentity(),
                "failureSite", failure.canonicalSite(), "namespace", ClosureResultAssemblySupport.namespace(charge.namespace()).wireValue(), "counter", charge.counter(),
                "quantity", charge.quantity(), "weight", charge.weight(), "admittedGas", charge.admittedGas(),
                "gasLimit", charge.gasLimit(), "remainingBeforeCharge", charge.remainingBeforeCharge(),
                "cap", charge.applicableCapKind().name(), "localDocument", charge.localDocumentId(),
                "context", map("documentId", context.documentId(), "scopePath", context.scopePath(),
                        "activationGeneration", context.activationGeneration(), "contractKey", context.contractKey(),
                        "logicalPath", context.logicalPath(), "workOccurrenceId", context.workOccurrenceId(), "reason", context.reason())));
    }

    /** The expected identity must be bound by the authenticated failure receipt, not self-asserted. */
    public static SameOriginRejectedChargeEvidence fromExactEvidence(String expectedIdentity, Map<String, Object> value) {
        ClosureValueSupport.requireSha256Identity(expectedIdentity, "rejectedChargeIdentity");
        return new SameOriginRejectedChargeEvidence(expectedIdentity, value);
    }
    public String identity() { return identity; }
    /** Closed primitive-valued constructor, with immutable context; excludes physical generations/encounter counters. */
    public Map<String, Object> canonicalValue() { return value; }
    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> result = new TreeMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]); return result;
    }
}
