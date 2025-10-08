package blue.language.processor;

final class RunTerminationException extends RuntimeException {
    private final boolean fatal;

    RunTerminationException(boolean fatal) {
        this.fatal = fatal;
    }

    boolean fatal() {
        return fatal;
    }
}
