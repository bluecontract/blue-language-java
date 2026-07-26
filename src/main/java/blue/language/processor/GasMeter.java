package blue.language.processor;

import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
    private long totalGas;

    public GasMeter() {
        this(GasSchedule.contracts10());
    }

    public GasMeter(GasSchedule schedule) {
        this(schedule, Objects.requireNonNull(schedule, "schedule").maxProcessGas());
    }

    public GasMeter(GasSchedule schedule, long gasLimit) {
        this.schedule = Objects.requireNonNull(schedule, "schedule");
        if (gasLimit < 0L || gasLimit > schedule.maxProcessGas()) {
            throw new IllegalArgumentException(
                    "Gas limit must be between 0 and manifest maxProcessGas "
                            + schedule.maxProcessGas());
        }
        this.gasLimit = gasLimit;
        this.semantic = new SemanticGasMeter(this);
    }

    public GasSchedule schedule() {
        return schedule;
    }

    public long gasLimit() {
        return gasLimit;
    }

    public long totalGas() {
        return totalGas;
    }

    public long remainingGas() {
        return gasLimit - totalGas;
    }

    /**
     * Returns this invocation's semantic formula meter.  The returned object
     * shares this meter's live limit and owns only run-local memoization.
     */
    public SemanticGasMeter semantic() {
        return semantic;
    }

    public List<GasTraceEntry> trace() {
        return Collections.unmodifiableList(new ArrayList<>(trace));
    }

    public void charge(String namespace, String counter, long quantity) {
        charge(namespace, counter, quantity, GasChargeContext.empty());
    }

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
     */
    public ChildGasLedger childLedger(String runtimeNamespace,
                                      Map<String, Long> counterWeights) {
        return new ChildGasLedger(runtimeNamespace, counterWeights, remainingGas());
    }

    /**
     * Merges a completed runtime child ledger once in its original order.
     */
    public void merge(ChildGasLedger child) {
        Objects.requireNonNull(child, "child");
        List<ChildGasLedger.Entry> entries = child.takeForMerge();
        for (ChildGasLedger.Entry entry : entries) {
            chargeWeighted(child.namespace(),
                    entry.counter,
                    entry.quantity,
                    entry.weight,
                    entry.context);
        }
    }

    /**
     * Compatibility entry point for pre-1.0 runtime processors. New runtimes
     * should publish named counter weights and use a child ledger.
     */
    @Deprecated
    void add(long amount) {
        chargeWeighted("runtime", "legacyUnits", amount, 1L,
                GasChargeContext.reason("legacy-runtime-ledger"));
    }

    void chargeProcessInvocation() {
        charge("processor", "processInvocation", 1L,
                GasChargeContext.of("/", null, null, "invocation"));
    }

    void chargeDeliverySnapshotEntry(String scopePath, String contractKey) {
        charge("processor", "deliverySnapshotEntry", 1L,
                GasChargeContext.of(
                        scopePath, contractKey, null, "revalidate-delivery"));
    }

    void chargeScopeEntry(String scopePath) {
        charge("processor", "scopeOpened", 1L,
                GasChargeContext.of(
                        scopePath, null, null, "participating-scope"));
    }

    void chargeParticipatingClosure(long quantity) {
        charge("processor", "scopeOpened", quantity,
                GasChargeContext.of(
                        "/", null, null,
                        quantity == 1L
                                ? "participating-scope"
                                : "participating-closure"));
    }

    void chargeContractHeaderRecognized(String scopePath,
                                        String contractKey,
                                        String reason) {
        charge("processor", "contractHeaderRecognized", 1L,
                GasChargeContext.of(scopePath, contractKey, null, reason));
    }

    void chargeContractHeadersRecognized(long quantity, String reason) {
        charge("processor", "contractHeaderRecognized", quantity,
                GasChargeContext.of("/", null, null, reason));
    }

    void chargeEmbeddedPathEntryRead(String scopePath, String logicalPath) {
        charge("processor", "embeddedPathEntryRead", 1L,
                GasChargeContext.of(scopePath, null, logicalPath, "route"));
    }

    void chargeEmbeddedPathSegmentsValidated(String scopePath,
                                             String logicalPath,
                                             long quantity) {
        charge("processor", "embeddedPathSegmentValidated", quantity,
                GasChargeContext.of(scopePath, null, logicalPath, "route"));
    }

    void chargeScopeEntry(int embeddedDepth) {
        if (embeddedDepth < 0) {
            throw new IllegalArgumentException("Scope embedded depth must be non-negative");
        }
        chargeScopeEntry("/");
    }

    void chargeInitialization(String scopePath) {
        charge("processor", "scopeInitialization", 1L,
                GasChargeContext.of(
                        scopePath, null, null, "scope-initialization"));
    }

    void chargeChannelMatchAttempt(String scopePath, String contractKey) {
        charge("processor", "channelCandidateTested", 1L,
                GasChargeContext.of(
                        scopePath, contractKey, null, "acceptance"));
    }

    void chargeChannelAccepted(String scopePath, String contractKey) {
        charge("processor", "channelAccepted", 1L,
                GasChargeContext.of(
                        scopePath, contractKey, null, "acceptance"));
    }

    void chargeHandlerCandidateTested(String scopePath, String contractKey) {
        charge("processor", "handlerCandidateTested", 1L,
                GasChargeContext.of(
                        scopePath, contractKey, null, "matching"));
    }

    void chargeHandlerOverhead(String scopePath, String contractKey) {
        charge("processor", "handlerCall", 1L,
                GasChargeContext.of(
                        scopePath, contractKey, null, "handler-call"));
    }

    void chargeBoundaryCheck() {
        charge("processor", "patchBoundaryChecked", 1L,
                GasChargeContext.reason("patch-boundary"));
    }

    void chargePointerSegments(long quantity, String logicalPath) {
        charge("processor", "pointerSegmentTraversed", quantity,
                GasChargeContext.of(null, null, logicalPath, "runtime-pointer"));
    }

    void chargePatchAddOrReplace(Node ignoredValue) {
        charge("processor", "patchAddOrReplace", 1L,
                GasChargeContext.reason("application-patch"));
    }

    void chargeFrozenPatchAddOrReplace(FrozenNode ignoredValue) {
        charge("processor", "patchAddOrReplace", 1L,
                GasChargeContext.reason("application-patch"));
    }

    void chargeFrozenPatchAddOrReplace(long ignoredAuthoredCanonicalSizeBytes) {
        if (ignoredAuthoredCanonicalSizeBytes < 0L) {
            throw new IllegalArgumentException("Authored canonical size must be non-negative");
        }
        charge("processor", "patchAddOrReplace", 1L,
                GasChargeContext.reason("application-patch"));
    }

    void chargePatchRemove() {
        charge("processor", "patchRemove", 1L,
                GasChargeContext.reason("application-patch"));
    }

    void chargeCascadeRouting(int matchingDeliveryCount) {
        if (matchingDeliveryCount > 0) {
            charge("processor", "documentUpdateDelivered", matchingDeliveryCount,
                    GasChargeContext.reason("document-update"));
        }
    }

    void chargeEmitEvent(Node ignoredEvent) {
        charge("processor", "internalEventEnqueued", 1L,
                GasChargeContext.reason("event-emission"));
    }

    void chargeRootEventRecorded() {
        charge("processor", "rootEventRecorded", 1L,
                GasChargeContext.reason("root-emission"));
    }

    void chargeBridge(Node ignoredEvent) {
        charge("processor", "embeddedEventDelivered", 1L,
                GasChargeContext.reason("embedded-event"));
    }

    void chargeTriggeredDelivery() {
        charge("processor", "triggeredEventDelivered", 1L,
                GasChargeContext.reason("triggered-event"));
    }

    void chargeDrainEvent() {
        charge("processor", "internalEventDequeued", 1L,
                GasChargeContext.reason("event-drain"));
    }

    void chargeCheckpointCompared() {
        charge("processor", "checkpointCompared", 1L,
                GasChargeContext.reason("checkpoint-compare"));
    }

    void chargeCheckpointUpdate() {
        charge("processor", "checkpointWritten", 1L,
                GasChargeContext.reason("checkpoint-write"));
    }

    void chargeProcessorMarkerWritten(String reason) {
        charge("processor", "processorMarkerWritten", 1L,
                GasChargeContext.reason(reason));
    }

    void chargeTerminationRequest() {
        charge("processor", "terminationRequested", 1L,
                GasChargeContext.reason("termination-request"));
    }

    void chargeTerminationMarker() {
        chargeProcessorMarkerWritten("termination-marker");
    }

    void chargeLifecycleDelivery() {
        charge("processor", "lifecycleDelivered", 1L,
                GasChargeContext.reason("lifecycle"));
    }

    /**
     * Fatal closeout gas was removed by Contracts 1.0. Kept as a no-op binary
     * compatibility shim for callers compiled against the preview.
     */
    @Deprecated
    void chargeFatalTerminationOverhead() {
        // No committed fatal mode and no fixed closeout charge in Contracts 1.0.
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
        if (quantity < 0L || weight < 0L) {
            throw new IllegalArgumentException("Gas quantity and weight must be non-negative");
        }
        if (quantity == 0L || weight == 0L) {
            return;
        }
        long subtotal = multiplyExact(quantity, weight);
        if (subtotal > gasLimit - totalGas) {
            throw new GasLimitExceededException(
                    namespace, counter, quantity, weight, totalGas, gasLimit);
        }
        trace.add(new GasTraceEntry(trace.size(),
                namespace,
                counter,
                quantity,
                weight,
                subtotal,
                context));
        totalGas += subtotal;
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
        private final List<Entry> entries = new ArrayList<>();
        private long totalGas;
        private boolean merged;

        private ChildGasLedger(String namespace,
                               Map<String, Long> counterWeights,
                               long gasLimit) {
            this.namespace = Objects.requireNonNull(namespace, "namespace");
            if (namespace.isEmpty()
                    || "processor".equals(namespace)
                    || "semantic".equals(namespace)) {
                throw new IllegalArgumentException(
                        "Runtime child namespace must be non-empty and disjoint");
            }
            Objects.requireNonNull(counterWeights, "counterWeights");
            Map<String, Long> copy = new LinkedHashMap<>();
            for (Map.Entry<String, Long> entry : counterWeights.entrySet()) {
                String counter = Objects.requireNonNull(entry.getKey(), "counter");
                Long weight = Objects.requireNonNull(entry.getValue(), "weight");
                if (counter.isEmpty() || weight < 0L) {
                    throw new IllegalArgumentException("Invalid runtime counter weight");
                }
                copy.put(counter, weight);
            }
            this.weights = Collections.unmodifiableMap(copy);
            this.gasLimit = gasLimit;
        }

        public String namespace() {
            return namespace;
        }

        public long totalGas() {
            return totalGas;
        }

        public long remainingGas() {
            return gasLimit - totalGas;
        }

        public void charge(String counter, long quantity) {
            charge(counter, quantity, GasChargeContext.empty());
        }

        public void charge(String counter, long quantity, GasChargeContext context) {
            ensureUnmerged();
            Long weight = weights.get(counter);
            if (weight == null) {
                throw new IllegalArgumentException(
                        "Unknown runtime gas counter " + namespace + "." + counter);
            }
            if (quantity < 0L) {
                throw new IllegalArgumentException("Gas quantity must be non-negative");
            }
            if (quantity == 0L || weight == 0L) {
                return;
            }
            long subtotal = multiplyExact(quantity, weight);
            if (subtotal > gasLimit - totalGas) {
                throw new GasLimitExceededException(
                        namespace, counter, quantity, weight, totalGas, gasLimit);
            }
            entries.add(new Entry(counter, quantity, weight,
                    context != null ? context : GasChargeContext.empty()));
            totalGas += subtotal;
        }

        private List<Entry> takeForMerge() {
            ensureUnmerged();
            merged = true;
            return new ArrayList<>(entries);
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
}
