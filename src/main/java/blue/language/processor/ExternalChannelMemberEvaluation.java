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

    /**
     * Returns the subscription keys exposed by the selected member.
     *
     * @return immutable channel subscription keys in runtime-defined order
     */
    public List<String> channelKeys() {
        return channelKeys;
    }

    /**
     * Returns keys derived from the exact evaluated event.
     *
     * @return immutable keys derived from the exact event
     */
    public List<String> eventKeys() {
        return eventKeys;
    }

    /**
     * Reports the finite-key preselection decision.
     *
     * @return whether finite-key preselection accepted the occurrence
     */
    public boolean preselects() {
        return preselects;
    }

    /**
     * Reports the member runtime's final acceptance decision.
     *
     * @return whether the runtime accepted the occurrence
     */
    public boolean accepts() {
        return accepts;
    }

    /**
     * Returns the checkpoint domain bound by the member runtime.
     *
     * @return exact checkpoint-domain identity
     */
    public String checkpointDomainBlueId() {
        return checkpointDomainBlueId;
    }

    /**
     * Returns the accepted delivery payload without exposing stored state.
     *
     * @return a defensive payload copy, or {@code null} when not accepted
     */
    public Node payload() {
        return payload != null ? payload.clone() : null;
    }

    /**
     * Returns the checkpoint subject without exposing stored state.
     *
     * @return a defensive checkpoint-subject copy, or {@code null}
     */
    public Node checkpointSubject() {
        return checkpointSubject != null
                ? checkpointSubject.clone()
                : null;
    }

    /**
     * Same-scope handler target selected by the member's immutable runtime
     * functions, or {@code null} when the member did not accept.
     *
     * @return selected handler channel key, or {@code null}
     */
    public String handlerChannelKey() {
        return handlerChannelKey;
    }

    /**
     * Run-local logical delivery identity selected by the member's immutable
     * runtime functions, or {@code null} when the member did not accept.
     *
     * @return logical delivery key, or {@code null}
     */
    public String logicalDeliveryKey() {
        return logicalDeliveryKey;
    }
}
