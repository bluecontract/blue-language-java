package blue.language.processor;

import blue.language.NodeProvider;
import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;
import blue.language.utils.BlueIdCalculator;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Reconstructs the actual ancestor-to-descendant Source contributions for one
 * effective contract without inventing an identity for the merged result.
 */
final class ContractContributionResolver {

    private final NodeProvider provider;
    private volatile GasSchedule gasSchedule;

    ContractContributionResolver(NodeProvider provider) {
        this(provider, GasSchedule.contracts10());
    }

    ContractContributionResolver(NodeProvider provider,
                                 GasSchedule gasSchedule) {
        this.provider = provider;
        this.gasSchedule = Objects.requireNonNull(gasSchedule, "gasSchedule");
    }

    void gasSchedule(GasSchedule gasSchedule) {
        this.gasSchedule = Objects.requireNonNull(gasSchedule, "gasSchedule");
    }

    /**
     * Materializes one exact contract contribution through the same verified
     * provider boundary used to reconstruct ordered Source identities.
     */
    FrozenNode materializeVerifiedReference(FrozenNode reference) {
        Objects.requireNonNull(reference, "reference");
        if (!reference.isReferenceOnly()) {
            return reference;
        }
        String blueId = reference.getReferenceBlueId();
        return FrozenNode.fromResolvedNode(
                materialize(reference.toNode(), blueId));
    }

    List<String> resolve(Node selectedScope,
                         String contractKey,
                         boolean effectiveContractExists) {
        return resolve(
                selectedScope,
                null,
                contractKey,
                effectiveContractExists);
    }

    List<String> resolve(Node selectedScope,
                         FrozenNode effectiveScope,
                         String contractKey,
                         boolean effectiveContractExists) {
        return resolveBinding(
                selectedScope,
                effectiveScope,
                contractKey,
                effectiveContractExists,
                Collections.<String>emptyList())
                .sourceContributions();
    }

    BindingResolution resolveBinding(
            Node selectedScope,
            FrozenNode effectiveScope,
            String contractKey,
            boolean effectiveContractExists,
            Collection<String> executableBodyFields) {
        List<String> contributions = new ArrayList<>();
        Map<String, Node> exactExecutableBodies =
                new LinkedHashMap<>();
        Set<String> requestedExecutableBodies =
                executableBodyFields == null
                        ? Collections.<String>emptySet()
                        : new LinkedHashSet<>(
                                executableBodyFields);
        Set<String> activeTypes = new LinkedHashSet<>();
        Node selectedType =
                selectedScope != null ? selectedScope.getType() : null;
        if (selectedType == null && effectiveScope != null) {
            /*
             * A canonical fragment selected from a ResolvedSnapshot can omit
             * a type supplied contextually by its parent type. The completed
             * scope retains that verified type's requested BlueId. Recreate
             * only the pure reference and pass it through the ordinary
             * provider-verification path; never treat merged effective
             * contract content as Source-contribution evidence.
             */
            FrozenNode effectiveType = effectiveScope.getType();
            String inheritedTypeBlueId = effectiveType != null
                    ? effectiveType.getReferenceBlueId()
                    : null;
            if (inheritedTypeBlueId != null) {
                selectedType =
                        new Node().blueId(inheritedTypeBlueId);
            }
        }
        collectTypeContributions(
                selectedType,
                contractKey,
                contributions,
                requestedExecutableBodies,
                exactExecutableBodies,
                activeTypes,
                0);
        Node contracts = selectedScope != null ? selectedScope.getContracts() : null;
        Node direct = null;
        if (contracts != null && contracts.getProperties() != null) {
            direct = contracts.getProperties().get(contractKey);
        }
        if (direct != null && contributesContent(direct)) {
            contributions.add(exactIdentity(direct));
            overlayDeclaredExecutableBodies(
                    direct,
                    requestedExecutableBodies,
                    exactExecutableBodies);
        }
        if (effectiveContractExists && contributions.isEmpty()) {
            throw new MustUnderstandFailureException(
                    "Cannot establish source contributions for effective contract '"
                            + contractKey + "'",
                    ProcessorErrorCategory.InvalidContractBinding);
        }
        return new BindingResolution(
                contributions,
                exactExecutableBodies);
    }

    private void collectTypeContributions(Node typeReference,
                                          String contractKey,
                                          List<String> result,
                                          Set<String> executableBodyFields,
                                          Map<String, Node> exactExecutableBodies,
                                          Set<String> activeTypes,
                                          int depth) {
        if (typeReference == null) {
            return;
        }
        long maxTypeEdges = gasSchedule.portableLimit("typeChainEdges");
        if (depth >= maxTypeEdges) {
            throw new PortableLimitExceededException(
                    ProcessorErrorCategory.DirectNodeLimitExceeded,
                    "typeChainEdges",
                    depth + 1L,
                    maxTypeEdges);
        }
        String typeBlueId = referenceIdentity(typeReference);
        Node typeNode = materialize(typeReference, typeBlueId);
        String cycleKey = typeBlueId != null
                ? typeBlueId
                : exactIdentity(typeNode);
        if (!activeTypes.add(cycleKey)) {
            throw new MustUnderstandFailureException(
                    "Cyclic type contribution while resolving contract '"
                            + contractKey + "'",
                    ProcessorErrorCategory.InvalidContractBinding);
        }
        collectTypeContributions(
                typeNode.getType(),
                contractKey,
                result,
                executableBodyFields,
                exactExecutableBodies,
                activeTypes,
                depth + 1);
        Node contracts = typeNode.getContracts();
        Node contribution = null;
        if (contracts != null && contracts.getProperties() != null) {
            contribution = contracts.getProperties().get(contractKey);
        }
        if (contribution != null && contributesContent(contribution)) {
            result.add(exactIdentity(contribution));
            overlayDeclaredExecutableBodies(
                    contribution,
                    executableBodyFields,
                    exactExecutableBodies);
        }
        activeTypes.remove(cycleKey);
    }

    private void overlayDeclaredExecutableBodies(
            Node contribution,
            Set<String> executableBodyFields,
            Map<String, Node> exactExecutableBodies) {
        if (contribution == null
                || executableBodyFields.isEmpty()) {
            return;
        }
        Node exactContribution =
                contribution.isReferenceOnly()
                        ? materialize(
                                contribution,
                                referenceIdentity(
                                        contribution))
                        : contribution;
        Map<String, Node> properties =
                exactContribution.getProperties();
        if (properties == null) {
            return;
        }
        for (String field : executableBodyFields) {
            if (!properties.containsKey(field)) {
                continue;
            }
            Node body = properties.get(field);
            exactExecutableBodies.put(
                    field,
                    body != null ? body.clone() : new Node());
        }
    }

    private Node materialize(Node reference, String blueId) {
        if (!reference.isReferenceOnly()) {
            return reference;
        }
        if (provider == null || blueId == null) {
            throw new MustUnderstandFailureException(
                    "Provider content is required for type contribution " + blueId,
                    ProcessorErrorCategory.InvalidContractBinding);
        }
        List<Node> nodes = provider.fetchByBlueId(blueId);
        if (nodes == null || nodes.size() != 1 || nodes.get(0) == null) {
            throw new MustUnderstandFailureException(
                    "Expected one verified type contribution for " + blueId,
                    ProcessorErrorCategory.InvalidContractBinding);
        }
        Node node = nodes.get(0);
        Node canonicalContent = node.clone();
        if (canonicalContent.getBlueId() != null
                && !canonicalContent.isReferenceOnly()) {
            /*
             * Verified providers may retain the requested root identity as
             * materialization provenance. It is not content and must not be
             * fed back into strict BlueId input as a mixed reference.
             */
            canonicalContent.blueId(null);
        }
        if (blueId.indexOf('#') >= 0) {
            /*
             * The processor's provider graph verifies MASTER#index through
             * the owning cyclic set. A member is not ordinary standalone
             * content and therefore must never be hashed independently.
             */
            return canonicalContent;
        }
        String calculated =
                BlueIdCalculator.calculateBlueId(canonicalContent);
        if (!blueId.equals(calculated)) {
            throw new MustUnderstandFailureException(
                    "Type contribution BlueId mismatch for " + blueId,
                    ProcessorErrorCategory.InvalidContractBinding);
        }
        return canonicalContent;
    }

    private String referenceIdentity(Node node) {
        return node != null && node.getBlueId() != null
                ? node.getBlueId()
                : node != null ? BlueIdCalculator.calculateBlueId(node) : null;
    }

    private String exactIdentity(Node node) {
        Objects.requireNonNull(node, "node");
        return node.getBlueId() != null
                ? node.getBlueId()
                : BlueIdCalculator.calculateBlueId(node);
    }

    private boolean contributesContent(Node node) {
        if (node == null) {
            return false;
        }
        if (node.isReferenceOnly()) {
            return true;
        }
        return node.getType() != null
                || node.getValue() != null
                || node.getItems() != null
                || node.getContracts() != null
                || (node.getProperties() != null
                && !node.getProperties().isEmpty())
                || node.getName() != null
                || node.getDescription() != null;
    }

    static final class BindingResolution {
        private final List<String> sourceContributions;
        private final Map<String, Node> exactExecutableBodies;

        private BindingResolution(
                List<String> sourceContributions,
                Map<String, Node> exactExecutableBodies) {
            this.sourceContributions =
                    Collections.unmodifiableList(
                            new ArrayList<>(
                                    sourceContributions));
            Map<String, Node> exactBodies =
                    new LinkedHashMap<>();
            for (Map.Entry<String, Node> entry
                    : exactExecutableBodies.entrySet()) {
                exactBodies.put(
                        entry.getKey(),
                        entry.getValue() != null
                                ? entry.getValue().clone()
                                : new Node());
            }
            this.exactExecutableBodies =
                    Collections.unmodifiableMap(
                            exactBodies);
        }

        List<String> sourceContributions() {
            return sourceContributions;
        }

        Map<String, Node> exactExecutableBodies() {
            return exactExecutableBodies;
        }
    }
}
