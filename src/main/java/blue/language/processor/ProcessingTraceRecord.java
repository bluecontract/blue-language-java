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

    public enum Kind {
        EXTERNAL_DELIVERY,
        LIFECYCLE,
        MARKER_WRITE,
        CHECKPOINT_COMPARE,
        CHECKPOINT_WRITE,
        CHECKPOINT_CLEANUP,
        DOCUMENT_UPDATE,
        EVENT_ENQUEUED,
        EVENT_DEQUEUED,
        EVENT_DELIVERED,
        ROOT_EVENT,
        SCOPE_CUT_OFF,
        TYPE_GENERALIZATION,
        SUBSCRIPTION_DELTA,
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

    public long sequence() {
        return sequence;
    }

    public Kind kind() {
        return kind;
    }

    public String scopePath() {
        return scopePath;
    }

    public String contractKey() {
        return contractKey;
    }

    public String logicalPath() {
        return logicalPath;
    }

    public Map<String, String> details() {
        return details;
    }

    public String detail(String name) {
        return details.get(name);
    }

    public Node node() {
        return node != null ? node.clone() : null;
    }
}
