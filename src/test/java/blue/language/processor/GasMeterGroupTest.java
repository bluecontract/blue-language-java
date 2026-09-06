package blue.language.processor;

import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/** Live accounting only; canonical group admission/rollback belongs to the closure coordinator. */
final class GasMeterGroupTest {
    @Test
    void shouldPreserveMemberTracesAndAvoidDoublePrefixesAfterAcceptedUnion() {
        // given
        GasMeter a = meter(), b = meter(), c = meter();
        charge(a, 60); charge(b, 30); charge(b, 1);
        // when
        GasMeter.GroupJoinResult result = a.tryJoinGroup(b);
        // then
        assertEquals(GasMeter.GroupJoinResult.Status.JOINED, result.status());
        assertEquals(60, result.leftAdmitted()); assertEquals(31, result.rightAdmitted());
        assertEquals(91, a.groupAdmittedGas()); assertEquals(9, b.remainingGas());
        assertEquals(60, a.totalGas()); assertEquals(31, b.totalGas());
        assertEquals(1, a.trace().size()); assertEquals(2, b.trace().size());
        assertTrue(b.tryJoinGroup(a).alreadyJoined());
        assertTrue(c.tryJoinGroup(b).joined());
        charge(c, 9);
        assertEquals(100, a.groupAdmittedGas()); assertEquals(0, b.remainingGas());
        assertEquals(9, c.totalGas());
        assertEquals(91, result.leftAdmitted() + result.rightAdmitted(), "join evidence is immutable");
    }

    @Test
    void shouldRetainExactInitiatorCostWhenUnionIsRejected() {
        // given
        GasMeter a = meter(), b = meter();
        charge(a, 60); charge(b, 50); charge(b, 1);
        // when
        GasMeter.GroupJoinResult result = a.tryJoinGroup(b);
        // then
        assertEquals(GasMeter.GroupJoinResult.Status.SHARED_LIMIT_EXCEEDED, result.status());
        assertEquals(60, result.leftAdmitted()); assertEquals(51, result.rightAdmitted());
        assertEquals(40, a.remainingGas()); assertEquals(49, b.remainingGas());
        charge(a, 40); charge(b, 49);
        assertEquals(100, a.groupAdmittedGas()); assertEquals(100, b.groupAdmittedGas());
    }

    @Test
    void shouldCountReservationsAndEnforceJoinedCapOnExistingChild() {
        // given
        GasMeter a = meter(), b = meter();
        charge(a, 20); charge(b, 50);
        // when
        RuntimeWorkSession session = session(a);
        GasMeter.ChildGasLedger child = session.openLedger("bex-test", Collections.singletonMap("step", 1L));
        // then
        assertEquals(80, child.effectiveBudget());
        child.charge("step", 20);
        GasMeter.GroupJoinResult result = a.tryJoinGroup(b);
        assertTrue(result.joined()); assertEquals(20, result.leftReserved());
        assertEquals(70, a.groupAdmittedGas()); assertEquals(20, b.groupReservedGas());
        assertEquals(10, a.remainingGas());
        GasLimitExceededException rejected = assertThrows(GasLimitExceededException.class,
                () -> child.charge("step", 11));
        assertEquals(90, rejected.admittedGas()); assertEquals(100, rejected.effectiveBudget());
        assertEquals(10, rejected.remainingBeforeCharge());
        assertTrue(a.matchesCurrentCapRejection(rejected));
        assertSame(rejected, assertThrows(GasLimitExceededException.class,
                () -> session.propagateGasExhaustion(rejected)));
        assertEquals(40, a.totalGas()); assertEquals(90, b.groupAdmittedGas());
        assertEquals(0, a.groupReservedGas()); assertEquals(10, b.remainingGas());
        assertThrows(IllegalStateException.class, () -> child.charge("step", 1));
    }

    @Test
    void shouldIncludeStagedChildCostWithoutConsumingChildOnRejectedUnion() {
        // given
        GasMeter a = meter(), b = meter();
        charge(a, 60); charge(b, 30);
        RuntimeWorkSession session = session(b);
        GasMeter.ChildGasLedger child = session.openLedger("bex-test", Collections.singletonMap("step", 1L));
        child.charge("step", 11);
        // when
        GasMeter.GroupJoinResult result = a.tryJoinGroup(b);
        // then
        assertFalse(result.joined()); assertEquals(11, result.rightReserved());
        assertEquals(40, a.remainingGas()); assertEquals(59, b.remainingGas());
        session.submit(child); session.complete();
        assertEquals(41, b.totalGas()); assertEquals(60, a.totalGas());
        assertEquals(0, b.groupReservedGas());
    }

    @Test
    void shouldMoveReservationExactlyOnceWhenJoinedChildCompletes() {
        // given
        GasMeter a = meter(), b = meter();
        charge(b, 30);
        RuntimeWorkSession session = session(a);
        GasMeter.ChildGasLedger child = session.openLedger("bex-test", Collections.singletonMap("step", 1L));
        child.charge("step", 20);
        // when
        a.tryJoinGroup(b);
        session.submit(child); session.complete();
        // then
        assertEquals(50, b.groupAdmittedGas()); assertEquals(50, a.remainingGas());
        assertEquals(0, a.groupReservedGas());
        assertThrows(IllegalStateException.class, () -> session.submit(child));
        assertThrows(IllegalStateException.class, () -> child.charge("step", 1));
        assertThrows(IllegalStateException.class, () -> b.merge(child));
    }

    @Test
    void shouldReleaseReservationsWithoutRefundingAdmittedWorkOnAbandonment() {
        // given
        GasMeter a = meter(), b = meter();
        charge(a, 5); charge(b, 30);
        RuntimeWorkSession session = session(a);
        GasMeter.ChildGasLedger child = session.openLedger("bex-test", Collections.singletonMap("step", 1L));
        child.charge("step", 20);
        // when
        a.tryJoinGroup(b); session.suspend();
        // then
        assertEquals(35, a.groupAdmittedGas()); assertEquals(0, b.groupReservedGas());
        assertEquals(5, a.totalGas());
        // No continuation or publication from this abandoned group: caller reconstructs the whole attempt.
    }

    @Test
    void shouldAggregateLocalCapsAndFreezePolicyAfterUnion() {
        // given
        GasMeter a = meter(), b = meter();
        a.configureLocalGasLimits(Collections.singletonMap("D", 50L));
        b.configureLocalGasLimits(Collections.singletonMap("D", 50L));
        charge(a, 20, context("D")); charge(b, 20, context("D"));
        // when
        GasMeter.GroupJoinResult joined = a.tryJoinGroup(b);
        // then
        assertTrue(joined.joined());
        RuntimeWorkSession session = session(a);
        GasMeter.ChildGasLedger child = session.openLedger("bex-test", Collections.singletonMap("step", 1L));
        GasLimitExceededException rejected = assertThrows(GasLimitExceededException.class,
                () -> child.charge("step", 11, context("D")));
        assertEquals(GasLimitExceededException.ApplicableCapKind.LOCAL, rejected.applicableCapKind());
        assertEquals(40, rejected.admittedGas()); assertEquals(50, rejected.effectiveBudget());
        assertTrue(a.matchesCurrentCapRejection(rejected));
        assertSame(rejected, assertThrows(GasLimitExceededException.class,
                () -> session.propagateGasExhaustion(rejected)));
        GasMeter emptyA = meter(), emptyB = meter();
        emptyA.tryJoinGroup(emptyB);
        assertThrows(IllegalStateException.class,
                () -> emptyB.configureLocalGasLimits(Collections.singletonMap("D", 50L)));
    }

    @Test
    void shouldRejectLocalOverCapUnionEvenWhenSharedBudgetFits() {
        // given
        GasMeter a = meter(), b = meter();
        a.configureLocalGasLimits(Collections.singletonMap("D", 50L));
        b.configureLocalGasLimits(Collections.singletonMap("D", 50L));
        charge(a, 30, context("D")); charge(b, 21, context("D"));
        // when
        GasMeter.GroupJoinResult result = a.tryJoinGroup(b);
        // then
        assertEquals(GasMeter.GroupJoinResult.Status.LOCAL_LIMIT_EXCEEDED, result.status());
        assertEquals("D", result.localDocumentId()); assertEquals(50, result.localLimit());
        assertEquals(30, result.leftLocal()); assertEquals(21, result.rightLocal());
        assertEquals(70, a.remainingGas()); assertEquals(79, b.remainingGas());
    }

    @Test
    void shouldRejectIncompatiblePoliciesAndProofMeters() {
        // given
        GasMeter a = meter();
        GasMeter b = meter(); b.configureLocalGasLimits(Collections.singletonMap("D", 50L));
        // when
        Throwable differentSharedCap = FailureCapture.captureFailure(() -> a.tryJoinGroup(new GasMeter(GasSchedule.contracts10(), 99)));
        Throwable differentLocalCap = FailureCapture.captureFailure(() -> a.tryJoinGroup(b));
        Throwable physicalProof = FailureCapture.captureFailure(() -> a.tryJoinGroup(GasMeter.retainedSourceProof(GasSchedule.contracts10())));
        // then
        assertInstanceOf(IllegalArgumentException.class, differentSharedCap);
        assertInstanceOf(IllegalArgumentException.class, differentLocalCap);
        assertInstanceOf(IllegalStateException.class, physicalProof);
        assertEquals(100, a.remainingGas());
    }

    @Test
    void shouldSnapshotJoinedBudgetWithoutChargingAuthoritativeGroup() {
        // given
        GasMeter a = meter(), b = meter();
        a.configureLocalGasLimits(Collections.singletonMap("D", 50L));
        b.configureLocalGasLimits(Collections.singletonMap("D", 50L));
        charge(a, 20, context("D")); charge(b, 10, context("D")); charge(b, 20);
        a.tryJoinGroup(b);
        RuntimeWorkSession authoritative = session(a);
        // when
        RuntimeWorkSession comparison = authoritative.diagnosticTwin();
        GasMeter.ChildGasLedger actual = authoritative.openLedger("bex-test", Collections.singletonMap("step", 1L));
        GasMeter.ChildGasLedger diagnostic = comparison.openLedger("bex-test", Collections.singletonMap("step", 1L));
        // then
        assertEquals(50, actual.effectiveBudget()); assertEquals(50, diagnostic.effectiveBudget());
        actual.charge("step", 20, context("D"));
        diagnostic.charge("step", 20, context("D"));
        assertEquals(20, a.groupReservedGas()); assertEquals(30, b.remainingGas());
        assertEquals(authoritative.stagedTrace().get(0).subtotal(), comparison.stagedTrace().get(0).subtotal());
        assertEquals(authoritative.stagedTrace().get(0).counter(), comparison.stagedTrace().get(0).counter());
        authoritative.submit(actual); authoritative.complete();
        comparison.submit(diagnostic); comparison.complete();
        assertEquals(70, b.groupAdmittedGas()); assertEquals(40, a.totalGas());
        assertEquals(0, a.groupReservedGas());
    }

    @Test
    void shouldAvoidPartialUnionWhenACompleteMultiPartyJoinFails() {
        // given
        GasMeter a = meter(), b = meter(), c = meter();
        charge(a, 40); charge(b, 40);
        RuntimeWorkSession session = session(c);
        GasMeter.ChildGasLedger child = session.openLedger("bex-test", Collections.singletonMap("step", 1L));
        child.charge("step", 21);
        // when
        GasMeter.MultiGroupJoinResult result = a.tryJoinGroups(java.util.Arrays.asList(b, c));
        // then
        assertFalse(result.joined()); assertEquals(3, result.contributions().size());
        assertEquals(21, result.contributions().get(2).reserved());
        assertEquals(60, a.remainingGas()); assertEquals(60, b.remainingGas()); assertEquals(79, c.remainingGas());
        charge(a, 60); charge(b, 60);
        session.submit(child); session.complete();
        assertEquals(100, a.groupAdmittedGas()); assertEquals(100, b.groupAdmittedGas()); assertEquals(21, c.groupAdmittedGas());
    }

    @Test
    void shouldCountRepeatedAndPreviouslyJoinedInputsOnce() {
        // given
        GasMeter a = meter(), b = meter(), c = meter();
        charge(a, 30); charge(b, 30); charge(c, 40);
        a.tryJoinGroup(b);
        // when
        GasMeter.MultiGroupJoinResult result = a.tryJoinGroups(java.util.Arrays.asList(b, a, c, b, c));
        // then
        assertTrue(result.joined()); assertEquals(2, result.contributions().size());
        assertEquals(0, result.contributions().get(0).firstInputIndex());
        assertEquals(3, result.contributions().get(1).firstInputIndex());
        assertEquals(60, result.contributions().get(0).admitted());
        assertEquals(40, result.contributions().get(1).admitted());
        assertEquals(100, c.groupAdmittedGas()); assertEquals(0, a.remainingGas());
    }

    @Test
    void shouldIncludeReservationsAndPreserveIndependentGroupsOnLocalFailure() {
        // given
        GasMeter a = meter(), b = meter(), c = meter();
        for (GasMeter member : java.util.Arrays.asList(a, b, c)) member.configureLocalGasLimits(Collections.singletonMap("D", 50L));
        charge(a, 20, context("D")); charge(b, 20, context("D"));
        RuntimeWorkSession session = session(c);
        GasMeter.ChildGasLedger child = session.openLedger("bex-test", Collections.singletonMap("step", 1L));
        child.charge("step", 11, context("D"));
        // when
        GasMeter.MultiGroupJoinResult result = a.tryJoinGroups(java.util.Arrays.asList(b, c));
        // then
        assertEquals(GasMeter.GroupJoinResult.Status.LOCAL_LIMIT_EXCEEDED, result.status());
        assertEquals("D", result.localDocumentId()); assertEquals(50, result.localLimit());
        assertEquals(11, result.contributions().get(2).reservedLocal().get("D"));
        assertEquals(80, a.remainingGas()); assertEquals(80, b.remainingGas());
        session.submit(child); session.complete();
        assertEquals(11, c.groupAdmittedGas());
    }

    private static GasMeter meter() { return new GasMeter(GasSchedule.contracts10(), 100); }
    private static RuntimeWorkSession session(GasMeter meter) {
        return new RuntimeWorkSession(meter, RuntimeWorkSession.Mode.PROCESSING);
    }
    private static GasChargeContext context(String id) {
        return GasChargeContext.closure(id, "/", 0L, 0L, null, null, null, "group test");
    }
    private static void charge(GasMeter meter, long quantity) { charge(meter, quantity, GasChargeContext.empty()); }
    private static void charge(GasMeter meter, long quantity, GasChargeContext context) {
        GasMeter.ChildGasLedger detached = meter.childLedger("unit-test", Collections.singletonMap("step", 1L));
        detached.charge("step", quantity, context);
        meter.merge(detached);
    }
}
