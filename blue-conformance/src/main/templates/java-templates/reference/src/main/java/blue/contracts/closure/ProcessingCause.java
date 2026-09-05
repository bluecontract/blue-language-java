package blue.contracts.closure;

/** Closed Java 8 cause union plus its exact platform identity. */
public abstract class ProcessingCause {
    ProcessingCause() { }

    public abstract String kind();
    public abstract String causeIdentity();
}
