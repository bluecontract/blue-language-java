package blue.language.processor;

import blue.language.model.wire.JsonPointer;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.infra.Blackhole;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static blue.language.processor.EmbeddedCollectionBenchmarkSupport.EXPECTED_SINGLE_PROVIDER_DEMAND;
import static blue.language.processor.EmbeddedCollectionBenchmarkSupport.EXPECTED_SINGLE_SUBSCRIPTION_CHANGE;
import static blue.language.processor.EmbeddedCollectionBenchmarkSupport.ROOT_SCOPE_COUNT;
import static blue.language.processor.EmbeddedCollectionBenchmarkSupport.collectionPath;
import static blue.language.processor.EmbeddedCollectionBenchmarkSupport.requireEmptyDelta;
import static blue.language.processor.EmbeddedCollectionBenchmarkSupport.requireExpectedCatalogOutcome;
import static blue.language.processor.EmbeddedCollectionBenchmarkSupport.requireExpectedGasObservation;
import static blue.language.processor.EmbeddedCollectionBenchmarkSupport.requireExpectedSelectedProcessingOutcome;
import static blue.language.processor.EmbeddedCollectionBenchmarkSupport.requireProjectedMembers;
import static blue.language.processor.EmbeddedCollectionBenchmarkSupport.requireProviderCounts;

/**
 * Collection-path projection, locality, catalog, and subscription-delta
 * benchmarks at the Contracts 1.0 portable collection sizes.
 *
 * <p>The projection lanes deliberately enumerate the complete direct key set.
 * The selected-processing lane uses the real processor and a provider-backed
 * executable body for every member, then rejects any unselected body demand.
 * Provider traffic and allocation are observational only: JMH reports
 * allocation through its GC profiler, while
 * {@link EmbeddedCollectionMetrics} exposes deterministic logical counters
 * alongside latency.</p>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Threads(1)
public class EmbeddedCollectionPathsBenchmark {

    /** Measures complete inline collection projection. */
    @Benchmark
    public EmbeddedScopePlan initialCollectionProjection(
            EmbeddedCollectionProjectionState state,
            EmbeddedCollectionMetrics metrics) {
        EmbeddedScopePlan plan = state.inlinePlanner().plan(
                state.inlineScope,
                JsonPointer.ROOT,
                Collections.<String>emptyList(),
                Collections.singletonList(collectionPath()),
                GasSchedule.contracts10());
        requireProjectedMembers(plan, state.size);
        metrics.recordProjection(
                plan,
                0L,
                0L,
                state.inlineGas);
        return plan;
    }

    /** Measures the validated transition from {@code size - 1} to {@code size}. */
    @Benchmark
    public SubscriptionDelta incrementalMemberAddition(
            EmbeddedCollectionProjectionState state,
            EmbeddedCollectionMetrics metrics) {
        SubscriptionDelta delta = DirectSubscriptionSurfaceValidator.INSTANCE
                .validate(state.additionContext);
        requireEmptyDelta(delta, "member addition without channels");
        metrics.recordDelta(
                state.size,
                delta);
        return delta;
    }

    /** Measures the validated transition from {@code size} to {@code size - 1}. */
    @Benchmark
    public SubscriptionDelta incrementalMemberRemoval(
            EmbeddedCollectionProjectionState state,
            EmbeddedCollectionMetrics metrics) {
        SubscriptionDelta delta = DirectSubscriptionSurfaceValidator.INSTANCE
                .validate(state.removalContext);
        requireEmptyDelta(delta, "member removal without channels");
        metrics.recordDelta(
                state.size,
                delta);
        return delta;
    }

    /** Measures projection through one exact pure-reference collection target. */
    @Benchmark
    public EmbeddedScopePlan pureReferenceCollectionTarget(
            EmbeddedCollectionProjectionState state,
            EmbeddedCollectionMetrics metrics) {
        state.pureTargetProvider.reset();
        EmbeddedScopePlan plan = state.pureTargetPlanner().plan(
                state.pureTargetScope,
                JsonPointer.ROOT,
                Collections.<String>emptyList(),
                Collections.singletonList(collectionPath()),
                GasSchedule.contracts10());
        requireProjectedMembers(plan, state.size);
        requireProviderCounts(
                state.pureTargetProvider,
                EXPECTED_SINGLE_PROVIDER_DEMAND,
                "pure-reference collection target");
        metrics.recordProjection(
                plan,
                state.pureTargetProvider.demands(),
                state.pureTargetProvider.materializations(),
                state.pureTargetGas);
        return plan;
    }

    /** Measures projection through exact pure-reference member headers. */
    @Benchmark
    public EmbeddedScopePlan pureReferenceMemberHeaders(
            EmbeddedCollectionProjectionState state,
            EmbeddedCollectionMetrics metrics) {
        state.pureMemberProvider.reset();
        EmbeddedScopePlan plan = state.pureMemberPlanner().plan(
                state.pureMemberScope,
                JsonPointer.ROOT,
                Collections.<String>emptyList(),
                Collections.singletonList(collectionPath()),
                GasSchedule.contracts10());
        requireProjectedMembers(plan, state.size);
        requireProviderCounts(
                state.pureMemberProvider,
                state.size,
                "pure-reference member headers");
        metrics.recordProjection(
                plan,
                state.pureMemberProvider.demands(),
                state.pureMemberProvider.materializations(),
                state.pureMemberGas);
        return plan;
    }

    /** Isolates immutable fragmentation-catalog value construction. */
    @Benchmark
    public EffectiveFragmentationCatalog immutableCatalogValueConstruction(
            EmbeddedCollectionProjectionState state,
            EmbeddedCollectionMetrics metrics) {
        EffectiveFragmentationCatalog catalog =
                new EffectiveFragmentationCatalog(
                        state.rootBlueId,
                        state.catalogPaths,
                        state.catalogContracts);
        long scopes = (long) state.size + ROOT_SCOPE_COUNT;
        metrics.recordCatalog(
                state.size,
                state.size,
                scopes,
                scopes,
                0L,
                0L,
                false);
        return catalog;
    }

    /**
     * Measures production fragmentation inspection and proves that it does
     * not open provider-backed executable bodies.
     */
    @Benchmark
    public EffectiveFragmentationCatalog fragmentationCatalogConstruction(
            EmbeddedCollectionSelectedProcessingState state,
            EmbeddedCollectionMetrics metrics) {
        EffectiveFragmentationCatalog catalog = null;
        PortableLimitExceededException rejection = null;
        try {
            catalog = state.processor.administration()
                    .effectiveFragmentationCatalog(state.root);
        } catch (PortableLimitExceededException expected) {
            rejection = expected;
        }
        requireExpectedCatalogOutcome(state.size, catalog, rejection);
        requireProviderCounts(
                state.provider,
                0L,
                "fragmentation catalog");
        long scopes = catalog != null
                ? catalog.effectiveContractsByScope().size()
                : 0L;
        metrics.recordCatalog(
                state.size,
                catalog != null ? state.size : 0L,
                scopes,
                scopes,
                state.provider.demands(),
                state.provider.materializations(),
                rejection != null);
        return catalog;
    }

    /** Measures final validation of one real collection-member subscription. */
    @Benchmark
    public SubscriptionDelta finalSubscriptionDeltaValidation(
            EmbeddedCollectionProjectionState state,
            EmbeddedCollectionMetrics metrics) {
        SubscriptionDelta delta = DirectSubscriptionSurfaceValidator.INSTANCE
                .validate(state.finalDeltaContext);
        if (delta.added().size()
                != EXPECTED_SINGLE_SUBSCRIPTION_CHANGE
                || !delta.removed().isEmpty()) {
            throw new IllegalStateException(
                    "Final collection delta did not contain exactly one addition");
        }
        metrics.recordDelta(
                state.size,
                delta);
        return delta;
    }

    /** Records canonical logical gas separately from unmetered projection. */
    @Benchmark
    public void logicalGasTrace(
            EmbeddedCollectionProjectionState state,
            EmbeddedCollectionMetrics metrics,
            Blackhole blackhole) {
        GasMeter meter = new GasMeter(GasSchedule.contracts10());
        EmbeddedScopePlan plan = null;
        GasLimitExceededException rejection = null;
        try {
            plan = state.inlinePlanner().plan(
                    state.inlineScope,
                    JsonPointer.ROOT,
                    Collections.<String>emptyList(),
                    Collections.singletonList(collectionPath()),
                    meter);
        } catch (GasLimitExceededException expected) {
            rejection = expected;
        }
        List<GasTraceEntry> trace = meter.trace();
        EmbeddedCollectionBenchmarkSupport.GasObservation observation =
                new EmbeddedCollectionBenchmarkSupport.GasObservation(
                        meter.totalGas(),
                        trace,
                        rejection);
        requireExpectedGasObservation(state.size, observation);
        metrics.recordLogicalGas(state.size, plan, observation);
        blackhole.consume(plan);
        blackhole.consume(trace);
    }

    /**
     * Measures one real selected member delivery and rejects every unselected
     * executable-body demand.
     */
    @Benchmark
    public ProcessingDebugResult selectedMemberProcessing(
            EmbeddedCollectionSelectedProcessingState state,
            EmbeddedCollectionMetrics metrics) {
        ProcessingDebugResult debug =
                state.processor.processDocumentWithTrace(
                        state.root,
                        state.event);
        long unselected = state.provider.unselectedBodyDemands(
                state.selectedBodyBlueId);
        if (unselected != 0L) {
            throw new IllegalStateException(
                    "Selected processing demanded " + unselected
                            + " unselected executable bodies");
        }
        DocumentProcessingResult result = debug.processResult();
        boolean gasRejected = requireExpectedSelectedProcessingOutcome(
                state.size,
                result,
                state.executions,
                state.provider);
        metrics.recordSelectedProcessing(
                state.size,
                !gasRejected,
                state.provider.demands(),
                state.provider.materializations(),
                state.executions,
                result.totalGas(),
                debug.trace().gas(),
                gasRejected);
        return debug;
    }
}
