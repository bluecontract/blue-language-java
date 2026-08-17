package blue.language.processor.closure;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;

/** Invocation FIFO that permanently rejects duplicate work occurrences. */
public final class ClosureWorkQueue {

    private final Deque<ClosureWorkOccurrence> queue =
            new ArrayDeque<ClosureWorkOccurrence>();
    private final Set<String> acceptedIdentities = new HashSet<String>();
    private final Set<Long> acceptedOrdinals = new HashSet<Long>();

    /** Creates an empty invocation FIFO. */
    public ClosureWorkQueue() {
    }

    /**
     * Enqueues once; a dequeued occurrence cannot later be re-enqueued.
     *
     * @param occurrence exact work occurrence
     * @throws IllegalArgumentException for a repeated identity or ordinal
     */
    public void enqueue(ClosureWorkOccurrence occurrence) {
        ClosureWorkOccurrence admitted = Objects.requireNonNull(
                occurrence, "occurrence");
        if (acceptedIdentities.contains(admitted.workIdentity())) {
            throw new IllegalArgumentException("Duplicate work identity");
        }
        Long ordinal = Long.valueOf(admitted.ordinal());
        if (acceptedOrdinals.contains(ordinal)) {
            throw new IllegalArgumentException("Duplicate work ordinal");
        }
        acceptedIdentities.add(admitted.workIdentity());
        acceptedOrdinals.add(ordinal);
        queue.addLast(admitted);
    }

    /**
     * Removes the oldest pending occurrence.
     *
     * @return oldest pending work
     * @throws NoSuchElementException when empty
     */
    public ClosureWorkOccurrence dequeue() {
        if (queue.isEmpty()) {
            throw new NoSuchElementException("Closure work queue is empty");
        }
        return queue.removeFirst();
    }

    /**
     * Returns the oldest pending occurrence without removing it.
     *
     * @return oldest pending work
     * @throws NoSuchElementException when empty
     */
    public ClosureWorkOccurrence peek() {
        if (queue.isEmpty()) {
            throw new NoSuchElementException("Closure work queue is empty");
        }
        return queue.getFirst();
    }

    /**
     * Tests whether no work remains pending.
     *
     * @return whether the FIFO is empty
     */
    public boolean isEmpty() {
        return queue.isEmpty();
    }

    /**
     * Returns the number of pending occurrences.
     *
     * @return pending work count
     */
    public int size() {
        return queue.size();
    }
}
