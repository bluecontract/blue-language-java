package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.util.ProcessorPointerConstants;
import blue.language.snapshot.FrozenNode;
import blue.language.model.wire.JsonPointer;
import blue.language.model.wire.BlueLanguageConstants;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Classifies contract headers authored directly by an application patch.
 *
 * <p>Direct contract additions are recognized before the tentative write.
 * This preserves must-understand ordering while leaving effective contract
 * resolution and executable-body loading with {@link ContractLoader}.</p>
 */
final class DirectContractMutationPreflight {

    private final ContractLoader contractLoader;

    DirectContractMutationPreflight(ContractLoader contractLoader) {
        this.contractLoader = Objects.requireNonNull(
                contractLoader, "contractLoader");
    }

    void validate(String scopePath, PatchInput patch) {
        if (patch.op() != JsonPatch.Op.ADD
                && patch.op() != JsonPatch.Op.REPLACE) {
            return;
        }
        String contractsPointer = ProcessorEngine.resolvePointer(
                scopePath,
                ProcessorPointerConstants.RELATIVE_CONTRACTS);
        List<String> contractsSegments = JsonPointer.split(
                contractsPointer);
        List<String> targetSegments = JsonPointer.split(
                patch.authoredPath());
        FrozenNode value = frozenValue(patch);
        if (value == null) {
            return;
        }

        if (targetSegments.equals(contractsSegments)) {
            preflightContractsMap(value);
            return;
        }
        if (isDirectContractEntry(
                targetSegments, contractsSegments)) {
            String key = targetSegments.get(contractsSegments.size());
            preflightContract(key, value);
            return;
        }
        if (isDirectContractType(
                targetSegments, contractsSegments)) {
            String key = targetSegments.get(contractsSegments.size());
            preflightContract(
                    key,
                    FrozenNode.fromResolvedNode(
                            new Node().type(value.toNode())));
        }
    }

    private FrozenNode frozenValue(PatchInput patch) {
        FrozenNode value = patch.frozenValue();
        if (value == null && patch.mutableValue() != null) {
            value = FrozenNode.fromResolvedNode(patch.mutableValue());
        }
        return value;
    }

    private void preflightContractsMap(FrozenNode value) {
        if (value.getProperties() == null) {
            return;
        }
        for (Map.Entry<String, FrozenNode> entry
                : value.getProperties().entrySet()) {
            preflightContract(entry.getKey(), entry.getValue());
        }
    }

    private void preflightContract(String key, FrozenNode value) {
        if (!DirectProtectedStateMutationGuard
                .isProcessorProtectedContractKey(key)) {
            contractLoader.preflightDirectContractHeader(key, value);
        }
    }

    private boolean isDirectContractEntry(
            List<String> targetSegments,
            List<String> contractsSegments) {
        return targetSegments.size() == contractsSegments.size() + 1
                && targetSegments.subList(
                0, contractsSegments.size()).equals(contractsSegments);
    }

    private boolean isDirectContractType(
            List<String> targetSegments,
            List<String> contractsSegments) {
        return targetSegments.size() == contractsSegments.size() + 2
                && targetSegments.subList(
                0, contractsSegments.size()).equals(contractsSegments)
                && BlueLanguageConstants.OBJECT_TYPE.equals(
                targetSegments.get(targetSegments.size() - 1));
    }
}
