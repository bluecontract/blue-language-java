package blue.contracts.closure;

/** Operation-specific closure entry points with one complete frozen input. */
public interface ClosureProcessor {
    ClosureProcessResult.Attempt processClosure(ClosureInvocationInput input);
    ClosureProcessResult.Attempt admitClosure(ClosureInvocationInput input);
}
