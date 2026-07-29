package blue.language.processor;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static blue.language.processor.FailureCapture.captureFailure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RuntimeWorkSharedBudgetTest {

    private static final String ALPHA_NAMESPACE =
            "alpha-runtime";
    private static final String ZETA_NAMESPACE =
            "zeta-runtime";
    private static final String COUNTER_STEP =
            "step";
    private static final long PARENT_BUDGET =
            100L;
    private static final long SHARED_BUDGET =
            10L;
    private static final long ALPHA_WEIGHT =
            3L;
    private static final long ZETA_WEIGHT =
            2L;
    private static final Map<String, Long> ALPHA_CATALOG =
            Collections.singletonMap(
                    COUNTER_STEP, ALPHA_WEIGHT);
    private static final Map<String, Long> ZETA_CATALOG =
            Collections.singletonMap(
                    COUNTER_STEP, ZETA_WEIGHT);

    @Test
    void shouldAccumulateAcceptedChargesAcrossNamedLedgersInOneSharedBudget() {
        // given
        GasMeter parent =
                new GasMeter(
                        GasSchedule.contracts10(),
                        PARENT_BUDGET);
        RuntimeWorkSession session =
                processing(parent);
        RuntimeWorkBudget sharedBudget =
                session.openSharedBudget(
                        SHARED_BUDGET);
        GasMeter.ChildGasLedger zeta =
                session.openLedger(
                        ZETA_NAMESPACE,
                        ZETA_CATALOG,
                        sharedBudget);
        GasMeter.ChildGasLedger alpha =
                session.openLedger(
                        ALPHA_NAMESPACE,
                        ALPHA_CATALOG,
                        sharedBudget);

        // when
        alpha.charge(COUNTER_STEP, 2L);
        zeta.charge(COUNTER_STEP, 2L);
        long admittedGas =
                sharedBudget.admittedGas();
        long remainingGas =
                sharedBudget.remainingGas();
        session.submit(zeta);
        session.submit(alpha);
        session.complete();
        List<GasTraceEntry> trace =
                parent.trace();

        // then
        assertEquals(SHARED_BUDGET, sharedBudget.maximumGas());
        assertEquals(SHARED_BUDGET, admittedGas);
        assertEquals(0L, remainingGas);
        assertEquals(SHARED_BUDGET, parent.totalGas());
        assertEquals(2, trace.size());
        assertEquals(ALPHA_NAMESPACE, trace.get(0).namespace());
        assertEquals(ZETA_NAMESPACE, trace.get(1).namespace());
        assertFalse(session.isOpen());
    }

    @Test
    void shouldRecordSharedBudgetRejectionThroughCanonicalSessionPath() {
        // given
        GasMeter parent =
                new GasMeter(
                        GasSchedule.contracts10(),
                        PARENT_BUDGET);
        RuntimeWorkSession session =
                processing(parent);
        RuntimeWorkBudget sharedBudget =
                session.openSharedBudget(
                        SHARED_BUDGET);
        GasMeter.ChildGasLedger alpha =
                session.openLedger(
                        ALPHA_NAMESPACE,
                        ALPHA_CATALOG,
                        sharedBudget);
        GasMeter.ChildGasLedger zeta =
                session.openLedger(
                        ZETA_NAMESPACE,
                        ZETA_CATALOG,
                        sharedBudget);
        alpha.charge(COUNTER_STEP, 2L);
        zeta.charge(COUNTER_STEP, 2L);
        long remainingParentBeforeRejection =
                parent.remainingGas();

        // when
        GasLimitExceededException rejected =
                captureFailure(
                        () -> zeta.charge(
                                COUNTER_STEP, 1L));
        long remainingParentAfterRejection =
                parent.remainingGas();
        List<GasTraceEntry> staged =
                session.stagedTrace();
        IllegalStateException laterWork =
                captureFailure(
                        () -> alpha.charge(
                                COUNTER_STEP, 1L));
        GasLimitExceededException canonical =
                captureFailure(
                        () -> session.propagateGasExhaustion(
                                RuntimeGasExhaustion.from(
                                        rejected)));
        List<GasTraceEntry> committed =
                parent.trace();

        // then
        assertNotNull(rejected);
        assertEquals(ZETA_NAMESPACE, rejected.namespace());
        assertEquals(COUNTER_STEP, rejected.counter());
        assertEquals(SHARED_BUDGET, rejected.admittedGas());
        assertEquals(SHARED_BUDGET, rejected.effectiveBudget());
        assertEquals(
                remainingParentBeforeRejection,
                remainingParentAfterRejection,
                "rejected local work must not reserve parent gas");
        assertEquals(2, staged.size());
        assertNotNull(laterWork);
        assertEquals(rejected, canonical);
        assertEquals(2, committed.size());
        assertEquals(SHARED_BUDGET, parent.totalGas());
        assertFalse(session.isOpen());
    }

    @Test
    void shouldRejectSharedBudgetOwnedByAnotherRuntimeWorkSession() {
        // given
        RuntimeWorkSession first =
                processing(new GasMeter());
        RuntimeWorkSession second =
                processing(new GasMeter());
        RuntimeWorkBudget firstBudget =
                first.openSharedBudget(
                        SHARED_BUDGET);

        // when
        IllegalArgumentException failure =
                captureFailure(
                        () -> second.openLedger(
                                ZETA_NAMESPACE,
                                ZETA_CATALOG,
                                firstBudget));
        boolean firstStillOpen =
                first.isOpen();
        boolean secondStillOpen =
                second.isOpen();
        first.suspend();
        second.suspend();

        // then
        assertNotNull(failure);
        assertTrue(firstStillOpen);
        assertTrue(secondStillOpen);
    }

    @Test
    void shouldRejectNegativeSharedBudgetBeforeOpeningAnyLedger() {
        // given
        RuntimeWorkSession session =
                processing(new GasMeter());

        // when
        IllegalArgumentException failure =
                captureFailure(
                        () -> session.openSharedBudget(
                                -1L));
        List<GasTraceEntry> staged =
                session.stagedTrace();
        session.suspend();

        // then
        assertNotNull(failure);
        assertTrue(staged.isEmpty());
    }

    private static RuntimeWorkSession processing(
            GasMeter meter) {
        return new RuntimeWorkSession(
                meter,
                RuntimeWorkSession.Mode.PROCESSING);
    }
}
