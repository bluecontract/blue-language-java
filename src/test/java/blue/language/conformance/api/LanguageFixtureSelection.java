package blue.language.conformance.api;

import blue.language.conformance.api.BlueConformanceFixturePrimitives.FixtureEntry;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DynamicTest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

/** Selects development cases before invoking the unchanged fixture executor. */
public final class LanguageFixtureSelection {

    static final String CASES_PROPERTY = "blue.fixture.cases";

    private LanguageFixtureSelection() {
    }

    /** Lists verified case IDs without evaluating their semantic assertions. */
    public static void main(String[] args) {
        if (args.length > 1) {
            throw new IllegalArgumentException("Expected at most one case selector");
        }
        String selector = args.length == 0
                ? System.getProperty(CASES_PROPERTY) : args[0];
        List<FixtureEntry> inventory = verifiedInventory();
        List<FixtureEntry> selected = select(inventory, selector);
        System.out.println("LIST ONLY: " + selected.size()
                + " selected Language cases of " + inventory.size());
        for (FixtureEntry entry : selected) {
            System.out.println(entry.id);
            printPrerequisites(entry, inventory);
        }
    }

    static Stream<DynamicTest> dynamicTests(String selector) {
        List<FixtureEntry> inventory = verifiedInventory();
        List<FixtureEntry> selected = select(inventory, selector);
        System.out.println("SELECTED LANGUAGE CASES: " + selected.size()
                + " of " + inventory.size());
        for (FixtureEntry entry : selected) {
            System.out.println("SELECTED " + entry.id);
            printPrerequisites(entry, inventory);
        }
        return dynamicTests(inventory, selector, (entry, allFixtures) -> {
            try {
                BlueConformanceFixtureExecution.runFixture(entry, allFixtures);
                System.out.println("EXECUTED " + entry.id + " PASS");
            } catch (RuntimeException | AssertionError failure) {
                System.out.println("EXECUTED " + entry.id + " FAIL"
                        + " (suite prerequisites may be incomplete)");
                throw failure;
            }
        });
    }

    static Stream<DynamicTest> dynamicTests(
            List<FixtureEntry> inventory,
            String selector,
            BiConsumer<FixtureEntry, List<FixtureEntry>> executor) {
        List<FixtureEntry> completeInventory = Collections.unmodifiableList(
                new ArrayList<>(inventory));
        List<FixtureEntry> selected = select(completeInventory, selector);
        return selected.stream().map(entry -> DynamicTest.dynamicTest(
                entry.id, () -> executor.accept(entry, completeInventory)));
    }

    static List<FixtureEntry> verifiedInventory() {
        // Preserve the complete metadata and package validation performed by
        // the full runner before any selection or semantic evaluation.
        BlueConformanceSuiteRunner.unexecutedReport();
        return BlueConformanceFixtureExecution.fixtureEntries();
    }

    static List<FixtureEntry> select(
            List<FixtureEntry> inventory, String selector) {
        if (inventory.isEmpty()) {
            throw new IllegalArgumentException("Language fixture inventory is empty");
        }
        Set<String> inventoryIds = new LinkedHashSet<>();
        for (FixtureEntry entry : inventory) {
            if (entry.id == null || entry.id.isEmpty()
                    || !inventoryIds.add(entry.id)) {
                throw new IllegalArgumentException(
                        "Invalid or duplicate Language fixture ID: " + entry.id);
            }
        }
        if (selector == null) {
            return Collections.unmodifiableList(new ArrayList<>(inventory));
        }
        Set<String> selectedIds = new LinkedHashSet<>();
        for (String rawToken : selector.split(",", -1)) {
            String token = rawToken.trim();
            if (token.isEmpty()) {
                throw new IllegalArgumentException("Language case selector is empty");
            }
            int wildcard = token.indexOf('*');
            if (wildcard >= 0 && wildcard != token.length() - 1) {
                throw new IllegalArgumentException(
                        "Only a terminal prefix wildcard is supported: " + token);
            }
            String prefix = wildcard < 0 ? null
                    : token.substring(0, token.length() - 1);
            boolean matched = false;
            for (String id : inventoryIds) {
                if (prefix == null ? id.equals(token) : id.startsWith(prefix)) {
                    selectedIds.add(id);
                    matched = true;
                }
            }
            if (!matched) {
                throw new IllegalArgumentException(
                        "Language case selector matched zero cases: " + token);
            }
        }
        List<FixtureEntry> selected = new ArrayList<>();
        for (FixtureEntry entry : inventory) {
            if (selectedIds.contains(entry.id)) {
                selected.add(entry);
            }
        }
        return Collections.unmodifiableList(selected);
    }

    static List<String> prerequisiteIds(
            FixtureEntry entry, List<FixtureEntry> inventory) {
        JsonNode fixture = BlueConformanceFixtureExecution.readYamlResource(
                BlueConformanceFixtureExecution.FIXTURE_ROOT + entry.path);
        if (!"suiteAssertion".equals(fixture.path("operation").asText())) {
            return Collections.emptyList();
        }
        List<String> prefixes = BlueConformanceFixtureExecution.textValues(
                BlueConformanceFixtureExecution.requirePresent(
                        fixture, "requiresVectorPrefixes"));
        List<String> prerequisites = new ArrayList<>();
        for (FixtureEntry candidate : inventory) {
            for (String prefix : prefixes) {
                if (candidate.id.startsWith(prefix + "_")) {
                    prerequisites.add(candidate.id);
                    break;
                }
            }
        }
        return Collections.unmodifiableList(prerequisites);
    }

    private static void printPrerequisites(
            FixtureEntry entry, List<FixtureEntry> inventory) {
        List<String> prerequisites = prerequisiteIds(entry, inventory);
        if (!prerequisites.isEmpty()) {
            System.out.println("SUITE PREREQUISITES " + entry.id + ": "
                    + String.join(",", prerequisites)
                    + " (executed by this case's unchanged assertions)");
        }
    }
}
