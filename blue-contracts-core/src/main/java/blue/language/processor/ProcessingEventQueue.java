package blue.language.processor;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/**
 * Deterministic invocation-wide FIFO of immutable event occurrences.
 *
 * <p>Every scope appends to the same queue, so nested cascades cannot acquire
 * an implementation-dependent ordering. The admitted occurrence counter is
 * monotonic and is used for portable-limit checks before insertion.</p>
 */
final class ProcessingEventQueue {

    private final Deque<EventOccurrence> occurrences = new ArrayDeque<>();
    private long admittedOccurrences;

    long nextAdmittedCount() {
        return admittedOccurrences + 1L;
    }

    void enqueue(EventOccurrence occurrence) {
        EventOccurrence sequenced = Objects.requireNonNull(
                occurrence, "occurrence")
                .withSequence(admittedOccurrences);
        occurrences.addLast(sequenced);
        admittedOccurrences++;
    }

    EventOccurrence poll() {
        return occurrences.pollFirst();
    }

    boolean hasPendingOccurrences() {
        return !occurrences.isEmpty();
    }

    int pendingOccurrenceCount() {
        return occurrences.size();
    }
}
