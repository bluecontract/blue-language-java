package blue.language.processor;

import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * One immutable invocation-local event occurrence.
 *
 * <p>The source and ancestor contexts identify the scope occurrences that
 * existed when the event was emitted. They are deliberately not looked up
 * again by path while the FIFO is drained.</p>
 */
final class EventOccurrence {

    enum SourceMode {
        TRIGGERED
    }

    private final FrozenNode event;
    private final String eventBlueId;
    private final ScopeRuntimeContext source;
    private final List<ScopeRuntimeContext> frozenAncestors;
    private final SourceMode sourceMode;
    private final String emittingContractKey;
    private final long occurrenceSequence;

    EventOccurrence(Node event,
                    String eventBlueId,
                    ScopeRuntimeContext source,
                    List<ScopeRuntimeContext> frozenAncestors,
                    SourceMode sourceMode,
                    String emittingContractKey) {
        this(event,
                eventBlueId,
                source,
                frozenAncestors,
                sourceMode,
                emittingContractKey,
                -1L);
    }

    private EventOccurrence(
            Node event,
            String eventBlueId,
            ScopeRuntimeContext source,
            List<ScopeRuntimeContext> frozenAncestors,
            SourceMode sourceMode,
            String emittingContractKey,
            long occurrenceSequence) {
        this.event = FrozenNode.fromResolvedNode(
                Objects.requireNonNull(event, "event"));
        this.eventBlueId =
                Objects.requireNonNull(eventBlueId, "eventBlueId");
        this.source = Objects.requireNonNull(source, "source");
        this.frozenAncestors = Collections.unmodifiableList(
                new ArrayList<>(Objects.requireNonNull(
                        frozenAncestors, "frozenAncestors")));
        this.sourceMode =
                Objects.requireNonNull(sourceMode, "sourceMode");
        this.emittingContractKey = emittingContractKey;
        this.occurrenceSequence = occurrenceSequence;
    }

    Node event() {
        return event.toNode();
    }

    FrozenNode frozenEvent() {
        return event;
    }

    String eventBlueId() {
        return eventBlueId;
    }

    ScopeRuntimeContext source() {
        return source;
    }

    List<ScopeRuntimeContext> frozenAncestors() {
        return frozenAncestors;
    }

    SourceMode sourceMode() {
        return sourceMode;
    }

    String emittingContractKey() {
        return emittingContractKey;
    }

    long occurrenceSequence() {
        return occurrenceSequence;
    }

    EventOccurrence withSequence(long sequence) {
        if (sequence < 0L) {
            throw new IllegalArgumentException(
                    "Occurrence sequence must be non-negative");
        }
        if (occurrenceSequence >= 0L) {
            if (occurrenceSequence != sequence) {
                throw new IllegalStateException(
                        "Event occurrence sequence is already frozen");
            }
            return this;
        }
        return new EventOccurrence(
                event.toNode(),
                eventBlueId,
                source,
                frozenAncestors,
                sourceMode,
                emittingContractKey,
                sequence);
    }
}
