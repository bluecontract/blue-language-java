package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.model.MarkerContract;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Read-only checkpoint context used by a channel to reject stale events.
 *
 * <p>The raw event is retained for channel policy, while current and previous
 * checkpoint subjects are exact defensive copies. This lets a runtime compare
 * a compact ordering subject without reconstructing it from the full event.</p>
 */
public final class ChannelCheckpointContext {

    private final String scopePath;
    private final String channelKey;
    private final Node event;
    private final String eventSignature;
    private final Node currentSubject;
    private Node lastEvent;
    private final String lastEventSignature;
    private final Map<String, MarkerContract> markers;
    private final Supplier<Node> lastEventMaterializer;
    private volatile boolean lastEventMaterialized;

    /**
     * Creates a context whose current checkpoint subject is the exact event.
     * Use the subject-aware overload when a channel freezes another subject.
     */
    public static ChannelCheckpointContext of(String scopePath,
                                              String channelKey,
                                              Node event,
                                              String eventSignature,
                                              Node lastEvent,
                                              String lastEventSignature,
                                              Map<String, MarkerContract> markers) {
        return of(scopePath,
                channelKey,
                event,
                eventSignature,
                event,
                lastEvent,
                lastEventSignature,
                markers);
    }

    /**
     * Creates a checkpoint context with the exact current subject already
     * frozen by the External Channel functions.
     */
    public static ChannelCheckpointContext of(
            String scopePath,
            String channelKey,
            Node event,
            String eventSignature,
            Node currentSubject,
            Node lastEvent,
            String lastEventSignature,
            Map<String, MarkerContract> markers) {
        return new ChannelCheckpointContext(scopePath,
                channelKey,
                event,
                eventSignature,
                currentSubject,
                lastEvent,
                lastEventSignature,
                markers);
    }

    static ChannelCheckpointContext withLazyLastEvent(
            String scopePath,
            String channelKey,
            Node event,
            String eventSignature,
            Node currentSubject,
            String lastEventSignature,
            Map<String, MarkerContract> markers,
            Supplier<Node> lastEventMaterializer) {
        return new ChannelCheckpointContext(
                scopePath,
                channelKey,
                event,
                eventSignature,
                currentSubject,
                null,
                lastEventSignature,
                markers,
                Objects.requireNonNull(
                        lastEventMaterializer,
                        "lastEventMaterializer"));
    }

    ChannelCheckpointContext(String scopePath,
                             String channelKey,
                             Node event,
                             String eventSignature,
                             Node lastEvent,
                             String lastEventSignature,
                             Map<String, MarkerContract> markers) {
        this(scopePath,
                channelKey,
                event,
                eventSignature,
                event,
                lastEvent,
                lastEventSignature,
                markers);
    }

    ChannelCheckpointContext(String scopePath,
                             String channelKey,
                             Node event,
                             String eventSignature,
                             Node currentSubject,
                             Node lastEvent,
                             String lastEventSignature,
                             Map<String, MarkerContract> markers) {
        this(scopePath,
                channelKey,
                event,
                eventSignature,
                currentSubject,
                lastEvent,
                lastEventSignature,
                markers,
                null);
    }

    private ChannelCheckpointContext(
            String scopePath,
            String channelKey,
            Node event,
            String eventSignature,
            Node currentSubject,
            Node lastEvent,
            String lastEventSignature,
            Map<String, MarkerContract> markers,
            Supplier<Node> lastEventMaterializer) {
        this.scopePath = Objects.requireNonNull(scopePath, "scopePath");
        this.channelKey = Objects.requireNonNull(channelKey, "channelKey");
        this.event = event != null ? event.clone() : null;
        this.eventSignature = eventSignature;
        this.currentSubject =
                currentSubject != null
                        ? currentSubject.clone()
                        : null;
        this.lastEvent = lastEvent != null ? lastEvent.clone() : null;
        this.lastEventSignature = lastEventSignature;
        this.markers = markers == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(markers));
        this.lastEventMaterializer = lastEventMaterializer;
        this.lastEventMaterialized =
                lastEventMaterializer == null;
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

    /**
     * Returns the exact BlueId of {@link #currentSubject()}, not necessarily
     * the BlueId of the raw accepted event.
     */
    public String eventSignature() {
        return eventSignature;
    }

    /**
     * Returns the exact current checkpoint subject frozen during immutable
     * External Channel evaluation. This can intentionally be smaller than the
     * raw accepted event and can encode a composite member selection.
     */
    public Node currentSubject() {
        return currentSubject != null
                ? currentSubject.clone()
                : null;
    }

    /**
     * Returns the exact previous checkpoint subject, not merely its stored
     * reference wrapper. Inline subjects are copied directly; a pure-reference
     * subject is verified and materialized only on the first call.
     */
    public Node lastEvent() {
        if (!lastEventMaterialized) {
            synchronized (this) {
                if (!lastEventMaterialized) {
                    Node materialized =
                            Objects.requireNonNull(
                                    lastEventMaterializer.get(),
                                    "materializedLastEvent");
                    lastEvent = materialized.clone();
                    lastEventMaterialized = true;
                }
            }
        }
        Node captured = lastEvent;
        return captured != null ? captured.clone() : null;
    }

    /**
     * Returns the previous subject's exact BlueId without materializing it.
     */
    public String lastEventSignature() {
        return lastEventSignature;
    }

    public Map<String, MarkerContract> markers() {
        return markers;
    }
}
