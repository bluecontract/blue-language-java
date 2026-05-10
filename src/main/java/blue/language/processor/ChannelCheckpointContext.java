package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.model.MarkerContract;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Read-only checkpoint context used by a channel to reject stale events.
 */
public final class ChannelCheckpointContext {

    private final String scopePath;
    private final String channelKey;
    private final Node event;
    private final String eventSignature;
    private final Node lastEvent;
    private final String lastEventSignature;
    private final Map<String, MarkerContract> markers;

    ChannelCheckpointContext(String scopePath,
                             String channelKey,
                             Node event,
                             String eventSignature,
                             Node lastEvent,
                             String lastEventSignature,
                             Map<String, MarkerContract> markers) {
        this.scopePath = Objects.requireNonNull(scopePath, "scopePath");
        this.channelKey = Objects.requireNonNull(channelKey, "channelKey");
        this.event = event != null ? event.clone() : null;
        this.eventSignature = eventSignature;
        this.lastEvent = lastEvent != null ? lastEvent.clone() : null;
        this.lastEventSignature = lastEventSignature;
        this.markers = markers == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(markers));
    }

    public String scopePath() {
        return scopePath;
    }

    public String channelKey() {
        return channelKey;
    }

    public Node event() {
        return event != null ? event.clone() : null;
    }

    public String eventSignature() {
        return eventSignature;
    }

    public Node lastEvent() {
        return lastEvent != null ? lastEvent.clone() : null;
    }

    public String lastEventSignature() {
        return lastEventSignature;
    }

    public Map<String, MarkerContract> markers() {
        return markers;
    }
}
