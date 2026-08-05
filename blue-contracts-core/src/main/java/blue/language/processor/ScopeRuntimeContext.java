package blue.language.processor;

import blue.language.model.Node;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Mutable invocation state for one participating scope.
 *
 * <p>Triggered work and bridgeable emissions preserve FIFO order. Termination
 * and cut-off are monotonic, while portable queue and depth limits reject
 * before admitting the item that would exceed them.</p>
 */
public final class ScopeRuntimeContext {

    private final String scopePath;
    private final Deque<Node> triggeredQueue = new ArrayDeque<>();
    private final List<Node> bridgeableEvents = new ArrayList<>();
    private final List<String> processedEmbeddedPaths = new ArrayList<>();
    private boolean entryEmbeddedScopePlanFrozen;
    private EmbeddedScopePlan entryEmbeddedScopePlan;
    private ScopeRuntimeContext parentOccurrence;
    private TerminationState terminationState = TerminationState.ACTIVE;
    private String terminationReason;
    private boolean cutOff;
    private int triggeredLimit = -1;
    private int bridgeableLimit = -1;
    private int embeddedDepth;
    private boolean embeddedDepthSet;

    /**
     * Creates invocation-local state for one absolute scope.
     *
     * @param scopePath canonical absolute scope path
     */
    public ScopeRuntimeContext(String scopePath) {
        this.scopePath = Objects.requireNonNull(scopePath, "scopePath");
    }

    /**
     * Returns the canonical absolute path of this participating scope.
     *
     * @return immutable canonical absolute scope path
     */
    public String scopePath() {
        return scopePath;
    }

    /**
     * Returns the live FIFO trigger queue owned by this invocation.
     *
     * @return mutable processor-owned queue
     */
    public Deque<Node> triggeredQueue() {
        return triggeredQueue;
    }

    /**
     * Appends one trigger unless the cut-off admission prefix is full.
     *
     * @param node non-null event
     */
    public void enqueueTriggered(Node node) {
        if (cutOff && triggeredLimit >= 0 && triggeredQueue.size() >= triggeredLimit) {
            return;
        }
        triggeredQueue.addLast(Objects.requireNonNull(node, "node"));
    }

    /**
     * Records one bridgeable event in encounter order.
     *
     * @param node non-null event
     */
    public void recordBridgeable(Node node) {
        if (cutOff && bridgeableLimit >= 0 && bridgeableEvents.size() >= bridgeableLimit) {
            return;
        }
        bridgeableEvents.add(Objects.requireNonNull(node, "node"));
    }

    /**
     * Removes and returns the admitted bridgeable-event prefix.
     *
     * @return mutable drained event list
     */
    public List<Node> drainBridgeableEvents() {
        List<Node> drained;
        if (cutOff && bridgeableLimit >= 0 && bridgeableLimit < bridgeableEvents.size()) {
            drained = new ArrayList<>(bridgeableEvents.subList(0, bridgeableLimit));
        } else {
            drained = new ArrayList<>(bridgeableEvents);
        }
        bridgeableEvents.clear();
        return drained;
    }

    /**
     * Clears the invocation-local history of processed embedded paths.
     */
    public void clearProcessedEmbeddedPaths() {
        processedEmbeddedPaths.clear();
    }

    /**
     * Records one processed embedded path in encounter order.
     *
     * @param path non-null processed embedded path
     */
    public void recordProcessedEmbeddedPath(String path) {
        processedEmbeddedPaths.add(Objects.requireNonNull(path, "path"));
    }

    /**
     * Returns the processed embedded paths in encounter order.
     *
     * @return defensive ordered copy of processed embedded paths
     */
    public List<String> processedEmbeddedPaths() {
        return new ArrayList<>(processedEmbeddedPaths);
    }

    /** Reports whether embedded membership has been frozen for this event. */
    boolean hasEntryEmbeddedScopePlan() {
        return entryEmbeddedScopePlanFrozen;
    }

    /** Returns the frozen entry plan, or {@code null} when no marker exists. */
    EmbeddedScopePlan entryEmbeddedScopePlan() {
        if (!entryEmbeddedScopePlanFrozen) {
            throw new IllegalStateException(
                    "Embedded scope entry plan has not been frozen at "
                            + scopePath);
        }
        return entryEmbeddedScopePlan;
    }

    /** Publishes embedded membership exactly once after successful planning. */
    void freezeEntryEmbeddedScopePlan(EmbeddedScopePlan plan) {
        if (entryEmbeddedScopePlanFrozen) {
            if (!Objects.equals(entryEmbeddedScopePlan, plan)) {
                throw new IllegalStateException(
                        "Embedded scope entry plan changed at " + scopePath);
            }
            return;
        }
        entryEmbeddedScopePlan = plan;
        entryEmbeddedScopePlanFrozen = true;
    }

    void attachToParentOccurrence(ScopeRuntimeContext parent) {
        Objects.requireNonNull(parent, "parent");
        if (parent == this) {
            throw new IllegalArgumentException(
                    "A scope occurrence cannot be its own parent");
        }
        if (parentOccurrence == null) {
            parentOccurrence = parent;
            return;
        }
        if (parentOccurrence != parent) {
            throw new IllegalStateException(
                    "Scope occurrence " + scopePath
                            + " already belongs to "
                            + parentOccurrence.scopePath());
        }
    }

    List<ScopeRuntimeContext> freezeAncestorChain() {
        List<ScopeRuntimeContext> ancestors = new ArrayList<>();
        Set<ScopeRuntimeContext> visited =
                Collections.newSetFromMap(
                        new IdentityHashMap<ScopeRuntimeContext, Boolean>());
        ScopeRuntimeContext current = parentOccurrence;
        while (current != null) {
            if (!visited.add(current)) {
                throw new IllegalStateException(
                        "Cyclic scope occurrence ancestry at "
                                + current.scopePath());
            }
            ancestors.add(current);
            current = current.parentOccurrence;
        }
        return Collections.unmodifiableList(ancestors);
    }

    /**
     * Returns the minimum embedded depth admitted for this occurrence.
     *
     * @return minimum admitted embedded depth
     */
    public int embeddedDepth() {
        return embeddedDepth;
    }

    /**
     * Retains the smallest non-negative embedded depth observed.
     *
     * @param depth non-negative embedded depth
     * @throws IllegalArgumentException when {@code depth} is negative
     */
    public void setEmbeddedDepth(int depth) {
        if (depth < 0) {
            throw new IllegalArgumentException("Scope embedded depth must be non-negative");
        }
        if (!embeddedDepthSet || depth < embeddedDepth) {
            embeddedDepth = depth;
            embeddedDepthSet = true;
        }
    }

    /**
     * Reports whether this scope occurrence has completed termination.
     *
     * @return {@code true} when termination is final
     */
    public boolean isTerminated() {
        return terminationState == TerminationState.TERMINATED;
    }

    /**
     * Reports whether this scope occurrence is currently terminating.
     *
     * @return {@code true} when termination has begun but is not final
     */
    public boolean isTerminating() {
        return terminationState == TerminationState.TERMINATING;
    }

    /**
     * Reports whether this scope occurrence remains active.
     *
     * @return {@code true} when the occurrence remains active
     */
    public boolean isActive() {
        return terminationState == TerminationState.ACTIVE;
    }

    /**
     * Atomically begins monotonic termination.
     *
     * @return {@code true} only when this call changed active state
     */
    public boolean beginTermination() {
        if (!isActive()) {
            return false;
        }
        terminationState = TerminationState.TERMINATING;
        return true;
    }

    /**
     * Returns the deterministic reason recorded when termination was finalized.
     *
     * @return final termination reason, or {@code null} when none was recorded
     */
    public String terminationReason() {
        return terminationReason;
    }

    /**
     * Finalizes termination and discards queued triggers.
     *
     * @param reason deterministic termination reason, or {@code null}
     */
    public void finalizeTermination(String reason) {
        if (isTerminated()) {
            return;
        }
        terminationState = TerminationState.TERMINATED;
        terminationReason = reason;
        triggeredQueue.clear();
    }

    /**
     * Freezes the currently admitted trigger and bridgeable-event prefixes.
     */
    public void markCutOff() {
        if (cutOff) {
            return;
        }
        cutOff = true;
        triggeredLimit = triggeredQueue.size();
        bridgeableLimit = bridgeableEvents.size();
    }

    /**
     * Reports whether this occurrence has been cut off.
     *
     * @return {@code true} when this occurrence has been cut off
     */
    public boolean isCutOff() {
        return cutOff;
    }

    /**
     * Defines the monotonic lifecycle states of a participating scope occurrence.
     */
    public enum TerminationState {
        /**
         * The scope may still accept and execute work.
         */
        ACTIVE,
        /**
         * The scope's termination effects are being finalized.
         */
        TERMINATING,
        /**
         * The scope is permanently terminated for the invocation.
         */
        TERMINATED
    }
}
