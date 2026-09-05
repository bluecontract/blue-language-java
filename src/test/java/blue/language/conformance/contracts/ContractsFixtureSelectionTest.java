package blue.language.conformance.contracts;

import blue.language.testing.CaseSelection;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

class ContractsFixtureSelectionTest {
    @Test void preservesFullInventoryAndSelectsLeavesAndFamiliesBeforeExecution() throws Throwable {
        List<blue.language.conformance.api.BlueContractsConformanceReport.FixtureInventoryEntry> all = ContractsFixtureSelection.inventory();
        assertEquals(295, ContractsFixtureSelection.select(null).size());
        String first = all.get(0).id();
        String second = all.get(1).id();
        assertEquals(1, ContractsFixtureSelection.select(first).size());
        assertEquals(2, ContractsFixtureSelection.select(second + "," + first).size());
        assertEquals(295, ContractsFixtureSelection.select("*").size());
        List<String> executed = new ArrayList<>();
        List<DynamicTest> scheduled = ContractsFixtureSelection.schedule(ContractsFixtureSelection.select(first),
                entry -> executed.add(entry.id())).collect(Collectors.toList());
        assertTrue(executed.isEmpty());
        scheduled.get(0).getExecutable().execute();
        assertEquals(Collections.singletonList(first), executed);
    }

    @Test void rejectsUnknownEmptyMalformedAndEmptyInventory() {
        for (String selector : Arrays.asList("", " ", "unknown", "*,", "c-**", "c-*,unknown")) {
            assertThrows(IllegalArgumentException.class, () -> ContractsFixtureSelection.select(selector));
        }
        assertThrows(IllegalArgumentException.class,
                () -> CaseSelection.select(Collections.<String>emptyList(), null, value -> value));
        assertThrows(IllegalArgumentException.class,
                () -> CaseSelection.select(Arrays.asList("a", "a"), null, value -> value));
    }

    @Test void preservesAssertionFailureAtTheSelectedExecutionBoundary() {
        DynamicTest test = ContractsFixtureSelection.schedule(ContractsFixtureSelection.select(null).subList(0, 1),
                entry -> { throw new AssertionError("controlled selected failure"); }).findFirst().get();
        assertThrows(AssertionError.class, () -> test.getExecutable().execute());
    }
}
