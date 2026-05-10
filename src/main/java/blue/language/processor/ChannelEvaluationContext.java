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
    }

    public String scopePath() {
        return scopePath;
    }

    public String bindingKey() {
        return bindingKey;
    }

    public Node event() {
        return event != null ? event.clone() : null;
    }

    public Object eventObject() {
        return eventObject;
    }

    public Map<String, ChannelContract> channels() {
        return channels;
    }

    public Set<String> channelKeys() {
        return channels.keySet();
    }

    public ChannelContract channel(String key) {
        return channels.get(key);
    }

    public ChannelProcessor<? extends ChannelContract> channelProcessor(String key) {
        return channelProcessor(channel(key));
    }

    public ChannelProcessor<? extends ChannelContract> channelProcessor(ChannelContract contract) {
        if (registry == null || contract == null) {
            return null;
        }
        return registry.lookupChannel(contract).orElse(null);
    }

    public ChannelEvaluationContext forBindingKey(String bindingKey) {
        return new ChannelEvaluationContext(scopePath,
                bindingKey,
                event,
                eventObject,
                channels,
                markers,
                registry);
    }

    public Map<String, MarkerContract> markers() {
        return markers;
    }
}
