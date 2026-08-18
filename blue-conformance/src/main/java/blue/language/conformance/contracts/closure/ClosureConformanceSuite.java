package blue.language.conformance.contracts.closure;

import blue.language.processor.closure.ClosureProcessor;

import java.util.List;

/** Initial suite boundary parallel to the ordinary Contracts suite. */
final class ClosureConformanceSuite {

    private ClosureConformanceSuite() {
    }

    static InventoryReport inventory() {
        return new InventoryReport(ClosureFixtureInventory.load());
    }

    static ClosureConformanceHarness.Result runCclo34(
            ClosureProcessor processor,
            ClosureConformanceHarness.TraceAdapter traceAdapter) {
        return new ClosureConformanceHarness().runCclo34(
                processor, traceAdapter);
    }

    static final class InventoryReport {
        private final List<ClosureFixtureInventory.Entry> fixtures;

        InventoryReport(List<ClosureFixtureInventory.Entry> fixtures) {
            this.fixtures = fixtures;
        }

        String fixturePackageIdentity() {
            return ClosureFixtureInventory.PACKAGE_IDENTITY;
        }

        List<ClosureFixtureInventory.Entry> fixtures() {
            return fixtures;
        }

        boolean closureInventoryExact() {
            return fixtures.size()
                    == ClosureFixtureInventory.CLOSURE_FIXTURE_COUNT;
        }

        boolean implementationConformanceClaimed() {
            return false;
        }
    }
}
