package blue.language.processor;

import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Shared live-bounded named gas ledger for one processing invocation.
 *
 * <p>Every charge is admitted and appended before its corresponding work. A
 * rejected charge is never present in {@link #trace()}.</p>
 */
public final class GasMeter {

    private final GasSchedule schedule;
    private final long gasLimit;
    private final List<GasTraceEntry> trace = new ArrayList<>();
    private final SemanticGasMeter semantic;
    private final ProcessorGasCharges processorCharges;
    private Map<String, Long> localGasLimits = Collections.emptyMap();
    private final Map<String, Long> admittedLocalGas =
            new LinkedHashMap<>();
    private final Map<String, Long> reservedLocalGas =
            new LinkedHashMap<>();
    private boolean localGasLimitsConfigured;
    private long totalGas;
    private GasChargeContext defaultAttribution = GasChargeContext.empty();
    /*
     * Runtime work sessions stage their ordered child traces until the
     * processor decides whether the execution unit completed, failed
     * deterministically, or was suspended for missing evidence.  Reservations
     * keep that staged work live-bounded without making it observable in the
     * parent trace before the lifecycle decision.
     */
    private long reservedRuntimeGas;

    /**
     * Creates a meter with the bound Contracts 1.0 schedule and its maximum budget.
     */
    public GasMeter() {
        this(GasSchedule.contracts10());
    }

    /**
     * Creates a meter using a schedule's maximum PROCESS budget.
     *
     * @param schedule immutable named-counter schedule
     */
    public GasMeter(GasSchedule schedule) {
        this(schedule, Objects.requireNonNull(schedule, "schedule").maxProcessGas());
    }

    /**
     * Creates a meter with an explicit budget not exceeding the schedule maximum.
     *
     * @param schedule immutable named-counter schedule
     * @param gasLimit non-negative invocation budget
     * @throws IllegalArgumentException when the budget is outside schedule bounds
     */
    public GasMeter(GasSchedule schedule, long gasLimit) {
        this.schedule = Objects.requireNonNull(schedule, "schedule");
        if (gasLimit < 0L || gasLimit > schedule.maxProcessGas()) {
            throw new IllegalArgumentException(
                    "Gas limit must be between 0 and manifest maxProcessGas "
                            + schedule.maxProcessGas());
        }
        this.gasLimit = gasLimit;
        this.semantic = new SemanticGasMeter(this);
        this.processorCharges = new ProcessorGasCharges(this);
    }

    /**
     * Returns the immutable schedule used to price this invocation.
     *
     * @return immutable schedule bound to this invocation
     */
    public GasSchedule schedule() {
        return schedule;
    }

    /**
     * Returns the maximum gas this invocation may admit.
     *
     * @return configured invocation gas limit
     */
    public long gasLimit() {
        return gasLimit;
    }

    /**
     * Returns the exact gas already admitted to the parent trace.
     *
     * @return exact gas admitted to the parent trace
     */
    public long totalGas() {
        return totalGas;
    }

    /**
     * Returns the budget that remains available after charges and reservations.
     *
     * @return budget not yet charged or reserved by runtime sessions
     */
    public long remainingGas() {
        return gasLimit - totalGas - reservedRuntimeGas;
    }

    /**
     * Returns this invocation's semantic formula meter.  The returned object
     * shares this meter's live limit and owns only run-local memoization.
     *
     * @return invocation-local semantic meter
     */
    public SemanticGasMeter semantic() {
        return semantic;
    }

    /**
     * Returns an immutable point-in-time copy of the admitted charge trace.
     *
     * @return immutable snapshot of admitted entries in sequence order
     */
    public List<GasTraceEntry> trace() {
        return Collections.unmodifiableList(new ArrayList<>(trace));
    }

    void configureLocalGasLimits(Map<String, Long> limitsByDocumentId) {
        Objects.requireNonNull(limitsByDocumentId, "limitsByDocumentId");
        if (localGasLimitsConfigured) {
            throw new IllegalStateException(
                    "Document-local gas limits were already configured");
        }
        if (totalGas != 0L || reservedRuntimeGas != 0L || !trace.isEmpty()) {
            throw new IllegalStateException(
                    "Document-local gas limits must be configured before gas work");
        }
        TreeMap<String, Long> sorted = new TreeMap<>();
        for (Map.Entry<String, Long> entry : limitsByDocumentId.entrySet()) {
            String documentId = Objects.requireNonNull(
                    entry.getKey(), "local gas documentId");
            Long boxedLimit = Objects.requireNonNull(
                    entry.getValue(), "local gas limit");
            long localLimit = boxedLimit.longValue();
            if (documentId.isEmpty()) {
                throw new IllegalArgumentException(
                        "Local gas documentId must not be empty");
            }
            if (localLimit < 0L || localLimit > gasLimit) {
                throw new IllegalArgumentException(
                        "Document-local gas limit must be between 0 and the "
                                + "shared gas limit " + gasLimit);
            }
            sorted.put(documentId, Long.valueOf(localLimit));
        }
        localGasLimits = Collections.unmodifiableMap(
                new LinkedHashMap<>(sorted));
        localGasLimitsConfigured = true;
    }

    AttributionScope withAttribution(GasChargeContext attribution) {
        GasChargeContext previous = defaultAttribution;
        defaultAttribution = Objects.requireNonNull(
                attribution, "attribution")
                .withAttributionDefaults(previous);
        return new AttributionScope(this, previous);
    }

    GasChargeContext resolveAttribution(GasChargeContext context) {
        return (context != null ? context : GasChargeContext.empty())
                .withAttributionDefaults(defaultAttribution);
    }

    /**
     * Charges a named counter without semantic attribution.
     *
     * @param namespace schedule namespace
     * @param counter schedule counter
     * @param quantity non-negative quantity
     * @throws GasLimitExceededException before mutation when budget is insufficient
     */
    public void charge(String namespace, String counter, long quantity) {
        charge(namespace, counter, quantity, GasChargeContext.empty());
    }

    /**
     * Charges a named counter with deterministic attribution.
     *
     * @param namespace schedule namespace
     * @param counter schedule counter
     * @param quantity non-negative quantity
     * @param context immutable attribution context
     * @throws IllegalArgumentException for an unknown counter or invalid quantity
     * @throws GasLimitExceededException before mutation when budget is insufficient
     */
    public void charge(String namespace,
                       String counter,
                       long quantity,
                       GasChargeContext context) {
        long weight = schedule.weight(namespace, counter);
        chargeWeighted(namespace, counter, quantity, weight, context);
    }

    /**
     * Creates a child runtime ledger with exactly the currently remaining
     * budget. The child must be merged exactly once.
     *
     * @param runtimeNamespace non-core runtime namespace
     * @param counterWeights complete immutable counter catalog copied by the ledger
     * @return detached child ledger with a snapshot of remaining budget
     */
    public ChildGasLedger childLedger(String runtimeNamespace,
                                      Map<String, Long> counterWeights) {
        return new ChildGasLedger(runtimeNamespace, counterWeights, remainingGas());
    }

    ChildGasLedger sessionChildLedger(
            String runtimeNamespace,
            Map<String, Long> counterWeights,
            Object ownerToken,
            ChildAdmissionController admissionController) {
        return new ChildGasLedger(
                runtimeNamespace,
                counterWeights,
                remainingGas(),
                Objects.requireNonNull(ownerToken, "ownerToken"),
                Objects.requireNonNull(
                        admissionController, "admissionController"));
    }

    /**
     * Merges a completed runtime child ledger once in its original order.
     *
     * @param child detached child ledger to consume
     * @throws IllegalStateException when the child was already consumed
     */
    public void merge(ChildGasLedger child) {
        Objects.requireNonNull(child, "child");
        List<ChildGasLedger.Entry> entries = child.takeForMerge(null);
        for (ChildGasLedger.Entry entry : entries) {
            chargeWeighted(child.namespace(),
                    entry.counter,
                    entry.quantity,
                    entry.weight,
                    entry.context);
        }
    }

    void mergeReserved(ChildGasLedger child, Object ownerToken) {
        Objects.requireNonNull(child, "child");
        List<ChildGasLedger.Entry> entries =
                child.takeForMerge(
                        Objects.requireNonNull(ownerToken, "ownerToken"));
        for (ChildGasLedger.Entry entry : entries) {
            long subtotal = multiplyExact(entry.quantity, entry.weight);
            releaseRuntimeReservation(subtotal, entry.context);
            chargeWeighted(child.namespace(),
                    entry.counter,
                    entry.quantity,
                    entry.weight,
                    entry.context);
        }
    }

    void discardReserved(ChildGasLedger child, Object ownerToken) {
        Objects.requireNonNull(child, "child");
        List<ChildGasLedger.Entry> entries = child.takeForDiscard(
                Objects.requireNonNull(ownerToken, "ownerToken"));
        for (ChildGasLedger.Entry entry : entries) {
            releaseRuntimeReservation(
                    multiplyExact(entry.quantity, entry.weight),
                    entry.context);
        }
    }

    void ensureRuntimeGasAdmissible(String namespace,
                                    String counter,
                                    long quantity,
                                    long weight,
                                    long subtotal,
                                    GasChargeContext context) {
        rejectIfCapExceeded(
                namespace,
                counter,
                quantity,
                weight,
                subtotal,
                resolveAttribution(context));
    }

    void reserveRuntimeGas(String namespace,
                           String counter,
                           long quantity,
                           long weight,
                           long subtotal,
                           GasChargeContext context) {
        GasChargeContext exactContext = resolveAttribution(context);
        rejectIfCapExceeded(
                namespace,
                counter,
                quantity,
                weight,
                subtotal,
                exactContext);
        reservedRuntimeGas += subtotal;
        addLocalGas(reservedLocalGas, exactContext, subtotal);
    }

    private void releaseRuntimeReservation(long subtotal,
                                           GasChargeContext context) {
        if (subtotal < 0L || subtotal > reservedRuntimeGas) {
            throw new IllegalStateException(
                    "Runtime gas reservation accounting mismatch");
        }
        reservedRuntimeGas -= subtotal;
        removeLocalGas(reservedLocalGas, context, subtotal);
    }

    boolean matchesCurrentCapRejection(
            GasLimitExceededException rejection) {
        Objects.requireNonNull(rejection, "rejection");
        if (rejection.applicableCapKind()
                == GasLimitExceededException.ApplicableCapKind.SHARED) {
            long admitted = totalGas + reservedRuntimeGas;
            return rejection.localDocumentId() == null
                    && rejection.effectiveBudget() == gasLimit
                    && rejection.admittedGas() == admitted
                    && rejection.remainingBeforeCharge()
                    == gasLimit - admitted;
        }
        String documentId = rejection.localDocumentId();
        Long limit = localGasLimits.get(documentId);
        if (limit == null) {
            return false;
        }
        long admitted = localGas(admittedLocalGas, documentId)
                + localGas(reservedLocalGas, documentId);
        return rejection.effectiveBudget() == limit.longValue()
                && rejection.admittedGas() == admitted
                && rejection.remainingBeforeCharge()
                == limit.longValue() - admitted;
    }

    GasMeter diagnosticTwin(long sharedBudget) {
        GasMeter twin = new GasMeter(schedule, sharedBudget);
        if (localGasLimitsConfigured) {
            Map<String, Long> remainingLocalLimits = new LinkedHashMap<>();
            for (Map.Entry<String, Long> entry : localGasLimits.entrySet()) {
                String documentId = entry.getKey();
                long admitted = localGas(admittedLocalGas, documentId)
                        + localGas(reservedLocalGas, documentId);
                long remaining = entry.getValue().longValue() - admitted;
                remainingLocalLimits.put(
                        documentId,
                        Long.valueOf(Math.min(remaining, sharedBudget)));
            }
            twin.configureLocalGasLimits(remainingLocalLimits);
        }
        twin.defaultAttribution = defaultAttribution;
        return twin;
    }

    void chargeProcessInvocation() {
        processorCharges.processInvocation();
    }

    void chargeDeliverySnapshotEntry(String scopePath, String contractKey) {
        processorCharges.deliverySnapshotEntry(scopePath, contractKey);
    }

    void chargeScopeEntry(String scopePath) {
        processorCharges.scopeEntry(scopePath);
    }

    void chargeParticipatingClosure(long quantity) {
        processorCharges.participatingClosure(quantity);
    }

    void chargeContractHeaderRecognized(String scopePath,
                                        String contractKey,
                                        String reason) {
        processorCharges.contractHeaderRecognized(scopePath, contractKey, reason);
    }

    void chargeContractHeadersRecognized(long quantity, String reason) {
        processorCharges.contractHeadersRecognized(quantity, reason);
    }

    void chargeEmbeddedPathEntryRead(String scopePath, String logicalPath) {
        processorCharges.embeddedPathEntryRead(scopePath, logicalPath);
    }

    void chargeEmbeddedPathSegmentsValidated(String scopePath,
                                             String logicalPath,
                                             long quantity) {
        processorCharges.embeddedPathSegmentsValidated(
                scopePath, logicalPath, quantity);
    }

    void chargeScopeEntry(int embeddedDepth) {
        processorCharges.scopeEntry(embeddedDepth);
    }

    void chargeInitialization(String scopePath) {
        processorCharges.initialization(scopePath);
    }

    void chargeChannelMatchAttempt(String scopePath, String contractKey) {
        processorCharges.channelMatchAttempt(scopePath, contractKey);
    }

    void chargeChannelAccepted(String scopePath, String contractKey) {
        processorCharges.channelAccepted(scopePath, contractKey);
    }

    void chargeHandlerCandidateTested(String scopePath, String contractKey) {
        processorCharges.handlerCandidateTested(scopePath, contractKey);
    }

    void chargeHandlerOverhead(String scopePath, String contractKey) {
        processorCharges.handlerOverhead(scopePath, contractKey);
    }

    void chargeBoundaryCheck() {
        processorCharges.boundaryCheck();
    }

    void chargePointerSegments(long quantity, String logicalPath) {
        processorCharges.pointerSegments(quantity, logicalPath);
    }

    void chargePatchAddOrReplace(Node ignoredValue) {
        processorCharges.patchAddOrReplace(ignoredValue);
    }

    void chargeFrozenPatchAddOrReplace(FrozenNode ignoredValue) {
        processorCharges.frozenPatchAddOrReplace(ignoredValue);
    }

    void chargeFrozenPatchAddOrReplace(long ignoredAuthoredCanonicalSizeBytes) {
        processorCharges.frozenPatchAddOrReplace(
                ignoredAuthoredCanonicalSizeBytes);
    }

    void chargePatchRemove() {
        processorCharges.patchRemove();
    }

    void chargeCascadeRouting(int matchingDeliveryCount) {
        processorCharges.cascadeRouting(matchingDeliveryCount);
    }

    void chargeEmitEvent(Node ignoredEvent) {
        processorCharges.emitEvent(ignoredEvent);
    }

    void chargeRootEventRecorded() {
        processorCharges.rootEventRecorded();
    }

    void chargeBridge(Node ignoredEvent) {
        processorCharges.bridge(ignoredEvent);
    }

    void chargeTriggeredDelivery() {
        processorCharges.triggeredDelivery();
    }

    void chargeDrainEvent() {
        processorCharges.drainEvent();
    }

    void chargeCheckpointCompared() {
        processorCharges.checkpointCompared();
    }

    void chargeCheckpointUpdate() {
        processorCharges.checkpointUpdate();
    }

    void chargeProcessorMarkerWritten(String reason) {
        processorCharges.processorMarkerWritten(reason);
    }

    void chargeTerminationRequest() {
        processorCharges.terminationRequest();
    }

    void chargeTerminationMarker() {
        processorCharges.terminationMarker();
    }

    void chargeLifecycleDelivery() {
        processorCharges.lifecycleDelivery();
    }

    private void chargeWeighted(String namespace,
                                String counter,
                                long quantity,
                                long weight,
                                GasChargeContext context) {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(counter, "counter");
        if (namespace.isEmpty() || counter.isEmpty()) {
            throw new IllegalArgumentException("Gas namespace and counter must not be empty");
        }
        if (quantity < 0L) {
            throw new IllegalArgumentException(
                    "Gas quantity must be non-negative");
        }
        if (weight <= 0L) {
            throw new IllegalArgumentException(
                    "Gas weight must be positive");
        }
        if (quantity == 0L) {
            return;
        }
        long subtotal = multiplyExact(quantity, weight);
        GasChargeContext exactContext = resolveAttribution(context);
        rejectIfCapExceeded(
                namespace,
                counter,
                quantity,
                weight,
                subtotal,
                exactContext);
        trace.add(new GasTraceEntry(trace.size(),
                namespace,
                counter,
                quantity,
                weight,
                subtotal,
                exactContext));
        totalGas += subtotal;
        addLocalGas(admittedLocalGas, exactContext, subtotal);
    }

    private void rejectIfCapExceeded(String namespace,
                                     String counter,
                                     long quantity,
                                     long weight,
                                     long subtotal,
                                     GasChargeContext context) {
        long sharedRemaining = remainingGas();
        LocalAllowance local = localAllowance(context);
        long localRemaining = local != null
                ? local.remaining
                : Long.MAX_VALUE;
        if (subtotal <= sharedRemaining && subtotal <= localRemaining) {
            return;
        }
        if (local != null && localRemaining < sharedRemaining) {
            throw new GasLimitExceededException(
                    namespace,
                    counter,
                    quantity,
                    weight,
                    local.admitted,
                    local.limit,
                    GasLimitExceededException.ApplicableCapKind.LOCAL,
                    local.documentId,
                    localRemaining);
        }
        long sharedAdmitted = totalGas + reservedRuntimeGas;
        throw new GasLimitExceededException(
                namespace,
                counter,
                quantity,
                weight,
                sharedAdmitted,
                gasLimit,
                GasLimitExceededException.ApplicableCapKind.SHARED,
                null,
                sharedRemaining);
    }

    private LocalAllowance localAllowance(GasChargeContext context) {
        String documentId = context != null ? context.documentId() : null;
        if (documentId == null) {
            return null;
        }
        Long limit = localGasLimits.get(documentId);
        if (limit == null) {
            return null;
        }
        long admitted = localGas(admittedLocalGas, documentId)
                + localGas(reservedLocalGas, documentId);
        return new LocalAllowance(
                documentId,
                limit.longValue(),
                admitted,
                limit.longValue() - admitted);
    }

    private void addLocalGas(Map<String, Long> gasByDocument,
                             GasChargeContext context,
                             long subtotal) {
        LocalAllowance local = localAllowanceWithoutReservations(
                context, gasByDocument);
        if (local == null) {
            return;
        }
        gasByDocument.put(
                local.documentId,
                Long.valueOf(local.admitted + subtotal));
    }

    private void removeLocalGas(Map<String, Long> gasByDocument,
                                GasChargeContext context,
                                long subtotal) {
        String documentId = context != null ? context.documentId() : null;
        if (documentId == null || !localGasLimits.containsKey(documentId)) {
            return;
        }
        long current = localGas(gasByDocument, documentId);
        if (subtotal < 0L || subtotal > current) {
            throw new IllegalStateException(
                    "Document-local runtime gas reservation mismatch");
        }
        long updated = current - subtotal;
        if (updated == 0L) {
            gasByDocument.remove(documentId);
        } else {
            gasByDocument.put(documentId, Long.valueOf(updated));
        }
    }

    private LocalAllowance localAllowanceWithoutReservations(
            GasChargeContext context,
            Map<String, Long> gasByDocument) {
        String documentId = context != null ? context.documentId() : null;
        if (documentId == null) {
            return null;
        }
        Long limit = localGasLimits.get(documentId);
        if (limit == null) {
            return null;
        }
        long admitted = localGas(gasByDocument, documentId);
        return new LocalAllowance(
                documentId,
                limit.longValue(),
                admitted,
                limit.longValue() - admitted);
    }

    private static long localGas(Map<String, Long> gasByDocument,
                                 String documentId) {
        Long value = gasByDocument.get(documentId);
        return value != null ? value.longValue() : 0L;
    }

    private static final class LocalAllowance {
        private final String documentId;
        private final long limit;
        private final long admitted;
        private final long remaining;

        private LocalAllowance(String documentId,
                               long limit,
                               long admitted,
                               long remaining) {
            this.documentId = documentId;
            this.limit = limit;
            this.admitted = admitted;
            this.remaining = remaining;
        }
    }

    /** Lexically scoped default attribution for one closure work frame. */
    static final class AttributionScope implements AutoCloseable {
        private final GasMeter meter;
        private final GasChargeContext previous;
        private boolean closed;

        private AttributionScope(
                GasMeter meter,
                GasChargeContext previous) {
            this.meter = meter;
            this.previous = previous;
        }

        @Override
        public void close() {
            if (closed) {
                throw new IllegalStateException(
                        "Gas attribution scope was already closed");
            }
            closed = true;
            meter.defaultAttribution = previous;
        }
    }

    private static long multiplyExact(long left, long right) {
        if (left != 0L && right > Long.MAX_VALUE / left) {
            throw new IllegalArgumentException("Gas subtotal exceeds long range");
        }
        return left * right;
    }

    /**
     * Runtime-owned, named child ledger. It is deliberately detached from
     * document access and can only be merged once.
     */
    public static final class ChildGasLedger {
        private final String namespace;
        private final Map<String, Long> weights;
        private final long gasLimit;
        private final Object ownerToken;
        private final ChildAdmissionController admissionController;
        private final List<Entry> entries = new ArrayList<>();
        private long totalGas;
        private boolean merged;

        private ChildGasLedger(String namespace,
                               Map<String, Long> counterWeights,
                               long gasLimit) {
            this(namespace, counterWeights, gasLimit, null, null);
        }

        private ChildGasLedger(String namespace,
                               Map<String, Long> counterWeights,
                               long gasLimit,
                               Object ownerToken,
                               ChildAdmissionController admissionController) {
            this.namespace = Objects.requireNonNull(namespace, "namespace");
            if (namespace.isEmpty()
                    || GasScheduleConstants.Namespace.PROCESSOR.equals(
                    namespace)
                    || GasScheduleConstants.Namespace.SEMANTIC.equals(
                    namespace)) {
                throw new IllegalArgumentException(
                        "Runtime child namespace must be non-empty and disjoint");
            }
            Objects.requireNonNull(counterWeights, "counterWeights");
            Map<String, Long> copy = new LinkedHashMap<>();
            for (Map.Entry<String, Long> entry : counterWeights.entrySet()) {
                String counter = Objects.requireNonNull(entry.getKey(), "counter");
                Long weight = Objects.requireNonNull(entry.getValue(), "weight");
                if (counter.isEmpty() || weight <= 0L) {
                    throw new IllegalArgumentException(
                            "Runtime counter names must be non-empty and "
                                    + "weights must be positive");
                }
                copy.put(counter, weight);
            }
            this.weights = Collections.unmodifiableMap(copy);
            this.gasLimit = gasLimit;
            this.ownerToken = ownerToken;
            this.admissionController = admissionController;
        }

        /**
         * Returns the runtime namespace isolated by this child ledger.
         *
         * @return runtime namespace owned by this ledger
         */
        public String namespace() {
            return namespace;
        }

        /**
         * Returns the exact gas already admitted to this child ledger.
         *
         * @return exact gas admitted to this child
         */
        public long totalGas() {
            return totalGas;
        }

        /**
         * Returns the child budget that is still available for admission.
         *
         * @return child budget not yet admitted
         */
        public long remainingGas() {
            return gasLimit - totalGas;
        }

        /**
         * Returns the exact parent budget captured when this ledger was
         * opened.
         *
         * @return immutable effective child budget
         */
        public long effectiveBudget() {
            return gasLimit;
        }

        /**
         * Returns the immutable counter catalog bound to this ledger.
         *
         * @return immutable counter-to-weight mapping
         */
        public Map<String, Long> counterWeights() {
            return weights;
        }

        /**
         * Charges a runtime counter without semantic attribution.
         *
         * @param counter bound runtime counter
         * @param quantity non-negative quantity
         * @throws GasLimitExceededException before mutation when budget is insufficient
         */
        public void charge(String counter, long quantity) {
            charge(counter, quantity, GasChargeContext.empty());
        }

        /**
         * Charges a runtime counter with deterministic attribution.
         *
         * @param counter bound runtime counter
         * @param quantity non-negative quantity
         * @param context immutable attribution context
         * @throws IllegalStateException after this child has been consumed
         * @throws IllegalArgumentException for an unknown counter or invalid quantity
         * @throws GasLimitExceededException before mutation when budget is insufficient
         */
        public void charge(String counter, long quantity, GasChargeContext context) {
            ensureUnmerged();
            if (admissionController != null) {
                admissionController.ensureChargeable(this);
            }
            Long weight = weights.get(counter);
            if (weight == null) {
                throw new IllegalArgumentException(
                        "Unknown runtime gas counter " + namespace + "." + counter);
            }
            if (quantity < 0L) {
                throw new IllegalArgumentException("Gas quantity must be non-negative");
            }
            if (quantity == 0L) {
                return;
            }
            GasChargeContext exactContext =
                    context != null ? context : GasChargeContext.empty();
            if (admissionController != null) {
                exactContext = admissionController.resolveAttribution(
                        this, exactContext);
            }
            long subtotal = multiplyExact(quantity, weight);
            if (admissionController != null) {
                try {
                    admissionController.ensureWithinLocalBudget(
                            this,
                            counter,
                            quantity,
                            weight,
                            subtotal,
                            exactContext);
                } catch (GasLimitExceededException rejection) {
                    admissionController.rejected(
                            this, rejection);
                    throw rejection;
                }
            }
            if (subtotal > gasLimit - totalGas) {
                GasLimitExceededException rejection =
                        new GasLimitExceededException(
                        namespace, counter, quantity, weight, totalGas, gasLimit);
                if (admissionController != null) {
                    admissionController.rejected(
                            this, rejection);
                }
                throw rejection;
            }
            if (admissionController != null) {
                try {
                    admissionController.beforeCharge(
                            this,
                            counter,
                            quantity,
                            weight,
                            subtotal,
                            exactContext);
                } catch (GasLimitExceededException rejection) {
                    admissionController.rejected(
                            this, rejection);
                    throw rejection;
                }
            }
            entries.add(new Entry(counter, quantity, weight,
                    exactContext));
            totalGas += subtotal;
        }

        private List<Entry> takeForMerge(Object requesterToken) {
            ensureUnmerged();
            requireOwner(requesterToken);
            merged = true;
            return new ArrayList<>(entries);
        }

        private List<Entry> takeForDiscard(Object requesterToken) {
            ensureUnmerged();
            requireOwner(requesterToken);
            merged = true;
            return new ArrayList<>(entries);
        }

        List<GasTraceEntry> snapshotTrace(
                Object requesterToken) {
            ensureUnmerged();
            requireOwner(requesterToken);
            List<GasTraceEntry> trace =
                    new ArrayList<>(entries.size());
            for (Entry entry : entries) {
                trace.add(new GasTraceEntry(
                        trace.size(),
                        namespace,
                        entry.counter,
                        entry.quantity,
                        entry.weight,
                        multiplyExact(
                                entry.quantity,
                                entry.weight),
                        entry.context));
            }
            return trace;
        }

        private void requireOwner(Object requesterToken) {
            if (ownerToken == null) {
                if (requesterToken != null) {
                    throw new IllegalArgumentException(
                            "Standalone runtime ledger has no session owner");
                }
                return;
            }
            if (ownerToken != requesterToken) {
                throw new IllegalArgumentException(
                        "Runtime child ledger belongs to a different work session");
            }
        }

        private void ensureUnmerged() {
            if (merged) {
                throw new IllegalStateException("Runtime child ledger was already merged");
            }
        }

        private static final class Entry {
            private final String counter;
            private final long quantity;
            private final long weight;
            private final GasChargeContext context;

            private Entry(String counter,
                          long quantity,
                          long weight,
                          GasChargeContext context) {
                this.counter = counter;
                this.quantity = quantity;
                this.weight = weight;
                this.context = context;
            }
        }
    }

    /**
     * Coordinates charges from an invocation-owned child ledger with the
     * authoritative runtime-work admission boundary.
     */
    interface ChildAdmissionController {

        /** Verifies that the child ledger may still accept a charge. */
        void ensureChargeable(ChildGasLedger ledger);

        /** Resolves the exact closure attribution at runtime charge time. */
        GasChargeContext resolveAttribution(ChildGasLedger ledger,
                                            GasChargeContext context);

        /**
         * Verifies invocation-local limits before the child performs its own
         * admission check.
         */
        void ensureWithinLocalBudget(ChildGasLedger ledger,
                                     String counter,
                                     long quantity,
                                     long weight,
                                     long subtotal,
                                     GasChargeContext context);

        /** Admits a charge before the child ledger mutates its local trace. */
        void beforeCharge(ChildGasLedger ledger,
                          String counter,
                          long quantity,
                          long weight,
                          long subtotal,
                          GasChargeContext context);

        /** Records a deterministic charge rejection for runtime-work lifecycle handling. */
        void rejected(ChildGasLedger ledger,
                      GasLimitExceededException rejection);
    }
}
