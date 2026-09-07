package blue.language.processor;

import blue.language.model.Node;
import blue.language.model.wire.JsonPointer;
import blue.language.snapshot.FrozenNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Processor-owned lifecycle and budget boundary for deterministic hosted
 * runtime work.
 *
 * <p>A session stages named child traces against live reservations in the
 * invocation meter.  The processor alone chooses whether those traces are
 * merged after success or deterministic failure, or discarded when an
 * attempt is suspended for transient evidence unavailability.</p>
 */
public final class RuntimeWorkSession {

    /**
     * Distinguishes portable work performed inside {@code PROCESS} from
     * feeder/index/admission diagnostics performed outside it.
     */
    public enum Mode {
        /** Portable hosted work that contributes to the PROCESS gas total. */
        PROCESSING,
        /** Feeder, index, or evidence-admission work outside PROCESS. */
        ADMISSION
    }

    private enum Outcome {
        OPEN,
        COMPLETED,
        FAILED,
        SUSPENDED,
        EXHAUSTED
    }

    private final GasMeter parent;
    private final Object ownerToken = new Object();
    private final Mode mode;
    private final long counterKindLimit;
    private final long initialBudget;
    private final Map<String, LedgerState> byNamespace =
            new LinkedHashMap<>();
    private final Map<GasMeter.ChildGasLedger, LedgerState> byIdentity =
            new IdentityHashMap<>();
    private final Map<String, ExactBlueValue> carriedInputsByIdentity =
            new LinkedHashMap<>();
    private final Map<FrozenNode.ResolvedStructuralKey, ExactBlueValue>
            carriedInputsByStructure = new LinkedHashMap<>();
    private Outcome outcome = Outcome.OPEN;
    private GasLimitExceededException rejectedCharge;
    private GasMeter.ChildGasLedger rejectedLedger;
    private SemanticOutputBoundary semanticOutputBoundary;

    RuntimeWorkSession(GasMeter parent, Mode mode) {
        this.parent = Objects.requireNonNull(parent, "parent");
        this.mode = Objects.requireNonNull(mode, "mode");
        /*
         * Contracts 1.0 already publishes this portable runtime-catalog
         * bound. It applies only to the distinct counter names in one child
         * catalog. Namespace count and repeated positively weighted trace
         * entries are not inferred from that differently named limit; live
         * parent gas bounds every admitted occurrence.
         */
        this.counterKindLimit = parent.schedule()
                .portableLimit(GasScheduleConstants.PortableLimit.RUNTIME_CHILD_LEDGER_COUNTER_KINDS);
        this.initialBudget = parent.remainingGas();
    }

    /**
     * Returns the phase in which this session accounts hosted work.
     *
     * @return immutable session mode
     */
    public Mode mode() {
        return mode;
    }

    /**
     * Reports whether committed child work contributes to PROCESS gas.
     *
     * @return {@code true} for {@link Mode#PROCESSING}
     */
    public boolean contributesToProcessGas() {
        return mode == Mode.PROCESSING;
    }

    /**
     * Opens an invocation-owned budget that can be shared by independently
     * named child ledgers.
     *
     * <p>The shared maximum is additional to, and cannot enlarge, the live
     * parent invocation budget. A charge must satisfy both boundaries before
     * it enters a child trace.</p>
     *
     * @param maximumGas non-negative weighted gas available to the shared
     *        ledger group
     * @return live shared budget owned by this session
     * @throws IllegalArgumentException if {@code maximumGas} is negative
     * @throws IllegalStateException if the session is closed or has a pending
     *         rejected charge
     */
    public synchronized RuntimeWorkBudget openSharedBudget(
            long maximumGas) {
        ensureOpen();
        return new RuntimeWorkBudget(
                ownerToken, maximumGas);
    }

    /**
     * Opens one uniquely named child ledger with the exact currently
     * remaining parent budget.
     *
     * @param namespace stable non-empty hosted-runtime namespace
     * @param counterWeights immutable counter-name to unit-weight catalog
     * @return live child ledger owned by this session
     * @throws IllegalStateException if the session is closed or the namespace
     *         was already opened
     * @throws PortableLimitExceededException if the portable counter-catalog
     *         bound is exceeded
     */
    public synchronized GasMeter.ChildGasLedger openLedger(
            String namespace,
            Map<String, Long> counterWeights) {
        return openLedger(
                namespace, counterWeights, null);
    }

    /**
     * Opens one uniquely named child ledger attached to an invocation-owned
     * shared budget.
     *
     * <p>All ledgers attached to the same budget consume one weighted maximum
     * even when their namespaces and counter catalogs differ. The budget must
     * have been created by this exact live session.</p>
     *
     * @param namespace stable non-empty hosted-runtime namespace
     * @param counterWeights immutable counter-name to unit-weight catalog
     * @param sharedBudget budget returned by this session's
     *        {@link #openSharedBudget(long)}
     * @return live child ledger owned by this session
     * @throws IllegalArgumentException if {@code sharedBudget} belongs to
     *         another session
     * @throws IllegalStateException if the session is closed or the namespace
     *         was already opened
     * @throws PortableLimitExceededException if the portable counter-catalog
     *         bound is exceeded
     */
    public synchronized GasMeter.ChildGasLedger openLedger(
            String namespace,
            Map<String, Long> counterWeights,
            RuntimeWorkBudget sharedBudget) {
        ensureOpen();
        if (sharedBudget != null) {
            requireOwned(sharedBudget);
        }
        String exactNamespace =
                Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(counterWeights, "counterWeights");
        LedgerState existing = byNamespace.get(exactNamespace);
        Map<String, Long> exactCatalog =
                immutableCatalog(counterWeights);
        if (existing != null) {
            if (!existing.counterWeights.equals(exactCatalog)) {
                throw new IllegalArgumentException(
                        "Runtime counter catalog mismatch for namespace "
                                + exactNamespace);
            }
            throw new IllegalStateException(
                    "Runtime namespace was already opened: "
                            + exactNamespace);
        }
        if (exactCatalog.size() > counterKindLimit) {
            throw new PortableLimitExceededException(
                    ProcessorErrorCategory.RuntimeLedgerLimitExceeded,
                    GasScheduleConstants.PortableLimit.RUNTIME_CHILD_LEDGER_COUNTER_KINDS,
                    exactCatalog.size(),
                    counterKindLimit);
        }

        GasMeter.ChildGasLedger ledger =
                parent.sessionChildLedger(
                        exactNamespace,
                        exactCatalog,
                        ownerToken,
                        new GasMeter.ChildAdmissionController() {
                            @Override
                            public void ensureChargeable(
                                    GasMeter.ChildGasLedger candidate) {
                                RuntimeWorkSession.this
                                        .ensureChargeable(candidate);
                            }

                            @Override
                            public GasChargeContext resolveAttribution(
                                    GasMeter.ChildGasLedger candidate,
                                    GasChargeContext context) {
                                RuntimeWorkSession.this
                                        .ensureChargeable(candidate);
                                return parent.resolveAttribution(context);
                            }

                            @Override
                            public void ensureWithinLocalBudget(
                                    GasMeter.ChildGasLedger candidate,
                                    String counter,
                                    long quantity,
                                    long weight,
                                    long subtotal,
                                    GasChargeContext context) {
                                RuntimeWorkSession.this
                                        .ensureWithinRuntimeBudgets(
                                                candidate,
                                                counter,
                                                quantity,
                                                weight,
                                                subtotal,
                                                context);
                            }

                            @Override
                            public void beforeCharge(
                                    GasMeter.ChildGasLedger candidate,
                                    String counter,
                                    long quantity,
                                    long weight,
                                    long subtotal,
                                    GasChargeContext context) {
                                RuntimeWorkSession.this.beforeCharge(
                                        candidate,
                                        counter,
                                        quantity,
                                        weight,
                                        subtotal,
                                        context);
                            }

                            @Override
                            public void rejected(
                                    GasMeter.ChildGasLedger candidate,
                                    GasLimitExceededException rejection) {
                                RuntimeWorkSession.this
                                        .recordRejectedCharge(
                                                candidate,
                                                rejection);
                            }
                        });
        LedgerState state =
                new LedgerState(
                        ledger,
                        exactCatalog,
                        sharedBudget);
        byNamespace.put(exactNamespace, state);
        byIdentity.put(ledger, state);
        return ledger;
    }

    /**
     * Marks a ledger as the successful output of its runtime component.  The
     * parent merge remains processor-owned and occurs in canonical namespace
     * order when the execution unit completes.
     *
     * @param ledger live child ledger opened by this session
     * @throws IllegalArgumentException if the ledger belongs to another
     *         session
     * @throws IllegalStateException if the session is closed or the ledger
     *         was already submitted
     */
    public synchronized void submit(
            GasMeter.ChildGasLedger ledger) {
        ensureOpen();
        LedgerState state = requireOwned(ledger);
        if (state.submitted) {
            throw new IllegalStateException(
                    "Runtime child ledger was already submitted");
        }
        state.submitted = true;
    }

    /**
     * Completes this execution unit and merges submitted ledgers once.
     */
    synchronized void complete() {
        ensureOutcomeOpen();
        throwPendingGasExhaustion();
        for (LedgerState state : orderedLedgers()) {
            if (!state.submitted
                    && !state.ledger.snapshotTrace(
                    ownerToken).isEmpty()) {
                finish(Outcome.FAILED, true);
                throw new IllegalStateException(
                        "Successful runtime execution has an unsubmitted "
                                + "charged ledger: "
                                + state.ledger.namespace());
            }
        }
        finish(Outcome.COMPLETED, false);
    }

    /**
     * Retains every admitted runtime prefix while application effects roll
     * back after a deterministic runtime failure.
     */
    synchronized void failDeterministically() {
        ensureOutcomeOpen();
        throwPendingGasExhaustion();
        finish(Outcome.FAILED, true);
    }

    /**
     * Discards all staged portable work for a transiently unavailable attempt.
     */
    synchronized void suspend() {
        ensureOutcomeOpen();
        throwPendingGasExhaustion();
        finish(Outcome.SUSPENDED, false);
    }

    /**
     * Propagates a processor gas rejection through the structured runtime
     * exhaustion boundary.
     *
     * @param exhaustion exact rejection produced by this live session
     * @throws IllegalArgumentException if the rejection was not produced by
     *         this session
     * @throws GasLimitExceededException always, after committing the admitted
     *         gas prefix
     */
    public void propagateGasExhaustion(
            RuntimeGasExhaustion exhaustion) {
        RuntimeGasExhaustion exact =
                Objects.requireNonNull(exhaustion, "exhaustion");
        synchronized (this) {
            ensureOutcomeOpen();
            if (rejectedCharge == null
                    || exact.source()
                    != rejectedCharge) {
                throw new IllegalArgumentException(
                        "Gas exhaustion was not produced by this live work session");
            }
            if (rejectedLedger != null) {
                validateChildGasExhaustion(exact);
            } else if (!GasScheduleConstants.Namespace.SEMANTIC.equals(
                    exact.namespace())) {
                throw new IllegalArgumentException(
                        "Semantic gas exhaustion names a non-semantic namespace");
            }
            finish(Outcome.EXHAUSTED, true);
        }
        throw exact.source();
    }

    /**
     * Converts a raw gas rejection to structured runtime exhaustion and
     * propagates it after retaining the admitted prefix.
     *
     * @param exhaustion rejection produced by this live session
     * @throws NullPointerException if {@code exhaustion} is {@code null}
     * @throws IllegalArgumentException if the rejection was not produced by
     *         this session
     * @throws GasLimitExceededException always, after committing the admitted
     *         gas prefix
     */
    public void propagateGasExhaustion(
            GasLimitExceededException exhaustion) {
        propagateGasExhaustion(
                RuntimeGasExhaustion.from(exhaustion));
    }

    /**
     * Returns the invocation-owned semantic output admission boundary.
     *
     * @return live semantic output capability
     * @throws IllegalStateException if the session is closed or this runtime
     *         phase has no semantic output boundary
     */
    public synchronized SemanticOutputBoundary semanticOutputBoundary() {
        ensureOpen();
        if (semanticOutputBoundary == null) {
            throw new IllegalStateException(
                    "Semantic output admission is not available in this runtime phase");
        }
        return semanticOutputBoundary;
    }

    synchronized boolean hasSemanticOutputBoundary() {
        return semanticOutputBoundary != null;
    }

    /**
     * Seeds a processor-admitted exact input so a hosted function can return
     * that value, inline or by identity, without reconstructing or charging
     * it as transient output.
     */
    synchronized void carryExactInput(
            Node input,
            String blueId) {
        carryExactInput(
                FrozenNode.fromResolvedNode(
                        Objects.requireNonNull(input, "input").clone()),
                blueId);
    }

    synchronized void carryExactInput(
            FrozenNode input,
            String blueId) {
        ensureOpen();
        if (semanticOutputBoundary != null) {
            semanticOutputBoundary.carryExactInput(input, blueId);
            return;
        }
        carryExactInputLocally(input, blueId);
    }

    synchronized List<ExactBlueValue> exactValuesSnapshot() {
        ensureOpen();
        return semanticOutputBoundary != null
                ? semanticOutputBoundary.exactValuesSnapshot()
                : Collections.unmodifiableList(
                        new ArrayList<>(new java.util.LinkedHashSet<>(
                                carriedInputsByIdentity.values())));
    }

    synchronized void carryExactInputs(
            Collection<ExactBlueValue> inputs) {
        ensureOpen();
        Collection<ExactBlueValue> exactInputs = Objects.requireNonNull(
                inputs, "inputs");
        if (semanticOutputBoundary != null) {
            semanticOutputBoundary.carryExactInputs(exactInputs);
            return;
        }
        for (ExactBlueValue input : exactInputs) {
            ExactBlueValue exact = Objects.requireNonNull(input, "input");
            admitCarriedInput(exact.frozenValue(), exact.blueId());
        }
    }

    synchronized void attachSemanticOutputBoundary(
            SemanticOutputBoundary boundary) {
        ensureOpen();
        if (semanticOutputBoundary != null) {
            throw new IllegalStateException(
                    "Semantic output boundary was already attached");
        }
        SemanticOutputBoundary attached = Objects.requireNonNull(
                boundary, "boundary");
        if (!carriedInputsByIdentity.isEmpty()) {
            attached.carryExactInputs(exactValuesSnapshot());
            carriedInputsByIdentity.clear();
            carriedInputsByStructure.clear();
        }
        semanticOutputBoundary = attached;
    }

    SemanticGasMeter semanticMeter() {
        return parent.semantic();
    }

    /**
     * Reports whether the session can still accept hosted-runtime work.
     *
     * @return {@code true} until the session reaches a terminal outcome
     */
    public synchronized boolean isOpen() {
        return outcome == Outcome.OPEN;
    }

    synchronized boolean acceptsWork() {
        return outcome == Outcome.OPEN
                && rejectedCharge == null;
    }

    /**
     * Returns the session's canonical staged trace without committing it.
     *
     * @return immutable trace ordered by namespace and local sequence
     * @throws IllegalStateException if the session already reached a terminal
     *         outcome
     */
    public synchronized List<GasTraceEntry> stagedTrace() {
        ensureOutcomeOpen();
        List<LedgerState> ordered =
                orderedLedgers();
        List<GasTraceEntry> trace =
                new ArrayList<>();
        for (LedgerState state : ordered) {
            for (GasTraceEntry entry :
                    state.ledger.snapshotTrace(
                            ownerToken)) {
                trace.add(new GasTraceEntry(
                        trace.size(),
                        state.ledger.namespace(),
                        entry.counter(),
                        entry.quantity(),
                        entry.weight(),
                        entry.subtotal(),
                        entry.context()));
            }
        }
        return Collections.unmodifiableList(trace);
    }

    RuntimeWorkSession diagnosticTwin() {
        RuntimeWorkSession twin =
                new RuntimeWorkSession(
                        parent.diagnosticTwin(initialBudget),
                        Mode.ADMISSION);
        synchronized (this) {
            if (semanticOutputBoundary != null) {
                twin.attachSemanticOutputBoundary(
                        semanticOutputBoundary
                                .forkFor(twin));
            } else if (!carriedInputsByIdentity.isEmpty()) {
                twin.carryExactInputs(exactValuesSnapshot());
            }
        }
        return twin;
    }

    /** Returns an already carried exact input matching one hosted output. */
    synchronized ExactBlueValue carriedExactInput(Node output) {
        ensureOpen();
        FrozenNode supplied = FrozenNode.fromResolvedNode(
                Objects.requireNonNull(output, "output").clone());
        ExactBlueValue matched = null;
        for (ExactBlueValue candidate : exactValuesSnapshot()) {
            boolean same = supplied.isReferenceOnly()
                    ? supplied.getReferenceBlueId().equals(candidate.blueId())
                    : !candidate.frozenValue().isReferenceOnly()
                    && supplied.resolvedStructuralKey().equals(
                            candidate.frozenValue().resolvedStructuralKey());
            if (!same) {
                continue;
            }
            if (matched != null
                    && !matched.blueId().equals(candidate.blueId())) {
                throw new InvalidExecutionEvidenceException(
                        "Carried exact inputs disagree on hosted output identity");
            }
            matched = candidate;
        }
        return matched;
    }

    private void carryExactInputLocally(
            FrozenNode input,
            String blueId) {
        FrozenNode frozen = Objects.requireNonNull(input, "input");
        admitCarriedInput(frozen, blueId);
        for (Map.Entry<String, FrozenNode> entry
                : frozen.pathIndex().entrySet()) {
            if (entry.getKey().isEmpty()
                    || JsonPointer.ROOT.equals(entry.getKey())
                    || entry.getValue() == null) {
                continue;
            }
            FrozenNode descendant = entry.getValue();
            if (descendant.isReferenceOnly()) {
                admitCarriedInput(
                        descendant, descendant.getReferenceBlueId());
            } else if (descendant.isStrictCanonical()) {
                admitCarriedInput(descendant, descendant.blueId());
            }
        }
    }

    private void admitCarriedInput(
            FrozenNode value,
            String blueId) {
        ExactBlueValue exact = new ExactBlueValue(value, blueId);
        ExactBlueValue byIdentity = carriedInputsByIdentity.get(
                exact.blueId());
        if (byIdentity != null) {
            return;
        }
        if (!value.isReferenceOnly()) {
            FrozenNode.ResolvedStructuralKey key =
                    value.resolvedStructuralKey();
            ExactBlueValue byStructure = carriedInputsByStructure.get(key);
            if (byStructure != null
                    && !byStructure.blueId().equals(exact.blueId())) {
                throw new InvalidExecutionEvidenceException(
                        "Carried exact inputs disagree on BlueId");
            }
            carriedInputsByStructure.put(key, exact);
        }
        carriedInputsByIdentity.put(exact.blueId(), exact);
    }

    /**
     * A context closed without an explicit success/suspension decision is a
     * deterministic failed attempt: portable work already performed remains
     * visible, while buffered document effects are abandoned.
     */
    synchronized void close() {
        if (outcome == Outcome.OPEN) {
            /*
             * Try-with-resources invokes close while the exact gas exception
             * may already be unwinding. Retain the prefix without throwing
             * the same object again (Java would reject self-suppression).
             */
            finish(
                    rejectedCharge != null
                            ? Outcome.EXHAUSTED
                            : Outcome.FAILED,
                    true);
        }
    }

    static Throwable failIfOpenPreserving(
            RuntimeWorkSession session,
            Throwable primary) {
        if (session == null || !session.isOpen()) {
            return primary;
        }
        try {
            session.failDeterministically();
        } catch (RuntimeException | Error failure) {
            return retain(primary, failure);
        }
        return primary;
    }

    static Throwable suspendIfOpenPreserving(
            RuntimeWorkSession session,
            Throwable primary) {
        if (session == null || !session.isOpen()) {
            return primary;
        }
        try {
            session.suspend();
        } catch (RuntimeException | Error failure) {
            return retain(primary, failure);
        }
        return primary;
    }

    static Throwable closePreserving(
            RuntimeWorkSession session,
            Throwable primary) {
        return closePreserving(
                session == null ? null : session::close,
                primary);
    }

    static Throwable closePreserving(
            CloseAction action,
            Throwable primary) {
        if (action == null) {
            return primary;
        }
        try {
            action.close();
        } catch (RuntimeException | Error failure) {
            return retain(primary, failure);
        }
        return primary;
    }

    static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException) {
            throw (RuntimeException) failure;
        }
        if (failure instanceof Error) {
            throw (Error) failure;
        }
    }

    private static Throwable retain(
            Throwable primary,
            Throwable additional) {
        if (primary == null) {
            return additional;
        }
        if (additional != primary) {
            primary.addSuppressed(additional);
        }
        return primary;
    }

    @FunctionalInterface
    interface CloseAction {
        void close();
    }

    private synchronized void ensureChargeable(
            GasMeter.ChildGasLedger ledger) {
        ensureOpen();
        LedgerState state = requireOwned(ledger);
        if (state.submitted) {
            throw new IllegalStateException(
                    "Submitted runtime child ledger cannot be charged");
        }
    }

    private synchronized void beforeCharge(
            GasMeter.ChildGasLedger ledger,
            String counter,
            long quantity,
            long weight,
            long subtotal,
            GasChargeContext context) {
        ensureChargeable(ledger);
        LedgerState state = requireOwned(ledger);
        parent.reserveRuntimeGas(
                ledger.namespace(),
                counter,
                quantity,
                weight,
                subtotal,
                context);
        if (state.sharedBudget != null) {
            state.sharedBudget.recordAdmission(
                    subtotal);
        }
    }

    private synchronized void ensureWithinRuntimeBudgets(
            GasMeter.ChildGasLedger ledger,
            String counter,
            long quantity,
            long weight,
            long subtotal,
            GasChargeContext context) {
        ensureChargeable(ledger);
        parent.ensureRuntimeGasAdmissible(
                ledger.namespace(),
                counter,
                quantity,
                weight,
                subtotal,
                context);
        LedgerState state = requireOwned(ledger);
        if (state.sharedBudget != null) {
            state.sharedBudget.ensureAdmissible(
                    ledger.namespace(),
                    counter,
                    quantity,
                    weight,
                    subtotal,
                    context);
        }
    }

    private synchronized void recordRejectedCharge(
            GasMeter.ChildGasLedger ledger,
            GasLimitExceededException rejection) {
        ensureOutcomeOpen();
        requireOwned(ledger);
        recordRejectedCharge(
                rejection, ledger);
    }

    synchronized void recordSemanticRejectedCharge(
            GasLimitExceededException rejection) {
        ensureOutcomeOpen();
        GasLimitExceededException exact =
                Objects.requireNonNull(
                        rejection, "rejection");
        if (!GasScheduleConstants.Namespace.SEMANTIC.equals(
                exact.namespace())) {
            throw new IllegalArgumentException(
                    "Semantic output rejection must use the semantic namespace");
        }
        recordRejectedCharge(exact, null);
    }

    private void recordRejectedCharge(
            GasLimitExceededException rejection,
            GasMeter.ChildGasLedger ledger) {
        if (rejectedCharge != null) {
            if (rejectedCharge == rejection
                    && rejectedLedger == ledger) {
                return;
            }
            throw new IllegalStateException(
                    "Runtime work session already recorded a rejected charge");
        }
        rejectedLedger = ledger;
        rejectedCharge =
                Objects.requireNonNull(
                        rejection, "rejection");
    }

    private void validateChildGasExhaustion(
            RuntimeGasExhaustion exact) {
        LedgerState state =
                byNamespace.get(exact.namespace());
        if (state == null) {
            throw new IllegalArgumentException(
                    "Gas exhaustion names a ledger outside this work session");
        }
        if (state.ledger != rejectedLedger) {
            throw new IllegalArgumentException(
                    "Gas exhaustion names a different owned ledger");
        }
        Long registeredWeight =
                state.counterWeights.get(exact.counter());
        if (registeredWeight == null
                || registeredWeight.longValue()
                != exact.weight()) {
            throw new IllegalArgumentException(
                    "Gas exhaustion does not match the registered runtime catalog");
        }
        boolean matchesLedgerBudget =
                state.ledger.totalGas()
                        == exact.admittedGas()
                        && state.ledger.effectiveBudget()
                        == exact.effectiveBudget();
        boolean matchesSharedBudget =
                state.sharedBudget != null
                        && state.sharedBudget.admittedGas()
                        == exact.admittedGas()
                        && state.sharedBudget.maximumGas()
                        == exact.effectiveBudget();
        boolean matchesParentCap =
                parent.matchesCurrentCapRejection(exact.source());
        if (!matchesLedgerBudget
                && !matchesSharedBudget
                && !matchesParentCap) {
            throw new IllegalArgumentException(
                    "Gas exhaustion does not match the owned ledger state");
        }
    }

    private void throwPendingGasExhaustion() {
        if (rejectedCharge == null) {
            return;
        }
        GasLimitExceededException exact =
                rejectedCharge;
        finish(Outcome.EXHAUSTED, true);
        throw exact;
    }

    private void finish(Outcome finalOutcome,
                        boolean retainUnsubmitted) {
        if (outcome != Outcome.OPEN) {
            if (outcome == finalOutcome) {
                return;
            }
            throw new IllegalStateException(
                    "Runtime work session is already closed as "
                            + outcome.name().toLowerCase());
        }
        List<LedgerState> ordered =
                orderedLedgers();
        for (LedgerState state : ordered) {
            if (finalOutcome != Outcome.SUSPENDED
                    && (retainUnsubmitted || state.submitted)) {
                parent.mergeReserved(state.ledger, ownerToken);
            } else {
                parent.discardReserved(state.ledger, ownerToken);
            }
        }
        outcome = finalOutcome;
    }

    private List<LedgerState> orderedLedgers() {
        List<LedgerState> ordered =
                new ArrayList<>(byNamespace.values());
        Collections.sort(
                ordered,
                Comparator.comparing(
                        state -> state.ledger.namespace()));
        return ordered;
    }

    private LedgerState requireOwned(
            GasMeter.ChildGasLedger ledger) {
        GasMeter.ChildGasLedger exact =
                Objects.requireNonNull(ledger, "ledger");
        LedgerState state = byIdentity.get(exact);
        if (state == null) {
            throw new IllegalArgumentException(
                    "Runtime child ledger belongs to a different work session");
        }
        return state;
    }

    private void requireOwned(
            RuntimeWorkBudget sharedBudget) {
        RuntimeWorkBudget exact =
                Objects.requireNonNull(
                        sharedBudget, "sharedBudget");
        if (!exact.isOwnedBy(ownerToken)) {
            throw new IllegalArgumentException(
                    "Runtime work budget belongs to a different work session");
        }
    }

    private void ensureOpen() {
        ensureOutcomeOpen();
        if (rejectedCharge != null) {
            throw new IllegalStateException(
                    "Rejected runtime gas charge must be propagated before "
                            + "any later runtime work");
        }
    }

    private void ensureOutcomeOpen() {
        if (outcome != Outcome.OPEN) {
            throw new IllegalStateException(
                    "Runtime work session is closed");
        }
    }

    private static Map<String, Long> immutableCatalog(
            Map<String, Long> counterWeights) {
        Map<String, Long> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Long> entry :
                counterWeights.entrySet()) {
            String counter =
                    Objects.requireNonNull(
                            entry.getKey(), "counter");
            Long weight =
                    Objects.requireNonNull(
                            entry.getValue(), "weight");
            if (counter.isEmpty() || weight <= 0L) {
                throw new IllegalArgumentException(
                        "Runtime counter names must be non-empty and weights "
                                + "must be positive");
            }
            copy.put(counter, weight);
        }
        return Collections.unmodifiableMap(copy);
    }

    private static final class LedgerState {
        private final GasMeter.ChildGasLedger ledger;
        private final Map<String, Long> counterWeights;
        private final RuntimeWorkBudget sharedBudget;
        private boolean submitted;

        private LedgerState(
                GasMeter.ChildGasLedger ledger,
                Map<String, Long> counterWeights,
                RuntimeWorkBudget sharedBudget) {
            this.ledger = ledger;
            this.counterWeights = counterWeights;
            this.sharedBudget = sharedBudget;
        }
    }
}
