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
 * <p>Invocation setup builds the processor, eagerly captures the optional
 * snapshot, and establishes the requested physical cache state outside the
 * timed method. The timed lane performs one selected leaf delivery through
 * the generic Contracts kernel. Provider requests, backend trips, and bytes
 * are consumed only as host metrics; they never enter semantic gas.</p>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class DeepGraphPhysicalLocalityBenchmark {

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
}
