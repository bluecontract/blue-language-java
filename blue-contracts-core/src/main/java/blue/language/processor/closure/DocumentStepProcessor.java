package blue.language.processor.closure;

/** Same isolated document-processing function for acyclic and cyclic members. */
public interface DocumentStepProcessor {

    /**
     * Executes one target document against the invocation's already-shared
     * processor gas context.
     *
     * @param input exact single-document work input
     * @return tentative local result with no finalized after identity
     */
    LocalDocumentStepResult process(DocumentStepInput input);
}
