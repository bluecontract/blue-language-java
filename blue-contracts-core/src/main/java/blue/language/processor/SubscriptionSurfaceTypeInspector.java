package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.snapshot.FrozenNode;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** Inspects exact type contributions to an external subscription surface. */
final class SubscriptionSurfaceTypeInspector {

    private SubscriptionSurfaceTypeInspector() {
    }

    static boolean contributes(
            ProcessingSnapshotManager snapshotManager,
            Node declaredType,
            Set<String> requestedChannelKeys,
            boolean includeProcessEmbedded,
            Set<String> visited) {
        return contributes(
                snapshotManager != null
                        ? snapshotManager::materializeVerifiedExactReference
                        : null,
                declaredType,
                requestedChannelKeys,
                includeProcessEmbedded,
                visited);
    }

    /**
     * Inspects exact type contributions without resolving the type target as
     * a standalone document. A type's instance schema is unrelated to this
     * structural subscription-surface query and must not be applied to the
     * type definition itself.
     */
    static boolean contributes(
            Function<FrozenNode, FrozenNode> exactMaterializer,
            Node declaredType,
            Set<String> requestedChannelKeys,
            boolean includeProcessEmbedded,
            Set<String> visited) {
        if (declaredType == null) {
            return false;
        }
        if (requestedChannelKeys.isEmpty()
                && !includeProcessEmbedded) {
            return false;
        }
        if (exactMaterializer == null) {
            return true;
        }
        FrozenNode declaredTypeReference = FrozenNode.fromNode(declaredType);
        FrozenNode exactType = declaredType.isReferenceOnly()
                ? requireMaterialized(
                declaredTypeReference,
                exactMaterializer.apply(declaredTypeReference),
                "Subscription-surface scope type content was not found")
                : FrozenNode.fromNode(declaredType.clone());
        String identity = declaredType.getBlueId() != null
                ? declaredType.getBlueId()
                : exactType.blueId();
        if (!visited.add(identity)) {
            throw new InvalidExecutionEvidenceException(
                    "Cyclic scope type hierarchy in subscription surface: "
                            + identity);
        }

        FrozenNode contracts = exactType.getContracts();
        if (contracts != null && contracts.isReferenceOnly()) {
            FrozenNode contractsReference = contracts;
            contracts = requireMaterialized(
                    contractsReference,
                    exactMaterializer.apply(contractsReference),
                    "Subscription-surface type contracts content was not found");
        }
        if (contracts != null
                && contracts.getProperties() != null) {
            Map<String, FrozenNode> entries = contracts.getProperties();
            for (String requestedChannelKey : requestedChannelKeys) {
                if (entries.containsKey(requestedChannelKey)) {
                    return true;
                }
            }
            FrozenNode embedded = includeProcessEmbedded
                    ? entries.get(ProcessorContractConstants.KEY_EMBEDDED)
                    : null;
            if (embedded != null
                    && isExactProcessEmbeddedContract(
                    exactMaterializer, embedded)) {
                return true;
            }
        }
        FrozenNode parent = exactType.getType();
        return parent != null
                && contributes(
                        exactMaterializer,
                        parent.toNode(),
                        requestedChannelKeys,
                        includeProcessEmbedded,
                        visited);
    }

    private static boolean isExactProcessEmbeddedContract(
            Function<FrozenNode, FrozenNode> exactMaterializer,
            FrozenNode contract) {
        FrozenNode exact = contract;
        if (exact != null && exact.isReferenceOnly()) {
            FrozenNode reference = exact;
            exact = requireMaterialized(
                    reference,
                    exactMaterializer.apply(reference),
                    "Process Embedded contract header content was not found");
        }
        FrozenNode type = exact != null ? exact.getType() : null;
        return exactTypeLineageContains(
                exactMaterializer,
                type,
                RuntimeBlueIds.PROCESS_EMBEDDED,
                new LinkedHashSet<String>());
    }

    private static boolean exactTypeLineageContains(
            Function<FrozenNode, FrozenNode> exactMaterializer,
            FrozenNode declaredType,
            String expectedTypeBlueId,
            Set<String> visitedReferences) {
        if (declaredType == null) {
            return false;
        }
        String referenceBlueId = declaredType.getReferenceBlueId();
        if (referenceBlueId != null) {
            if (expectedTypeBlueId.equals(referenceBlueId)) {
                return true;
            }
            if (!visitedReferences.add(referenceBlueId)) {
                throw new InvalidExecutionEvidenceException(
                        "Cyclic exact contract type hierarchy: "
                                + referenceBlueId);
            }
            FrozenNode exact = requireMaterialized(
                    declaredType,
                    exactMaterializer.apply(declaredType),
                    "Contract type content was not found");
            return exactTypeLineageContains(
                    exactMaterializer,
                    exact.getType(),
                    expectedTypeBlueId,
                    visitedReferences);
        }
        return exactTypeLineageContains(
                exactMaterializer,
                declaredType.getType(),
                expectedTypeBlueId,
                visitedReferences);
    }

    private static FrozenNode requireMaterialized(
            FrozenNode reference,
            FrozenNode materialized,
            String message) {
        if (materialized != null) {
            return materialized;
        }
        throw ExternalEvidenceVerificationSupport.invalid(
                message + " for " + reference.getReferenceBlueId());
    }
}
