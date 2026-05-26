package blue.language.processor;

/**
 * Processor-visible status for a PROCESS run.
 */
public enum ProcessorStatus {
    SUCCESS("success"),
    CAPABILITY_FAILURE("capability-failure"),
    RUNTIME_FATAL("runtime-fatal"),
    INVALID_PROCESSING_DOCUMENT("invalid-processing-document");

    private final String wireValue;

    ProcessorStatus(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}
