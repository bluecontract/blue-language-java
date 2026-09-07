package blue.contracts.closure;

/** Same one-document execution path for acyclic and cyclic closure members. */
public interface DocumentStepProcessor {
    LocalDocumentStepResult process(
            DocumentStepInput input, SharedGasMeter sharedMeter);
}
