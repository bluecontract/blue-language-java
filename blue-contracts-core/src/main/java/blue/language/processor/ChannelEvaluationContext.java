package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.model.ChannelContract;
import blue.language.processor.model.MarkerContract;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Snapshot of the data passed to a channel processor during matching.
 *
 * <p>The event node supplied here is read-only from the processor model's
 * perspective. {@link #event()} returns a fresh mutable copy for convenience,
 * but mutations to that copy are ignored. Channel processors that normalize or
 * enrich an event must return the adapted event in {@link ChannelEvaluation}.</p>
 */
public final class ChannelEvaluationContext {

    private final String scopePath;
    private final String bindingKey;
    private final Node event;
    private final Object eventObject;
    private final Map<String, ChannelContract> channels;
    private final Map<String, MarkerContract> markers;
    private final ContractProcessorRegistry registry;
    private final RuntimeWorkSession runtimeWorkSession;

    ChannelEvaluationContext(String scopePath,
                             String bindingKey,
                             Node event,
                             Object eventObject,
                             Map<String, ChannelContract> channels,
                             Map<String, MarkerContract> markers) {
        this(scopePath, bindingKey, event, eventObject, channels, markers, null);
    }

    ChannelEvaluationContext(String scopePath,
                             String bindingKey,
                             Node event,
                             Object eventObject,
                             Map<String, ChannelContract> channels,
                             Map<String, MarkerContract> markers,
                             ContractProcessorRegistry registry) {
        this(scopePath,
                bindingKey,
                event,
                eventObject,
                channels,
                markers,
                registry,
                null);
    }

    ChannelEvaluationContext(String scopePath,
                             String bindingKey,
                             Node event,
                             Object eventObject,
                             Map<String, ChannelContract> channels,
                             Map<String, MarkerContract> markers,
                             ContractProcessorRegistry registry,
                             RuntimeWorkSession runtimeWorkSession) {
        this.scopePath = Objects.requireNonNull(scopePath, "scopePath");
        this.bindingKey = bindingKey;
        this.event = event != null ? event.clone() : null;
        this.eventObject = eventObject;
        this.channels = channels == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(channels));
        this.markers = markers == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(markers));
        this.registry = registry;
        this.runtimeWorkSession = runtimeWorkSession;
    }

    /**
     * Returns the absolute scope containing the Channel.
     *
     * @return normalized scope path
     */
    public String scopePath() {
        return scopePath;
    }

    /**
     * Returns the raw key currently bound for evaluation.
     *
     * @return binding key, or {@code null}
     */
    public String bindingKey() {
        return bindingKey;
    }

    /**
     * Returns a detached mutable copy of the exact event.
     *
     * @return event copy, or {@code null}
     */
    public Node event() {
        return event != null ? event.clone() : null;
    }

    /**
     * Returns the event converted to a registered Java runtime model.
     *
     * @return converted event object, or {@code null}
     */
    public Object eventObject() {
        return eventObject;
    }

    /**
     * Returns the immutable same-scope Channel model snapshot.
     *
     * @return immutable Channel map
     */
    public Map<String, ChannelContract> channels() {
        return channels;
    }

    /**
     * Returns the captured same-scope Channel keys.
     *
     * @return immutable key set
     */
    public Set<String> channelKeys() {
        return channels.keySet();
    }

    /**
     * Returns one captured same-scope Channel model.
     *
     * @param key raw contract key
     * @return Channel model, or {@code null}
     */
    public ChannelContract channel(String key) {
        return channels.get(key);
    }

    /**
     * Looks up the processor registered for a captured Channel key.
     *
     * @param key raw contract key
     * @return exact registered processor, or {@code null}
     */
    public ChannelProcessor<? extends ChannelContract> channelProcessor(String key) {
        return channelProcessor(channel(key));
    }

    /**
     * Looks up the processor registered for a Channel model.
     *
     * @param contract Channel model
     * @return exact registered processor, or {@code null}
     */
    public ChannelProcessor<? extends ChannelContract> channelProcessor(ChannelContract contract) {
        if (registry == null || contract == null) {
            return null;
        }
        return registry.lookupChannel(contract).orElse(null);
    }

    /**
     * Creates an immutable sibling context for another binding key.
     *
     * @param bindingKey new raw binding key
     * @return context sharing the captured event and same-scope snapshots
     */
    public ChannelEvaluationContext forBindingKey(String bindingKey) {
        return new ChannelEvaluationContext(scopePath,
                bindingKey,
                event,
                eventObject,
                channels,
                markers,
                registry,
                runtimeWorkSession);
    }

    /**
     * Returns the immutable same-scope Marker snapshot.
     *
     * @return immutable marker map
     */
    public Map<String, MarkerContract> markers() {
        return markers;
    }

    /**
     * Returns the live hosted-runtime work session for this evaluation.
     *
     * @return invocation-owned runtime work session
     * @throws IllegalStateException for a legacy out-of-band context
     */
    public RuntimeWorkSession runtimeWorkSession() {
        if (runtimeWorkSession == null) {
            throw new IllegalStateException(
                    "Runtime work is unavailable in this out-of-band context");
        }
        return runtimeWorkSession;
    }
}
