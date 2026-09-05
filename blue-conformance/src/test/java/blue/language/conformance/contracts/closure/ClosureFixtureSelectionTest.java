package blue.language.conformance.contracts.closure;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ClosureFixtureSelectionTest {
    @Test void preservesCompleteInventoryAndRejectsInvalidSelection() {
        ClosureFixtureCorpusSource source = ClosureFixtureCorpusSource.open();
        assertEquals(98, ClosureFixtureSelection.select(source, null).size());
        String first = source.entries().get(0).id();
        assertEquals(1, ClosureFixtureSelection.select(source, first).size());
        assertThrows(IllegalArgumentException.class, () -> ClosureFixtureSelection.select(source, ""));
        assertThrows(IllegalArgumentException.class, () -> ClosureFixtureSelection.select(source, "unknown"));
    }
}
