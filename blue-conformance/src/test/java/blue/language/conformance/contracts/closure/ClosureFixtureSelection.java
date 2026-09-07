package blue.language.conformance.contracts.closure;

import blue.language.testing.CaseSelection;
import java.util.List;

/** Selection only; the independent public-facade corpus remains the executor. */
public final class ClosureFixtureSelection {
    private ClosureFixtureSelection() {}

    static List<ClosureFixtureInventory.Entry> select(ClosureFixtureCorpusSource source, String selector) {
        if (source.entries().size() != 98 || source.entries().stream()
                .filter(entry -> "limit-micro".equals(entry.operation())).count() != 18) {
            throw new AssertionError("Complete closure inventory must retain 98 cases including 18 limit micros");
        }
        return CaseSelection.select(source.entries(), selector, ClosureFixtureInventory.Entry::id);
    }

    public static void main(String[] args) {
        if (args.length > 1) throw new IllegalArgumentException("Expected at most one selector");
        System.out.println("LIST ONLY; each fixture includes its declared invocation/lifecycle queue; no other fixtures execute");
        for (ClosureFixtureInventory.Entry entry : select(ClosureFixtureCorpusSource.open(), args.length == 0 ? null : args[0])) {
            System.out.println("SELECTED " + entry.id() + " operation=" + entry.operation());
        }
    }
}
