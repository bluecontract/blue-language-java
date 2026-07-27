package blue.language.processor;

import blue.language.model.Node;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable result of evaluating one registered same-scope External Channel
 * member through its exact runtime functions.
 *
 * <p>Composite runtimes may delegate the selected member's exact payload and
 * checkpoint subject from this result without reconstructing either value.</p>
 */
public final class ExternalChannelMemberEvaluation {

    private final List<String> channelKeys;
    private final List<String> eventKeys;
    private final boolean preselects;
    private final boolean accepts;
    private final String checkpointDomainBlueId;
    private final Node payload;
    private final Node checkpointSubject;
    private final String handlerChannelKey;
    private final String logicalDeliveryKey;

    ExternalChannelMemberEvaluation(
            List<String> channelKeys,
            List<String> eventKeys,
            boolean preselects,
            boolean accepts,
            String checkpointDomainBlueId,
            Node payload,
            Node checkpointSubject,
            String handlerChannelKey,
            String logicalDeliveryKey) {
        this.channelKeys = Collections.unmodifiableList(
                new ArrayList<>(channelKeys));
        this.eventKeys = Collections.unmodifiableList(
                new ArrayList<>(eventKeys));
        this.preselects = preselects;
        this.accepts = accepts;
        this.checkpointDomainBlueId = checkpointDomainBlueId;
        this.payload = payload != null ? payload.clone() : null;
        this.checkpointSubject =
                checkpointSubject != null
                        ? checkpointSubject.clone()
                        : null;
        this.handlerChannelKey = handlerChannelKey;
        this.logicalDeliveryKey = logicalDeliveryKey;
    }

    public List<String> channelKeys() {
        return channelKeys;
    }

    public List<String> eventKeys() {
        return eventKeys;
    }

    public boolean preselects() {
        return preselects;
    }

    public boolean accepts() {
        return accepts;
    }

    public String checkpointDomainBlueId() {
        return checkpointDomainBlueId;
    }

    public Node payload() {
        return payload != null ? payload.clone() : null;
    }

    public Node checkpointSubject() {
        return checkpointSubject != null
                ? checkpointSubject.clone()
                : null;
    }

    /**
     * Same-scope handler target selected by the member's immutable runtime
     * functions, or {@code null} when the member did not accept.
     */
    public String handlerChannelKey() {
        return handlerChannelKey;
    }

    /**
     * Run-local logical delivery identity selected by the member's immutable
     * runtime functions, or {@code null} when the member did not accept.
     */
    public String logicalDeliveryKey() {
        return logicalDeliveryKey;
    }
}
