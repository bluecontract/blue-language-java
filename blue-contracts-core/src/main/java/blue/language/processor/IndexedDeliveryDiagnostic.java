package blue.language.processor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable evaluated admission facts for one retained subscription interval.
 *
 * <p>Diagnostics include false PRESELECTS occurrences and ineligible
 * occurrences; they describe the complete evaluated surface rather than only
 * the deliveries admitted into the resulting plan.</p>
 */
public final class IndexedDeliveryDiagnostic {

    private final ExternalSubscriptionOccurrenceKey occurrenceKey;
    private final boolean eligibleAtEvent;
    private final boolean physicalCandidate;
    private final boolean preselects;
    private final boolean accepts;
    private final List<String> channelKeys;
    private final List<String> eventKeys;
    private final ExternalChannelDependencySnapshot dependencies;
    private final String checkpointDomainBlueId;
    private final String checkpointSubjectBlueId;
    private final String payloadBlueId;
    private final String handlerChannelKey;
    private final String logicalDeliveryKey;

    IndexedDeliveryDiagnostic(
            ExternalSubscriptionOccurrenceKey occurrenceKey,
            boolean eligibleAtEvent,
            boolean physicalCandidate,
            boolean preselects,
            boolean accepts,
            List<String> channelKeys,
            List<String> eventKeys,
            ExternalChannelDependencySnapshot dependencies,
            String checkpointDomainBlueId,
            String checkpointSubjectBlueId,
            String payloadBlueId,
            String handlerChannelKey,
            String logicalDeliveryKey) {
        this.occurrenceKey = Objects.requireNonNull(
                occurrenceKey, "occurrenceKey");
        this.eligibleAtEvent = eligibleAtEvent;
        this.physicalCandidate = physicalCandidate;
        this.preselects = preselects;
        this.accepts = accepts;
        this.channelKeys = immutable(channelKeys, "channelKeys");
        this.eventKeys = immutable(eventKeys, "eventKeys");
        this.dependencies = Objects.requireNonNull(
                dependencies, "dependencies");
        this.checkpointDomainBlueId = Objects.requireNonNull(
                checkpointDomainBlueId,
                "checkpointDomainBlueId");
        this.checkpointSubjectBlueId = checkpointSubjectBlueId;
        this.payloadBlueId = payloadBlueId;
        this.handlerChannelKey = handlerChannelKey;
        this.logicalDeliveryKey = logicalDeliveryKey;
    }

    /**
     * Returns the identity of the evaluated occurrence.
     *
     * @return normalized identity of the evaluated occurrence
     */
    public ExternalSubscriptionOccurrenceKey occurrenceKey() {
        return occurrenceKey;
    }

    /**
     * Reports whether the interval is eligible at the event order.
     *
     * @return whether the event is after the interval's activation boundary
     */
    public boolean eligibleAtEvent() {
        return eligibleAtEvent;
    }

    /**
     * Returns whether evaluated channel and event keys intersect for an
     * occurrence eligible at this event order.
     *
     * @return whether the feeder must have supplied this physical candidate
     */
    public boolean physicalCandidate() {
        return physicalCandidate;
    }

    /**
     * Returns the registered PRESELECTS result.
     *
     * @return exact registered PRESELECTS result
     */
    public boolean preselects() {
        return preselects;
    }

    /**
     * Returns the registered ACCEPTS result.
     *
     * @return exact registered ACCEPTS result
     */
    public boolean accepts() {
        return accepts;
    }

    /**
     * Returns the evaluated subscription keys.
     *
     * @return immutable runtime-defined subscription key order
     */
    public List<String> channelKeys() {
        return channelKeys;
    }

    /**
     * Returns the evaluated event keys.
     *
     * @return immutable runtime-defined event key order
     */
    public List<String> eventKeys() {
        return eventKeys;
    }

    /**
     * Returns the dependencies observed while evaluating the occurrence.
     *
     * @return immutable dependency surface observed during evaluation
     */
    public ExternalChannelDependencySnapshot dependencies() {
        return dependencies;
    }

    /**
     * Returns the evaluated checkpoint domain.
     *
     * @return exact checkpoint-domain identity
     */
    public String checkpointDomainBlueId() {
        return checkpointDomainBlueId;
    }

    /**
     * Returns the planned checkpoint subject. Accepted occurrences use the
     * evaluated subject; PRESELECTS-only occurrences use the exact event
     * identity.
     *
     * @return checkpoint subject identity, or {@code null} when not selected
     */
    public String checkpointSubjectBlueId() {
        return checkpointSubjectBlueId;
    }

    /**
     * Returns the evaluated payload identity.
     *
     * @return evaluated payload identity, or {@code null} when unavailable
     */
    public String payloadBlueId() {
        return payloadBlueId;
    }

    /**
     * Returns the evaluated handler-channel key.
     *
     * @return evaluated handler-channel key, or {@code null}
     */
    public String handlerChannelKey() {
        return handlerChannelKey;
    }

    /**
     * Returns the evaluated logical-delivery key.
     *
     * @return evaluated logical-delivery key, or {@code null}
     */
    public String logicalDeliveryKey() {
        return logicalDeliveryKey;
    }

    private static List<String> immutable(
            List<String> values,
            String label) {
        return Collections.unmodifiableList(
                new ArrayList<>(Objects.requireNonNull(values, label)));
    }
}
