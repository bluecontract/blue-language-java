package blue.language.processor;

/** Prevents broad implementation catches from manufacturing authoritative semantic failures. */
final class ProcessingFailureBoundary {
    private ProcessingFailureBoundary() { }

    static void requireSemantic(Throwable failure) {
        if (failure instanceof ProcessorFailureException
                || failure instanceof blue.language.model.InvalidNodeStructureException
                || failure instanceof blue.language.snapshot.InvalidCanonicalPatchException
                || failure instanceof ProcessorFatalException
                || failure instanceof MustUnderstandFailureException
                || failure instanceof ProcessorEngine.BoundaryViolationException) {
            return;
        }
        if (failure instanceof NoncommittingExecutionException
                || failure instanceof ExecutionEvidenceUnavailableException
                || failure instanceof InvalidExecutionEvidenceException
                || failure instanceof GasLimitExceededException
                || failure instanceof PortableLimitExceededException
                || failure instanceof SubscriptionSurfaceInvalidException
                || failure instanceof DocumentStepRuntimeGapException) {
            throw (RuntimeException) failure;
        }
        if (failure instanceof Error) {
            throw (Error) failure;
        }
        throw new UnclassifiedProcessingException(failure);
    }
}
