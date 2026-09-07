package blue.language.conformance.contracts;

import blue.language.conformance.api.BlueContractsConformanceReport;
import blue.language.conformance.api.BlueContractsConformanceReport.FixtureInventoryEntry;
import blue.language.testing.CaseSelection;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DynamicTest;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

/** Test-only selection over the existing closed Contracts executor (including lifecycle/gas). */
public final class ContractsFixtureSelection {
    public static final String PROPERTY = "blue.contracts.fixture.cases";
    private ContractsFixtureSelection() {}

    static List<FixtureInventoryEntry> inventory() {
        BlueContractsConformanceReport.validateFixturePackageIntegrity();
        BlueContractsConformanceReport.validateReleaseBindings();
        List<FixtureInventoryEntry> entries = BlueContractsConformanceReport.loadFixtureInventory();
        if (entries.size() != 295) throw new AssertionError("Expected complete 295-case inventory");
        return entries;
    }

    static List<FixtureInventoryEntry> select(String selector) {
        return CaseSelection.select(inventory(), selector, FixtureInventoryEntry::id);
    }

    static Stream<DynamicTest> dynamicTests(String selector) {
        List<FixtureInventoryEntry> all = inventory();
        List<FixtureInventoryEntry> selected = CaseSelection.select(all, selector, FixtureInventoryEntry::id);
        // Coverage is a metadata prerequisite, not a claim that all microfixtures executed.
        List<JsonNode> ordinary = new ArrayList<>();
        ContractsFixtureHarness harness = new ContractsFixtureHarness();
        for (FixtureInventoryEntry entry : all) {
            JsonNode fixture = read(entry);
            if (!"closure-fixture".equals(entry.role())) {
                harness.validate(fixture);
                ordinary.add(fixture);
            }
        }
        boolean coverage = new ContractsGasSchedule().hasCompleteMicrofixtureCoverage(ordinary);
        if (!coverage) throw new AssertionError("Incomplete gas counter metadata coverage");
        print(selected);
        return schedule(selected, entry -> {
            JsonNode fixture = read(entry);
            if ("closure-fixture".equals(entry.role())) {
                ContractsConformanceSuite.executeClosureFixture(entry, fixture);
            } else {
                ContractsConformanceProjection projection = harness.execute(fixture, coverage);
                for (String counter : new String[] {"result.status", "result.gasUsed", "trace.handlerExecutionCount", "trace.eventOccurrencesDequeued"}) {
                    ContractsConformanceProjection.Presence value = projection.project(counter);
                    if (value.isPresent()) System.out.println("COUNTER " + entry.id() + " " + counter + "=" + value.getValue());
                }
            }
        });
    }

    static Stream<DynamicTest> schedule(List<FixtureInventoryEntry> selected, Consumer<FixtureInventoryEntry> execute) {
        if (selected.isEmpty()) throw new IllegalArgumentException("Empty scheduled inventory");
        return selected.stream().map(entry -> DynamicTest.dynamicTest(entry.id(), () -> {
            try {
                execute.accept(entry);
                System.out.println("EXECUTED " + entry.id() + " PASS (includes declared variants)");
            } catch (RuntimeException | AssertionError failure) {
                System.out.println("EXECUTED " + entry.id() + " FAIL");
                throw failure;
            }
        }));
    }

    private static JsonNode read(FixtureInventoryEntry entry) {
        JsonNode fixture = BlueContractsConformanceReport.readFixture(entry.path());
        ContractsConformanceSuite.requireInventoryMatch(entry, fixture);
        return fixture;
    }

    private static void print(List<FixtureInventoryEntry> entries) {
        System.out.println("METADATA PREREQUISITES: complete 295-entry package integrity and ordinary gas counter coverage; these checks do not execute other cases");
        for (FixtureInventoryEntry entry : entries) {
            System.out.println("SELECTED " + entry.id() + " operation=" + entry.operation() + " vectors=" + entry.vectors()
                    + " variants=" + read(entry).path("input").path("variants").size());
        }
    }

    public static void main(String[] args) {
        if (args.length > 1) throw new IllegalArgumentException("Expected at most one selector");
        System.out.println("LIST ONLY; no semantic execution");
        print(select(args.length == 0 ? null : args[0]));
    }
}
