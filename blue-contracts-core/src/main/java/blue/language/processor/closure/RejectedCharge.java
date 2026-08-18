package blue.language.processor.closure;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Complete exact gas charge rejected before admission to the trace. */
public final class RejectedCharge {

    private final String rejectedChargeIdentity;
    private final GasTraceEntry.Namespace namespace;
    private final String counter;
    private final long quantity;
    private final long weight;
    private final long subtotal;
    private final ApplicableCap applicableCap;
    private final long remainingBeforeCharge;
    private final Owner owner;

    /**
     * Creates and independently verifies one rejected charge.
     *
     * @param rejectedChargeIdentity asserted exact rejected-charge identity
     * @param namespace closed schedule namespace
     * @param counter non-empty schedule counter
     * @param quantity positive rejected quantity
     * @param weight exact non-negative unit weight
     * @param subtotal asserted exact product
     * @param applicableCap exact shared-or-local cap branch
     * @param remainingBeforeCharge exact remaining allowance
     * @param owner exact invocation, work, or finalization owner branch
     */
    public RejectedCharge(
            String rejectedChargeIdentity,
            GasTraceEntry.Namespace namespace,
            String counter,
            long quantity,
            long weight,
            long subtotal,
            ApplicableCap applicableCap,
            long remainingBeforeCharge,
            Owner owner) {
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        this.counter = ClosureValueSupport.requireNonEmptyText(
                counter, "counter");
        this.quantity = ClosureValueSupport.requirePositiveSafeInteger(
                quantity, "quantity");
        this.weight = ClosureValueSupport.requireSafeInteger(weight, "weight");
        this.subtotal = ClosureValueSupport.requireSafeInteger(
                subtotal, "subtotal");
        long product;
        try {
            product = Math.multiplyExact(quantity, weight);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(
                    "Rejected gas multiplication overflow", exception);
        }
        if (product != subtotal) {
            throw new IllegalArgumentException(
                    "subtotal must equal quantity multiplied by weight");
        }
        this.applicableCap = Objects.requireNonNull(
                applicableCap, "applicableCap");
        this.remainingBeforeCharge =
                ClosureValueSupport.requireSafeInteger(
                        remainingBeforeCharge,
                        "remainingBeforeCharge");
        if (this.subtotal <= this.remainingBeforeCharge) {
            throw new IllegalArgumentException(
                    "Rejected charge must exceed its remaining applicable cap");
        }
        this.owner = Objects.requireNonNull(owner, "owner");
        String asserted = ClosureValueSupport.requireSha256Identity(
                rejectedChargeIdentity, "rejectedChargeIdentity");
        String computed = ClosureIdentityService.INSTANCE.identity(
                ClosureIdentityService.Constructor.REJECTED_CHARGE,
                identityConstructorValue());
        if (!asserted.equals(computed)) {
            throw new IllegalArgumentException(
                    "rejectedChargeIdentity does not identify this charge");
        }
        this.rejectedChargeIdentity = asserted;
    }

    /**
     * Creates a charge while calculating its exact identity.
     *
     * @param namespace closed schedule namespace
     * @param counter non-empty schedule counter
     * @param quantity positive rejected quantity
     * @param weight exact non-negative unit weight
     * @param applicableCap exact shared-or-local cap branch
     * @param remainingBeforeCharge exact remaining allowance
     * @param owner exact charge owner
     * @return verified immutable rejected charge
     */
    public static RejectedCharge identified(
            GasTraceEntry.Namespace namespace,
            String counter,
            long quantity,
            long weight,
            ApplicableCap applicableCap,
            long remainingBeforeCharge,
            Owner owner) {
        long subtotal;
        try {
            subtotal = Math.multiplyExact(quantity, weight);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(
                    "Rejected gas multiplication overflow", exception);
        }
        LinkedHashMap<String, Object> value = constructorValue(
                namespace,
                counter,
                quantity,
                weight,
                subtotal,
                applicableCap,
                remainingBeforeCharge,
                owner);
        String identity = ClosureIdentityService.INSTANCE.identity(
                ClosureIdentityService.Constructor.REJECTED_CHARGE,
                value);
        return new RejectedCharge(
                identity,
                namespace,
                counter,
                quantity,
                weight,
                subtotal,
                applicableCap,
                remainingBeforeCharge,
                owner);
    }

    /**
     * Returns exact rejected-charge identity.
     *
     * @return exact rejected-charge identity
     */
    public String rejectedChargeIdentity() {
        return rejectedChargeIdentity;
    }

    /**
     * Returns closed schedule namespace.
     *
     * @return closed schedule namespace
     */
    public GasTraceEntry.Namespace namespace() {
        return namespace;
    }

    /**
     * Returns exact schedule counter.
     *
     * @return exact schedule counter
     */
    public String counter() {
        return counter;
    }

    /**
     * Returns positive rejected quantity.
     *
     * @return positive rejected quantity
     */
    public long quantity() {
        return quantity;
    }

    /**
     * Returns exact unit weight.
     *
     * @return exact unit weight
     */
    public long weight() {
        return weight;
    }

    /**
     * Returns exact rejected subtotal.
     *
     * @return exact rejected subtotal
     */
    public long subtotal() {
        return subtotal;
    }

    /**
     * Returns exact shared-or-local cap branch.
     *
     * @return exact shared-or-local cap branch
     */
    public ApplicableCap applicableCap() {
        return applicableCap;
    }

    /**
     * Returns allowance immediately before rejection.
     *
     * @return allowance immediately before rejection
     */
    public long remainingBeforeCharge() {
        return remainingBeforeCharge;
    }

    /**
     * Returns exact closed charge-owner branch.
     *
     * @return exact closed charge-owner branch
     */
    public Owner owner() {
        return owner;
    }

    Map<String, Object> identityConstructorValue() {
        return constructorValue(
                namespace,
                counter,
                quantity,
                weight,
                subtotal,
                applicableCap,
                remainingBeforeCharge,
                owner);
    }

    private static LinkedHashMap<String, Object> constructorValue(
            GasTraceEntry.Namespace namespace,
            String counter,
            long quantity,
            long weight,
            long subtotal,
            ApplicableCap applicableCap,
            long remainingBeforeCharge,
            Owner owner) {
        LinkedHashMap<String, Object> value =
                new LinkedHashMap<String, Object>();
        value.put("namespace", Objects.requireNonNull(
                namespace, "namespace").wireValue());
        value.put("counter", counter);
        value.put("quantity", Long.valueOf(quantity));
        value.put("weight", Long.valueOf(weight));
        value.put("subtotal", Long.valueOf(subtotal));
        value.put("applicableCap", Objects.requireNonNull(
                applicableCap, "applicableCap").identityValue());
        value.put("remainingBeforeCharge",
                Long.valueOf(remainingBeforeCharge));
        value.put("owner", Objects.requireNonNull(
                owner, "owner").identityValue());
        return value;
    }

    /** Closed applicable-cap union. */
    public abstract static class ApplicableCap {

        /** Stable cap branch kind. */
        public enum Kind {
            /** Shared closure cap. */
            SHARED,
            /** Lower document-local cap. */
            LOCAL
        }

        private final Kind kind;

        private ApplicableCap(Kind kind) {
            this.kind = kind;
        }

        /**
         * Returns shared cap branch.
         *
         * @return shared cap branch
         */
        public static ApplicableCap shared() {
            return SharedCap.INSTANCE;
        }

        /**
         * Creates a document-local cap branch.
         *
         * @param documentId exact owning document
         * @return local cap branch
         */
        public static ApplicableCap local(DocumentId documentId) {
            return new LocalCap(documentId);
        }

        /**
         * Returns closed cap kind.
         *
         * @return closed cap kind
         */
        public final Kind kind() {
            return kind;
        }

        /**
         * Returns local owning document, or {@code null} for shared.
         *
         * @return local owning document, or {@code null} for shared
         */
        public DocumentId documentId() {
            return null;
        }

        abstract Map<String, Object> identityValue();
    }

    /** Closed SHARED cap branch. */
    public static final class SharedCap extends ApplicableCap {
        private static final SharedCap INSTANCE = new SharedCap();

        private SharedCap() {
            super(Kind.SHARED);
        }

        @Override
        Map<String, Object> identityValue() {
            LinkedHashMap<String, Object> value =
                    new LinkedHashMap<String, Object>();
            value.put("kind", Kind.SHARED.name());
            return value;
        }
    }

    /** Closed LOCAL cap branch. */
    public static final class LocalCap extends ApplicableCap {
        private final DocumentId documentId;

        private LocalCap(DocumentId documentId) {
            super(Kind.LOCAL);
            this.documentId = Objects.requireNonNull(
                    documentId, "documentId");
        }

        /** {@inheritDoc} */
        @Override
        public DocumentId documentId() {
            return documentId;
        }

        @Override
        Map<String, Object> identityValue() {
            LinkedHashMap<String, Object> value =
                    new LinkedHashMap<String, Object>();
            value.put("kind", Kind.LOCAL.name());
            value.put("documentId", documentId.value());
            return value;
        }
    }

    /** Closed rejected-charge owner union. */
    public abstract static class Owner {

        /** Stable owner branch kind. */
        public enum Kind {
            /** Invocation-wide pre-work charge. */
            INVOCATION,
            /** Charge owned by one work occurrence. */
            WORK,
            /** Charge owned by one tentative finalization occurrence. */
            FINALIZATION
        }

        private final Kind kind;

        private Owner(Kind kind) {
            this.kind = kind;
        }

        /**
         * Returns invocation-owned branch.
         *
         * @return invocation-owned branch
         */
        public static Owner invocation() {
            return InvocationOwner.INSTANCE;
        }

        /**
         * Creates a work-owned branch.
         *
         * @param workOccurrenceIdentity exact rejected work identity
         * @return work owner
         */
        public static Owner work(String workOccurrenceIdentity) {
            return new WorkOwner(workOccurrenceIdentity);
        }

        /**
         * Creates a finalization-owned branch.
         *
         * @param finalizationOrdinal exact attempted finalization ordinal
         * @param componentIdentity stable component identity
         * @param componentGeneration exact component generation
         * @return finalization owner
         */
        public static Owner finalization(
                long finalizationOrdinal,
                String componentIdentity,
                long componentGeneration) {
            return new FinalizationOwner(
                    finalizationOrdinal,
                    componentIdentity,
                    componentGeneration);
        }

        /**
         * Returns closed owner kind.
         *
         * @return closed owner kind
         */
        public final Kind kind() {
            return kind;
        }

        /**
         * Returns work identity, or {@code null}.
         *
         * @return work identity, or {@code null}
         */
        public String workOccurrenceIdentity() {
            return null;
        }

        /**
         * Returns finalization ordinal, or {@code null}.
         *
         * @return finalization ordinal, or {@code null}
         */
        public Long finalizationOrdinal() {
            return null;
        }

        /**
         * Returns component identity, or {@code null}.
         *
         * @return component identity, or {@code null}
         */
        public String componentIdentity() {
            return null;
        }

        /**
         * Returns component generation, or {@code null}.
         *
         * @return component generation, or {@code null}
         */
        public Long componentGeneration() {
            return null;
        }

        abstract Map<String, Object> identityValue();
    }

    /** Closed INVOCATION owner branch. */
    public static final class InvocationOwner extends Owner {
        private static final InvocationOwner INSTANCE = new InvocationOwner();

        private InvocationOwner() {
            super(Kind.INVOCATION);
        }

        @Override
        Map<String, Object> identityValue() {
            LinkedHashMap<String, Object> value =
                    new LinkedHashMap<String, Object>();
            value.put("kind", Kind.INVOCATION.name());
            return value;
        }
    }

    /** Closed WORK owner branch. */
    public static final class WorkOwner extends Owner {
        private final String workOccurrenceIdentity;

        private WorkOwner(String workOccurrenceIdentity) {
            super(Kind.WORK);
            this.workOccurrenceIdentity =
                    ClosureValueSupport.requireSha256Identity(
                            workOccurrenceIdentity,
                            "workOccurrenceIdentity");
        }

        /** {@inheritDoc} */
        @Override
        public String workOccurrenceIdentity() {
            return workOccurrenceIdentity;
        }

        @Override
        Map<String, Object> identityValue() {
            LinkedHashMap<String, Object> value =
                    new LinkedHashMap<String, Object>();
            value.put("kind", Kind.WORK.name());
            value.put("workOccurrenceIdentity", workOccurrenceIdentity);
            return value;
        }
    }

    /** Closed FINALIZATION owner branch. */
    public static final class FinalizationOwner extends Owner {
        private final long finalizationOrdinal;
        private final String componentIdentity;
        private final long componentGeneration;

        private FinalizationOwner(
                long finalizationOrdinal,
                String componentIdentity,
                long componentGeneration) {
            super(Kind.FINALIZATION);
            this.finalizationOrdinal =
                    ClosureValueSupport.requireSafeInteger(
                            finalizationOrdinal,
                            "finalizationOrdinal");
            this.componentIdentity =
                    ClosureValueSupport.requireSha256Identity(
                            componentIdentity, "componentIdentity");
            this.componentGeneration =
                    ClosureValueSupport.requireSafeInteger(
                            componentGeneration,
                            "componentGeneration");
        }

        /** {@inheritDoc} */
        @Override
        public Long finalizationOrdinal() {
            return Long.valueOf(finalizationOrdinal);
        }

        /** {@inheritDoc} */
        @Override
        public String componentIdentity() {
            return componentIdentity;
        }

        /** {@inheritDoc} */
        @Override
        public Long componentGeneration() {
            return Long.valueOf(componentGeneration);
        }

        @Override
        Map<String, Object> identityValue() {
            LinkedHashMap<String, Object> value =
                    new LinkedHashMap<String, Object>();
            value.put("kind", Kind.FINALIZATION.name());
            value.put("finalizationOrdinal",
                    Long.valueOf(finalizationOrdinal));
            value.put("componentIdentity", componentIdentity);
            value.put("componentGeneration",
                    Long.valueOf(componentGeneration));
            return value;
        }
    }
}
