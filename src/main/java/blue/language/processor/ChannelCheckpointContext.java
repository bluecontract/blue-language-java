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
    private final RuntimeWorkSession runtimeWorkSession;
    private volatile boolean lastEventMaterialized;

    /**
     * Creates a context whose current checkpoint subject is the exact event.
     * Use the subject-aware overload when a channel freezes another subject.
     *
     * @param scopePath absolute scope containing the Channel
     * @param channelKey raw Channel key
     * @param event exact accepted event
     * @param eventSignature exact event BlueId
     * @param lastEvent previous exact checkpoint subject, or {@code null}
     * @param lastEventSignature previous subject BlueId, or {@code null}
     * @param markers immutable same-scope Marker snapshot
     * @return immutable checkpoint comparison context
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
     *
     * @param scopePath absolute scope containing the Channel
     * @param channelKey raw Channel key
     * @param event exact accepted event
     * @param eventSignature exact current-subject BlueId
     * @param currentSubject exact subject selected for this occurrence
     * @param lastEvent previous exact checkpoint subject, or {@code null}
     * @param lastEventSignature previous subject BlueId, or {@code null}
     * @param markers immutable same-scope Marker snapshot
     * @return immutable checkpoint comparison context
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
                        "lastEventMaterializer"),
                null);
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
                null,
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
            Supplier<Node> lastEventMaterializer,
            RuntimeWorkSession runtimeWorkSession) {
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
        this.runtimeWorkSession = runtimeWorkSession;
        this.lastEventMaterialized =
                lastEventMaterializer == null;
    }

    static ChannelCheckpointContext withRuntimeWorkSession(
            String scopePath,
            String channelKey,
            Node event,
            String eventSignature,
            Node currentSubject,
            Node lastEvent,
            String lastEventSignature,
            Map<String, MarkerContract> markers,
            Supplier<Node> lastEventMaterializer,
            RuntimeWorkSession runtimeWorkSession) {
        return new ChannelCheckpointContext(
                scopePath,
                channelKey,
                event,
                eventSignature,
                currentSubject,
                lastEvent,
                lastEventSignature,
                markers,
                lastEventMaterializer,
                Objects.requireNonNull(
                        runtimeWorkSession,
                        "runtimeWorkSession"));
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
     * Returns the raw same-scope Channel key.
     *
     * @return Channel key
     */
    public String channelKey() {
        return channelKey;
    }

    /**
     * Returns a detached mutable copy of the accepted raw event.
     *
     * @return event copy, or {@code null}
     */
    public Node event() {
        return event != null ? event.clone() : null;
    }

    /**
     * Returns the exact BlueId of {@link #currentSubject()}, not necessarily
     * the BlueId of the raw accepted event.
     *
     * @return exact current-subject identity, or {@code null}
     */
    public String eventSignature() {
        return eventSignature;
    }

    /**
     * Returns the exact current checkpoint subject frozen during immutable
     * External Channel evaluation. This can intentionally be smaller than the
     * raw accepted event and can encode a composite member selection.
     *
     * @return defensive current-subject copy, or {@code null}
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
     *
     * @return defensive previous-subject copy, or {@code null}
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
     *
     * @return previous subject identity, or {@code null}
     */
    public String lastEventSignature() {
        return lastEventSignature;
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
     * Returns the live hosted-runtime work session for this comparison.
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
