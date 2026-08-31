package blue.language.processor.closure;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Reconstructible retry of one processing invocation with exact demand-bound
 * managed-occurrence resolutions.
 *
 * <p>The base invocation remains unchanged and independently verifiable. The
 * retry owns a distinct deterministic identity, so different resolution sets
 * cannot share work, transition, result, or publication identities.</p>
 */
public final class ClosureProcessRetryInput {

    private static final ClosureIdentityService IDENTITIES =
            ClosureIdentityService.INSTANCE;

    private final String retryInvocationIdentity;
    private final ClosureInvocationInput baseInvocation;
    private final List<ManagedOccurrenceEvidenceResolution> resolutions;
    private final String resolutionSetIdentity;

    /**
     * Creates and verifies one canonical retry input.
     *
     * @param retryInvocationIdentity asserted retry invocation identity
     * @param baseInvocation exact previously suspended processing invocation
     * @param resolutions canonical non-empty demand-resolution sequence
     */
    public ClosureProcessRetryInput(
            String retryInvocationIdentity,
            ClosureInvocationInput baseInvocation,
            List<ManagedOccurrenceEvidenceResolution> resolutions) {
        this.retryInvocationIdentity =
                ClosureValueSupport.requireSha256Identity(
                        retryInvocationIdentity,
                        "retryInvocationIdentity");
        this.baseInvocation = Objects.requireNonNull(
                baseInvocation, "baseInvocation");
        if (this.baseInvocation.operation()
                != ClosureInvocationInput.Operation.PROCESS_CLOSURE) {
            throw new IllegalArgumentException(
                    "A process retry requires a PROCESS_CLOSURE base "
                            + "invocation");
        }
        ArrayList<ManagedOccurrenceEvidenceResolution> copy =
                new ArrayList<ManagedOccurrenceEvidenceResolution>(
                        Objects.requireNonNull(resolutions, "resolutions"));
        if (copy.isEmpty()) {
            throw new IllegalArgumentException(
                    "A process retry requires at least one exact resolution");
        }
        Set<String> demands = new HashSet<String>();
        for (int index = 0; index < copy.size(); index++) {
            ManagedOccurrenceEvidenceResolution resolution =
                    Objects.requireNonNull(
                            copy.get(index), "managed occurrence resolution");
            if (!demands.add(resolution.demand().demandIdentity())) {
                throw new IllegalArgumentException(
                        "A process retry repeats one demand resolution");
            }
            if (index > 0 && copy.get(index - 1).compareTo(resolution) >= 0) {
                throw new IllegalArgumentException(
                        "Process retry resolutions are not canonical");
            }
        }
        this.resolutions = Collections.unmodifiableList(copy);
        this.resolutionSetIdentity =
                IDENTITIES.managedOccurrenceResolutionSetIdentity(copy);
        String exact = IDENTITIES.closureProcessRetryIdentity(
                this.baseInvocation.invocationIdentity(),
                this.resolutionSetIdentity);
        if (!this.retryInvocationIdentity.equals(exact)) {
            throw new IllegalArgumentException(
                    "retryInvocationIdentity does not identify this exact "
                            + "process retry");
        }
    }

    /**
     * Derives one canonical retry input from an unordered resolution list.
     *
     * @param baseInvocation exact previously suspended processing invocation
     * @param resolutions non-empty demand resolutions in any order
     * @return verified retry input with resolutions in canonical order
     */
    public static ClosureProcessRetryInput derived(
            ClosureInvocationInput baseInvocation,
            List<ManagedOccurrenceEvidenceResolution> resolutions) {
        ClosureInvocationInput base = Objects.requireNonNull(
                baseInvocation, "baseInvocation");
        ArrayList<ManagedOccurrenceEvidenceResolution> ordered =
                new ArrayList<ManagedOccurrenceEvidenceResolution>(
                        Objects.requireNonNull(resolutions, "resolutions"));
        Collections.sort(ordered);
        String setIdentity =
                IDENTITIES.managedOccurrenceResolutionSetIdentity(ordered);
        return new ClosureProcessRetryInput(
                IDENTITIES.closureProcessRetryIdentity(
                        base.invocationIdentity(), setIdentity),
                base,
                ordered);
    }

    /**
     * Returns the distinct deterministic retry invocation identity.
     *
     * @return exact retry invocation identity
     */
    public String retryInvocationIdentity() {
        return retryInvocationIdentity;
    }

    /**
     * Returns the unchanged independently verifiable base invocation.
     *
     * @return exact base invocation
     */
    public ClosureInvocationInput baseInvocation() {
        return baseInvocation;
    }

    /**
     * Returns the immutable canonical exact resolution sequence.
     *
     * @return canonical demand-resolution sequence
     */
    public List<ManagedOccurrenceEvidenceResolution> resolutions() {
        return resolutions;
    }

    /**
     * Returns the exact ordered resolution-set identity.
     *
     * @return exact resolution-set identity
     */
    public String resolutionSetIdentity() {
        return resolutionSetIdentity;
    }
}
