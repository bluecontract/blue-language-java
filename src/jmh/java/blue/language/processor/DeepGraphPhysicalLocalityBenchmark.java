package blue.language.processor;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * End-to-end selected-closure benchmark for the same seven-scope physical
 * graph used by {@link DeepGraphPhysicalLocalityIntegrationTest}.
 *
 * <p>The PROCESS-only states prepare a fresh single-use invocation outside the
 * timed method. The setup-inclusive platform lane performs that construction,
 * PROCESS, and close inside one measured operation. Provider requests, backend
 * trips, and bytes are consumed only as host metrics; they never enter semantic
 * gas.</p>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class DeepGraphPhysicalLocalityBenchmark {

    /**
     * Measures the public platform-commit boundary against the production-
     * shaped Root, Event, indexed plan, and invocation-local provider fixture.
     * Locality counters are consumed as host observations and do not affect
     * the returned semantic result.
     */
    @Benchmark
    public PlatformProcessingResult processPlatformCommit(
            PlatformLocalityState state,
            Blackhole blackhole) {
        return processPlatformCommit(
                state.invocation, blackhole);
    }

    /**
     * Measures construction of the complete single-use platform invocation and
     * its PROCESS call in one timed operation. Unlike
     * {@link #processPlatformCommit(PlatformLocalityState, Blackhole)}, this
     * lane makes per-call setup, warming, and close allocation visible to JMH's
     * allocation profilers.
     */
    @Benchmark
    public PlatformProcessingResult processPlatformCommitIncludingSetup(
            PlatformSetupInclusiveState state,
            Blackhole blackhole) {
        try (DeepGraphPhysicalLocalityIntegrationTest
                     .PlatformBenchmarkInvocation invocation =
                     DeepGraphPhysicalLocalityIntegrationTest
                             .preparePlatformBenchmark(
                                     state.representation,
                                     state.cacheMode,
                                     state.batchMode)) {
            return processPlatformCommit(
                    invocation, blackhole);
        }
    }

    private static PlatformProcessingResult processPlatformCommit(
            DeepGraphPhysicalLocalityIntegrationTest
                    .PlatformBenchmarkInvocation invocation,
            Blackhole blackhole) {
        PlatformProcessingResult result = invocation.process();
        blackhole.consume(
                invocation.providerRequestCount());
        blackhole.consume(
                invocation.providerBackendTrips());
        blackhole.consume(
                invocation.providerBackendBytes());
        blackhole.consume(
                invocation.unrelatedProviderRequestCount());
        blackhole.consume(
                invocation.selectedBodyDemandCount());
        blackhole.consume(
                invocation.unselectedBodyDemandCount());
        return result;
    }

    @Benchmark
    public ProcessingDebugResult processSelectedLeaf(
            LocalityState state,
            Blackhole blackhole) {
        ProcessingDebugResult result =
                state.invocation.process();
        blackhole.consume(
                state.invocation.providerRequestCount());
        blackhole.consume(
                state.invocation.providerBackendTrips());
        blackhole.consume(
                state.invocation.providerBackendBytes());
        return result;
    }

    @State(Scope.Thread)
    public static class LocalityState {

        @Param({"INLINE", "REFERENCE"})
        public String bodyForm;

        @Param({"EAGER_SNAPSHOT", "LAZY_NODE"})
        public String entryMode;

        @Param({"COLD", "WARM"})
        public String cacheMode;

        @Param({"UNBATCHED", "BOUNDED_BATCH"})
        public String batchMode;

        private DeepGraphPhysicalLocalityIntegrationTest
                .BenchmarkInvocation invocation;

        @Setup(Level.Invocation)
        public void prepareInvocation() {
            invocation =
                    DeepGraphPhysicalLocalityIntegrationTest
                            .prepareBenchmark(
                                    bodyForm,
                                    entryMode,
                                    cacheMode,
                                    batchMode);
        }

        @TearDown(Level.Invocation)
        public void closeInvocation() {
            if (invocation != null) {
                invocation.close();
                invocation = null;
            }
        }
    }

    /** Invocation-scoped state for the public platform PROCESS matrix. */
    @State(Scope.Thread)
    public static class PlatformLocalityState {

        @Param({
                "INLINE",
                "PURE_REFERENCE",
                "PARTIAL",
                "FRAGMENTED"
        })
        public String representation;

        @Param({"COLD", "WARM"})
        public String cacheMode;

        @Param({"UNBATCHED", "BOUNDED_BATCH"})
        public String batchMode;

        private DeepGraphPhysicalLocalityIntegrationTest
                .PlatformBenchmarkInvocation invocation;

        @Setup(Level.Invocation)
        public void prepareInvocation() {
            invocation =
                    DeepGraphPhysicalLocalityIntegrationTest
                            .preparePlatformBenchmark(
                                    representation,
                                    cacheMode,
                                    batchMode);
        }

        @TearDown(Level.Invocation)
        public void closeInvocation() {
            if (invocation != null) {
                invocation.close();
                invocation = null;
            }
        }
    }

    /** Parameter-only state for setup-inclusive platform measurements. */
    @State(Scope.Thread)
    public static class PlatformSetupInclusiveState {

        @Param({
                "INLINE",
                "PURE_REFERENCE",
                "PARTIAL",
                "FRAGMENTED"
        })
        public String representation;

        @Param({"COLD", "WARM"})
        public String cacheMode;

        @Param({"UNBATCHED", "BOUNDED_BATCH"})
        public String batchMode;
    }
}
