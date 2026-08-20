package blue.language.processor;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Exact shared-ledger checks for affected-closure document-local ceilings. */
final class ClosureLocalGasLimitTest {

    private static final String COUNTER = "closureWorkOccurrenceDequeued";
    private static final long WEIGHT = 5L;

    @Test
    void shouldDebitOnlyTheDocumentNamedByCanonicalAttribution() {
        GasMeter meter = meter(100L, limits("member-a", 15L, "member-b", 5L));

        meter.charge("processor", COUNTER, 2L);
        chargeAs(meter, "member-a", 3L);
        chargeAs(meter, "member-b", 1L);

        GasLimitExceededException rejectedA = assertThrows(
                GasLimitExceededException.class,
                () -> chargeAs(meter, "member-a", 1L));
        GasLimitExceededException rejectedB = assertThrows(
                GasLimitExceededException.class,
                () -> chargeAs(meter, "member-b", 1L));

        assertLocalRejection(rejectedA, "member-a", 15L, 0L);
        assertLocalRejection(rejectedB, "member-b", 5L, 0L);
        assertEquals(30L, meter.totalGas());
        assertEquals(3, meter.trace().size());
        assertNull(meter.trace().get(0).documentId());
        assertEquals("member-a", meter.trace().get(1).documentId());
        assertEquals("member-b", meter.trace().get(2).documentId());
    }

    @Test
    void shouldChooseTheCapWithLessRemainingAndSharedOnTie() {
        GasMeter localFirst = meter(40L, singletonLimit("member-a", 20L));
        chargeAs(localFirst, "member-a", 1L);
        GasLimitExceededException local = assertThrows(
                GasLimitExceededException.class,
                () -> chargeAs(localFirst, "member-a", 4L));
        assertLocalRejection(local, "member-a", 20L, 15L);

        GasMeter sharedFirst = meter(30L, singletonLimit("member-a", 20L));
        sharedFirst.charge("processor", COUNTER, 3L);
        chargeAs(sharedFirst, "member-a", 1L);
        GasLimitExceededException shared = assertThrows(
                GasLimitExceededException.class,
                () -> chargeAs(sharedFirst, "member-a", 4L));
        assertSharedRejection(shared, 30L, 20L, 10L);

        GasMeter tied = meter(30L, singletonLimit("member-a", 20L));
        tied.charge("processor", COUNTER, 2L);
        chargeAs(tied, "member-a", 2L);
        GasLimitExceededException tie = assertThrows(
                GasLimitExceededException.class,
                () -> chargeAs(tied, "member-a", 3L));
        assertSharedRejection(tie, 30L, 20L, 10L);
    }

    @Test
    void shouldApplyLocalCapBeforeRuntimeChildAdmissionAndRetainContext() {
        GasMeter meter = meter(100L, singletonLimit("member-a", 15L));
        ProcessingGasContext gas = new ProcessingGasContext(meter);
        RuntimeWorkSession session = gas.newRuntimeWorkSession(null, null);
        GasMeter.ChildGasLedger ledger = session.openLedger(
                "runtime.fixture",
                Collections.singletonMap("call", Long.valueOf(WEIGHT)));

        GasLimitExceededException rejection;
        try (GasMeter.AttributionScope ignored = meter.withAttribution(
                attribution("member-a"))) {
            ledger.charge("call", 2L, GasChargeContext.reason("runtime.call"));
            long remainingBeforeRejection = meter.remainingGas();
            rejection = assertThrows(
                    GasLimitExceededException.class,
                    () -> ledger.charge(
                            "call", 2L, GasChargeContext.reason("runtime.call")));
            assertEquals(remainingBeforeRejection, meter.remainingGas());
        }

        assertLocalRejection(rejection, "member-a", 15L, 5L);
        assertEquals(10L, ledger.totalGas());
        List<GasTraceEntry> staged = session.stagedTrace();
        assertEquals(1, staged.size());
        assertEquals("member-a", staged.get(0).documentId());
        assertEquals("runtime.call", staged.get(0).reason());
        assertEquals(0, meter.trace().size());

        GasLimitExceededException propagated = assertThrows(
                GasLimitExceededException.class,
                () -> session.propagateGasExhaustion(
                        RuntimeGasExhaustion.from(rejection)));
        assertEquals(rejection, propagated);
        assertEquals(10L, meter.totalGas());
        assertEquals(1, meter.trace().size());
        assertEquals("member-a", meter.trace().get(0).documentId());
        assertEquals("runtime.call", meter.trace().get(0).reason());
    }

    @Test
    void shouldReleaseDocumentReservationWhenRuntimeAttemptSuspends() {
        GasMeter meter = meter(100L, singletonLimit("member-a", 15L));
        RuntimeWorkSession session =
                new ProcessingGasContext(meter).newRuntimeWorkSession(null, null);
        GasMeter.ChildGasLedger ledger = session.openLedger(
                "runtime.fixture",
                Collections.singletonMap("call", Long.valueOf(WEIGHT)));
        try (GasMeter.AttributionScope ignored = meter.withAttribution(
                attribution("member-a"))) {
            ledger.charge("call", 2L);
        }
        session.submit(ledger);

        session.suspend();
        chargeAs(meter, "member-a", 3L);

        assertEquals(15L, meter.totalGas());
        assertEquals(1, meter.trace().size());
        assertEquals("member-a", meter.trace().get(0).documentId());
    }

    @Test
    void shouldShareOneDocumentCeilingAcrossRuntimeChildLedgers() {
        GasMeter meter = meter(100L, singletonLimit("member-a", 15L));
        RuntimeWorkSession session =
                new ProcessingGasContext(meter).newRuntimeWorkSession(null, null);
        GasMeter.ChildGasLedger alpha = session.openLedger(
                "runtime.alpha",
                Collections.singletonMap("call", Long.valueOf(WEIGHT)));
        GasMeter.ChildGasLedger beta = session.openLedger(
                "runtime.beta",
                Collections.singletonMap("call", Long.valueOf(WEIGHT)));

        GasLimitExceededException rejection;
        try (GasMeter.AttributionScope ignored = meter.withAttribution(
                attribution("member-a"))) {
            alpha.charge("call", 2L);
            beta.charge("call", 1L);
            rejection = assertThrows(
                    GasLimitExceededException.class,
                    () -> beta.charge("call", 1L));
        }

        assertLocalRejection(rejection, "member-a", 15L, 0L);
        assertEquals(2, session.stagedTrace().size());
        assertEquals(85L, meter.remainingGas());
        assertThrows(
                GasLimitExceededException.class,
                () -> session.propagateGasExhaustion(rejection));
        assertEquals(15L, meter.totalGas());
        assertEquals(2, meter.trace().size());
        assertEquals("runtime.alpha", meter.trace().get(0).namespace());
        assertEquals("runtime.beta", meter.trace().get(1).namespace());
    }

    @Test
    void shouldUseClosureCapPrecedenceBeforeAChildSnapshotBudget() {
        GasMeter meter = meter(30L, singletonLimit("member-a", 20L));
        meter.charge("processor", COUNTER, 2L);
        chargeAs(meter, "member-a", 2L);
        RuntimeWorkSession session =
                new ProcessingGasContext(meter).newRuntimeWorkSession(null, null);
        GasMeter.ChildGasLedger ledger = session.openLedger(
                "runtime.fixture",
                Collections.singletonMap("call", Long.valueOf(WEIGHT)));

        GasLimitExceededException rejection;
        try (GasMeter.AttributionScope ignored = meter.withAttribution(
                attribution("member-a"))) {
            rejection = assertThrows(
                    GasLimitExceededException.class,
                    () -> ledger.charge("call", 3L));
        }

        assertSharedRejection(rejection, 30L, 20L, 10L);
        assertEquals(0L, ledger.totalGas());
        assertEquals(2, meter.trace().size());
        assertThrows(
                GasLimitExceededException.class,
                () -> session.propagateGasExhaustion(rejection));
        assertEquals(2, meter.trace().size());
    }

    @Test
    void shouldForkRuntimeDiagnosticWorkWithExactRemainingLocalPolicy() {
        GasMeter meter = meter(100L, singletonLimit("member-a", 15L));

        try (GasMeter.AttributionScope ignored = meter.withAttribution(
                attribution("member-a"))) {
            RuntimeWorkSession authoritative =
                    new ProcessingGasContext(meter)
                            .newRuntimeWorkSession(null, null);
            RuntimeWorkSession comparison = authoritative.diagnosticTwin();
            GasMeter.ChildGasLedger first = authoritative.openLedger(
                    "runtime.fixture",
                    Collections.singletonMap("call", Long.valueOf(WEIGHT)));
            GasMeter.ChildGasLedger second = comparison.openLedger(
                    "runtime.fixture",
                    Collections.singletonMap("call", Long.valueOf(WEIGHT)));
            first.charge("call", 2L);
            second.charge("call", 2L);

            GasLimitExceededException firstRejection = assertThrows(
                    GasLimitExceededException.class,
                    () -> first.charge("call", 2L));
            GasLimitExceededException secondRejection = assertThrows(
                    GasLimitExceededException.class,
                    () -> second.charge("call", 2L));

            assertLocalRejection(firstRejection, "member-a", 15L, 5L);
            assertLocalRejection(secondRejection, "member-a", 15L, 5L);
            assertEquals("member-a",
                    authoritative.stagedTrace().get(0).documentId());
            assertEquals("member-a",
                    comparison.stagedTrace().get(0).documentId());
            assertThrows(
                    GasLimitExceededException.class,
                    () -> authoritative.propagateGasExhaustion(firstRejection));
            assertThrows(
                    GasLimitExceededException.class,
                    () -> comparison.propagateGasExhaustion(secondRejection));
        }
    }

    @Test
    void shouldConfigureExactLocalLimitsOnceBeforeAnyGasWork() {
        GasMeter meter = new GasMeter(GasSchedule.contracts10(), 30L);
        ProcessingGasContext gas = new ProcessingGasContext(meter);
        gas.configureLocalGasLimits(singletonLimit("member-a", 20L));

        assertThrows(
                IllegalStateException.class,
                () -> gas.configureLocalGasLimits(Collections.<String, Long>emptyMap()));

        GasMeter alreadyUsed = new GasMeter(GasSchedule.contracts10(), 30L);
        alreadyUsed.charge("processor", COUNTER, 1L);
        assertThrows(
                IllegalStateException.class,
                () -> alreadyUsed.configureLocalGasLimits(
                        singletonLimit("member-a", 20L)));

        GasMeter invalid = new GasMeter(GasSchedule.contracts10(), 30L);
        assertThrows(
                IllegalArgumentException.class,
                () -> invalid.configureLocalGasLimits(
                        singletonLimit("member-a", 31L)));
    }

    private static GasMeter meter(long sharedLimit,
                                  Map<String, Long> localLimits) {
        GasMeter meter = new GasMeter(GasSchedule.contracts10(), sharedLimit);
        new ProcessingGasContext(meter).configureLocalGasLimits(localLimits);
        return meter;
    }

    private static void chargeAs(GasMeter meter,
                                 String documentId,
                                 long quantity) {
        try (GasMeter.AttributionScope ignored = meter.withAttribution(
                attribution(documentId))) {
            meter.charge("processor", COUNTER, quantity);
        }
    }

    private static GasChargeContext attribution(String documentId) {
        return GasChargeContext.closure(
                documentId,
                "/",
                Long.valueOf(0L),
                Long.valueOf(1L),
                null,
                "work/0",
                null,
                "work");
    }

    private static Map<String, Long> singletonLimit(String documentId,
                                                     long limit) {
        return Collections.singletonMap(documentId, Long.valueOf(limit));
    }

    private static Map<String, Long> limits(String firstDocumentId,
                                             long firstLimit,
                                             String secondDocumentId,
                                             long secondLimit) {
        Map<String, Long> limits = new LinkedHashMap<>();
        limits.put(firstDocumentId, Long.valueOf(firstLimit));
        limits.put(secondDocumentId, Long.valueOf(secondLimit));
        return limits;
    }

    private static void assertLocalRejection(
            GasLimitExceededException rejection,
            String documentId,
            long localLimit,
            long remaining) {
        assertEquals(
                GasLimitExceededException.ApplicableCapKind.LOCAL,
                rejection.applicableCapKind());
        assertEquals(documentId, rejection.localDocumentId());
        assertEquals(localLimit, rejection.effectiveBudget());
        assertEquals(localLimit - remaining, rejection.admittedGas());
        assertEquals(remaining, rejection.remainingBeforeCharge());
    }

    private static void assertSharedRejection(
            GasLimitExceededException rejection,
            long sharedLimit,
            long admitted,
            long remaining) {
        assertEquals(
                GasLimitExceededException.ApplicableCapKind.SHARED,
                rejection.applicableCapKind());
        assertNull(rejection.localDocumentId());
        assertEquals(sharedLimit, rejection.effectiveBudget());
        assertEquals(admitted, rejection.admittedGas());
        assertEquals(remaining, rejection.remainingBeforeCharge());
    }
}
