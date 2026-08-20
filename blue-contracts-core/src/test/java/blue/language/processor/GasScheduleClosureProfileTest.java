package blue.language.processor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Verifies that production loads the complete frozen Contracts 1.0 schedule. */
final class GasScheduleClosureProfileTest {

    @Test
    void shouldLoadClosureCountersAndPortableLimits() {
        GasSchedule schedule = GasSchedule.contracts10();

        assertEquals(GasSchedule.CONTRACTS_1_0_PACKAGE_IDENTITY,
                schedule.packageIdentity());
        assertEquals(39, schedule.namespaces().get("processor").size());
        assertEquals(17, schedule.namespaces().get("semantic").size());
        assertEquals(100L,
                schedule.weight("processor", "closureInvocation"));
        assertEquals(10L,
                schedule.weight("processor", "cyclicMemberFinalized"));
        assertEquals(8192L,
                schedule.portableLimit(
                        "closureWorkOccurrencesPerInvocation"));
        assertEquals(128L,
                schedule.portableLimit("cyclicMembersPerComponent"));
    }
}
