package blue.language.processor;

/** Stateless observer that discards every observation. */
public final class NoOpProcessingObserver implements ProcessingObserver {

    /** Shared instance suitable for every processor. */
    public static final NoOpProcessingObserver INSTANCE = new NoOpProcessingObserver();

    private NoOpProcessingObserver() {
    }

    /** Discards the observation. */
    @Override
    public void record(ProcessingObservation observation) {
        // Intentionally empty; even null is harmless at this boundary.
    }
}
