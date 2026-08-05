package blue.language.processor;

/** Prints the deterministic generated observation reference for maintainers. */
public final class ProcessingMetricReferenceCli {

    private ProcessingMetricReferenceCli() {
    }

    /** Emits the generated Markdown reference to standard output. */
    public static void main(String[] arguments) {
        System.out.print(ProcessingMetricManifest.markdown());
    }
}
