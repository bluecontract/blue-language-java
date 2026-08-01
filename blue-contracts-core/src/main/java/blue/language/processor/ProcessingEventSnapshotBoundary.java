package blue.language.processor;

import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;
import java.util.Objects;

/**
 * Lazily freezes one mutable PROCESS event exactly once per invocation.
 *
 * <p>A successful snapshot and a construction failure are both memoized, so
 * every observer of the invocation sees the same immutable value or the same
 * deterministic failure.</p>
 */
final class ProcessingEventSnapshotBoundary {

    private final Node source;
    private final ProcessorEngine.ProcessEventSnapshotFactory factory;
    private final ProcessingObserver observer;
    private final Object lock = new Object();
    private volatile State state;
    private volatile FrozenNode snapshot;
    private RuntimeException failure;

    ProcessingEventSnapshotBoundary(
            Node source,
            ProcessorEngine.ProcessEventSnapshotFactory factory,
            ProcessingObserver observer) {
        this.source = source;
        this.factory = Objects.requireNonNull(factory, "factory");
        this.observer = observer;
        this.state = source != null ? State.UNINITIALIZED : State.ABSENT;
    }

    boolean isPresent() {
        return source != null;
    }

    FrozenNode frozenEvent() {
        State observed = state;
        if (observed == State.ABSENT) {
            return null;
        }
        if (observed == State.READY) {
            return snapshot;
        }
        if (observed == State.FAILED) {
            throw failure;
        }
        synchronized (lock) {
            observed = state;
            if (observed == State.READY) {
                return snapshot;
            }
            if (observed == State.FAILED) {
                throw failure;
            }
            return freeze();
        }
    }

    private FrozenNode freeze() {
        ProcessingObservations.record(
                observer,
                ProcessingMetricId.PROCESS_EVENT_SNAPSHOT_ATTEMPTS,
                1L);
        long startedAt = System.nanoTime();
        try {
            FrozenNode frozen = factory.freeze(source);
            if (frozen == null) {
                throw new IllegalStateException(
                        "Processing Event snapshot construction returned null");
            }
            snapshot = frozen;
            state = State.READY;
            ProcessingObservations.record(
                    observer,
                    ProcessingMetricId.PROCESS_EVENT_SNAPSHOT_BUILDS,
                    1L);
            return frozen;
        } catch (RuntimeException exception) {
            failure = exception;
            state = State.FAILED;
            ProcessingObservations.record(
                    observer,
                    ProcessingMetricId.PROCESS_EVENT_SNAPSHOT_FAILURES,
                    1L);
            throw exception;
        } finally {
            ProcessingObservations.record(
                    observer,
                    ProcessingMetricId.PROCESS_EVENT_SNAPSHOT_CONSTRUCTION_NANOS,
                    System.nanoTime() - startedAt);
        }
    }

    private enum State {
        UNINITIALIZED,
        ABSENT,
        READY,
        FAILED
    }
}
