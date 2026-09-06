package blue.language.processor;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.*;

/** The observer records admitted evidence; it cannot perform or decide semantic work. */
class GasMeterAdmittedObserverTest {
    private static final String NAMESPACE = "processor";
    private static final String COUNTER = "pointerSegmentTraversed";

    @Test
    void notificationSeesTheAppendedEntryAndCompleteSharedAndLocalAccounting() {
        GasMeter meter = meter(100);
        meter.configureLocalGasLimits(Collections.singletonMap("A", 10L));
        List<GasTraceEntry> observed = new ArrayList<>();
        meter.observeAdmittedGas((original, entry) -> {
            assertSame(meter, original);
            assertSame(entry, original.trace().get(original.trace().size() - 1));
            observed.add(entry);
            long admitted = observed.stream().mapToLong(GasTraceEntry::subtotal).sum();
            assertEquals(admitted, original.totalGas());
            assertEquals(admitted, original.groupAdmittedGas());
            assertEquals(100L - admitted, original.remainingGas());
            // Read-only comparison proves local accounting is visible before notification too.
            assertTrue(original.matchesCurrentCapRejection(new GasLimitExceededException(
                    NAMESPACE, COUNTER, 11L, 1L, admitted, 10L,
                    GasLimitExceededException.ApplicableCapKind.LOCAL, "A", 10L - admitted, context("A"))));
        });
        charge(meter, 2, "A");
        charge(meter, 3, "A");
        assertEquals(2, observed.size());
        assertEquals(5, meter.totalGas());
        assertEquals(observed, meter.trace());
    }

    @Test
    void completedChildrenNotifyOnlyOnCanonicalParentAppendNotReservationOrSubmit() {
        GasMeter meter = meter(100);
        List<String> observed = new ArrayList<>();
        meter.observeAdmittedGas((original, entry) -> observed.add(entry.namespace() + ":" + entry.quantity()));
        charge(meter, 1, "A");
        RuntimeWorkSession session = session(meter);
        GasMeter.ChildGasLedger zeta = session.openLedger("zeta", Collections.singletonMap("step", 3L));
        zeta.charge("step", 2, context("A"));
        GasMeter.ChildGasLedger alpha = session.openLedger("alpha", Collections.singletonMap("read", 5L));
        alpha.charge("read", 1, context("A"));
        assertEquals(Collections.singletonList("processor:1"), observed);
        assertEquals(11, meter.groupReservedGas());
        charge(meter, 2, "A");
        session.submit(zeta);
        session.submit(alpha);
        assertEquals(Arrays.asList("processor:1", "processor:2"), observed);
        session.complete();
        assertEquals(Arrays.asList("processor:1", "processor:2", "alpha:1", "zeta:2"), observed);
        assertEquals(14, meter.totalGas());
        assertEquals(0, meter.groupReservedGas());
        assertFalse(session.isOpen());
    }

    @Test
    void deterministicChildFailureRetainsUnsubmittedPrefixButSuspensionEmitsNothing() {
        GasMeter meter = meter(100);
        List<GasTraceEntry> observed = new ArrayList<>();
        meter.observeAdmittedGas((original, entry) -> observed.add(entry));
        RuntimeWorkSession failed = session(meter);
        GasMeter.ChildGasLedger child = failed.openLedger("runtime", Collections.singletonMap("step", 1L));
        child.charge("step", 3, context("A"));
        assertTrue(observed.isEmpty());
        failed.failDeterministically();
        assertEquals(1, observed.size());
        assertEquals(3, meter.totalGas());
        assertEquals(0, meter.groupReservedGas());

        RuntimeWorkSession suspended = session(meter);
        GasMeter.ChildGasLedger discarded = suspended.openLedger("discarded", Collections.singletonMap("step", 1L));
        discarded.charge("step", 7, context("A"));
        suspended.submit(discarded);
        suspended.suspend();
        assertEquals(1, observed.size());
        assertEquals(3, meter.totalGas());
        assertEquals(0, meter.groupReservedGas());
        assertFalse(suspended.isOpen());
    }

    @Test
    void runtimeObserverKeepsOriginalOwnerAfterJoinWithoutReplayingOldEntries() {
        DocumentProcessor processor = DocumentProcessor.builder().build();
        try (ManagedDocumentStepRuntime a = new ManagedDocumentStepRuntime(processor);
             ManagedDocumentStepRuntime b = new ManagedDocumentStepRuntime(processor)) {
            List<ManagedDocumentStepRuntime> owners = new ArrayList<>();
            List<GasTraceEntry> entries = new ArrayList<>();
            BiConsumer<ManagedDocumentStepRuntime, GasTraceEntry> journal = (original, entry) -> {
                owners.add(original);
                entries.add(entry);
                assertSame(entry, original.gasTrace().get(original.gasTrace().size() - 1));
            };
            a.observeAdmittedGas(journal);
            b.observeAdmittedGas(journal);
            a.charge(NAMESPACE, COUNTER, 2, context("A"));
            b.charge(NAMESPACE, COUNTER, 3, context("B"));
            assertTrue(a.tryJoinGasGroup(b).joined());
            assertEquals(2, entries.size(), "Joining is not another admission of either prefix");
            a.charge(NAMESPACE, COUNTER, 4, context("A"));
            RuntimeWorkSession bSession = session(b.sharedGasContext().meter());
            GasMeter.ChildGasLedger bChild = bSession.openLedger("child", Collections.singletonMap("step", 1L));
            bChild.charge("step", 1, context("B"));
            bSession.submit(bChild);
            bSession.complete();
            assertEquals(Arrays.asList(a, b, a, b), owners);
            assertEquals(6, a.totalGas());
            assertEquals(4, b.totalGas());
            assertEquals(10, a.groupAdmittedGas());
            assertEquals(10, b.groupAdmittedGas());
            assertSame(entries.get(2), a.gasTrace().get(1));
            assertSame(entries.get(3), b.gasTrace().get(1));
        } finally {
            processor.close();
        }
    }

    @Test
    void installationIsOneTimeBeforeAnyActualOrPreviouslyDiscardedAccounting() {
        GasMeter fresh = meter(10);
        charge(fresh, 0, "A");
        RuntimeWorkSession empty = session(fresh);
        empty.openLedger("empty", Collections.singletonMap("step", 1L));
        fresh.observeAdmittedGas((owner, entry) -> { });
        empty.complete();
        assertThrows(IllegalStateException.class, () -> fresh.observeAdmittedGas((owner, entry) -> { }));

        GasMeter charged = meter(10);
        charge(charged, 1, "A");
        assertThrows(IllegalStateException.class, () -> charged.observeAdmittedGas((owner, entry) -> { }));
        GasMeter rejected = meter(0);
        assertThrows(GasLimitExceededException.class, () -> charge(rejected, 1, "A"));
        assertThrows(IllegalStateException.class, () -> rejected.observeAdmittedGas((owner, entry) -> { }));

        GasMeter reserved = meter(10);
        RuntimeWorkSession staged = session(reserved);
        GasMeter.ChildGasLedger child = staged.openLedger("child", Collections.singletonMap("step", 1L));
        child.charge("step", 1);
        assertThrows(IllegalStateException.class, () -> reserved.observeAdmittedGas((owner, entry) -> { }));
        staged.suspend();
        assertEquals(0, reserved.totalGas());
        assertEquals(0, reserved.groupReservedGas());
        assertThrows(IllegalStateException.class, () -> reserved.observeAdmittedGas((owner, entry) -> { }));
        assertThrows(IllegalStateException.class, () -> GasMeter.retainedSourceProof(GasSchedule.contracts10())
                .observeAdmittedGas((owner, entry) -> { }));
    }

    @Test
    void firstRejectedRuntimeChargeAlsoClosesTheObserverInstallationWindow() {
        GasMeter meter = meter(0);
        RuntimeWorkSession session = session(meter);
        GasMeter.ChildGasLedger child = session.openLedger("child", Collections.singletonMap("step", 1L));
        GasLimitExceededException rejected = assertThrows(GasLimitExceededException.class, () -> child.charge("step", 1));
        assertEquals(0, meter.totalGas());
        assertEquals(0, meter.groupReservedGas());
        assertThrows(IllegalStateException.class, () -> meter.observeAdmittedGas((owner, entry) -> { }));
        assertSame(rejected, assertThrows(GasLimitExceededException.class, () -> session.propagateGasExhaustion(rejected)));
    }

    @Test
    void rejectedChargesNeverNotifyButExhaustedChildrenPublishTheirAdmittedPrefix() {
        GasMeter meter = meter(5);
        List<GasTraceEntry> observed = new ArrayList<>();
        meter.observeAdmittedGas((owner, entry) -> observed.add(entry));
        charge(meter, 2, "A");
        assertThrows(GasLimitExceededException.class, () -> charge(meter, 4, "A"));
        assertEquals(1, observed.size());
        RuntimeWorkSession session = session(meter);
        GasMeter.ChildGasLedger child = session.openLedger("child", Collections.singletonMap("step", 1L));
        child.charge("step", 2, context("A"));
        GasLimitExceededException rejected = assertThrows(GasLimitExceededException.class, () -> child.charge("step", 2, context("A")));
        assertEquals(1, observed.size());
        assertSame(rejected, assertThrows(GasLimitExceededException.class, () -> session.propagateGasExhaustion(rejected)));
        assertEquals(2, observed.size());
        assertEquals(4, meter.totalGas());
        assertEquals(0, meter.groupReservedGas());
        assertEquals(Arrays.asList("processor", "child"), Arrays.asList(observed.get(0).namespace(), observed.get(1).namespace()));
    }

    @Test
    void anyRuntimeExceptionFromJournalIsNoncommittingEvenWhenItLooksSemantic() {
        for (RuntimeException journalFailure : Arrays.asList(
                new IllegalStateException("journal unavailable"),
                new ProcessorFailureException(ProcessorErrorCategory.InvalidContractBinding, "not authored failure"))) {
            GasMeter meter = meter(10);
            meter.observeAdmittedGas((owner, entry) -> { throw journalFailure; });
            UnclassifiedProcessingException failure = assertThrows(UnclassifiedProcessingException.class, () -> charge(meter, 1, "A"));
            assertSame(journalFailure, failure.getCause());
            assertTrue(failure instanceof NoncommittingExecutionException);
            assertEquals(1, meter.totalGas(), "The tentative accounting append preceded journal failure");
            assertEquals(1, meter.trace().size());
            GasMeter unrelatedNextAttempt = meter(10);
            assertDoesNotThrow(() -> charge(unrelatedNextAttempt, 1, "B"), "Failed callback must not leak its thread-local guard");
        }
    }

    @Test
    void callbackFailureDuringChildCompletionDiscardsUnappendedReservationsWithoutMaskingFailure() {
        GasMeter meter = meter(100);
        IllegalStateException journalFailure = new IllegalStateException("journal unavailable during child merge");
        List<GasTraceEntry> observed = new ArrayList<>();
        meter.observeAdmittedGas((owner, entry) -> {
            observed.add(entry);
            throw journalFailure;
        });
        RuntimeWorkSession session = session(meter);
        GasMeter.ChildGasLedger alpha = session.openLedger("alpha", Collections.singletonMap("step", 1L));
        alpha.charge("step", 2, context("A"));
        alpha.charge("step", 3, context("A"));
        GasMeter.ChildGasLedger zeta = session.openLedger("zeta", Collections.singletonMap("step", 1L));
        zeta.charge("step", 4, context("A"));
        session.submit(alpha);
        session.submit(zeta);
        assertEquals(9, meter.groupReservedGas());
        UnclassifiedProcessingException failure = assertThrows(UnclassifiedProcessingException.class, session::complete);
        assertSame(journalFailure, failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
        assertEquals(1, observed.size());
        assertEquals(2, meter.totalGas());
        assertEquals(0, meter.groupReservedGas());
        assertDoesNotThrow(() -> { if (session.isOpen()) session.suspend(); });
        assertDoesNotThrow(session::close);
        assertFalse(session.isOpen());
        assertEquals(1, observed.size(), "Failure cleanup cannot publish the remaining child prefix");
    }

    @Test
    void callbacksCannotChargeReserveOrJoinThroughEitherOriginalOrPeerMeter() {
        for (int action = 0; action < 5; action++) {
            GasMeter a = meter(100), b = meter(100), outside = meter(100);
            assertTrue(a.tryJoinGroup(b).joined());
            RuntimeWorkSession aSession = session(a), bSession = session(b);
            GasMeter.ChildGasLedger aChild = aSession.openLedger("a-child", Collections.singletonMap("step", 1L));
            GasMeter.ChildGasLedger bChild = bSession.openLedger("b-child", Collections.singletonMap("step", 1L));
            final int selected = action;
            a.observeAdmittedGas((owner, entry) -> {
                switch (selected) {
                    case 0: charge(a, 1, "A"); break;
                    case 1: charge(b, 1, "B"); break;
                    case 2: aChild.charge("step", 1, context("A")); break;
                    case 3: bChild.charge("step", 1, context("B")); break;
                    case 4: a.tryJoinGroup(outside); break;
                    default: throw new AssertionError(selected);
                }
            });
            assertThrows(UnclassifiedProcessingException.class, () -> charge(a, 1, "A"), "reentrant action " + action);
            assertEquals(1, a.totalGas());
            assertEquals(0, b.totalGas());
            assertEquals(1, a.groupAdmittedGas());
            assertEquals(0, a.groupReservedGas());
            assertFalse(a.sharesBudgetWith(outside));
            assertDoesNotThrow(aSession::suspend);
            assertDoesNotThrow(bSession::suspend);
        }
    }

    private static GasMeter meter(long limit) { return new GasMeter(GasSchedule.contracts10(), limit); }
    private static RuntimeWorkSession session(GasMeter meter) { return new RuntimeWorkSession(meter, RuntimeWorkSession.Mode.PROCESSING); }
    private static GasChargeContext context(String owner) {
        return GasChargeContext.closure(owner, "/", 0L, 0L, null, null, null, "observer-test");
    }
    private static void charge(GasMeter meter, long quantity, String owner) {
        meter.charge(NAMESPACE, COUNTER, quantity, context(owner));
    }
}
