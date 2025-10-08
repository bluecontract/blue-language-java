package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.model.MarkerContract;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Snapshot of the data passed to a channel processor during matching.
 *
 * <p>The event node supplied here is a fresh clone of the inbound event.
 * Channel processors MAY mutate it (for example, to normalise or enrich the
 * payload); any changes are confined to this invocation and the adapted node
 * becomes the one delivered to downstream handlers and persisted in the
 * checkpoint.</p>
 */
public final class ChannelEvaluationContext {

    private final String scopePath;
    private final Node event;
    private final Object eventObject;
    private final Map<String, MarkerContract> markers;

    ChannelEvaluationContext(String scopePath,
                             Node event,
                             Object eventObject,
                             Map<String, MarkerContract> markers) {
        this.scopePath = Objects.requireNonNull(scopePath, "scopePath");
        this.event = event;
        this.eventObject = eventObject;
        this.markers = markers == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(markers));
    }

    public String scopePath() {
        return scopePath;
    }

    public Node event() {
        // Mutable clone scoped to this invocation; safe to adapt.
        return event;
    }

    public Object eventObject() {
        return eventObject;
    }

    public Map<String, MarkerContract> markers() {
        return markers;
    }
}
