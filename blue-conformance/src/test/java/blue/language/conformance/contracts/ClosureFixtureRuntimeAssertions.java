package blue.language.conformance.contracts;

import java.util.Collection;

/** Test-source bridge for transport-only closure fixture assertions. */
public final class ClosureFixtureRuntimeAssertions {

    private ClosureFixtureRuntimeAssertions() {
    }

    /** Verifies that the fixture runtime physically requested exact nodes. */
    public static void verifyExactProviderLoads(
            ClosureFixtureRuntime runtime,
            Collection<String> blueIds) {
        runtime.verifyExactProviderLoads(blueIds);
    }
}
