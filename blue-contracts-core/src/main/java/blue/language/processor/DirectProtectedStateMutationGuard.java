package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.util.PointerUtils;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.processor.util.ProcessorPointerConstants;
import blue.language.snapshot.FrozenNode;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Rejects direct application writes to processor-protected transition state.
 *
 * <p>The guard runs against current canonical state before patch planning.
 * {@link ProtectedStateGuard} remains the transaction-level comparison for
 * indirect or resolution-driven changes after a tentative mutation.</p>
 */
final class DirectProtectedStateMutationGuard {

    private static final List<String> PROTECTED_CONTRACT_KEYS =
            Arrays.asList(
                    ProcessorContractConstants.KEY_INITIALIZED,
                    ProcessorContractConstants.KEY_TERMINATED,
                    ProcessorContractConstants.KEY_CHECKPOINT,
                    ProcessorContractConstants.KEY_GENERALIZATION);

    private final DocumentProcessingRuntime runtime;

    DirectProtectedStateMutationGuard(
            DocumentProcessingRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    void validate(String scopePath,
                  PatchInput patch,
                  boolean allowReservedMutation) {
        if (allowReservedMutation) {
            return;
        }
        String normalizedScope = ProcessorEngine.normalizeScope(scopePath);
        String targetPath = PointerUtils.assertValidRuntimePointer(
                patch.authoredPath());
        enforceInlineTypeMutation(normalizedScope, targetPath, patch);
        String contractsPointer = ProcessorEngine.resolvePointer(
                normalizedScope,
                ProcessorPointerConstants.RELATIVE_CONTRACTS);
        if (targetPath.equals(contractsPointer)) {
            enforceContractsMapPreservation(normalizedScope, patch);
            return;
        }
        for (String key
                : PROTECTED_CONTRACT_KEYS) {
            String protectedPointer = ProcessorEngine.resolvePointer(
                    normalizedScope,
                    ProcessorPointerConstants.relativeContractsEntry(key));
            if (!PointerUtils.descendantOrEqual(
                    targetPath, protectedPointer)) {
                continue;
            }
            throw directMutationFailure(key, protectedPointer);
        }
    }

    private void enforceInlineTypeMutation(
            String scopePath,
            String targetPath,
            PatchInput patch) {
        if ((patch.op() != JsonPatch.Op.ADD
                && patch.op() != JsonPatch.Op.REPLACE)
                || !targetPath.equals(ProcessorEngine.resolvePointer(
                scopePath,
                ProcessorPointerConstants.RELATIVE_TYPE))) {
            return;
        }
        Node authoredContracts = patch.mutableValue() != null
                ? patch.mutableValue().getContracts()
                : null;
        FrozenNode frozenContracts = patch.frozenValue() != null
                ? patch.frozenValue().getContracts()
                : null;
        for (String protectedKey : PROTECTED_CONTRACT_KEYS) {
            if (contains(authoredContracts, protectedKey)
                    || contains(frozenContracts, protectedKey)) {
                throw protectedStateFailure(
                        "Application type patch contributes protected "
                                + "processor state at "
                                + ProcessorEngine.resolvePointer(
                                targetPath,
                                ProcessorPointerConstants
                                        .relativeContractsEntry(
                                                protectedKey)));
            }
        }
    }

    private void enforceContractsMapPreservation(
            String scopePath,
            PatchInput patch) {
        if (patch.op() == JsonPatch.Op.REMOVE) {
            for (String key
                    : PROTECTED_CONTRACT_KEYS) {
                if (canonicalProtected(scopePath, key) != null) {
                    throw replacementFailure(key);
                }
            }
            return;
        }
        Node replacement = patch.mutableValue();
        FrozenNode frozenReplacement = patch.frozenValue();
        for (String key
                : PROTECTED_CONTRACT_KEYS) {
            FrozenNode selected = canonicalProtected(scopePath, key);
            boolean equal;
            if (patch.isFrozen()) {
                FrozenNode proposed = frozenReplacement != null
                        ? frozenReplacement.property(key)
                        : null;
                equal = semanticallyEqual(selected, proposed);
            } else {
                Node proposed = replacement != null
                        && replacement.getProperties() != null
                        ? replacement.getProperties().get(key)
                        : null;
                equal = semanticallyEqual(
                        selected != null ? selected.toNode() : null,
                        proposed);
            }
            if (!equal) {
                throw replacementFailure(key);
            }
        }
    }

    private FrozenNode canonicalProtected(String scopePath, String key) {
        return runtime.selectedFrozenAt(
                ProcessorEngine.resolvePointer(
                        scopePath,
                        ProcessorPointerConstants
                                .relativeContractsEntry(key)));
    }

    private boolean contains(Node contracts, String key) {
        return contracts != null
                && contracts.getProperties() != null
                && contracts.getProperties().containsKey(key);
    }

    private boolean contains(FrozenNode contracts, String key) {
        return contracts != null
                && contracts.getProperties() != null
                && contracts.getProperties().containsKey(key);
    }

    private boolean semanticallyEqual(FrozenNode left, FrozenNode right) {
        if (left == null || right == null) {
            return left == right;
        }
        return left.blueId().equals(right.blueId());
    }

    private boolean semanticallyEqual(Node left, Node right) {
        if (left == null || right == null) {
            return left == right;
        }
        return FrozenNode.fromNode(left).blueId()
                .equals(FrozenNode.fromNode(right).blueId());
    }

    private ProcessorFailureException replacementFailure(String key) {
        return protectedStateFailure(
                "Replacing /contracts must preserve protected key '"
                        + key + "'");
    }

    private ProcessorFailureException directMutationFailure(
            String key,
            String protectedPointer) {
        if (ProcessorContractConstants.RESERVED_CONTRACT_KEYS
                .contains(key)) {
            return protectedStateFailure(
                    "Reserved key '" + key
                            + "' is write-protected at "
                            + protectedPointer);
        }
        return protectedStateFailure(
                "Effective contract '" + key
                        + "' is frozen for this transition at "
                        + protectedPointer);
    }

    static boolean isProcessorProtectedContractKey(String key) {
        return PROTECTED_CONTRACT_KEYS.contains(key);
    }

    private ProcessorFailureException protectedStateFailure(
            String message) {
        return new ProcessorFailureException(
                ProcessorErrorCategory.ProtectedProcessorStateMutation,
                message);
    }
}
