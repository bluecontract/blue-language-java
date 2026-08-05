package blue.language.processor;

import blue.language.processor.EmbeddedCollectionBenchmarkSupport.GasObservation;
import org.openjdk.jmh.annotations.AuxCounters;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.util.List;

import static blue.language.processor.EmbeddedCollectionBenchmarkSupport.ROOT_AND_COLLECTION_CONTAINER_COUNT;
import static blue.language.processor.EmbeddedCollectionBenchmarkSupport.manifestQuantity;

/**
 * Per-invocation JMH observations that remain non-semantic.
 *
 * <p>Every record method overwrites the complete snapshot instead of adding
 * iteration totals. Consequently the JSON secondary metrics are the exact
 * deterministic values for one invocation when the benchmark's single-thread
 * contract is retained.</p>
 */
@AuxCounters(AuxCounters.Type.EVENTS)
@State(Scope.Thread)
public class EmbeddedCollectionMetrics {

    /** Direct collection members in the benchmark input. */
    public long inputCollectionMembers;
    /** Concrete child paths produced by a completed projection. */
    public long concreteChildPaths;
    /** Exact-reference lookup calls made through the observed provider. */
    public long providerDemands;
    /** Exact references physically returned by the observed provider. */
    public long exactReferencesMaterialized;
    /**
     * Conceptual scope/container nodes represented by the result; this is not
     * a JVM allocation or provider-materialization count.
     */
    public long conceptualStructuralNodes;
    /** Logical {@code nodeManifestOpened} quantity in the canonical gas trace. */
    public long logicalNodeManifestOpens;
    /** Total admitted Contracts gas for the observed logical operation. */
    public long logicalGas;
    /** Entries in the exact canonical gas trace. */
    public long gasTraceEntries;
    /** One when the invocation reaches its expected gas boundary, otherwise zero. */
    public long gasLimitRejections;
    /** One when the invocation reaches its expected portable boundary, otherwise zero. */
    public long portableLimitRejections;
    /** Subscription intervals added by the timed validation. */
    public long subscriptionAdditions;
    /** Subscription intervals removed by the timed validation. */
    public long subscriptionRemovals;
    /** Selected handlers executed by the timed processor invocation. */
    public long handlersExecuted;
    /** Scope entries in a successfully constructed fragmentation catalog. */
    public long catalogScopes;

    /** Clears the observation before each JMH measurement iteration. */
    @Setup(Level.Iteration)
    public void reset() {
        clear();
    }

    /** Records one complete, unmetered projection plus its paired gas trace. */
    void recordProjection(
            EmbeddedScopePlan plan,
            long demands,
            long materializations,
            GasObservation gas) {
        clear();
        inputCollectionMembers = plan.concreteChildPaths().size();
        concreteChildPaths = plan.concreteChildPaths().size();
        providerDemands = demands;
        exactReferencesMaterialized = materializations;
        conceptualStructuralNodes =
                plan.concreteChildPaths().size()
                        + ROOT_AND_COLLECTION_CONTAINER_COUNT;
        applyGas(gas);
    }

    /** Records one subscription validation without attributing unrelated gas. */
    void recordDelta(
            long inputMembers,
            SubscriptionDelta delta) {
        clear();
        inputCollectionMembers = inputMembers;
        subscriptionAdditions = delta.added().size();
        subscriptionRemovals = delta.removed().size();
    }

    /** Records one catalog construction or its expected portable rejection. */
    void recordCatalog(
            long inputMembers,
            long paths,
            long structuralNodes,
            long scopes,
            long demands,
            long materializations,
            boolean portableRejected) {
        clear();
        inputCollectionMembers = inputMembers;
        concreteChildPaths = paths;
        conceptualStructuralNodes = structuralNodes;
        catalogScopes = scopes;
        providerDemands = demands;
        exactReferencesMaterialized = materializations;
        portableLimitRejections = portableRejected ? 1L : 0L;
    }

    /** Records one logical-gas projection, including an admitted rejection prefix. */
    void recordLogicalGas(
            long inputMembers,
            EmbeddedScopePlan plan,
            GasObservation gas) {
        clear();
        inputCollectionMembers = inputMembers;
        if (plan != null) {
            concreteChildPaths = plan.concreteChildPaths().size();
        }
        applyGas(gas);
    }

    /** Records one exact selected-member processor result. */
    void recordSelectedProcessing(
            long inputMembers,
            boolean completed,
            long demands,
            long materializations,
            long executions,
            long totalGas,
            List<GasTraceEntry> trace,
            boolean gasRejected) {
        clear();
        inputCollectionMembers = inputMembers;
        concreteChildPaths = completed ? inputMembers : 0L;
        providerDemands = demands;
        exactReferencesMaterialized = materializations;
        handlersExecuted = executions;
        logicalGas = totalGas;
        gasTraceEntries = trace.size();
        logicalNodeManifestOpens = manifestQuantity(trace);
        gasLimitRejections = gasRejected ? 1L : 0L;
    }

    private void applyGas(GasObservation gas) {
        logicalGas = gas.totalGas;
        gasTraceEntries = gas.traceEntries;
        logicalNodeManifestOpens = gas.manifestQuantity;
        gasLimitRejections = gas.rejected ? 1L : 0L;
    }

    private void clear() {
        inputCollectionMembers = 0L;
        concreteChildPaths = 0L;
        providerDemands = 0L;
        exactReferencesMaterialized = 0L;
        conceptualStructuralNodes = 0L;
        logicalNodeManifestOpens = 0L;
        logicalGas = 0L;
        gasTraceEntries = 0L;
        gasLimitRejections = 0L;
        portableLimitRejections = 0L;
        subscriptionAdditions = 0L;
        subscriptionRemovals = 0L;
        handlersExecuted = 0L;
        catalogScopes = 0L;
    }
}
