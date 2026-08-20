package blue.language.processor.closure;

import org.junit.jupiter.api.Test;

import java.util.NoSuchElementException;

import static blue.language.processor.closure.ManagedDocumentGraphTest.identity;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ClosureWorkQueueTest {

    @Test
    void shouldExposeExactlySevenClosedKinds() {
        assertEquals(7, WorkKind.values().length);
        assertEquals(
                WorkKind.LIFECYCLE,
                WorkKind.valueOf("LIFECYCLE"));
        assertEquals(
                WorkKind.CONTAINING_REFERENCE_UPDATE,
                WorkKind.valueOf("CONTAINING_REFERENCE_UPDATE"));
    }

    @Test
    void shouldPreserveFifoOrderAndRootExecutionKey() {
        ClosureWorkQueue queue = new ClosureWorkQueue();
        ClosureWorkOccurrence first = work(0, 1, WorkKind.INITIALIZATION);
        ClosureWorkOccurrence second = work(1, 2, WorkKind.LIFECYCLE);

        queue.enqueue(first);
        queue.enqueue(second);

        assertEquals(2, queue.size());
        assertSame(first, queue.peek());
        assertSame(first, queue.dequeue());
        assertSame(second, queue.dequeue());
        assertTrue(second.targetManagedScopeKey().isRoot());
        assertTrue(queue.isEmpty());
        assertThrows(NoSuchElementException.class, queue::dequeue);
    }

    @Test
    void shouldRejectDuplicateWorkPermanently() {
        ClosureWorkQueue queue = new ClosureWorkQueue();
        ClosureWorkOccurrence first = work(0, 1, WorkKind.EXTERNAL_DELIVERY);
        queue.enqueue(first);
        queue.dequeue();

        assertThrows(IllegalArgumentException.class,
                () -> queue.enqueue(first));
        assertThrows(IllegalArgumentException.class,
                () -> queue.enqueue(work(
                        0, 2, WorkKind.DOCUMENT_UPDATE)));
    }

    private static ClosureWorkOccurrence work(
            long ordinal,
            int identityOrdinal,
            WorkKind kind) {
        return new ClosureWorkOccurrence(
                ordinal,
                kind,
                new DocumentId("document-" + identityOrdinal),
                "channel",
                "event-blue-id",
                Long.valueOf(ordinal),
                identity(1000 + identityOrdinal),
                identity(2000 + identityOrdinal),
                identity(3000 + identityOrdinal));
    }
}
