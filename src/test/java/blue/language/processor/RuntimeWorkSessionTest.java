package blue.language.processor;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static blue.language.processor.FailureCapture.captureFailure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RuntimeWorkSessionTest {

    private static final String MEMBER_VISIT_NAMESPACE =
            "member-visits";
    private static final String COUNTER_MEMBER_VISITED =
            "compositeMemberVisited";
    private static final String COUNTER_HEADER_READ =
            "timelineHeaderRead";
    private static final String COUNTER_TIMELINE_COMPARED =
            "timelineBindingCompared";
    private static final String COUNTER_ACTOR_COMPARED =
            "actorBindingCompared";
    private static final String[] MEMBER_VISIT_COUNTERS = {
            COUNTER_MEMBER_VISITED,
            COUNTER_HEADER_READ,
            COUNTER_TIMELINE_COMPARED,
            COUNTER_ACTOR_COMPARED
    };
    private static final long UNIT_WEIGHT = 1L;
    private static final int PORTABLE_CAPACITY_VISITS = 129;
    private static final int PORTABLE_CAPACITY_ENTRIES =
            PORTABLE_CAPACITY_VISITS
                    * MEMBER_VISIT_COUNTERS.length;
    private static final int BOUNDED_MEMBER_VISITS = 1024;
    private static final int BOUNDED_MEMBER_VISIT_ENTRIES =
            BOUNDED_MEMBER_VISITS
                    * MEMBER_VISIT_COUNTERS.length;
    private static final int ENTRIES_PER_SHARED_NAMESPACE = 160;
    private static final Map<String, Long> MEMBER_VISIT_CATALOG =
            memberVisitCatalog();

    @Test
    void shouldNotReadmitCyclicMemberRootUnderItsDirectHash() {
        // given
        Node input = new Node().properties(
                "kind", new Node().value("cyclic-member"));
        FrozenNode frozen = FrozenNode.fromResolvedNode(input);
        String cyclicMemberBlueId =
                DirectBlueIdCalculator.calculateBlueId(
                        new Node().name("cycle")) + "#0";
        RuntimeWorkSession session = processing(new GasMeter());

        // when
        session.carryExactInput(frozen, cyclicMemberBlueId);
        List<ExactBlueValue> exactValues =
                session.exactValuesSnapshot();

        // then
        assertEquals(1L, exactValues.stream()
                .filter(value -> frozen.resolvedStructuralKey().equals(
                        value.frozenValue().resolvedStructuralKey()))
                .count());
        assertEquals(cyclicMemberBlueId, exactValues.get(0).blueId());
    }

    @Test
    void shouldExpireExactValueAccessWhenRuntimeWorkCompletes() {
        // given
        Node input = new Node().properties(
                "kind", new Node().value("exact-input"));
        RuntimeWorkSession session = processing(new GasMeter());
        session.carryExactInput(
                input,
                DirectBlueIdCalculator.calculateBlueId(input));

        // when
        List<ExactBlueValue> snapshot = session.exactValuesSnapshot();
        session.complete();

        // then
        assertEquals(1, snapshot.size());
        assertThrows(
                IllegalStateException.class,
                session::exactValuesSnapshot);
        session.close();
    }

    @Test
    void shouldVerifySeveralNamespacesReserveLiveBudgetAndMergeCanonically() {
        // given
        GasMeter parent = new GasMeter(
                GasSchedule.contracts10(), 100L);
        RuntimeWorkSession session = processing(parent);

        // when
        GasMeter.ChildGasLedger zeta =
                session.openLedger(
                        "zeta",
                        Collections.singletonMap("step", 3L));
        long zetaBudget = zeta.effectiveBudget();
        zeta.charge("step", 2L);

        GasMeter.ChildGasLedger alpha =
                session.openLedger(
                        "alpha",
                        Collections.singletonMap("read", 5L));
        long alphaBudget = alpha.effectiveBudget();
        alpha.charge("read", 1L);

        long stagedParentGas = parent.totalGas();
        long reservedParentGas = parent.remainingGas();
        session.submit(zeta);
        session.submit(alpha);
        session.complete();

        // then
        assertEquals(100L, zetaBudget);
        assertEquals(
                94L,
                alphaBudget,
                "later children receive the exact live remaining parent budget");
        assertEquals(0L, stagedParentGas);
        assertEquals(89L, reservedParentGas);
        assertEquals(11L, parent.totalGas());
        assertEquals(2, parent.trace().size());
        assertEquals("alpha", parent.trace().get(0).namespace());
        assertEquals("zeta", parent.trace().get(1).namespace());
        assertEquals("read", parent.trace().get(0).counter());
        assertEquals("step", parent.trace().get(1).counter());
        assertFalse(session.isOpen());
    }

    @Test
    void shouldRejectDuplicateRuntimeNamespace() {
        // given
        RuntimeWorkSession session =
                processing(new GasMeter());
        Map<String, Long> catalog =
                Collections.singletonMap("step", 1L);
        session.openLedger("runtime-a", catalog);

        // when
        IllegalStateException failure = captureFailure(
                () -> session.openLedger(
                        "runtime-a",
                        catalog));

        // then
        assertNotNull(failure);
    }

    @Test
    void shouldRejectConflictingCatalogForDuplicateRuntimeNamespace() {
        // given
        RuntimeWorkSession session =
                processing(new GasMeter());
        session.openLedger(
                "runtime-a",
                Collections.singletonMap("step", 1L));

        // when
        IllegalArgumentException failure = captureFailure(
                () -> session.openLedger(
                        "runtime-a",
                        Collections.singletonMap(
                                "step", 2L)));

        // then
        assertNotNull(failure);
    }

    @Test
    void shouldRejectDuplicateSubmissionAndChargingSubmittedLedger() {
        // given
        RuntimeWorkSession session =
                processing(new GasMeter());
        GasMeter.ChildGasLedger ledger =
                session.openLedger(
                        "runtime-a",
                        Collections.singletonMap(
                                "step", 1L));
        session.submit(ledger);

        // when
        IllegalStateException duplicateSubmission =
                captureFailure(() -> session.submit(ledger));
        IllegalStateException submittedCharge =
                captureFailure(
                        () -> ledger.charge("step", 1L));

        // then
        assertNotNull(duplicateSubmission);
        assertNotNull(submittedCharge);
    }

    @Test
    void shouldRejectOpeningLedgerAfterSessionCompletion() {
        // given
        RuntimeWorkSession session =
                processing(new GasMeter());
        session.complete();

        // when
        IllegalStateException failure = captureFailure(
                () -> session.openLedger(
                        "later",
                        Collections.singletonMap(
                                "step", 1L)));

        // then
        assertNotNull(failure);
    }

    @Test
    void shouldVerifySuccessfulCompletionCannotHideUnsubmittedChargedWork() {
        // given
        GasMeter parent = new GasMeter();
        RuntimeWorkSession session =
                processing(parent);
        GasMeter.ChildGasLedger ledger =
                session.openLedger(
                        "unsubmitted",
                        Collections.singletonMap(
                                "step", 2L));
        // when
        ledger.charge("step", 3L);
        int stagedTraceSize =
                session.stagedTrace().size();
        IllegalStateException failure =
                captureFailure(session::complete);
        boolean open = session.isOpen();
        long totalGas = parent.totalGas();
        int traceSize = parent.trace().size();

        // then
        assertEquals(
                1,
                stagedTraceSize,
                "determinism checks must see admitted work before submit");
        assertNotNull(failure);
        assertFalse(open);
        assertEquals(6L, totalGas);
        assertEquals(1, traceSize);
    }

    @Test
    void shouldRetainPrefixAfterDeterministicFailure() {
        // given
        GasMeter parent = new GasMeter();
        RuntimeWorkSession session =
                processing(parent);
        GasMeter.ChildGasLedger ledger =
                session.openLedger(
                        "failed-runtime",
                        Collections.singletonMap("step", 7L));
        ledger.charge("step", 2L);

        // when
        session.failDeterministically();

        // then
        assertEquals(14L, parent.totalGas());
        assertEquals(1, parent.trace().size());
    }

    @Test
    void shouldDiscardPrefixAfterSuspension() {
        // given
        GasMeter parent = new GasMeter();
        RuntimeWorkSession session =
                processing(parent);
        GasMeter.ChildGasLedger ledger =
                session.openLedger(
                        "suspended-runtime",
                        Collections.singletonMap("step", 7L));
        ledger.charge("step", 2L);
        session.submit(ledger);

        // when
        session.suspend();

        // then
        assertEquals(0L, parent.totalGas());
        assertTrue(parent.trace().isEmpty());
        assertEquals(
                parent.gasLimit(),
                parent.remainingGas());
    }

    @Test
    void shouldVerifyCanonicalExhaustionRetainsOnlyAdmittedChildPrefix() {
        // given
        GasMeter parent = new GasMeter(
                GasSchedule.contracts10(), 10L);
        RuntimeWorkSession session = processing(parent);
        GasMeter.ChildGasLedger ledger =
                session.openLedger(
                        "hosted",
                        Collections.singletonMap("iteration", 3L));
        ledger.charge("iteration", 2L);

        // when
        GasLimitExceededException rejected = captureFailure(
                () -> ledger.charge(
                        "iteration", 2L));
        int counterWeightCount =
                ledger.counterWeights().size();
        IllegalStateException laterCharge = captureFailure(
                () -> ledger.charge(
                        "iteration", 1L));
        IllegalStateException laterLedger = captureFailure(
                () -> session.openLedger(
                        "later",
                        Collections.singletonMap(
                                "step", 1L)));
        GasLimitExceededException canonical = captureFailure(
                () -> session.propagateGasExhaustion(
                        RuntimeGasExhaustion.from(
                                rejected)));
        long totalGas = parent.totalGas();
        int traceSize = parent.trace().size();
        long admittedQuantity =
                parent.trace().get(0).quantity();

        // then
        assertNotNull(rejected);
        assertEquals(6L, rejected.admittedGas());
        assertEquals(10L, rejected.effectiveBudget());
        assertEquals(1, counterWeightCount);
        assertNotNull(
                laterCharge,
                "no work may continue after the rejected charge");
        assertNotNull(laterLedger);
        assertNotNull(canonical);
        assertEquals("hosted", canonical.namespace());
        assertEquals("iteration", canonical.counter());
        assertEquals(6L, totalGas);
        assertEquals(1, traceSize);
        assertEquals(2L, admittedQuantity);
    }

    @Test
    void shouldVerifyExhaustionProofCannotBeReplayedAcrossSessions() {
        // given
        RuntimeWorkSession first =
                processing(new GasMeter(
                        GasSchedule.contracts10(), 1L));
        GasMeter.ChildGasLedger firstLedger =
                first.openLedger(
                        "hosted",
                        Collections.singletonMap(
                                "step", 1L));
        firstLedger.charge("step", 1L);
        RuntimeWorkSession second =
                processing(new GasMeter(
                        GasSchedule.contracts10(), 1L));
        GasMeter.ChildGasLedger secondLedger =
                second.openLedger(
                        "hosted",
                        Collections.singletonMap(
                                "step", 1L));
        secondLedger.charge("step", 1L);

        // when
        GasLimitExceededException firstRejection = captureFailure(
                () -> firstLedger.charge(
                        "step", 1L));
        GasLimitExceededException secondRejection = captureFailure(
                () -> secondLedger.charge(
                        "step", 1L));
        IllegalArgumentException foreignProof = captureFailure(
                () -> second.propagateGasExhaustion(
                        RuntimeGasExhaustion.from(
                                firstRejection)));
        GasLimitExceededException secondCanonical =
                captureFailure(
                        () -> second.propagateGasExhaustion(
                                RuntimeGasExhaustion.from(
                                        secondRejection)));
        GasLimitExceededException firstCanonical =
                captureFailure(
                        () -> first.propagateGasExhaustion(
                                RuntimeGasExhaustion.from(
                                        firstRejection)));

        // then
        assertNotNull(firstRejection);
        assertNotNull(secondRejection);
        assertNotNull(foreignProof);
        assertNotNull(secondCanonical);
        assertNotNull(firstCanonical);
    }

    @Test
    void shouldVerifyPendingChildGasCannotBeConvertedToSuspension() {
        // given
        GasMeter parent =
                new GasMeter(
                        GasSchedule.contracts10(), 10L);
        RuntimeWorkSession session =
                processing(parent);
        GasMeter.ChildGasLedger ledger =
                session.openLedger(
                        "hosted",
                        Collections.singletonMap(
                                "step", 3L));
        ledger.charge("step", 2L);

        // when
        GasLimitExceededException rejection = captureFailure(
                () -> ledger.charge(
                        "step", 2L));
        GasLimitExceededException canonical =
                captureFailure(session::suspend);
        boolean open = session.isOpen();
        long totalGas = parent.totalGas();
        int traceSize = parent.trace().size();
        String counter = parent.trace().get(0).counter();

        // then
        assertNotNull(rejection);
        assertNotNull(canonical);
        assertEquals(rejection, canonical);
        assertFalse(open);
        assertEquals(6L, totalGas);
        assertEquals(1, traceSize);
        assertEquals("step", counter);
    }

    @Test
    void shouldVerifyForeignLedgerAndDirectParentMergeCannotBypassOwnership() {
        // given
        GasMeter parent = new GasMeter();
        RuntimeWorkSession first = processing(parent);
        RuntimeWorkSession second = processing(parent);
        GasMeter.ChildGasLedger ledger =
                first.openLedger(
                        "owned",
                        Collections.singletonMap("step", 1L));

        // when
        ledger.charge("step", 1L);
        IllegalArgumentException foreignSubmission =
                captureFailure(
                        () -> second.submit(ledger));
        IllegalArgumentException directMerge =
                captureFailure(
                        () -> parent.merge(ledger));
        first.failDeterministically();
        second.suspend();

        // then
        assertNotNull(foreignSubmission);
        assertNotNull(directMerge);
    }

    @Test
    void shouldVerifyOutOfBandModeIsExplicitAndDoesNotClaimProcessGas() {
        // given
        RuntimeWorkSession admission =
                new RuntimeWorkSession(
                        new GasMeter(),
                        RuntimeWorkSession.Mode.ADMISSION);

        // when
        RuntimeWorkSession.Mode mode = admission.mode();
        boolean contributesToProcessGas =
                admission.contributesToProcessGas();
        admission.suspend();

        // then
        assertEquals(
                RuntimeWorkSession.Mode.ADMISSION,
                mode);
        assertFalse(contributesToProcessGas);
    }

    @Test
    void shouldVerifyCounterCatalogIsDefensivelyFrozen() {
        // given
        Map<String, Long> mutable =
                new LinkedHashMap<>();
        mutable.put("step", 2L);
        RuntimeWorkSession session =
                processing(new GasMeter());
        GasMeter.ChildGasLedger ledger =
                session.openLedger("frozen", mutable);

        // when
        mutable.put("step", 99L);
        mutable.put("other", 1L);
        Map<String, Long> frozenCatalog =
                ledger.counterWeights();
        session.suspend();

        // then
        assertEquals(
                Collections.singletonMap("step", 2L),
                frozenCatalog);
    }

    @Test
    void shouldVerifyLogicalTraceIsRepresentationBlindAndPreservesLedgerOrder() {
        // given
        Map<String, Long> inlineCatalog =
                new LinkedHashMap<>();
        inlineCatalog.put("read", 2L);
        inlineCatalog.put("construct", 3L);
        Map<String, Long> referencedCatalog =
                new LinkedHashMap<>();
        referencedCatalog.put("construct", 3L);
        referencedCatalog.put("read", 2L);

        // when
        GasMeter inline = runLogicalWork(inlineCatalog);
        GasMeter referenced =
                runLogicalWork(referencedCatalog);

        // then
        assertEquals(inline.totalGas(), referenced.totalGas());
        assertEquals(
                traceFingerprint(inline.trace()),
                traceFingerprint(referenced.trace()));
        assertEquals(
                "read:1:first",
                traceFingerprint(inline.trace()).get(0));
        assertEquals(
                "construct:2:second",
                traceFingerprint(inline.trace()).get(1));
        assertEquals(
                "read:3:third",
                traceFingerprint(inline.trace()).get(2));
    }

    @Test
    void shouldNotReuseCounterKindLimitAsNamespaceLimit() {
        // given
        int limit = (int) GasSchedule.contracts10()
                .portableLimit(
                        GasScheduleConstants.PortableLimit
                                .RUNTIME_CHILD_LEDGER_COUNTER_KINDS);

        RuntimeWorkSession namespaceSession =
                processing(new GasMeter());

        // when
        for (int index = 0; index <= limit; index++) {
            namespaceSession.openLedger(
                    "namespace-" + index,
                    Collections.singletonMap(
                            "step", 1L));
        }
        namespaceSession.suspend();

        // then
        assertFalse(namespaceSession.isOpen());
    }

    @Test
    void shouldRejectCounterCatalogLimitBeforeAdmission() {
        // given
        int limit = (int) GasSchedule.contracts10()
                .portableLimit(
                        GasScheduleConstants.PortableLimit
                                .RUNTIME_CHILD_LEDGER_COUNTER_KINDS);
        Map<String, Long> oversizedCatalog =
                new LinkedHashMap<>();
        for (int index = 0; index <= limit; index++) {
            oversizedCatalog.put(
                    "counter-" + index, 1L);
        }
        RuntimeWorkSession catalogSession =
                processing(new GasMeter());

        // when
        PortableLimitExceededException catalogFailure =
                captureFailure(
                        () -> catalogSession.openLedger(
                                "catalog-overflow",
                                oversizedCatalog));
        catalogSession.suspend();

        // then
        assertNotNull(catalogFailure);
        assertEquals(
                ProcessorErrorCategory.RuntimeLedgerLimitExceeded,
                catalogFailure.diagnostic().category());
        assertEquals(
                GasScheduleConstants.PortableLimit
                        .RUNTIME_CHILD_LEDGER_COUNTER_KINDS,
                catalogFailure.limitName());
        assertEquals(limit + 1L, catalogFailure.observed());
        assertEquals(limit, catalogFailure.limit());
    }

    @Test
    void shouldAdmit516OrderedEntriesForSmallCounterCatalogWhenGasPermits() {
        // given
        GasMeter parent = new GasMeter();
        RuntimeWorkSession session = processing(parent);
        GasMeter.ChildGasLedger ledger =
                session.openLedger(
                        MEMBER_VISIT_NAMESPACE,
                        MEMBER_VISIT_CATALOG);

        // when
        chargeMemberVisitEntries(
                ledger,
                PORTABLE_CAPACITY_ENTRIES);
        session.submit(ledger);
        session.complete();
        List<GasTraceEntry> trace = parent.trace();

        // then
        assertExactMemberVisitPrefix(
                trace,
                MEMBER_VISIT_NAMESPACE,
                PORTABLE_CAPACITY_ENTRIES);
        assertEquals(
                PORTABLE_CAPACITY_ENTRIES,
                parent.totalGas());
        assertFalse(session.isOpen());
    }

    @Test
    void shouldRetainExactPrefixAndOmitRejectedChargeAtKnownEntry() {
        // given
        int admittedEntries =
                PORTABLE_CAPACITY_ENTRIES - 1;
        GasMeter parent = new GasMeter(
                GasSchedule.contracts10(),
                admittedEntries);
        RuntimeWorkSession session = processing(parent);
        GasMeter.ChildGasLedger ledger =
                session.openLedger(
                        MEMBER_VISIT_NAMESPACE,
                        MEMBER_VISIT_CATALOG);
        chargeMemberVisitEntries(
                ledger,
                admittedEntries);
        String rejectedReason =
                memberVisitReason(
                        admittedEntries,
                        memberVisitCounter(
                                admittedEntries));

        // when
        GasLimitExceededException rejected =
                captureFailure(
                        () -> chargeMemberVisitEntry(
                                ledger,
                                admittedEntries));
        List<GasTraceEntry> staged =
                session.stagedTrace();
        IllegalStateException laterWork =
                captureFailure(
                        () -> chargeMemberVisitEntry(
                                ledger,
                                admittedEntries + 1));
        GasLimitExceededException canonical =
                captureFailure(
                        () -> session.propagateGasExhaustion(
                                RuntimeGasExhaustion.from(
                                        rejected)));
        List<GasTraceEntry> committed =
                parent.trace();

        // then
        assertNotNull(rejected);
        assertEquals(
                admittedEntries,
                rejected.admittedGas());
        assertEquals(
                admittedEntries,
                rejected.effectiveBudget());
        assertNotNull(laterWork);
        assertEquals(rejected, canonical);
        assertExactMemberVisitPrefix(
                staged,
                MEMBER_VISIT_NAMESPACE,
                admittedEntries);
        assertExactMemberVisitPrefix(
                committed,
                MEMBER_VISIT_NAMESPACE,
                admittedEntries);
        assertFalse(
                containsReason(
                        committed,
                        rejectedReason),
                "the rejected charge must not enter the trace");
        assertEquals(
                admittedEntries,
                parent.totalGas());
        assertFalse(session.isOpen());
    }

    @Test
    void shouldAdmitAllChargesFor1024BoundedMemberVisitsWhenGasPermits() {
        // given
        GasMeter parent = new GasMeter();
        RuntimeWorkSession session = processing(parent);
        GasMeter.ChildGasLedger ledger =
                session.openLedger(
                        MEMBER_VISIT_NAMESPACE,
                        MEMBER_VISIT_CATALOG);

        // when
        chargeMemberVisitEntries(
                ledger,
                BOUNDED_MEMBER_VISIT_ENTRIES);
        session.submit(ledger);
        session.complete();
        List<GasTraceEntry> trace = parent.trace();

        // then
        assertExactMemberVisitPrefix(
                trace,
                MEMBER_VISIT_NAMESPACE,
                BOUNDED_MEMBER_VISIT_ENTRIES);
        for (String counter : MEMBER_VISIT_COUNTERS) {
            assertEquals(
                    BOUNDED_MEMBER_VISITS,
                    countCounter(trace, counter));
        }
        assertEquals(
                BOUNDED_MEMBER_VISIT_ENTRIES,
                parent.totalGas());
    }

    @Test
    void shouldAdmitMoreThan256CombinedEntriesAcrossValidNamespaces() {
        // given
        String alphaNamespace = "alpha-runtime";
        String zetaNamespace = "zeta-runtime";
        String counter = "step";
        Map<String, Long> catalog =
                Collections.singletonMap(
                        counter,
                        UNIT_WEIGHT);
        GasMeter parent = new GasMeter();
        RuntimeWorkSession session = processing(parent);
        GasMeter.ChildGasLedger zeta =
                session.openLedger(
                        zetaNamespace,
                        catalog);
        GasMeter.ChildGasLedger alpha =
                session.openLedger(
                        alphaNamespace,
                        catalog);

        // when
        chargeRepeatedEntries(
                zeta,
                counter,
                zetaNamespace,
                ENTRIES_PER_SHARED_NAMESPACE);
        chargeRepeatedEntries(
                alpha,
                counter,
                alphaNamespace,
                ENTRIES_PER_SHARED_NAMESPACE);
        session.submit(zeta);
        session.submit(alpha);
        session.complete();
        List<GasTraceEntry> trace = parent.trace();

        // then
        assertEquals(
                ENTRIES_PER_SHARED_NAMESPACE * 2,
                trace.size());
        assertNamespaceBlock(
                trace,
                0,
                ENTRIES_PER_SHARED_NAMESPACE,
                alphaNamespace);
        assertNamespaceBlock(
                trace,
                ENTRIES_PER_SHARED_NAMESPACE,
                ENTRIES_PER_SHARED_NAMESPACE * 2,
                zetaNamespace);
        assertEquals(
                ENTRIES_PER_SHARED_NAMESPACE * 2,
                parent.totalGas());
    }

    @Test
    void shouldRetainLongExactPrefixAfterDeterministicRuntimeFailure() {
        // given
        GasMeter parent = new GasMeter();
        RuntimeWorkSession session = processing(parent);
        GasMeter.ChildGasLedger ledger =
                session.openLedger(
                        MEMBER_VISIT_NAMESPACE,
                        MEMBER_VISIT_CATALOG);

        // when
        chargeMemberVisitEntries(
                ledger,
                PORTABLE_CAPACITY_ENTRIES);
        List<GasTraceEntry> staged =
                session.stagedTrace();
        session.failDeterministically();
        List<GasTraceEntry> retained =
                parent.trace();

        // then
        assertExactMemberVisitPrefix(
                staged,
                MEMBER_VISIT_NAMESPACE,
                PORTABLE_CAPACITY_ENTRIES);
        assertExactMemberVisitPrefix(
                retained,
                MEMBER_VISIT_NAMESPACE,
                PORTABLE_CAPACITY_ENTRIES);
        assertEquals(
                PORTABLE_CAPACITY_ENTRIES,
                parent.totalGas());
        assertFalse(session.isOpen());
    }

    @Test
    void shouldDiscardLongStagedPortablePrefixAfterTransientSuspension() {
        // given
        GasMeter parent = new GasMeter();
        RuntimeWorkSession session = processing(parent);
        GasMeter.ChildGasLedger ledger =
                session.openLedger(
                        MEMBER_VISIT_NAMESPACE,
                        MEMBER_VISIT_CATALOG);

        // when
        chargeMemberVisitEntries(
                ledger,
                PORTABLE_CAPACITY_ENTRIES);
        session.submit(ledger);
        List<GasTraceEntry> staged =
                session.stagedTrace();
        session.suspend();

        // then
        assertExactMemberVisitPrefix(
                staged,
                MEMBER_VISIT_NAMESPACE,
                PORTABLE_CAPACITY_ENTRIES);
        assertTrue(parent.trace().isEmpty());
        assertEquals(0L, parent.totalGas());
        assertEquals(
                parent.gasLimit(),
                parent.remainingGas());
        assertFalse(session.isOpen());
    }

    @Test
    void shouldRejectZeroWeightRuntimeCounterCatalogBeforeOpeningLedger() {
        // given
        GasMeter parent = new GasMeter();
        RuntimeWorkSession session = processing(parent);
        Map<String, Long> zeroWeightCatalog =
                Collections.singletonMap(
                        "zero-weight",
                        0L);

        // when
        IllegalArgumentException failure =
                captureFailure(
                        () -> session.openLedger(
                                "zero-weight-runtime",
                                zeroWeightCatalog));
        boolean openAfterRejection =
                session.isOpen();
        session.suspend();

        // then
        assertNotNull(failure);
        assertTrue(openAfterRejection);
        assertTrue(parent.trace().isEmpty());
        assertEquals(0L, parent.totalGas());
    }

    @Test
    void shouldRejectZeroWeightDetachedRuntimeCounterCatalog() {
        // given
        GasMeter parent = new GasMeter();
        Map<String, Long> zeroWeightCatalog =
                Collections.singletonMap(
                        "zero-weight",
                        0L);

        // when
        IllegalArgumentException failure =
                captureFailure(
                        () -> parent.childLedger(
                                "zero-weight-runtime",
                                zeroWeightCatalog));

        // then
        assertNotNull(failure);
        assertTrue(parent.trace().isEmpty());
        assertEquals(0L, parent.totalGas());
    }

    private static GasMeter runLogicalWork(
            Map<String, Long> catalog) {
        GasMeter parent = new GasMeter();
        RuntimeWorkSession session = processing(parent);
        GasMeter.ChildGasLedger ledger =
                session.openLedger("runtime", catalog);
        ledger.charge(
                "read",
                1L,
                GasChargeContext.reason("first"));
        ledger.charge(
                "construct",
                2L,
                GasChargeContext.reason("second"));
        ledger.charge(
                "read",
                3L,
                GasChargeContext.reason("third"));
        session.submit(ledger);
        session.complete();
        return parent;
    }

    private static List<String> traceFingerprint(
            List<GasTraceEntry> trace) {
        List<String> fingerprint =
                new ArrayList<>(trace.size());
        for (GasTraceEntry entry : trace) {
            fingerprint.add(
                    entry.counter()
                            + ":" + entry.quantity()
                            + ":" + entry.reason());
        }
        return fingerprint;
    }

    private static Map<String, Long> memberVisitCatalog() {
        Map<String, Long> catalog =
                new LinkedHashMap<>();
        for (String counter : MEMBER_VISIT_COUNTERS) {
            catalog.put(counter, UNIT_WEIGHT);
        }
        return Collections.unmodifiableMap(catalog);
    }

    private static void chargeMemberVisitEntries(
            GasMeter.ChildGasLedger ledger,
            int entryCount) {
        for (int entryIndex = 0;
             entryIndex < entryCount;
             entryIndex++) {
            chargeMemberVisitEntry(
                    ledger,
                    entryIndex);
        }
    }

    private static void chargeMemberVisitEntry(
            GasMeter.ChildGasLedger ledger,
            int entryIndex) {
        String counter =
                memberVisitCounter(entryIndex);
        ledger.charge(
                counter,
                1L,
                GasChargeContext.reason(
                        memberVisitReason(
                                entryIndex,
                                counter)));
    }

    private static String memberVisitCounter(
            int entryIndex) {
        return MEMBER_VISIT_COUNTERS[
                entryIndex
                        % MEMBER_VISIT_COUNTERS.length];
    }

    private static String memberVisitReason(
            int entryIndex,
            String counter) {
        int visitIndex =
                entryIndex
                        / MEMBER_VISIT_COUNTERS.length;
        return "visit-" + visitIndex
                + ":" + counter;
    }

    private static void chargeRepeatedEntries(
            GasMeter.ChildGasLedger ledger,
            String counter,
            String reasonPrefix,
            int entryCount) {
        for (int index = 0;
             index < entryCount;
             index++) {
            ledger.charge(
                    counter,
                    1L,
                    GasChargeContext.reason(
                            reasonPrefix + "-" + index));
        }
    }

    private static void assertExactMemberVisitPrefix(
            List<GasTraceEntry> trace,
            String namespace,
            int entryCount) {
        assertEquals(entryCount, trace.size());
        for (int entryIndex = 0;
             entryIndex < entryCount;
             entryIndex++) {
            String counter =
                    memberVisitCounter(
                            entryIndex);
            GasTraceEntry entry =
                    trace.get(entryIndex);
            assertEquals(entryIndex, entry.sequence());
            assertEquals(namespace, entry.namespace());
            assertEquals(counter, entry.counter());
            assertEquals(1L, entry.quantity());
            assertEquals(UNIT_WEIGHT, entry.weight());
            assertEquals(UNIT_WEIGHT, entry.subtotal());
            assertEquals(
                    memberVisitReason(
                            entryIndex,
                            counter),
                    entry.reason());
        }
    }

    private static boolean containsReason(
            List<GasTraceEntry> trace,
            String reason) {
        for (GasTraceEntry entry : trace) {
            if (reason.equals(entry.reason())) {
                return true;
            }
        }
        return false;
    }

    private static int countCounter(
            List<GasTraceEntry> trace,
            String counter) {
        int count = 0;
        for (GasTraceEntry entry : trace) {
            if (counter.equals(entry.counter())) {
                count++;
            }
        }
        return count;
    }

    private static void assertNamespaceBlock(
            List<GasTraceEntry> trace,
            int start,
            int end,
            String namespace) {
        for (int index = start;
             index < end;
             index++) {
            assertEquals(
                    namespace,
                    trace.get(index).namespace());
        }
    }

    private static RuntimeWorkSession processing(
            GasMeter parent) {
        return new RuntimeWorkSession(
                parent,
                RuntimeWorkSession.Mode.PROCESSING);
    }
}
