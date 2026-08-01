package blue.language.processor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Immutable fan-out observer that isolates each delegate from processing and
 * from the other delegates.
 */
public final class CompositeProcessingObserver implements ProcessingObserver {

    private final List<ProcessingObserver> observers;

    /**
     * Creates a composite from the supplied observers.
     *
     * @param observers observers; null entries are ignored
     */
    public CompositeProcessingObserver(ProcessingObserver... observers) {
        this(observers == null
                ? Collections.<ProcessingObserver>emptyList()
                : Arrays.asList(observers));
    }

    /**
     * Creates a composite from the supplied observers.
     *
     * @param observers observers; null entries are ignored
     */
    public CompositeProcessingObserver(Iterable<? extends ProcessingObserver> observers) {
        List<ProcessingObserver> copy = new ArrayList<>();
        if (observers != null) {
            for (ProcessingObserver observer : observers) {
                if (observer != null && observer != NoOpProcessingObserver.INSTANCE) {
                    copy.add(observer);
                }
            }
        }
        this.observers = Collections.unmodifiableList(copy);
    }

    /**
     * Returns delegates in their invocation order.
     *
     * @return immutable delegate list
     */
    public List<ProcessingObserver> observers() {
        return observers;
    }

    /**
     * Invokes every delegate, suppressing non-fatal exporter failures.
     *
     * @param observation immutable observation
     */
    @Override
    public void record(ProcessingObservation observation) {
        if (observation == null) {
            return;
        }
        for (ProcessingObserver observer : observers) {
            ProcessingObservations.record(observer, observation);
        }
    }
}
