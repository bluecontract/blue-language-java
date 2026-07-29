package blue.language.processor;

import blue.language.model.Node;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable deterministic record for one semantically observable processor
 * step in the conformance/debug projection.
 */
public final class ProcessingTraceRecord {

    /**
     * Defines the stable semantic step categories used by closed conformance projections.
     */
    public enum Kind {
        /**
         * External evidence was admitted for delivery.
         */
        EXTERNAL_DELIVERY,
        /**
         * A channel contract was looked up.
         */
        CHANNEL_LOOKUP,
        /**
         * Eligible sources were grouped into one logical delivery.
         */
        LOGICAL_DELIVERY_GROUP,
        /**
         * A matched handler executed.
         */
        HANDLER_EXECUTION,
        /**
         * A lifecycle phase was evaluated.
         */
        LIFECYCLE,
        /**
         * Processor-managed marker state was written.
         */
        MARKER_WRITE,
        /**
         * Incoming checkpoint ordering was compared.
         */
        CHECKPOINT_COMPARE,
        /**
         * A checkpoint entry was written.
         */
        CHECKPOINT_WRITE,
        /**
         * Obsolete checkpoint state was removed.
         */
        CHECKPOINT_CLEANUP,
        /**
         * A document-update event was constructed or routed.
         */
        DOCUMENT_UPDATE,
        /**
         * An internal event entered the deterministic queue.
         */
        EVENT_ENQUEUED,
        /**
         * An internal event left the deterministic queue.
         */
        EVENT_DEQUEUED,
        /**
         * An event was delivered to a scope.
         */
        EVENT_DELIVERED,
        /**
         * The root event entered processing.
         */
        ROOT_EVENT,
        /**
         * A terminated scope was excluded.
         */
        SCOPE_CUT_OFF,
        /**
         * A type-generalization decision was made.
         */
        TYPE_GENERALIZATION,
        /**
         * A validated subscription delta was produced.
         */
        SUBSCRIPTION_DELTA,
        /**
         * A tentative semantic effect was discarded.
         */
        DISCARDED_EFFECT
    }

    private final long sequence;
    private final Kind kind;
    private final String scopePath;
    private final String contractKey;
    private final String logicalPath;
    private final Map<String, String> details;
    private final Node node;

    ProcessingTraceRecord(long sequence,
                          Kind kind,
                          String scopePath,
                          String contractKey,
                          String logicalPath,
                          Map<String, String> details,
                          Node node) {
        this.sequence = sequence;
        this.kind = Objects.requireNonNull(kind, "kind");
        this.scopePath = scopePath;
        this.contractKey = contractKey;
        this.logicalPath = logicalPath;
        this.details = Collections.unmodifiableMap(new LinkedHashMap<>(details));
        this.node = node != null ? node.clone() : null;
    }

    /**
     * Returns this record's deterministic position in the trace.
     *
     * @return zero-based deterministic encounter sequence
     */
    public long sequence() {
        return sequence;
    }

    /**
     * Returns the stable semantic category of the recorded step.
     *
     * @return stable semantic record kind
     */
    public Kind kind() {
        return kind;
    }

    /**
     * Returns the absolute path of the scope associated with this step.
     *
     * @return absolute scope path, or {@code null} when the step is not scope-specific
     */
    public String scopePath() {
        return scopePath;
    }

    /**
     * Returns the contract key associated with this step.
     *
     * @return contract key, or {@code null} when the step is not contract-specific
     */
    public String contractKey() {
        return contractKey;
    }

    /**
     * Returns the deterministic logical path associated with this step.
     *
     * @return deterministic logical path, or {@code null} when none applies
     */
    public String logicalPath() {
        return logicalPath;
    }

    /**
     * Returns the normalized details recorded for this step.
     *
     * @return immutable stable detail map
     */
    public Map<String, String> details() {
        return details;
    }

    /**
     * Reads one stable detail.
     *
     * @param name detail name
     * @return detail value, or {@code null}
     */
    public String detail(String name) {
        return details.get(name);
    }

    /**
     * Returns the node captured for this step without exposing stored state.
     *
     * @return defensive node clone, or {@code null} when no node was captured
     */
    public Node node() {
        return node != null ? node.clone() : null;
    }
}
