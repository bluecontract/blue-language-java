package blue.language.conformance.api;

import blue.language.conformance.api.BlueConformanceFixturePrimitives.FixtureEntry;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards selection, complete inventory retention, and deferred execution. */
final class LanguageFixtureSelectionTest {

    @Test
    void shouldSelectExactIdsAndPrefixesInManifestOrder() {
        // given
        List<FixtureEntry> inventory = inventory("B_one", "R_one", "B_two");

        // when
        List<FixtureEntry> selected = LanguageFixtureSelection.select(
                inventory, " B_two, R_*, B_* ");

        // then
        assertEquals(Arrays.asList("B_one", "R_one", "B_two"), ids(selected));
    }

    @Test
    void shouldRejectEveryUnknownSelectorIncludingMixedSelections() {
        // given
        List<FixtureEntry> inventory = inventory("B_one", "R_one");

        // when
        List<String> selectors = Arrays.asList("missing", "Z_*", "B_one,missing");

        // then
        for (String selector : selectors) {
            assertThrows(IllegalArgumentException.class,
                    () -> LanguageFixtureSelection.select(inventory, selector));
        }
    }

    @Test
    void shouldRejectEmptyTokensAndNonterminalWildcards() {
        // given
        List<FixtureEntry> inventory = inventory("B_one");

        // when
        List<String> selectors = Arrays.asList(
                "", " ", ",", "B_one,", ",B_one", "B_one,,B_one",
                "B*one", "B**");

        // then
        for (String selector : selectors) {
            assertThrows(IllegalArgumentException.class,
                    () -> LanguageFixtureSelection.select(inventory, selector));
        }
    }

    @Test
    void shouldRejectEmptyOrDuplicateInventoriesBeforeSelection() {
        // given
        List<FixtureEntry> duplicate = inventory("B_one", "B_one");
        List<FixtureEntry> empty = Collections.emptyList();

        // when
        List<String> selectors = Arrays.asList(null, "B_one");

        // then
        for (String selector : selectors) {
            assertThrows(IllegalArgumentException.class,
                    () -> LanguageFixtureSelection.select(duplicate, selector));
            assertThrows(IllegalArgumentException.class,
                    () -> LanguageFixtureSelection.select(empty, selector));
        }
    }

    @Test
    void shouldEvaluateOnlyTheInvokedDynamicCaseWithCompleteSuiteContext()
            throws Throwable {
        // given
        List<FixtureEntry> inventory = inventory("B_one", "R_one", "B_two");
        List<String> executed = new ArrayList<>();
        List<List<FixtureEntry>> contexts = new ArrayList<>();

        // when
        List<DynamicTest> cases = LanguageFixtureSelection.dynamicTests(
                inventory, "B_*", (entry, complete) -> {
                    executed.add(entry.id);
                    contexts.add(complete);
                }).collect(Collectors.toList());
        List<String> beforeExecution = new ArrayList<>(executed);
        cases.get(1).getExecutable().execute();

        // then
        assertTrue(beforeExecution.isEmpty());
        assertEquals(Arrays.asList("B_one", "B_two"), cases.stream()
                .map(DynamicTest::getDisplayName).collect(Collectors.toList()));
        assertEquals(Collections.singletonList("B_two"), executed);
        assertEquals(inventory, contexts.get(0));
    }

    @Test
    void shouldPropagateTheOriginalFixtureFailure() {
        // given
        AssertionError expected = new AssertionError("fixture failed");
        List<FixtureEntry> inventory = inventory("B_one", "R_one");

        // when
        DynamicTest selected = LanguageFixtureSelection.dynamicTests(
                inventory, "B_one", (entry, complete) -> {
                    throw expected;
                }).findFirst().get();

        // then
        assertSame(expected, assertThrows(AssertionError.class,
                () -> selected.getExecutable().execute()));
    }

    @Test
    void shouldPreserveEveryReleasedFixtureWithoutExecutingTheCorpus() {
        // given
        List<FixtureEntry> inventory = LanguageFixtureSelection.verifiedInventory();
        List<String> executed = new ArrayList<>();

        // when
        List<DynamicTest> cases = LanguageFixtureSelection.dynamicTests(
                inventory, null, (entry, complete) -> executed.add(entry.id))
                .collect(Collectors.toList());
        List<String> completeIds = ids(LanguageFixtureSelection.select(inventory, "*"));

        // then
        assertEquals(185, cases.size());
        assertEquals(BlueConformanceReport.loadFixtureIds(), completeIds);
        assertEquals(completeIds, cases.stream().map(DynamicTest::getDisplayName)
                .collect(Collectors.toList()));
        assertTrue(executed.isEmpty());
    }

    @Test
    void shouldExposeTheSuiteAssertionsDeclaredPrerequisiteInventory() {
        // given
        List<FixtureEntry> inventory = LanguageFixtureSelection.verifiedInventory();
        FixtureEntry suite = LanguageFixtureSelection.select(
                inventory, "F_all_language_vectors_pass").get(0);

        // when
        List<String> prerequisites = LanguageFixtureSelection.prerequisiteIds(
                suite, inventory);

        // then
        assertEquals(ids(LanguageFixtureSelection.select(inventory, "B_*,R_*")),
                prerequisites);
        assertTrue(prerequisites.size() > 1);
    }

    private static List<FixtureEntry> inventory(String... ids) {
        return Arrays.stream(ids).map(id -> new FixtureEntry(
                id, BlueFixtureCategory.BLUE_ID, id + ".yaml"))
                .collect(Collectors.toList());
    }

    private static List<String> ids(List<FixtureEntry> entries) {
        return entries.stream().map(entry -> entry.id).collect(Collectors.toList());
    }
}
