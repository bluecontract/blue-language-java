package blue.contracts.closure;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** One deterministic shared meter with exact non-work and work rejection evidence. */
public final class SharedGasMeter {
    private final ExecutionPolicy policy;
    private final RejectedChargeIdentityFactory rejectedChargeIdentityFactory;
    private final List<GasCharge> trace = new ArrayList<GasCharge>();
    private final Map<DocumentId, Long> localUsed = new HashMap<DocumentId, Long>();
    private long used;

    public SharedGasMeter(
            ExecutionPolicy policy,
            RejectedChargeIdentityFactory rejectedChargeIdentityFactory) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.rejectedChargeIdentityFactory = Objects.requireNonNull(
                rejectedChargeIdentityFactory, "rejectedChargeIdentityFactory");
    }

    public void charge(
            GasCharge.Context context,
            GasCharge.Namespace namespace,
            String counter,
            long quantity,
            long weight,
            RejectedChargeOwner owner) {
        GasCharge.Context exactContext = Objects.requireNonNull(context, "context");
        DocumentId documentId = exactContext.documentId();
        CanonicalOrders.requireSafeInteger(quantity, "quantity");
        CanonicalOrders.requireSafeInteger(weight, "weight");
        long subtotal = CanonicalOrders.requireSafeInteger(
                Math.multiplyExact(quantity, weight), "subtotal");
        long sharedRemaining = policy.sharedLimit() - used;
        long memberUsed = documentId == null
                ? 0L : localUsed.getOrDefault(documentId, Long.valueOf(0L)).longValue();
        Long localLimit = documentId == null ? null : policy.localLimit(documentId);
        long localRemaining = localLimit == null
                ? Long.MAX_VALUE : localLimit.longValue() - memberUsed;
        ApplicableCap applicableCap;
        long remaining;
        if (localLimit != null && localRemaining < sharedRemaining) {
            applicableCap = new LocalCap(documentId);
            remaining = localRemaining;
        } else {
            applicableCap = new SharedCap();
            remaining = sharedRemaining;
        }
        RejectedChargeOwner exactOwner = Objects.requireNonNull(owner, "owner");
        if (exactOwner instanceof WorkOwner
                && !((WorkOwner) exactOwner).workOccurrenceIdentity().equals(
                        exactContext.workOccurrenceId())) {
            throw new IllegalArgumentException("work owner/context mismatch");
        }
        if (subtotal > remaining) {
            String identity = rejectedChargeIdentityFactory.identity(
                    namespace,
                    counter,
                    quantity,
                    weight,
                    subtotal,
                    applicableCap,
                    remaining,
                    exactOwner);
            throw new GasLimitExceeded(new RejectedCharge(
                    identity,
                    namespace,
                    counter,
                    quantity,
                    weight,
                    subtotal,
                    applicableCap,
                    remaining,
                    exactOwner));
        }
        GasCharge charge = new GasCharge(
                trace.size(),
                namespace,
                counter,
                quantity,
                weight,
                exactContext);
        trace.add(charge);
        used = CanonicalOrders.requireSafeInteger(
                Math.addExact(used, charge.subtotal()), "totalGas");
        if (documentId != null) {
            localUsed.put(
                    documentId,
                    Long.valueOf(CanonicalOrders.requireSafeInteger(
                            Math.addExact(memberUsed, charge.subtotal()), "localGas")));
        }
    }

    public long used() { return used; }

    public List<GasCharge> trace() {
        return Collections.unmodifiableList(new ArrayList<GasCharge>(trace));
    }

    /** Computes the domain-separated identity of complete rejected-charge evidence. */
    public interface RejectedChargeIdentityFactory {
        String identity(
                GasCharge.Namespace namespace,
                String counter,
                long quantity,
                long weight,
                long subtotal,
                ApplicableCap applicableCap,
                long remainingBeforeCharge,
                RejectedChargeOwner owner);
    }

    /** Closed structured gas cap; no delimiter-based cap encoding is permitted. */
    public abstract static class ApplicableCap {
        private ApplicableCap() { }
        public abstract String kind();
    }

    public static final class SharedCap extends ApplicableCap {
        public SharedCap() { }
        @Override public String kind() { return "SHARED"; }
    }

    public static final class LocalCap extends ApplicableCap {
        private final DocumentId documentId;

        public LocalCap(DocumentId documentId) {
            this.documentId = Objects.requireNonNull(documentId, "documentId");
        }

        @Override public String kind() { return "LOCAL"; }
        public DocumentId documentId() { return documentId; }
    }

    /**
     * Closed owner union for charges outside and inside queued work. A
     * FINALIZATION owner carries the invocation-global finalization ordinal so
     * repeated state churn in one component generation remains unambiguous.
     */
    public abstract static class RejectedChargeOwner {
        private RejectedChargeOwner() { }
        public abstract String kind();
    }

    public static final class InvocationOwner extends RejectedChargeOwner {
        public InvocationOwner() { }
        @Override public String kind() { return "INVOCATION"; }
    }

    public static final class WorkOwner extends RejectedChargeOwner {
        private final String workOccurrenceIdentity;

        public WorkOwner(String workOccurrenceIdentity) {
            this.workOccurrenceIdentity = Objects.requireNonNull(
                    workOccurrenceIdentity, "workOccurrenceIdentity");
        }

        @Override public String kind() { return "WORK"; }
        public String workOccurrenceIdentity() { return workOccurrenceIdentity; }
    }

    public static final class FinalizationOwner extends RejectedChargeOwner {
        private final long finalizationOrdinal;
        private final String componentIdentity;
        private final long componentGeneration;

        public FinalizationOwner(
                long finalizationOrdinal,
                String componentIdentity,
                long componentGeneration) {
            this.finalizationOrdinal = CanonicalOrders.requireSafeInteger(
                    finalizationOrdinal, "finalizationOrdinal");
            this.componentIdentity = Objects.requireNonNull(
                    componentIdentity, "componentIdentity");
            this.componentGeneration = CanonicalOrders.requireSafeInteger(
                    componentGeneration, "componentGeneration");
        }

        @Override public String kind() { return "FINALIZATION"; }
        public long finalizationOrdinal() { return finalizationOrdinal; }
        public String componentIdentity() { return componentIdentity; }
        public long componentGeneration() { return componentGeneration; }
    }

    /** Exact rejected charge, absent from the admitted gas trace. */
    public static final class RejectedCharge {
        private final String rejectedChargeIdentity;
        private final GasCharge.Namespace namespace;
        private final String counter;
        private final long quantity;
        private final long weight;
        private final long subtotal;
        private final ApplicableCap applicableCap;
        private final long remainingBeforeCharge;
        private final RejectedChargeOwner owner;

        public RejectedCharge(
                String rejectedChargeIdentity,
                GasCharge.Namespace namespace,
                String counter,
                long quantity,
                long weight,
                long subtotal,
                ApplicableCap applicableCap,
                long remainingBeforeCharge,
                RejectedChargeOwner owner) {
            this.rejectedChargeIdentity = Objects.requireNonNull(
                    rejectedChargeIdentity, "rejectedChargeIdentity");
            this.namespace = Objects.requireNonNull(namespace, "namespace");
            this.counter = Objects.requireNonNull(counter, "counter");
            this.quantity = CanonicalOrders.requireSafeInteger(quantity, "quantity");
            this.weight = CanonicalOrders.requireSafeInteger(weight, "weight");
            this.subtotal = CanonicalOrders.requireSafeInteger(subtotal, "subtotal");
            if (Math.multiplyExact(quantity, weight) != subtotal) {
                throw new IllegalArgumentException("subtotal");
            }
            this.applicableCap = Objects.requireNonNull(applicableCap, "applicableCap");
            this.remainingBeforeCharge = CanonicalOrders.requireSafeInteger(
                    remainingBeforeCharge, "remainingBeforeCharge");
            this.owner = Objects.requireNonNull(owner, "owner");
        }

        public String rejectedChargeIdentity() { return rejectedChargeIdentity; }
        public GasCharge.Namespace namespace() { return namespace; }
        public String counter() { return counter; }
        public long quantity() { return quantity; }
        public long weight() { return weight; }
        public long subtotal() { return subtotal; }
        public ApplicableCap applicableCap() { return applicableCap; }
        public long remainingBeforeCharge() { return remainingBeforeCharge; }
        public RejectedChargeOwner owner() { return owner; }
    }

    @SuppressWarnings("serial")
    public static final class GasLimitExceeded extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final RejectedCharge rejectedCharge;

        GasLimitExceeded(RejectedCharge rejectedCharge) {
            super("gas limit exceeded before " + rejectedCharge.counter());
            this.rejectedCharge = rejectedCharge;
        }

        public RejectedCharge rejectedCharge() { return rejectedCharge; }
    }
}
