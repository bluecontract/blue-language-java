package blue.language.processor;

import java.util.Objects;

/**
 * Runs the ordered preflight gates for one prepared patch input.
 *
 * <p>Boundary and protected-state checks deliberately precede direct contract
 * recognition and cyclic-boundary validation. This ordering preserves stable
 * failure categories and ensures forbidden writes cannot demand providers.</p>
 */
final class PatchPreflight {

    private final DirectProtectedStateMutationGuard protectedState;
    private final DirectContractMutationPreflight contractMutation;
    private final DocumentProcessingRuntime runtime;

    PatchPreflight(ProcessorInvocationServices owner,
                   DocumentProcessingRuntime runtime) {
        Objects.requireNonNull(owner, "owner");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.protectedState = new DirectProtectedStateMutationGuard(runtime);
        this.contractMutation = new DirectContractMutationPreflight(
                owner.contractLoader());
    }

    void validate(String scopePath,
                  ContractBundle bundle,
                  PatchInput patch,
                  boolean allowReservedMutation) {
        PatchBoundaryValidator.validate(scopePath, bundle, patch);
        protectedState.validate(
                scopePath, patch, allowReservedMutation);
        contractMutation.validate(scopePath, patch);
        runtime.validateMutationPathWithoutResolution(patch);
    }
}
