package blue.language.processor;

import blue.language.model.Node;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * Per-scope runtime state tracked during processing.
 */
public final class ScopeRuntimeContext {

    private final String scopePath;
    private final Deque<Node> triggeredQueue = new ArrayDeque<>();
    private final List<Node> bridgeableEvents = new ArrayList<>();
    private final List<String> processedEmbeddedPaths = new ArrayList<>();
    private TerminationState terminationState = TerminationState.ACTIVE;
    private TerminationKind terminationKind;
    private String terminationReason;
    private boolean cutOff;
    private int triggeredLimit = -1;
    private int bridgeableLimit = -1;
    private int embeddedDepth;
    private boolean embeddedDepthSet;

    public ScopeRuntimeContext(String scopePath) {
        this.scopePath = Objects.requireNonNull(scopePath, "scopePath");
    }

    public String scopePath() {
        return scopePath;
    }

    public Deque<Node> triggeredQueue() {
        return triggeredQueue;
    }

    public void enqueueTriggered(Node node) {
        if (cutOff && triggeredLimit >= 0 && triggeredQueue.size() >= triggeredLimit) {
            return;
        }
        triggeredQueue.addLast(Objects.requireNonNull(node, "node"));
    }

    public void recordBridgeable(Node node) {
        if (cutOff && bridgeableLimit >= 0 && bridgeableEvents.size() >= bridgeableLimit) {
            return;
        }
        bridgeableEvents.add(Objects.requireNonNull(node, "node"));
    }

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

    public void clearProcessedEmbeddedPaths() {
        processedEmbeddedPaths.clear();
    }

    public void recordProcessedEmbeddedPath(String path) {
        processedEmbeddedPaths.add(Objects.requireNonNull(path, "path"));
    }

    public List<String> processedEmbeddedPaths() {
        return new ArrayList<>(processedEmbeddedPaths);
    }

    public int embeddedDepth() {
        return embeddedDepth;
    }

    public void setEmbeddedDepth(int depth) {
        if (depth < 0) {
            throw new IllegalArgumentException("Scope embedded depth must be non-negative");
        }
        if (!embeddedDepthSet || depth < embeddedDepth) {
            embeddedDepth = depth;
            embeddedDepthSet = true;
        }
    }

    public boolean isTerminated() {
        return terminationState == TerminationState.TERMINATED;
    }

    public boolean isTerminating() {
        return terminationState == TerminationState.TERMINATING;
    }

    public boolean isActive() {
        return terminationState == TerminationState.ACTIVE;
    }

    public boolean beginTermination() {
        if (!isActive()) {
            return false;
        }
        terminationState = TerminationState.TERMINATING;
        return true;
    }

    public TerminationKind terminationKind() {
        return terminationKind;
    }

    public String terminationReason() {
        return terminationReason;
    }

    public void finalizeTermination(TerminationKind kind, String reason) {
        if (isTerminated()) {
            return;
        }
        terminationState = TerminationState.TERMINATED;
        terminationKind = Objects.requireNonNull(kind, "kind");
        terminationReason = reason;
        triggeredQueue.clear();
    }

    public void markCutOff() {
        if (cutOff) {
            return;
        }
        cutOff = true;
        triggeredLimit = triggeredQueue.size();
        bridgeableLimit = bridgeableEvents.size();
    }

    public boolean isCutOff() {
        return cutOff;
    }

    public enum TerminationState {
        ACTIVE,
        TERMINATING,
        TERMINATED
    }

    public enum TerminationKind {
        GRACEFUL,
        FATAL
    }
}
