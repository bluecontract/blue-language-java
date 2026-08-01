package blue.language.processor;

/**
 * Receives typed, operational observations from document processing.
 *
 * <p>Observers are outside the semantic execution model: implementations must
 * not mutate processor state, charge gas, or influence processing results.
 * Processor-owned dispatch uses a failure-isolating recorder so an exporter
 * failure is observational only.</p>
 */
@FunctionalInterface
public interface ProcessingObserver {

    /**
     * Records one immutable observation.
     *
     * @param observation typed observation
     */
    void record(ProcessingObservation observation);
}
